// Copyright (C) 2026 Chris Anderson. Licensed under the Apache License, Version 2.0.

package org.aohp.driver.web

import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A WebViewClient that keeps an Esc key from closing the window.
 *
 * When a page does not use a key, the WebView reports it here, and the default hands it back to the
 * window as unhandled. The window then applies the keyboard's fallback for it, and for Esc that is
 * Back: the page is left, or the whole app. This happens after the key was dispatched and no matter
 * what dispatchKeyEvent returned for it. So Esc stops here; every other key keeps the default.
 */
open class EscSafeWebViewClient : WebViewClient() {
    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return
        super.onUnhandledKeyEvent(view, event)
    }
}
