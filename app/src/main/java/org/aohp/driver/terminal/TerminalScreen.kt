// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import org.aohp.driver.DriverApp
import org.aohp.driver.ui.CenteredMessage

private const val TERM_URL = "https://appassets.androidplatform.net/assets/term/index.html"
private const val TAG = "AohpDriver"

/** Extra-key row: label → bytes sent to the pty ("Ctrl"/"Alt" are sticky modifiers; "Paste" sends the clipboard). */
private val EXTRA_KEYS: List<Pair<String, String>> = listOf(
    "Paste" to "", "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "", "Alt" to "",
    "←" to "\u001b[D", "↓" to "\u001b[B", "↑" to "\u001b[A", "→" to "\u001b[C",
    "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~",
    "-" to "-", "|" to "|", "/" to "/", "~" to "~",
)

/**
 * JS ↔ Kotlin bridge, exposed as window.AohpTerm. Modifier state lives here
 * (plain volatile fields) because onInput runs on the WebView's JavaBridge thread.
 */
class TermBridge(private val session: PtySession) {
    @Volatile var ctrl = false
    @Volatile var alt = false
    @Volatile var onModsConsumed: (() -> Unit)? = null
    @Volatile var ready = false

    @JavascriptInterface fun onInput(data: String) {
        if (data.isEmpty()) { Log.d(TAG, "xterm onData(\"\") ignored"); return }
        var bytes = data.toByteArray(Charsets.UTF_8)
        if ((ctrl || alt) && data.length == 1) {
            val c = data[0]
            if (ctrl) {
                val code = when {
                    c in 'a'..'z' -> c.code - 'a'.code + 1
                    c in 'A'..'Z' -> c.code - 'A'.code + 1
                    c == '[' -> 27; c == '\\' -> 28; c == ']' -> 29; c == '^' -> 30; c == '_' -> 31
                    c == ' ' || c == '@' -> 0
                    else -> -1
                }
                if (code >= 0) bytes = byteArrayOf(code.toByte())
            }
            if (alt) bytes = byteArrayOf(0x1b) + bytes
            ctrl = false; alt = false
            onModsConsumed?.invoke()
        }
        session.write(bytes)
    }
    @JavascriptInterface fun onBinary(data: String) { session.write(ByteArray(data.length) { data[it].code.toByte() }) }
    @JavascriptInterface fun onResize(cols: Int, rows: Int) { session.resize(cols, rows) }
    /** True when the size is set beside the shell, so the page need not wait for an empty prompt. */
    @JavascriptInterface fun sizeDirect(): Boolean = session.sizeDirect
    @JavascriptInterface fun onReady() { ready = true }
}

/**
 * Where an Esc key press goes while the Terminal tab is on screen (set by TerminalScreen, read by
 * MainActivity.dispatchKeyEvent, which explains why the activity takes the key): a terminal needs
 * Esc as the byte 0x1b (vi, nano's Meta).
 */
object TerminalKeys {
    @Volatile var esc: (() -> Unit)? = null
}

/** A live xterm.js WebView bound to one PtySession. Survives tab switches. */
@SuppressLint("SetJavaScriptEnabled")
class TerminalView(ctx: Context, val session: PtySession) {
    val bridge = TermBridge(session)
    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile var pageReady = false
    val webView: WebView

    init {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
        webView = WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            setBackgroundColor(0xFF0B0F14.toInt())
            webViewClient = object : org.aohp.driver.web.EscSafeWebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = loader.shouldInterceptRequest(request.url)
                override fun onPageFinished(view: WebView, url: String?) {
                    pageReady = true
                    session.attach(
                        s = { b64 -> post { evaluateJavascript("termWrite('$b64')", null) } },
                        closedCb = {
                            val msg = Base64.encodeToString("\r\n[${session.exitReason ?: "closed"}]\r\n".toByteArray(), Base64.NO_WRAP)
                            post { evaluateJavascript("termWrite('$msg')", null) }
                            onStatus?.invoke("exited")
                        },
                    )
                    post { evaluateJavascript("termFit(); termFocus();", null) }
                    onStatus?.invoke("connected")
                }
            }
            addJavascriptInterface(bridge, "AohpTerm")
            loadUrl(TERM_URL)
        }
    }

    fun fit() { if (pageReady) webView.post { webView.evaluateJavascript("termFit()", null) } }
    /** Types the clipboard text into the terminal. Goes through xterm so a shell's bracketed paste is honoured. */
    fun pasteClipboard(ctx: Context) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        if (text.isEmpty() || !pageReady) return
        webView.post { webView.evaluateJavascript("termPaste(" + org.json.JSONObject.quote(text) + "); termFocus();", null) }
    }
    fun detachFromParent() { (webView.parent as? ViewGroup)?.removeView(webView) }
    fun destroy() { session.detach(); detachFromParent(); webView.destroy() }
}

