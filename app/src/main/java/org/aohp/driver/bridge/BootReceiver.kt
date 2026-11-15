// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** BOOT_COMPLETED -> bridge up, then autostart flagged gateways (see BridgeService.autostartGateways). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        Log.i(BridgeService.TAG, "boot: " + intent.action + " -> starting bridge")
        BridgeService.start(context, autostart = true)
    }
}
