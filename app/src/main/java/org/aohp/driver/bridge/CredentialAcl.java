package org.aohp.driver.bridge;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-(principal, provider) authorization for the credential broker. Default is DENY.
 *
 * <p>Granting a provider to a caller is an explicit, user-visible decision (a Driver consent
 * screen). The grant table is persisted in {@link SecretStore} under {@link #ACL_KEY} as JSON so
 * it survives restarts and is itself held in the Keystore-backed store.
 *
 * <p>Stored shape:
 * <pre>{ "org.aohp.search": ["anthropic","brave"], "uid:10234": ["anthropic"] }</pre>
 */
public final class CredentialAcl {

    static final String ACL_KEY = "__cred_acl__";

    private final SecretStore store;
    private final Map<String, Set<String>> grants = new HashMap<>();

    public CredentialAcl(@NonNull SecretStore store) {
        this.store = store;
        load();
    }

    private synchronized void load() {
        grants.clear();
        String raw = store.has(ACL_KEY) ? store.get(ACL_KEY) : null;
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String principal = it.next();
                JSONArray arr = o.optJSONArray(principal);
                Set<String> provs = new HashSet<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) provs.add(arr.optString(i));
                grants.put(principal, provs);
            }
        } catch (JSONException ignored) {
            // Corrupt ACL => treat as empty (deny-all), the safe direction.
        }
    }

    private synchronized void persist() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Set<String>> e : grants.entrySet()) {
                o.put(e.getKey(), new JSONArray(e.getValue()));
            }
            if (!store.set(ACL_KEY, o.toString())) {
                android.util.Log.e("AohpCredAudit", "ACL persist FAILED (store rejected " + ACL_KEY + ")");
            }
        } catch (JSONException ignored) { }
    }

    /** The one gate the broker calls on every cred.* operation. Fail-closed. */
    public synchronized boolean isAllowed(@NonNull CallerIdentity caller, @NonNull String provider) {
        if (caller.isAnonymous()) return false;
        load();   // grants may have been changed by CredentialConsentActivity since construction
        Set<String> provs = grants.get(caller.principal());
        return provs != null && provs.contains(provider);
    }

    public synchronized void grant(@NonNull String principal, @NonNull String provider) {
        load();
        grants.computeIfAbsent(principal, k -> new HashSet<>()).add(provider);
        persist();
    }

    public synchronized void revoke(@NonNull String principal, @NonNull String provider) {
        load();
        Set<String> provs = grants.get(principal);
        if (provs != null) { provs.remove(provider); persist(); }
    }

    /** Snapshot of every grant: principal -> providers. */
    public synchronized Map<String, Set<String>> all() {
        load();
        Map<String, Set<String>> out = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : grants.entrySet()) out.put(e.getKey(), new HashSet<>(e.getValue()));
        return out;
    }

    /** Principals granted a provider. */
    public synchronized Set<String> principalsFor(@NonNull String provider) {
        load();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : grants.entrySet()) if (e.getValue().contains(provider)) out.add(e.getKey());
        return out;
    }

    public synchronized Set<String> providersFor(@NonNull String principal) {
        load();
        Set<String> provs = grants.get(principal);
        return provs == null ? Collections.emptySet() : new HashSet<>(provs);
    }
}
