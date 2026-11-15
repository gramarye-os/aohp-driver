// Ported from AOHPAgentDriverApp (https://github.com/aohp-os/AOHPAgentDriverApp), which states
// no licence: this file is NOT under this repository's Apache-2.0 licence, and its upstream
// authors keep all rights in their code. See NOTICE. What was changed or added here is
// Copyright (C) 2026 Chris Anderson, under Apache-2.0 as far as it can be told apart.

package org.aohp.driver.bridge;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.aohp.IAohpEventStream;

import org.json.JSONObject;

/** Binder client for the {@code aohp_event_stream} system service. */
public final class AohpEventStreamClient {
    private static final String TAG = "AohpEventStreamClient";
    public static final String SERVICE_NAME = "aohp_event_stream";

    private volatile IAohpEventStream mService;

    public AohpEventStreamClient(Context context) {
    }

    public boolean isServiceAvailable() {
        return getService() != null;
    }

    public JSONObject register(String clientId, JSONObject options) {
        return call(() -> getServiceOrThrow().register(clientId, json(options)));
    }

    public JSONObject drain(String sessionId, JSONObject options) {
        return call(() -> getServiceOrThrow().drain(sessionId, json(options)));
    }

    public JSONObject unregister(String sessionId) {
        return call(() -> {
            JSONObject o = new JSONObject();
            o.put("ok", getServiceOrThrow().unregister(sessionId));
            o.put("sessionId", sessionId);
            return o.toString();
        });
    }

    public JSONObject status() {
        return call(() -> getServiceOrThrow().status());
    }

    private IAohpEventStream getServiceOrThrow() throws RemoteException {
        IAohpEventStream svc = getService();
        if (svc == null) {
            throw new RemoteException("aohp_event_stream unavailable");
        }
        return svc;
    }

    private IAohpEventStream getService() {
        IAohpEventStream s = mService;
        if (s != null) return s;
        synchronized (this) {
            s = mService;
            if (s != null) return s;
            try {
                IBinder b = getServiceManagerBinder(SERVICE_NAME);
                if (b == null) return null;
                s = IAohpEventStream.Stub.asInterface(b);
                mService = s;
            } catch (Throwable t) {
                Log.w(TAG, "getService failed: " + t.getMessage());
                return null;
            }
            return s;
        }
    }

    private static IBinder getServiceManagerBinder(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private JSONObject call(RemoteJsonCall call) {
        try {
            String s = call.run();
            if (s == null || s.isEmpty()) {
                return new JSONObject().put("ok", false).put("error", "empty");
            }
            return new JSONObject(s);
        } catch (Exception e) {
            try {
                return new JSONObject()
                        .put("error", true)
                        .put("code", "event_stream")
                        .put("message", e.getMessage());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private static String json(JSONObject o) {
        return o != null ? o.toString() : "{}";
    }

    private interface RemoteJsonCall {
        String run() throws Exception;
    }
}
