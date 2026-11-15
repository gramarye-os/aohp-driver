// Ported from AOHPAgentDriverApp (https://github.com/aohp-os/AOHPAgentDriverApp), which states
// no licence: this file is NOT under this repository's Apache-2.0 licence, and its upstream
// authors keep all rights in their code. See NOTICE. What was changed or added here is
// Copyright (C) 2026 Chris Anderson, under Apache-2.0 as far as it can be told apart.

package org.aohp.driver.bridge;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.aohp.IAohpSecurityBridge;

/** Binder client for the {@code aohp_security_bridge} system service. */
public final class AohpSecurityBridgeClient {
    private static final String TAG = "AohpSecurityBridgeClient";
    public static final String SERVICE_NAME = "aohp_security_bridge";

    private volatile IAohpSecurityBridge mService;

    public boolean isServiceAvailable() {
        return getService() != null;
    }

    public String resolveVaultReference(String maybeBracketedToken) {
        if (maybeBracketedToken == null || maybeBracketedToken.isEmpty()) {
            return maybeBracketedToken;
        }
        String token = maybeBracketedToken.trim();
        if (token.startsWith("[") && token.endsWith("]") && token.length() > 2) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (!token.contains("aohp://vault/")) {
            return maybeBracketedToken;
        }
        try {
            String plain = getServiceOrThrow().resolveVaultToken(token, "event_drain");
            return plain != null && !plain.isEmpty() ? plain : maybeBracketedToken;
        } catch (Exception e) {
            Log.w(TAG, "resolveVaultReference failed: " + e.getMessage());
            return maybeBracketedToken;
        }
    }

    private IAohpSecurityBridge getServiceOrThrow() throws RemoteException {
        IAohpSecurityBridge svc = getService();
        if (svc == null) {
            throw new RemoteException("aohp_security_bridge unavailable");
        }
        return svc;
    }

    private IAohpSecurityBridge getService() {
        IAohpSecurityBridge s = mService;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            s = mService;
            if (s != null) {
                return s;
            }
            try {
                IBinder b = getServiceManagerBinder(SERVICE_NAME);
                if (b == null) {
                    return null;
                }
                s = IAohpSecurityBridge.Stub.asInterface(b);
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
}
