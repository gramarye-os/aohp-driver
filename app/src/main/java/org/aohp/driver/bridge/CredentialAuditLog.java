package org.aohp.driver.bridge;

import android.util.Log;
import androidx.annotation.NonNull;

/**
 * Minimal audit sink for credential-broker operations. One line per cred.* call: principal,
 * provider, operation, outcome. Never logs secret values. Mirrors the framework
 * AohpSecurityAuditLog pattern on the Driver side; can later forward to the framework log.
 */
public final class CredentialAuditLog {
    private static final String TAG = "AohpCredAudit";

    public void record(@NonNull CallerIdentity caller, @NonNull String provider,
                       @NonNull String op, boolean ok) {
        Log.i(TAG, (ok ? "ALLOW " : "DENY  ") + op + " provider=" + provider
                + " caller=" + caller.principal() + " src=" + caller.source());
    }
}
