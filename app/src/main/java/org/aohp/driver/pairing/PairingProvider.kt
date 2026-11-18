// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.pairing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.aohp.driver.DriverApp
import java.io.File
import java.security.MessageDigest

/**
 * Hands Tessera (the Linux app launcher this image ships) a pairing code for the env its agent
 * runs in, so a new phone pairs without the user finding a root shell to run `tessera-pair`.
 *
 * The agent's token is root in the env, and the agent gives it out once for a six-digit code
 * that root asked it for a moment ago. This asks in the user's place, for one caller only: the
 * app named [CLIENT], and only while it is signed like the APK this image ships under
 * [SHIPPED_DIR] (an app of the same name from elsewhere gets nothing). The code goes back to
 * the caller, which then pairs with the agent as it does with a typed code; the token is still
 * made in the env on this phone and is never seen here.
 *
 * call("tessera-pair") answers a Bundle with "code" (six digits), or "error" (why not).
 */
class PairingProvider : ContentProvider() {
    companion object {
        private const val TAG = "AohpDriver"
        const val METHOD = "tessera-pair"
        const val CLIENT = "com.daryan.tessera"
        const val SHIPPED_DIR = "/system/etc/aohp/preinstall"

        /**
         * One line (containerd's exec runs only the first). Only where the agent's unit runs: the
         * agent's port is the phone's, so of several envs with the agent one holds it.
         */
        const val COMMAND = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "[ -x /usr/local/bin/tessera-pair ] && systemctl is-active --quiet tessera-agent && tessera-pair"

        private val CODE = Regex("""Pairing code:\s*(\d{3})\s*(\d{3})""")

        /** The code in what `tessera-pair` printed, or null. */
        fun parseCode(stdout: String): String? = CODE.find(stdout)?.let { it.groupValues[1] + it.groupValues[2] }

        private fun digests(info: SigningInfo?): Set<String> {
            val signers = info?.apkContentsSigners ?: return emptySet()
            return signers.map { s ->
                MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }

        /** Why the app with [uid] gets no code; null when it is the launcher this image ships. */
        fun refusal(ctx: Context, uid: Int): String? {
            val pm = ctx.packageManager
            val packages = pm.getPackagesForUid(uid)
            if (packages == null || packages.size != 1 || packages[0] != CLIENT) return "the caller is not $CLIENT"
            val shipped = File(SHIPPED_DIR, "$CLIENT.apk")
            if (!shipped.isFile) return "this image does not ship $CLIENT"
            val want = digests(pm.getPackageArchiveInfo(shipped.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo)
            val have = runCatching {
                digests(pm.getPackageInfo(CLIENT, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo)
            }.getOrDefault(emptySet())
            if (want.isEmpty() || want != have) return "the installed $CLIENT is not signed like the one this image ships"
            return null
        }
    }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val ctx = context
        if (method != METHOD || ctx == null) return out.apply { putString("error", "unknown method") }
        val uid = Binder.getCallingUid()
        refusal(ctx, uid)?.let { why ->
            Log.w(TAG, "pairing: refused uid $uid: $why")
            return out.apply { putString("error", why) }
        }
        val containers = (ctx.applicationContext as DriverApp).containers
        val identity = Binder.clearCallingIdentity()
        try {
            val envs = runBlocking { containers.listContainers() }.getOrDefault(emptyList())
            for (env in envs) {
                val ran = runBlocking { containers.execSync(env, COMMAND, 15_000) }.getOrNull() ?: continue
                val code = parseCode(ran.stdout) ?: continue
                Log.i(TAG, "pairing: a code for $CLIENT from env $env")
                return out.apply { putString("code", code) }
            }
            Log.i(TAG, "pairing: no env with a running Tessera agent (${envs.size} env(s))")
            return out.apply { putString("error", "no env with a running Tessera agent") }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
