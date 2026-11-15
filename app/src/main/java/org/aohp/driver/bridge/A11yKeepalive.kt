// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.bridge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing with events. Being *enabled* is what matters: AccessibilityManagerService
 * only tracks windows and apps only register their view-hierarchy connections while an
 * accessibility service is enabled, and the framework's AOHP ui-tree / node-action hooks
 * (used by ui.tree, ui.find, shot.node, act.*_node) read those. The stock AOHPAgentDriver
 * satisfied this with MyAccessibilityService; this is the minimal replacement.
 */
class BridgeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var connected = false
            private set
    }
    override fun onServiceConnected() { super.onServiceConnected(); connected = true; Log.i(BridgeService.TAG, "a11y keepalive connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { connected = false; return super.onUnbind(intent) }
}

object A11yKeepalive {
    private const val TAG = BridgeService.TAG

    fun component(ctx: Context) = ComponentName(ctx, BridgeAccessibilityService::class.java)

    fun isEnabled(ctx: Context): Boolean {
        val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = component(ctx).flattenToString()
        return cur.split(':').any { it == me || it == component(ctx).flattenToShortString() } &&
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }

    /** Self-enable via WRITE_SECURE_SETTINGS (granted to the platform-signed app). Returns null on success, else error. */
    fun ensureEnabled(ctx: Context): String? {
        return try {
            val cr = ctx.contentResolver
            val me = component(ctx).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val parts = cur.split(':').filter { it.isNotBlank() }
            if (me !in parts) {
                val next = (parts + me).joinToString(":")
                if (!Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)) return "putString failed"
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                if (!Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) return "putInt failed"
            }
            Log.i(TAG, "a11y keepalive enabled in secure settings")
            null
        } catch (e: Exception) {
            Log.w(TAG, "a11y keepalive enable failed", e)
            e.toString()
        }
    }

    /** Remove ourselves from the secure setting (used when the bridge is stopped by the user). */
    fun disable(ctx: Context) {
        try {
            val cr = ctx.contentResolver
            val me = component(ctx).flattenToString()
            val parts = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').filter { it.isNotBlank() && it != me }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, parts.joinToString(":"))
            if (parts.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        } catch (e: Exception) { Log.w(TAG, "a11y keepalive disable failed", e) }
    }
}
