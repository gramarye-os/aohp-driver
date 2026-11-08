package org.aohp.driver.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.aohp.driver.Tab
import org.aohp.driver.harness.GatewayEndpoint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import java.net.HttpURLConnection
import java.net.URL

private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "::1", "0.0.0.0")

/** Single app-wide WebView so the Control UI keeps its state across tab switches. */
object WebHolder {
    @SuppressLint("StaticFieldLeak") var webView: WebView? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(modifier: Modifier = Modifier, onGoToTab: (Tab) -> Unit) {
    val ctx = LocalContext.current
    var gatewayUp by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var title by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    // Probe loop: while down, re-probe every 3 s; when up, load once.
    LaunchedEffect(Unit) {
        while (true) {
            val up = withContext(Dispatchers.IO) {
                runCatching {
                    GatewayEndpoint.refresh((ctx.applicationContext as org.aohp.driver.DriverApp).containers, null)
                    val c = GatewayEndpoint.open()
                    c.connectTimeout = 1500; c.readTimeout = 2500
                    try { c.responseCode in 200..399 } finally { c.disconnect() }
                }.getOrDefault(false)
            }
            if (up && gatewayUp != true) {
                val wv = WebHolder.webView
                if (wv != null && (wv.url == null || loadError != null)) { loadError = null; wv.loadUrl(GatewayEndpoint.url()) }
            }
            gatewayUp = up
            delay(if (up) 10_000 else 3_000)
        }
    }

    val webView = remember {
        WebHolder.webView ?: WebView(ctx.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            WebHolder.webView = this
        }
    }
    // (Re)bind clients each composition of the screen so callbacks update this screen's state.
    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val host = u.host ?: return false
                if (u.scheme in setOf("http", "https") && host in LOOPBACK) return false
                return try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (t: Throwable) { Log.w("AohpDriver", "no handler for $u", t); true }
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { loadError = null; canGoBack = view.canGoBack() }
            override fun onPageFinished(view: WebView, url: String?) { canGoBack = view.canGoBack(); title = view.title ?: "" }
            // The gateway's self-signed cert: accept it only when it is the pinned one and the page is loopback.
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = runCatching { java.net.URL(error.url).host }.getOrNull()
                val cert = runCatching { error.certificate?.x509Certificate }.getOrNull()
                if ((host == "127.0.0.1" || host == "localhost") && GatewayEndpoint.matches(cert)) handler.proceed() else handler.cancel()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { loadError = error.description?.toString() ?: "load error"; gatewayUp = false }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t ?: "" }
        }
        onDispose { (webView.parent as? ViewGroup)?.removeView(webView) }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(title.ifEmpty { "Web" }, maxLines = 1) }, actions = {
                IconButton(onClick = { webView.loadUrl(GatewayEndpoint.url()) }) { Icon(Icons.Filled.Home, "Home") }
                IconButton(onClick = { webView.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            val showPlaceholder = gatewayUp == false && (webView.url == null || loadError != null)
            if (showPlaceholder) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Gateway is down", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(GatewayEndpoint.url(), fontFamily = FontFamily.Monospace)
                        loadError?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(16.dp))
                        Row {
                            Button(onClick = { onGoToTab(Tab.Harness) }) { Text("Go to Harness") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { loadError = null; webView.loadUrl(GatewayEndpoint.url()) }) { Text("Retry") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Re-probing every 3 s…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                AndroidView(factory = { (webView.parent as? ViewGroup)?.removeView(webView); webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
