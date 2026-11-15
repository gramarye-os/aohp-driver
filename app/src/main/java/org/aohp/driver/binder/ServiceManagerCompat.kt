// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.binder

import android.os.IBinder
import android.util.Log

/**
 * Reflection access to the hidden android.os.ServiceManager.getService().
 * Works because the APK is signed with the platform key (hidden-API checks
 * are disabled for platform-signed apps).
 */
object ServiceManagerCompat {
    private const val TAG = "AohpDriver"

    /** Last failure (for the UI / report), null when the last call succeeded. */
    @Volatile var lastError: String? = null
        private set

    fun getService(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val m = sm.getMethod("getService", String::class.java)
            val b = m.invoke(null, name) as IBinder?
            if (b == null) {
                lastError = "ServiceManager.getService(\"$name\") returned null"
                Log.w(TAG, lastError!!)
            } else lastError = null
            b
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = "getService($name) failed: " + cause.javaClass.name + ": " + cause.message
            Log.e(TAG, lastError!!, cause)
            null
        }
    }
}
