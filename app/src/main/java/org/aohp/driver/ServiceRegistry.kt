// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers which containerd services were started per env (serviceId + command, in start
 * order) so the boot receiver can bring back everything, not only openclaw-gateway.
 * containerd itself keeps no service definitions across a reboot; this is the only record.
 *
 * Plain SharedPreferences (synchronous) so the Java bridge (JsonCommandHandler, the
 * `aohp sandbox svc-start/svc-stop` path) can update it without coroutines. One JSON array
 * per env under key "env:<name>". Removed on svc-stop and on sandbox destroy.
 */
class ServiceRegistry(ctx: Context) {
    data class Entry(val serviceId: String, val command: String)

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("services", Context.MODE_PRIVATE)

    private fun key(env: String) = "env:" + env

    /** Services recorded for [env], in the order they were first started. */
    fun list(env: String): List<Entry> {
        val raw = prefs.getString(key(env), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("serviceId", "")
                if (id.isEmpty()) null else Entry(id, o.optString("command", ""))
            }
        } catch (_: Throwable) { emptyList() }
    }

    /** Record (or update the command of) a started service; keeps its position if known. */
    @Synchronized
    fun record(env: String, serviceId: String, command: String) {
        if (env.isEmpty() || serviceId.isEmpty()) return
        val cur = list(env).toMutableList()
        val i = cur.indexOfFirst { it.serviceId == serviceId }
        if (i >= 0) cur[i] = Entry(serviceId, command) else cur.add(Entry(serviceId, command))
        save(env, cur)
    }

    @Synchronized
    fun forget(env: String, serviceId: String) {
        val cur = list(env).filterNot { it.serviceId == serviceId }
        save(env, cur)
    }

    @Synchronized
    fun forgetEnv(env: String) { prefs.edit().remove(key(env)).apply() }

    private fun save(env: String, entries: List<Entry>) {
        if (entries.isEmpty()) { prefs.edit().remove(key(env)).apply(); return }
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("serviceId", e.serviceId).put("command", e.command))
        prefs.edit().putString(key(env), arr.toString()).apply()
    }

    companion object {
        @Volatile private var inst: ServiceRegistry? = null
        /** Shared instance (Java callers: ServiceRegistry.Companion.get(ctx) / ServiceRegistry.get(ctx)). */
        @JvmStatic
        fun get(ctx: Context): ServiceRegistry =
            inst ?: synchronized(this) { inst ?: ServiceRegistry(ctx).also { inst = it } }
    }
}
