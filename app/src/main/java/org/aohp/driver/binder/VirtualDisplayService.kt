// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.binder

import android.util.Log
import com.android.internal.aohp.IAohpVirtualDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class VirtualDisplayInfo(val displayId: Int, val name: String, val width: Int, val height: Int, val type: Int, val state: Int, val topActivity: String, val raw: String)

/** Read-only status over IAohpVirtualDisplay (v1: list only). */
class VirtualDisplayService {
    companion object { const val SERVICE_NAME = "aohp_virtual_display" }

    @Volatile private var svc: IAohpVirtualDisplay? = null

    @Synchronized
    fun service(): IAohpVirtualDisplay? {
        svc?.let { if (it.asBinder().isBinderAlive) return it else svc = null }
        val b = ServiceManagerCompat.getService(SERVICE_NAME) ?: return null
        return IAohpVirtualDisplay.Stub.asInterface(b).also { svc = it }
    }

    suspend fun snapshotJson(): Result<String> = withContext(Dispatchers.IO) {
        val s = service() ?: return@withContext Result.failure(IllegalStateException("aohp_virtual_display not available"))
        runCatching { s.getDisplayRuntimeSnapshotJson(null) ?: "{}" }
            .onFailure { Log.w(ContainerService.TAG, "vd snapshot failed", it) }
    }

    /** Best-effort parse: looks for an array of display objects anywhere at top level. */
    suspend fun listDisplays(): Result<List<VirtualDisplayInfo>> = snapshotJson().map { raw ->
        val out = mutableListOf<VirtualDisplayInfo>()
        // Schema (ActivityTaskManagerService.buildAohpDisplayRuntimeSnapshotJson):
        // {timestamp, displays:[{displayId, display:{name,type,logicalWidth,logicalHeight,state,...}, topRunningActivity:{...}, rootTasks:[...]}]}
        fun add(o: JSONObject) {
            val id = o.optInt("displayId", -1)
            val d = o.optJSONObject("display") ?: o
            val top = o.optJSONObject("topRunningActivity")
            val topName = top?.optString("component", top.optString("packageName", "")) ?: ""
            out += VirtualDisplayInfo(id, d.optString("name", ""), d.optInt("logicalWidth", 0), d.optInt("logicalHeight", 0),
                d.optInt("type", -1), d.optInt("state", -1), topName, o.toString())
        }
        runCatching {
            val top = JSONObject(raw)
            val keys = top.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = top.opt(k)
                if (v is JSONArray) for (i in 0 until v.length()) v.optJSONObject(i)?.let(::add)
            }
        }.recoverCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
        }
        out
    }
}
