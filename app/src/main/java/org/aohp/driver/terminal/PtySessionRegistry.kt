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
class PtySession(
    val env: String, private val pfd: ParcelFileDescriptor,
    /** Runs a command in [env] beside the shell and gives its output, or null when it could not run. */
    private val beside: (suspend (String) -> String?)? = null,
    private val scope: CoroutineScope? = null,
) {
    companion object {
        const val TAG = "AohpDriver"; const val REPLAY_MAX = 256 * 1024
        private val PTS = Regex("^/proc/[0-9]+/fd/0$")

        // Shell text; '@' stands for the shell's dollar sign.
        /** The daemon's pid in d. A command run in the env is a child of the daemon, like the shell. */
        private const val DAEMON =
            "d=@PPID; [ \"@(cat /proc/@d/comm 2>/dev/null)\" = aohp-containerd ] || d=@(pidof aohp-containerd 2>/dev/null | cut -d' ' -f1)"

        /**
         * Finds this session's terminal and leaves it in t: the newest direct child of the daemon whose
         * input is a /dev/pts/N. The terminal is named /proc/<that pid>/fd/0, not /dev/pts/N: the daemon
         * makes the pty before the shell enters the env, so that name is from the phone's own /dev/pts
         * and does not exist in the env, while the /proc link opens the same terminal from anywhere.
         */
        private const val FIND_TTY = DAEMON +
            "; t=; b=-1; for p in @(awk -v d=\"@d\" '@4==d{print @1}' /proc/[0-9]*/stat 2>/dev/null); do " +
            "case \"@(readlink /proc/@p/fd/0 2>/dev/null)\" in /dev/pts/[0-9]*) " +
            "s=@(awk '{print @22}' /proc/@p/stat 2>/dev/null); [ \"@{s:-0}\" -gt \"@b\" ] && { b=@s; t=/proc/@p/fd/0; };; esac; done"

        /** Keeps a terminal found earlier (pid #PID#) in t if that pid is still such a shell, else leaves t empty. */
        private const val KEEP_TTY = DAEMON +
            "; t=/proc/#PID#/fd/0; [ -n \"@d\" ] && [ \"@(awk '{print @4}' /proc/#PID#/stat 2>/dev/null)\" = \"@d\" ] || t=; " +
            "case \"@(readlink \"@t\" 2>/dev/null)\" in /dev/pts/[0-9]*) ;; *) t=;; esac"
    }

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
    /** The shell's terminal as seen from the env (/proc/<shell pid>/fd/0), once found. */
    @Volatile private var tty: String? = null
    /** True once the size was set from beside the shell: the page may then send a size at any time. */
    @Volatile var sizeDirect = false
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

    private fun sh(s: String) = s.replace('@', '$')

    /** Find the shell's terminal as soon as the shell is open, so that the first size is set directly too. */
    fun probe() {
        val b = beside ?: return
        scope?.launch {
            val t = b(sh(FIND_TTY + "; [ -n \"@t\" ] && echo \"@t\""))?.trim()
            if (t != null && PTS.matches(t) && !closed) { tty = t; sizeDirect = true; Log.i(TAG, "pty $env: shell terminal $t") }
            else Log.w(TAG, "pty $env: shell terminal not found; the size will be typed at the prompt")
        }
    }

    /**
     * Set the terminal size. The PFD is a socket relay to the daemon, which holds the pty, so the
     * size cannot be set on it from here. Instead a command run beside the shell sets it on the
     * shell's terminal (stty -F /proc/<shell pid>/fd/0); the kernel then sends SIGWINCH to whatever runs in front, as with
     * any terminal. Nothing is typed and nothing echoes.
     *
     * Fallback when the terminal cannot be found: type "stty cols C rows R" into the shell, as before.
     * The page only asks for that at an empty prompt (see [sizeDirect] and assets/term/index.html):
     * typed into a half-written command line, the text would be appended to it and run.
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
            val b = beside; val s = scope
            val wasDirect = sizeDirect
            if (b == null || s == null) { write("stty cols $c rows $r\n"); return@Runnable }
            s.launch {
                val known = tty
                val find = if (known != null) KEEP_TTY.replace("#PID#", known.removePrefix("/proc/").removeSuffix("/fd/0")) else FIND_TTY
                val t = if (closed) null else b(sh(find) + sh("; [ -n \"@t\" ] && stty -F \"@t\" cols $c rows $r && echo \"@t\""))?.trim()
                if (t != null && PTS.matches(t)) { tty = t; sizeDirect = true }
                else if (!closed) {
                    tty = null; sizeDirect = false
                    Log.w(TAG, "pty $env: could not set the size beside the shell" + (if (wasDirect) "" else "; typing it"))
                    // The page was at an empty prompt when it asked (it had not been told the direct way works).
                    if (!wasDirect) write("stty cols $c rows $r\n")
                }
            }
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
            PtySession(env, pfd, beside = { cmd ->
                containers.execSync(env, cmd, 8_000).getOrNull()?.takeIf { it.ok }?.stdout
            }, scope = scope).also { sessions[env] = it; it.probe() }
        }
    }

    fun close(env: String) { sessions.remove(env)?.close() }

    fun closeAll() { sessions.keys.toList().forEach { close(it) } }
}
