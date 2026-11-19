// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.harness

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import org.json.JSONObject

/**
 * Setup codes: one QR code that sets an env up (WireGuard tunnel, ssh, bootstrap). The format belongs to
 * aohp-agents (bin/aohp-setup, which applies it inside the env): "AOHP1:" + base64url(zlib(JSON profile)).
 * The Driver only reads enough of it to tell the user what it is about to do.
 */
const val SETUP_CODE_PREFIX = "AOHP1:"

/** What a setup code would do, in plain lines for the confirmation dialog. Holds no secret. */
data class SetupSummary(val name: String, val lines: List<String>)

/** The summary of a scanned setup code, or null when the text is not a setup code this version understands. */
fun describeSetupCode(code: String): SetupSummary? = try {
    val text = code.trim()
    if (!text.startsWith(SETUP_CODE_PREFIX)) null else {
        val inflater = Inflater()
        inflater.setInput(Base64.getUrlDecoder().decode(text.substring(SETUP_CODE_PREFIX.length)))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 1 shl 20) break   // a profile is a few hundred bytes; refuse to inflate without end
        }
        inflater.end()
        val p = JSONObject(out.toString("UTF-8"))
        val lines = mutableListOf<String>()
        p.optJSONObject("wireguard")?.let { lines += "WireGuard tunnel " + it.optString("address") + " to " + it.optString("endpoint") }
        p.optJSONObject("ssh")?.let {
            val n = it.optJSONArray("authorized_keys")?.length() ?: 0
            lines += "ssh server on port " + it.optInt("port", 2222) + ": " + n + (if (n == 1) " key" else " keys") + " may log in as root"
        }
        p.optJSONObject("bootstrap")?.let {
            lines += "Bootstrap from " + it.optString("repo") + (if (it.has("branch")) ", branch " + it.optString("branch") else "")
        }
        if (p.optInt("v") != 1 || lines.isEmpty()) null else SetupSummary(p.optString("name"), lines)
    }
} catch (e: Exception) {
    null
}
