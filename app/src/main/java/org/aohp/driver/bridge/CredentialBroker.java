package org.aohp.driver.bridge;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central credential broker. Owns storage (via {@link SecretStore}), authorization (via
 * {@link CredentialAcl}), and the OAuth refresh lifecycle. The single refresher of any
 * credential: consumers call {@link #getToken} and never refresh themselves.
 *
 * <p>Design: {@code docs/CREDENTIALS.md}. Refresh tokens and API keys exist in plaintext only
 * inside this process; {@link #getToken} returns only a short-lived access token (OAuth) or the
 * key (API key), to authorized callers.
 */
public final class CredentialBroker {

    private static final String TAG = "AohpCredBroker";
    private static final String KEY_PREFIX = "cred:";
    private static final long EXPIRY_SKEW_MS = 60_000L; // refresh 60s before expiry

    private final Context ctx;
    private final SecretStore store;
    private final CredentialAcl acl;
    private final CredentialAuditLog audit;

    /** Per-provider lock so concurrent getToken calls collapse to one network refresh. */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CredentialBroker(@NonNull Context ctx, @NonNull SecretStore store,
                            @NonNull CredentialAcl acl, @NonNull CredentialAuditLog audit) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        this.acl = acl;
        this.audit = audit;
    }

    public CredentialAcl acl() { return acl; }

    // ----- storage -----

    private String key(String provider) { return KEY_PREFIX + provider; }

    @Nullable
    private CredentialRecord load(String provider) {
        String raw = store.has(key(provider)) ? store.get(key(provider)) : null;
        if (raw != null) {
            try { return CredentialRecord.fromJson(new JSONObject(raw)); }
            catch (JSONException e) { Log.w(TAG, "corrupt cred record for " + provider); }
        }
        // Back-compat: a legacy bare-string secret (e.g. ANTHROPIC_API_KEY) as an implicit API key.
        String legacy = legacyName(provider);
        if (legacy != null && store.has(legacy)) {
            return CredentialRecord.fromLegacyBareString(provider, store.get(legacy));
        }
        return null;
    }

    /** @return false when the store rejected the record (never silently: the caller reports it). */
    private boolean save(CredentialRecord rec) {
        try {
            boolean ok = store.set(key(rec.provider), rec.toJson().toString());
            if (!ok) Log.e(TAG, "save REJECTED by store for " + rec.provider + " (key " + key(rec.provider) + ")");
            return ok;
        } catch (JSONException e) { Log.e(TAG, "save failed: " + e.getMessage()); return false; }
    }

    /** Providers that have a stored record or a legacy bare-string secret. */
    private List<String> storedProviders() {
        List<String> out = new ArrayList<>();
        for (String k : store.reservedNames(KEY_PREFIX)) out.add(k.substring(KEY_PREFIX.length()));
        for (String p : new String[]{"anthropic", "brave"}) {
            String legacy = legacyName(p);
            if (!out.contains(p) && legacy != null && store.has(legacy)) out.add(p);
        }
        return out;
    }

    @Nullable
    private static String legacyName(String provider) {
        switch (provider) {
            case "anthropic": return "ANTHROPIC_API_KEY";
            case "brave": return "BRAVE_API_KEY";
            default: return null;
        }
    }

    private ReentrantLock lockFor(String provider) {
        return locks.computeIfAbsent(provider, k -> new ReentrantLock());
    }

    // ----- cred.configure -----

    public JSONObject configureApiKey(@NonNull CallerIdentity caller, @NonNull String provider,
                                      @NonNull String apikey) throws JSONException {
        if (!acl.isAllowed(caller, provider)) return denied(caller, provider, "configure");
        if (!save(CredentialRecord.apiKey(provider, apikey))) {
            audit.record(caller, provider, "configure_apikey", false);
            return err("store_failed", provider);
        }
        audit.record(caller, provider, "configure_apikey", true);
        return ok();
    }

    /** Persist an OAuth credential obtained by the Driver's AppAuth flow. */
    public JSONObject configureOauth(@NonNull CallerIdentity caller, @NonNull CredentialRecord rec)
            throws JSONException {
        if (!acl.isAllowed(caller, rec.provider)) return denied(caller, rec.provider, "configure");
        if (!save(rec)) {
            audit.record(caller, rec.provider, "configure_oauth", false);
            return err("store_failed", rec.provider);
        }
        audit.record(caller, rec.provider, "configure_oauth", true);
        return ok();
    }

    // ----- cred.getToken: the core call -----

    public JSONObject getToken(@NonNull CallerIdentity caller, @NonNull String provider,
                               @Nullable String purpose) throws JSONException {
        if (!acl.isAllowed(caller, provider)) return denied(caller, provider, "getToken");
        CredentialRecord rec = load(provider);
        if (rec == null) { audit.record(caller, provider, "getToken_missing", false);
            return err("not_configured", provider); }

        if (rec.kind == CredentialRecord.Kind.APIKEY) {
            audit.record(caller, provider, "getToken_apikey:" + safe(purpose), true);
            return token(headerName(provider, false), apiHeaderValue(provider, rec.apikey), 0);
        }

        // OAuth: refresh if needed under the per-provider lock.
        ReentrantLock lock = lockFor(provider);
        lock.lock();
        try {
            rec = load(provider); // re-read inside lock
            if (rec == null) return err("not_configured", provider);
            long now = System.currentTimeMillis();
            if (rec.accessToken == null || rec.expiresAt == 0 || now >= rec.expiresAt - EXPIRY_SKEW_MS) {
                boolean refreshed = refresh(rec);
                if (!refreshed) {
                    rec.needsReauth = true; save(rec);
                    audit.record(caller, provider, "getToken_refresh_failed", false);
                    return err("needs_reauth", provider);
                }
                save(rec);
            }
            audit.record(caller, provider, "getToken_oauth:" + safe(purpose), true);
            return token("authorization", "Bearer " + rec.accessToken, rec.expiresAt);
        } finally {
            lock.unlock();
        }
    }

    /** Exchange the refresh token for a new access token; persist any rotated refresh token. */
    private boolean refresh(@NonNull CredentialRecord rec) {
        if (rec.tokenUrl == null || rec.refreshToken == null || rec.clientId == null) return false;
        HttpURLConnection c = null;
        try {
            StringBuilder body = new StringBuilder();
            body.append("grant_type=refresh_token");
            body.append("&refresh_token=").append(enc(rec.refreshToken));
            body.append("&client_id=").append(enc(rec.clientId));
            c = (HttpURLConnection) new URL(rec.tokenUrl).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { Log.w(TAG, "refresh http " + code); return false; }
            JSONObject resp = new JSONObject(readAll(c));
            String at = resp.optString("access_token", null);
            if (at == null) return false;
            rec.accessToken = at;
            long ttl = resp.optLong("expires_in", 3600);
            rec.expiresAt = System.currentTimeMillis() + ttl * 1000L;
            // Rotated refresh token: keep the new one or we lose the account. refreshToken is
            // mutable; getToken() does the single save() after this returns.
            String newRt = resp.optString("refresh_token", null);
            if (newRt != null && !newRt.isEmpty()) {
                rec.refreshToken = newRt;
            }
            rec.needsReauth = false;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed: " + t.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ----- cred.status / revoke / list -----

    public JSONObject status(@NonNull CallerIdentity caller, @NonNull String provider) throws JSONException {
        if (!acl.isAllowed(caller, provider)) return denied(caller, provider, "status");
        CredentialRecord rec = load(provider);
        JSONObject o = new JSONObject();
        o.put("provider", provider);
        o.put("configured", rec != null);
        if (rec != null) {
            o.put("kind", rec.kind == CredentialRecord.Kind.OAUTH ? "oauth" : "apikey");
            if (rec.accountLabel != null) o.put("account_label", rec.accountLabel);
            if (rec.kind == CredentialRecord.Kind.OAUTH) {
                o.put("expires_at", rec.expiresAt);
                o.put("needs_reauth", rec.needsReauth);
            }
        }
        return o;
    }

    public JSONObject revoke(@NonNull CallerIdentity caller, @NonNull String provider) throws JSONException {
        if (!acl.isAllowed(caller, provider)) return denied(caller, provider, "revoke");
        store.delete(key(provider));
        audit.record(caller, provider, "revoke", true);
        return ok();
    }

    public JSONObject list(@NonNull CallerIdentity caller) throws JSONException {
        // Only providers this caller is granted; existence of others is not disclosed.
        List<String> out = new ArrayList<>();
        for (String provider : storedProviders()) {
            if (acl.isAllowed(caller, provider) && load(provider) != null) out.add(provider);
        }
        JSONObject o = new JSONObject();
        o.put("providers", new org.json.JSONArray(out));
        return o;
    }

    // ----- helpers -----

    private static String headerName(String provider, boolean oauth) {
        if (oauth) return "authorization";
        if ("brave".equals(provider)) return "x-subscription-token";
        return "x-api-key";
    }
    private static String apiHeaderValue(String provider, String key) { return key; }

    private static JSONObject token(String headerName, String headerValue, long expiresAt)
            throws JSONException {
        JSONObject o = new JSONObject();
        o.put("header_name", headerName);
        o.put("header_value", headerValue);
        o.put("expires_at", expiresAt);
        return o;
    }
    private JSONObject denied(CallerIdentity caller, String provider, String op) throws JSONException {
        audit.record(caller, provider, op + "_denied", false);
        return err("denied", provider);
    }
    private static JSONObject ok() throws JSONException { return new JSONObject().put("ok", true); }
    private static JSONObject err(String code, String msg) throws JSONException {
        return new JSONObject().put("error", true).put("code", code).put("message", msg);
    }
    private static String safe(@Nullable String s) { return s == null ? "" : s.replaceAll("[^a-zA-Z0-9_.-]", ""); }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
    private static String readAll(HttpURLConnection c) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
