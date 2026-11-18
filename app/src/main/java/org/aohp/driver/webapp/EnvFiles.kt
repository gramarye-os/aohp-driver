// Copyright (C) 2026 Chris Anderson. Licensed under the Apache License, Version 2.0.

package org.aohp.driver.webapp

import android.util.Base64
import org.aohp.driver.bridge.AohpContainerClient
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads the files of one folder of an env, for a web app in "dir" mode.
 *
 * The Driver has no file call into an env, so every read is a command run in the env. The answer
 * comes back through Binder, which carries about 1 MB per call, so a file travels in base64 chunks.
 * A path is only served when it resolves (symlinks included) to a regular file inside the folder.
 */
object EnvFiles {
    private const val CHUNK = 96 * 1024
    private const val TIMEOUT_MS = 10_000

    class Opened(val path: String, val size: Long, val first: ByteArray)

    /** True for text that must not reach a shell line or the line-based daemon protocol. */
    fun unsafe(s: String): Boolean = s.any { it == '\u0000' || it == '\n' || it == '\r' }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Resolve [rel] under [root] (a folder gives its index.html) and read its first chunk; null when not servable. */
    fun open(c: AohpContainerClient, env: String, root: String, rel: String): Opened? {
        if (unsafe(root) || unsafe(rel) || !root.startsWith("/")) return null
        val cmd = "( R=\$(realpath -e -- " + quote(root) + ") || exit 44; [ \"\$R\" = / ] && R=; " +
            "f=\$(realpath -e -- \"\$R\"/" + quote(rel.trimStart('/')) + ") || exit 44; " +
            "if [ -d \"\$f\" ]; then f=\$(realpath -e -- \"\$f/index.html\") || exit 44; fi; " +
            "case \"\$f\" in \"\$R\"/*) [ -f \"\$f\" ] || exit 44; stat -c %s -- \"\$f\"; printf '%s\\n' \"\$f\"; " +
            "head -c " + CHUNK + " -- \"\$f\" | base64 -w0 ;; *) exit 44 ;; esac )"
        val r = c.execSync(env, cmd, TIMEOUT_MS) ?: return null
        if (!r.success) return null
        val parts = (r.output ?: "").split('\n', limit = 3)
        if (parts.size < 2) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        val first = if (parts.size == 3) decode(parts[2]) ?: return null else ByteArray(0)
        return Opened(parts[1], size, first)
    }

    private fun decode(s: String): ByteArray? = runCatching { Base64.decode(s.trim(), Base64.DEFAULT) }.getOrNull()

    private fun chunk(c: AohpContainerClient, env: String, path: String, offset: Long): ByteArray? {
        val cmd = "tail -c +" + (offset + 1) + " -- " + quote(path) + " | head -c " + CHUNK + " | base64 -w0"
        val r = c.execSync(env, cmd, TIMEOUT_MS) ?: return null
        if (!r.success) return null
        return decode(r.output ?: "")
    }

    /** The whole file as a stream; later chunks are fetched as the reader gets to them. */
    fun stream(c: AohpContainerClient, env: String, o: Opened): InputStream = object : InputStream() {
        private var buf = o.first
        private var pos = 0
        private var fetched = o.first.size.toLong()

        private fun fill(): Boolean {
            if (pos < buf.size) return true
            if (fetched >= o.size) return false
            val next = chunk(c, env, o.path, fetched) ?: return false
            if (next.isEmpty()) return false
            buf = next; pos = 0; fetched += next.size
            return true
        }

        override fun read(): Int = if (fill()) buf[pos++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n); pos += n
            return n
        }
    }

    /** A small file in one piece (an icon); null when missing or larger than [max] bytes. */
    fun readAll(c: AohpContainerClient, env: String, root: String, rel: String, max: Int): ByteArray? {
        val o = open(c, env, root, rel) ?: return null
        if (o.size > max) return null
        val out = ByteArrayOutputStream(o.size.toInt())
        stream(c, env, o).use { it.copyTo(out) }
        return out.toByteArray()
    }

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "txt", "md" -> "text/plain"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "wasm" -> "application/wasm"
        "mp3" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    fun isText(mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/json" || mime == "application/manifest+json" ||
            mime == "image/svg+xml" || mime == "application/xml"
}
