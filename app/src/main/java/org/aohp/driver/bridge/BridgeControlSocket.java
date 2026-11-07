package org.aohp.driver.bridge;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AF_UNIX control channel for the credential broker ({@code cred.*}).
 *
 * <p>Unlike the TCP WebSocket bridge (127.0.0.1:6666), a Unix-domain socket exposes the caller's
 * kernel-supplied credentials via {@link LocalSocket#getPeerCredentials()}. The peer uid cannot be
 * forged from user space and maps 1:1 to an installed app (or a container uid), so it is the
 * authoritative caller principal for authorizing {@code cred.*} — stronger than a capability token
 * over TCP. This is build-order item 1b of {@code docs/CREDENTIALS.md}.
 *
 * <p>Protocol: newline-delimited JSON. Each request line is {@code {"id":..,"method":"cred.x",
 * "params":{..}}}; the reply is one JSON object per line. Only {@code cred.*} methods are served
 * here; everything else returns {@code unknown_method}. Bound to an abstract-namespace socket, so
 * no filesystem path and no TCP port is exposed.
 */
public final class BridgeControlSocket {

    private static final String TAG = "AohpCredCtl";
    /** Abstract-namespace name clients connect to: LocalSocketAddress("aohp_cred", ABSTRACT). */
    public static final String SOCKET_NAME = "aohp_cred";

    private final Context ctx;
    private final JsonCommandHandler handler;
    private final AohpContainerClient containers;   // peer pid -> env name (env:<name> principals)
    private volatile boolean running;
    private Thread acceptThread;
    private LocalServerSocket serverSocket;

    public BridgeControlSocket(@NonNull Context ctx, @NonNull JsonCommandHandler handler) {
        this.ctx = ctx.getApplicationContext();
        this.handler = handler;
        this.containers = new AohpContainerClient(this.ctx);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "aohp-cred-ctl");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    private void acceptLoop() {
        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            Log.i(TAG, "cred control socket listening on @" + SOCKET_NAME);
        } catch (Exception e) {
            Log.e(TAG, "failed to bind @" + SOCKET_NAME + ": " + e.getMessage());
            running = false;
            return;
        }
        while (running) {
            final LocalSocket conn;
            try {
                conn = serverSocket.accept();
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept failed: " + e.getMessage());
                continue;
            }
            // Handle each connection on its own short-lived thread.
            new Thread(() -> serveConnection(conn), "aohp-cred-conn").start();
        }
    }

    private void serveConnection(@NonNull LocalSocket conn) {
        CallerIdentity caller;
        try {
            Credentials pc = conn.getPeerCredentials();
            caller = CallerIdentity.fromPeerCredentials(ctx, pc, containers);
        } catch (Exception e) {
            caller = CallerIdentity.anonymous();
        }
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = conn.getOutputStream()) {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                JSONObject reply = dispatch(line, caller);
                out.write((reply.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            Log.d(TAG, "connection ended: " + e.getMessage());
        } finally {
            try { conn.close(); } catch (Exception ignored) {}
        }
    }

    private JSONObject dispatch(@NonNull String line, @NonNull CallerIdentity caller) {
        String id = "";
        try {
            JSONObject req = new JSONObject(line);
            id = req.optString("id", "");
            String method = req.optString("method", "");
            JSONObject params = req.optJSONObject("params");
            if (params == null) params = new JSONObject();
            if (!method.startsWith("cred.")) {
                return reply(id, false, null, "unknown_method", method);
            }
            JSONObject result = handler.handleCredControl(method, params, caller);
            if (result != null && result.optBoolean("error", false)) {
                return reply(id, false, null,
                        result.optString("code", "error"), result.optString("message", "failed"));
            }
            return reply(id, true, result, null, null);
        } catch (Exception e) {
            return reply(id, false, null, "exception", e.getMessage());
        }
    }

    private static JSONObject reply(String id, boolean ok, JSONObject result, String code, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("ok", ok);
            if (result != null) o.put("result", result);
            if (!ok) {
                JSONObject err = new JSONObject();
                err.put("code", code == null ? "error" : code);
                err.put("message", msg == null ? "failed" : msg);
                o.put("error", err);
            }
        } catch (Exception ignored) {}
        return o;
    }
}
