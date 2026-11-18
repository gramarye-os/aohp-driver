// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.terminal

import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.aohp.driver.binder.ContainerService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * One interactive shell (openShell PFD) per env. Lives at Application scope so
 * it survives tab switches and recomposition; dies with the process.
 *
 * Output bytes are buffered in [pending] while no WebView is attached and
 * replayed on attach (bounded), so switching tabs doesn't lose output.
 */
class PtySession(val env: String, private val pfd: ParcelFileDescriptor) {
    companion object { const val TAG = "AohpDriver"; const val REPLAY_MAX = 256 * 1024 }

    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    /** Identity sentinel that stops the writer thread. Never compare by content: xterm can emit empty onData() (IME composition). */
    private val poison = ByteArray(0)
    @Volatile var closed = false
        private set
    @Volatile var exitReason: String? = null
        private set

    /** Bytes not yet delivered to a sink (or replay buffer when detached). */
    private val replay = java.io.ByteArrayOutputStream()
    @Volatile private var sink: ((String) -> Unit)? = null   // receives base64 chunks
    @Volatile private var onClosed: (() -> Unit)? = null
    var cols = 0; var rows = 0
        private set

    private val reader = Thread({
        val buf = ByteArray(16 * 1024)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) { exitReason = "shell exited (EOF)"; break }
                if (n == 0) continue
                deliver(buf.copyOf(n))
            }
        } catch (t: Throwable) {
            if (!closed) { exitReason = "read failed: " + t.message; Log.w(TAG, "pty reader $env", t) }
        }
        Log.i(TAG, "pty reader $env finished: $exitReason")
        closed = true
        onClosed?.invoke()
    }, "pty-reader-$env").apply { isDaemon = true }

    private val writer = Thread({
        try {
            while (!closed) {
                val chunk = writeQueue.take()
                if (chunk === poison) break
                if (chunk.isEmpty()) continue
                output.write(chunk); output.flush()
            }
        } catch (t: Throwable) {
            if (!closed) { exitReason = "write failed: " + t.message; Log.w(TAG, "pty writer $env", t); closed = true; onClosed?.invoke() }
        }
    }, "pty-writer-$env").apply { isDaemon = true }

    init { reader.start(); writer.start() }

    @Synchronized private fun deliver(bytes: ByteArray) {
        val s = sink
        if (s != null) s(Base64.encodeToString(bytes, Base64.NO_WRAP))
        else {
            replay.write(bytes)
            if (replay.size() > REPLAY_MAX) {
                val all = replay.toByteArray(); replay.reset(); replay.write(all, all.size - REPLAY_MAX / 2, REPLAY_MAX / 2)
            }
        }
    }

    /** Attach a sink; replays buffered output first. */
    @Synchronized fun attach(s: (String) -> Unit, closedCb: () -> Unit) {
        sink = s; onClosed = closedCb
        if (replay.size() > 0) { s(Base64.encodeToString(replay.toByteArray(), Base64.NO_WRAP)); replay.reset() }
        if (closed) closedCb()
    }

    @Synchronized fun detach() { sink = null; onClosed = null }

    fun write(bytes: ByteArray) { if (!closed && bytes.isNotEmpty()) writeQueue.offer(bytes) }
    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * v1 resize hack: the PFD is a socket relay, so TIOCSWINSZ is impossible from
     * here; tell the shell instead. Echoes in the terminal until containerd gets
     * resizeShell. Only sent when the size actually changes. The terminal page calls this
     * only at an empty shell prompt and does the debouncing (assets/term/index.html): typed
     * into a half-written command line, the stty text would be appended to it and run.
     */
    private val resizeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    fun resize(c: Int, r: Int) {
        if (c <= 0 || r <= 0) return
        pendingResize?.let { resizeHandler.removeCallbacks(it) }
        val run = Runnable {
            pendingResize = null
            if (c == cols && r == rows) return@Runnable
            cols = c; rows = r
            write("stty cols $c rows $r\n")
        }
        pendingResize = run
        resizeHandler.post(run)
    }

    fun close() {
        closed = true
        writeQueue.offer(poison)
        runCatching { pfd.close() }
        Log.i(TAG, "pty session closed for $env")
    }
}

class PtySessionRegistry(private val containers: ContainerService) {
    private val sessions = ConcurrentHashMap<String, PtySession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(env: String): PtySession? = sessions[env]?.takeIf { !it.closed }

    /** Opens (or returns) the session for [env]. */
    suspend fun open(env: String): Result<PtySession> {
        get(env)?.let { return Result.success(it) }
        return containers.openShell(env).map { pfd ->
            PtySession(env, pfd).also { sessions[env] = it }
        }
    }

    fun close(env: String) { sessions.remove(env)?.close() }

    fun closeAll() { sessions.keys.toList().forEach { close(it) } }
}