/** Activity-lifetime holder of terminal views (one per env). */
object TerminalHolder {
    private val views = HashMap<String, TerminalView>()
    @Synchronized fun get(ctx: Context, session: PtySession): TerminalView {
        views[session.env]?.let { if (it.session === session) return it else it.destroy() }
        return TerminalView(ctx, session).also { views[session.env] = it }
    }
    @Synchronized fun drop(env: String) { views.remove(env)?.destroy() }
    @Synchronized fun destroyAll() { views.values.forEach { it.destroy() }; views.clear() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as DriverApp
    val selected by app.settings.selectedEnv.collectAsStateWithLifecycle(initialValue = null)
    val env = selected
    var session by remember(env) { mutableStateOf<PtySession?>(app.ptySessions.get(env ?: "")) }
    var error by remember(env) { mutableStateOf<String?>(null) }
    var status by remember(env) { mutableStateOf(if (session != null) "connected" else "") }
    var reopenTick by remember { mutableStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }

    LaunchedEffect(env, reopenTick) {
        if (env == null) return@LaunchedEffect
        if (session?.closed == false && reopenTick == 0) return@LaunchedEffect
        session = null; error = null
        app.ptySessions.open(env).onSuccess { session = it; status = "connected" }.onFailure { error = it.message ?: it.toString() }
    }

    fun closeShell() { env?.let { TerminalHolder.drop(it); app.ptySessions.close(it) } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Terminal" + (env?.let { " · $it" } ?: "") + if (status.isNotEmpty()) "  ($status)" else "") },
                actions = {
                    IconButton(onClick = { closeShell(); reopenTick++ }) { Icon(Icons.Filled.Refresh, "Reconnect") }
                    IconButton(onClick = { closeShell(); session = null; status = "closed" }) { Icon(Icons.Filled.PowerSettingsNew, "Close shell") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val s = session
            when {
                env == null -> CenteredMessage("Select an environment on the Runtime tab first.", Modifier.weight(1f))
                error != null -> CenteredMessage("openShell failed:\n$error", Modifier.weight(1f))
                s == null -> CenteredMessage(if (status == "closed") "Shell closed. Tap ↻ to reconnect." else "Opening shell in $env…", Modifier.weight(1f))
                else -> {
                    val tv = remember(s) { TerminalHolder.get(ctx, s) }
                    DisposableEffect(tv) {
                        tv.onStatus = { st -> status = st }
                        tv.bridge.onModsConsumed = { ctrl = false; alt = false }
                        // Same as the Esc pill below, for an Esc key on a keyboard.
                        val onEsc: () -> Unit = { s.write("\u001b"); ctrl = false; alt = false }
                        TerminalKeys.esc = onEsc
                        onDispose {
                            if (TerminalKeys.esc === onEsc) TerminalKeys.esc = null
                            tv.onStatus = null; tv.bridge.onModsConsumed = null; tv.detachFromParent()
                        }
                    }
                    tv.bridge.ctrl = ctrl; tv.bridge.alt = alt
                    AndroidView(
                        factory = { tv.detachFromParent(); tv.webView },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        update = { tv.fit() },
                    )
                    Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EXTRA_KEYS.forEach { (label, seq) ->
                            val active = (label == "Ctrl" && ctrl) || (label == "Alt" && alt)
                            FilledTonalButton(
                                onClick = {
                                    when (label) {
                                        "Ctrl" -> ctrl = !ctrl
                                        "Alt" -> alt = !alt
                                        "Paste" -> tv.pasteClipboard(ctx)
                                        else -> { s.write(seq); ctrl = false; alt = false }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = if (active) ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                                else ButtonDefaults.filledTonalButtonColors(),
                            ) { Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }
    }
}
