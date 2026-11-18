// Copyright (C) 2026 Chris Anderson. Licensed under the Apache License, Version 2.0.

package org.aohp.driver.webapp

import android.content.Context
import org.json.JSONObject

/**
 * A web app an env has put on the home screen (bridge methods webapp.*).
 *
 * mode "dir": the files of [dir] in [env], handed to the window one by one; the app has no network.
 * mode "url": a server the env runs on 127.0.0.1 ([url]); [unit], if set, is started first.
 */
data class WebApp(
    val id: String, val name: String, val env: String, val mode: String,
    val dir: String = "", val entry: String = "index.html",
    val url: String = "", val unit: String = "",
    val color: String = "", val immersive: Boolean = false, val createdMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("env", env).put("mode", mode)
        .put("dir", dir).put("entry", entry).put("url", url).put("unit", unit)
        .put("color", color).put("immersive", immersive).put("createdMs", createdMs)

    companion object {
        fun from(o: JSONObject) = WebApp(
            o.optString("id"), o.optString("name"), o.optString("env"), o.optString("mode"),
            o.optString("dir"), o.optString("entry", "index.html"), o.optString("url"), o.optString("unit"),
            o.optString("color"), o.optBoolean("immersive", false), o.optLong("createdMs", 0L))
    }
}

/** The pinned web apps, one preference entry per id. */
class WebAppRegistry private constructor(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("webapps", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<WebApp> =
        prefs.all.values.mapNotNull { v -> (v as? String)?.let { runCatching { WebApp.from(JSONObject(it)) }.getOrNull() } }
            .filter { it.id.isNotEmpty() }.sortedBy { it.name.lowercase() }

    @Synchronized fun get(id: String): WebApp? =
        prefs.getString(id, null)?.let { runCatching { WebApp.from(JSONObject(it)) }.getOrNull() }

    @Synchronized fun put(a: WebApp) { prefs.edit().putString(a.id, a.toJson().toString()).commit() }

    @Synchronized fun remove(id: String): Boolean {
        if (!prefs.contains(id)) return false
        prefs.edit().remove(id).commit(); return true
    }

    companion object {
        @Volatile private var inst: WebAppRegistry? = null
        @JvmStatic fun get(ctx: Context): WebAppRegistry =
            inst ?: synchronized(this) { inst ?: WebAppRegistry(ctx).also { inst = it } }
    }
}
