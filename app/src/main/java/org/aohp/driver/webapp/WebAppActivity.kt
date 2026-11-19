// Copyright (C) 2026 Chris Anderson. Licensed under the Apache License, Version 2.0.

package org.aohp.driver.webapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.aohp.driver.bridge.AohpContainerClient
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The window of one pinned web app (see [WebApps]); the app's id is the last segment of the intent's
 * data, aohp-webapp://app/<id>. Each app gets its own entry in recents.
 *
 * "dir" apps are shown under an origin of their own that never reaches the network: the Driver
 * answers every request with a file of the app's folder in the env and refuses everything else.
 * "url" apps are a server the env runs on 127.0.0.1; links that leave it open in the browser.
 */
class WebAppActivity : Activity() {
    private var web: WebView? = null
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private lateinit var retry: Button
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gone = false
    private var appId = ""
    private var container: AohpContainerClient? = null
    /** The first env check (in onCreate) has answered; later returns to the front check again. */
    private var checkedOnce = false
    @Volatile private var dropped = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.data?.lastPathSegment ?: ""
        val app = WebAppRegistry.get(this).get(id)
        if (app == null) {
            Toast.makeText(this, "This app is no longer on the phone", Toast.LENGTH_LONG).show()
            finishAndRemoveTask(); return
        }
        appId = id
        live[id] = this
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(app.name).build())
        val bg = runCatching { Color.parseColor(app.color) }.getOrDefault(Color.BLACK)

        val root = FrameLayout(this)
        root.setBackgroundColor(bg)
        val wv = WebView(this)
        wv.setBackgroundColor(bg)
        web = wv
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(64, 64, 64, 32) }
        retry = Button(this).apply { text = "Try again"; visibility = View.GONE }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setBackgroundColor(bg)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(wv, fill)
        root.addView(box, FrameLayout.LayoutParams(fill))
        setContentView(root)

        // The window is edge to edge: keep the page clear of the bars and the keyboard ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            var types = WindowInsets.Type.ime()
            if (!app.immersive) types = types or WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val i = insets.getInsets(types)
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        if (app.immersive) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        wv.webChromeClient = WebChromeClient()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (wv.canGoBack()) wv.goBack() else finish()
        }

        val container = AohpContainerClient(applicationContext)
        this.container = container
        // Is the env still the one this app was pinned from? One command in the env; the page waits for the answer.
        Thread {
            val v = try { EnvId.verify(applicationContext, container, app) } catch (t: Throwable) { EnvId.Verdict.UNKNOWN }
            main.post {
                if (gone) return@post
                checkedOnce = true
                if (v == EnvId.Verdict.GONE) envGone(app)
                else if (app.mode == "dir") showDir(app, wv, container) else showUrl(app, wv, container)
            }
        }.start()
    }

    /** The env this app was pinned from was destroyed, reset or replaced: say so and take the app off the phone. */
    private fun envGone(app: WebApp) {
        dropped = true
        web?.loadUrl("about:blank")
        WebApps.dropStale(applicationContext, app)
        status.text = app.name + " cannot be opened.\n\nIt was made in the env \"" + app.env + "\", which has been destroyed or reset since, " +
            "so its files are gone. The app has been removed from this phone. Its home-screen icon can be dragged away."
        retry.text = "Close"
        retry.setOnClickListener { finishAndRemoveTask() }
        retry.visibility = View.VISIBLE
        box.visibility = View.VISIBLE
    }

    // ---- "dir": files from the env, no network ----

    private fun showDir(app: WebApp, wv: WebView, c: AohpContainerClient) {
        val host = WebApps.host(app.id)
        wv.settings.blockNetworkLoads = true
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val u = request.url
                if (u.scheme != "https" || u.host != host) return plain(403, "Forbidden", "This app has no network access.")
                return serve(c, app, u.path ?: "/")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host != host
        }
        wv.loadUrl("https://" + host + "/" + Uri.encode(app.entry, "/"))
    }

    private fun headers(): MutableMap<String, String> = mutableMapOf(
        "Cache-Control" to "no-cache",
        // Second lock on the network, for what does not pass through request interception (WebSocket).
        "Content-Security-Policy" to "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; form-action 'self'",
    )

    private fun plain(code: Int, reason: String, text: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, headers(), ByteArrayInputStream(text.toByteArray()))

    private fun serve(c: AohpContainerClient, app: WebApp, path: String): WebResourceResponse {
        val o = try { EnvFiles.open(c, app.env, app.dir, path) } catch (t: Throwable) { null }
            ?: return plain(404, "Not Found", "Not found: " + path)
        val mime = EnvFiles.mime(o.path)
        return WebResourceResponse(mime, if (EnvFiles.isText(mime)) "utf-8" else null, 200, "OK", headers(), EnvFiles.stream(c, app.env, o))
    }

    // ---- "url": a server in the env ----

    private fun showUrl(app: WebApp, wv: WebView, c: AohpContainerClient) {
        val target = Uri.parse(app.url)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if ((u.host == "127.0.0.1" || u.host == "localhost") && u.port == target.port) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) unreachable(app)
            }
        }
        retry.setOnClickListener { connect(app, wv, c, target) }
        connect(app, wv, c, target)
    }

    private fun connect(app: WebApp, wv: WebView, c: AohpContainerClient, target: Uri) {
        status.text = "Starting " + app.name + "…"
        retry.visibility = View.GONE
        box.visibility = View.VISIBLE
        Thread {
            if (app.unit.isNotEmpty()) runCatching { c.execSync(app.env, "systemctl start " + EnvFiles.quote(app.unit), 30_000) }
            val up = waitForPort(target.port, if (app.unit.isNotEmpty()) 20_000 else 4_000)
            main.post {
                if (gone) return@post
                if (up) { box.visibility = View.GONE; wv.loadUrl(app.url) } else unreachable(app)
            }
        }.start()
    }

    private fun waitForPort(port: Int, budgetMs: Long): Boolean {
        val end = System.currentTimeMillis() + budgetMs
        while (!gone) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }; return true } catch (t: Throwable) { }
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(300)
        }
        return false
    }

    private fun unreachable(app: WebApp) {
        status.text = app.name + " is not answering at " + app.url + ".\n\nIts server runs in the env \"" + app.env + "\"" +
            (if (app.unit.isNotEmpty()) " as " + app.unit else "") + "."
        retry.visibility = View.VISIBLE
        box.visibility = View.VISIBLE
    }

    /** A window left open comes back to the front: its env may have been replaced in the meantime. */
    override fun onStart() {
        super.onStart()
        val c = container ?: return
        if (!checkedOnce || dropped) return
        Thread {
            // The record as it is now: one from before env ids has taken its id at the first check.
            val cur = WebAppRegistry.get(applicationContext).get(appId)
            val v = if (cur == null) EnvId.Verdict.UNKNOWN else try { EnvId.verify(applicationContext, c, cur) } catch (t: Throwable) { EnvId.Verdict.UNKNOWN }
            main.post {
                if (gone || dropped) return@post
                if (cur == null) finishAndRemoveTask() else if (v == EnvId.Verdict.GONE) envGone(cur)
            }
        }.start()
    }

    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onPause() { web?.onPause(); super.onPause() }

    override fun onDestroy() {
        gone = true
        if (appId.isNotEmpty()) live.remove(appId, this)
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    companion object {
        private val live = java.util.concurrent.ConcurrentHashMap<String, WebAppActivity>()

        /** A window still showing "its env is gone" for [id] is closed, so that a new pin of that id opens fresh. */
        fun closeIfDropped(id: String) {
            val a = live[id] ?: return
            if (a.dropped) a.runOnUiThread { a.finishAndRemoveTask() }
        }

        /** Close the window of an app that was removed, and take it out of recents. */
        fun close(id: String) {
            val a = live[id] ?: return
            a.runOnUiThread { a.finishAndRemoveTask() }
        }
    }
}
