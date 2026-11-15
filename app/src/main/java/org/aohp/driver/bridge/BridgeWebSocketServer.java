// Ported from AOHPAgentDriverApp (https://github.com/aohp-os/AOHPAgentDriverApp), which states
// no licence: this file is NOT under this repository's Apache-2.0 licence, and its upstream
// authors keep all rights in their code. See NOTICE. What was changed or added here is
// Copyright (C) 2026 Chris Anderson, under Apache-2.0 as far as it can be told apart.

package org.aohp.driver.bridge;

import android.content.Context;
import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket 服务：仅接受 JSON 对象消息，由 {@link JsonCommandHandler} 处理（与 aohp CLI 一致）。
 */
public class BridgeWebSocketServer extends WebSocketServer {
    private static final String TAG = "AohpDriver";

    private JsonCommandHandler jsonCommandHandler;
    /**
     * RPCs run here, not on the Java-WebSocket worker threads. A worker is pinned to a set of
     * connections, and blocking handlers (sandbox.exec with a long timeout, createContainer)
     * would starve every other connection on the same worker — including nested aohp calls
     * made by the very command being executed (deadlock until the exec timeout).
     */
    private final ExecutorService dispatchPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aohp-rpc");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean listening;
    private volatile Exception lastError;

    public boolean isListening() { return listening; }
    public Exception getLastError() { return lastError; }

    public BridgeWebSocketServer(InetSocketAddress host) {
        super(host);
        Log.d(TAG, "WebSocket 监听 " + host);
    }

    public void setContext(Context context) {
        if (context != null) {
            jsonCommandHandler = new JsonCommandHandler(context);
        } else {
            jsonCommandHandler = null;
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.d(TAG, "onOpen: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.d(TAG, "onClose: " + code + " " + reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "onMessage: " + message);
        if (message != null && message.trim().startsWith("{")) {
            if (jsonCommandHandler != null) {
                final JsonCommandHandler h = jsonCommandHandler;
                dispatchPool.execute(() -> h.dispatch(conn, message));
            } else {
                try {
                    conn.send(
                            new JSONObject()
                                    .put("status", "failed")
                                    .put("message", "JsonCommandHandler not ready")
                                    .toString());
                } catch (JSONException e) {
                    Log.e(TAG, "Error building response", e);
                }
            }
        } else {
            try {
                String err =
                        new JSONObject()
                                .put("status", "failed")
                                .put(
                                        "message",
                                        "Only JSON-RPC is supported; send a JSON object (same as aohp CLI).")
                                .toString();
                conn.send(err);
            } catch (JSONException e) {
                Log.e(TAG, "Error sending error response", e);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        Log.d(TAG, "onMessage(ByteBuffer) — echo binary");
        conn.send(message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "onError", ex);
        if (conn == null) lastError = ex;
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart: WebSocket server running");
        listening = true;
    }

    public void stopServer() {
        listening = false;
        for (WebSocket connection : getConnections()) {
            connection.close();
        }
        try {
            super.stop(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String byteBufferToString(ByteBuffer buffer) {
        try {
            Charset charset = Charset.forName("UTF-8");
            CharsetDecoder decoder = charset.newDecoder();
            CharBuffer charBuffer = decoder.decode(buffer);
            buffer.flip();
            return charBuffer.toString();
        } catch (Exception ex) {
            Log.w(TAG, "byteBufferToString", ex);
            return null;
        }
    }
}
