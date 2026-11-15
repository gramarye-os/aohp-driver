// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.bridge

import android.content.Context
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One-shot migration: connect to another bridge (the stock app's ws://127.0.0.1:6666)
 * as a client, read every secret with secret.list / secret.get and store it in this
 * app's SecretStore. Values are never logged.
 */
object LegacySecretImport {
    private const val TAG = "AohpDriver"

    data class Result(val imported: List<String>, val failed: List<String>, val remoteApp: String?)

    fun run(ctx: Context, url: String = "ws://127.0.0.1:6666", timeoutSec: Long = 10): Result {
        val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
        val opened = CompletableFuture<Boolean>()
        val client = object : WebSocketClient(URI(url)) {
            override fun onOpen(h: ServerHandshake?) { opened.complete(true) }
            override fun onMessage(m: String) {
                val o = runCatching { JSONObject(m) }.getOrNull() ?: return
                pending.remove(o.optString("id"))?.complete(o)
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                opened.complete(false)
                pending.values.forEach { it.completeExceptionally(IllegalStateException("closed: $reason")) }
            }
            override fun onError(e: Exception) { opened.completeExceptionally(e) }
        }
        var n = 0
        fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
            val id = "imp" + (++n)
            val f = CompletableFuture<JSONObject>()
            pending[id] = f
            client.send(JSONObject().put("id", id).put("method", method).put("params", params).toString())
            val r = f.get(timeoutSec, TimeUnit.SECONDS)
            if (!r.optBoolean("ok")) throw IllegalStateException(method + ": " + r.optJSONObject("error")?.optString("message"))
            return r.getJSONObject("result")
        }
        client.connect()
        if (opened.get(timeoutSec, TimeUnit.SECONDS) != true) throw IllegalStateException("no legacy bridge at $url")
        try {
            val meta = runCatching { call("meta.version") }.getOrNull()
            val remoteApp = meta?.optString("app")
            if (remoteApp == ctx.packageName) throw IllegalStateException("$url is this app's own bridge")
            val names: JSONArray = call("secret.list").optJSONArray("names") ?: JSONArray()
            val store = SecretStore(ctx)
            val ok = ArrayList<String>(); val bad = ArrayList<String>()
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                try {
                    val v = call("secret.get", JSONObject().put("name", name)).getString("value")
                    if (store.set(name, v)) ok.add(name) else bad.add(name)
                } catch (e: Exception) {
                    Log.w(TAG, "import $name failed: ${e.message}")
                    bad.add(name)
                }
            }
            Log.i(TAG, "legacy secret import from $remoteApp: ok=$ok failed=$bad")
            return Result(ok, bad, remoteApp)
        } finally {
            runCatching { client.closeBlocking() }
        }
    }
}
