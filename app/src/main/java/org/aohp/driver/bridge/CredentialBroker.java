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
        loadCatalog(this.ctx, store);
    }

    public CredentialAcl acl() { return acl; }

    /** Install a catalog pushed by an env (cred.providers.set): validated, persisted, reloaded. */
    public synchronized boolean installCatalog(@NonNull String json) {
        try { parseCatalog(json); } catch (JSONException e) { Log.w("AohpCredAudit", "catalog rejected: " + e.getMessage()); return false; }
        if (!store.set(CATALOG_KEY, json)) return false;
        loadCatalog(ctx, store);
        return true;
    }

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
        for (String p : KNOWN.keySet()) {
            String legacy = legacyName(p);
            if (!out.contains(p) && legacy != null && store.has(legacy)) out.add(p);
        }
        return out;
    }

    @Nullable
    /** Legacy bare-string secret name for a provider (its env var), e.g. ANTHROPIC_API_KEY seeded via secret.set. */
    private static String legacyName(String provider) {
        Provider p = KNOWN.get(provider);
        return p != null ? p.envVar : null;
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

    // ----- provider catalog -----
    //
    // Loaded from JSON (aohp-agents/providers.json is canonical). Order of precedence:
    //   1. the catalog an env pushed over the socket (cred.providers.set), persisted under CATALOG_KEY;
    //   2. the built-in copy shipped in assets/providers.json;
    //   3. a two-entry hardcoded minimum, so the broker never has an empty table.
    // Loaded once per process by the constructor; reloaded after a push.

    /** How a provider's API key is named (env var OpenClaw reads), carried on the wire, and verified. */
    public static final class Provider {
        public final String id, label, envVar, header;
        public final boolean bearer;
        /** A cheap authenticated GET that answers 2xx for a valid key and 401/403 for a bad one; null = cannot verify. */
        @Nullable public final String probeUrl;
        public final java.util.Map<String, String> probeHeaders;
        public final List<String> aliases;
        Provider(String id, String label, String envVar, String header, boolean bearer, @Nullable String probeUrl,
                 java.util.Map<String, String> probeHeaders, List<String> aliases) {
            this.id = id; this.label = label; this.envVar = envVar; this.header = header; this.bearer = bearer;
            this.probeUrl = probeUrl; this.probeHeaders = probeHeaders; this.aliases = aliases;
        }
        static Provider fromJson(JSONObject o) throws JSONException {
            String id = o.getString("id"), env = o.getString("env");
            if (!id.matches("[a-z][a-z0-9_-]{0,31}") || !env.matches("[A-Z][A-Z0-9_]{0,63}")) throw new JSONException("bad provider id/env: " + id);
            JSONObject auth = o.optJSONObject("auth");
            String header = auth != null ? auth.optString("header", "x-api-key") : "x-api-key";
            boolean bearer = auth != null && "Bearer".equalsIgnoreCase(auth.optString("scheme", ""));
            java.util.Map<String, String> ph = new java.util.LinkedHashMap<>();
            JSONObject pho = o.optJSONObject("probeHeaders");
            if (pho != null) for (java.util.Iterator<String> it = pho.keys(); it.hasNext(); ) { String k = it.next(); ph.put(k, pho.optString(k)); }
            List<String> al = new ArrayList<>();
            org.json.JSONArray aa = o.optJSONArray("aliases");
            if (aa != null) for (int i = 0; i < aa.length(); i++) al.add(aa.optString(i));
            return new Provider(id, o.optString("label", id), env, header, bearer, o.has("probe") ? o.getString("probe") : null, ph, al);
        }
    }

    static final String CATALOG_KEY = "__providers__";
    private static final java.util.LinkedHashMap<String, Provider> KNOWN = new java.util.LinkedHashMap<>();
    private static String catalogSource = "none";

    /** Parse a catalog document into a map; throws on any malformed entry (all-or-nothing). */
    static java.util.LinkedHashMap<String, Provider> parseCatalog(String json) throws JSONException {
        JSONObject doc = new JSONObject(json);
        if (doc.optInt("version", 0) < 1) throw new JSONException("catalog version missing");
        org.json.JSONArray arr = doc.getJSONArray("providers");
        java.util.LinkedHashMap<String, Provider> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) { Provider p = Provider.fromJson(arr.getJSONObject(i)); m.put(p.id, p); }
        if (m.isEmpty()) throw new JSONException("catalog has no providers");
        return m;
    }

    /** (Re)load the catalog: pushed > asset > minimum. Safe to call repeatedly. */
    public static synchronized void loadCatalog(@NonNull Context ctx, @Nullable SecretStore store) {
        String pushed = (store != null && store.has(CATALOG_KEY)) ? store.get(CATALOG_KEY) : null;
        if (pushed != null) {
            try { KNOWN.clear(); KNOWN.putAll(parseCatalog(pushed)); catalogSource = "pushed"; return; }
            catch (JSONException e) { Log.w("AohpCredAudit", "pushed provider catalog rejected: " + e.getMessage()); }
        }
        try (java.io.InputStream in = ctx.getAssets().open("providers.json")) {
            byte[] b = new byte[in.available()]; int n = in.read(b);
            KNOWN.clear(); KNOWN.putAll(parseCatalog(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8))); catalogSource = "asset"; return;
        } catch (Exception e) { Log.e("AohpCredAudit", "built-in provider catalog unreadable: " + e.getMessage()); }
        if (KNOWN.isEmpty()) {
            KNOWN.put("anthropic", new Provider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", "x-api-key", false, null, new java.util.HashMap<>(), new ArrayList<>()));
            KNOWN.put("brave", new Provider("brave", "Brave Search", "BRAVE_API_KEY", "x-subscription-token", false, null, new java.util.HashMap<>(), new ArrayList<>()));
            catalogSource = "minimum";
        }
    }
    public static String catalogSource() { return catalogSource; }

    /** The current catalog as a JSON document (what cred.providers returns). */
    public static synchronized JSONObject catalogJson() throws JSONException {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Provider p : KNOWN.values()) {
            JSONObject o = new JSONObject().put("id", p.id).put("label", p.label).put("env", p.envVar)
                    .put("auth", new JSONObject().put("header", p.header).put("scheme", p.bearer ? "Bearer" : JSONObject.NULL));
            if (p.probeUrl != null) o.put("probe", p.probeUrl);
            if (!p.aliases.isEmpty()) o.put("aliases", new org.json.JSONArray(p.aliases));
            arr.put(o);
        }
        return new JSONObject().put("version", 1).put("source", catalogSource).put("providers", arr);
    }

    /** Provider whose env var or alias is {@code envVar} (e.g. a scanned "ZAI_API_KEY=…" line), or null. */
    @Nullable public static Provider byEnvVar(String envVar) {
        for (Provider p : KNOWN.values()) if (p.envVar.equals(envVar) || p.aliases.contains(envVar)) return p;
        return null;
    }

    /**
     * Verify an API key against the provider's probe endpoint. Returns "ok", "rejected", or
     * "unverified: <why>" — a wrong probe URL or no network must never read as a bad key.
     */
    public static String verifyApiKey(@NonNull String provider, @NonNull String key) {
        Provider p = KNOWN.get(provider);
        if (p == null || p.probeUrl == null) return "unverified: no probe for " + provider;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(p.probeUrl).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestMethod("GET");
            c.setRequestProperty(p.header, p.bearer ? "Bearer " + key : key);
            for (java.util.Map.Entry<String, String> h : p.probeHeaders.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            c.setRequestProperty("accept", "application/json");
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) return "ok";
            if (code == 401 || code == 403) return "rejected";
            return "unverified: HTTP " + code;
        } catch (Exception e) {
            return "unverified: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally { if (c != null) c.disconnect(); }
    }

    /** Known providers in display order (the Credentials screen's picker; the launcher's env-var map). */
    public static List<Provider> knownProviders() { return new ArrayList<>(KNOWN.values()); }
    @Nullable public static Provider known(String id) { return KNOWN.get(id); }
    /** Env var OpenClaw reads for a provider: the catalog entry, else the `<PROVIDER>_API_KEY` convention. */
    public static String envVarFor(String provider) {
        Provider p = KNOWN.get(provider);
        return p != null ? p.envVar : provider.toUpperCase(java.util.Locale.ROOT).replace('-', '_') + "_API_KEY";
    }

    private static String headerName(String provider, boolean oauth) {
        if (oauth) return "authorization";
        Provider p = KNOWN.get(provider);
        return p != null ? p.header : "x-api-key";
    }
    private static String apiHeaderValue(String provider, String key) {
        Provider p = KNOWN.get(provider);
        return (p != null && p.bearer) ? "Bearer " + key : key;
    }

    // ----- Driver-local API (the Credentials screen; no ACL: the Driver is the consent authority) -----

    /** Store an API key from the Driver's own UI. Audited with the Driver as caller. */
    public boolean localStoreApiKey(@NonNull String provider, @NonNull String apikey) {
        boolean ok = save(CredentialRecord.apiKey(provider, apikey));
        Log.i("AohpCredAudit", (ok ? "STORE  " : "STORE_FAILED ") + "provider=" + provider + " by=driver-ui");
        return ok;
    }
    public void localRemove(@NonNull String provider) {
        store.delete(key(provider));
        String legacy = legacyName(provider);
        if (legacy != null && store.has(legacy)) store.delete(legacy);
        Log.i("AohpCredAudit", "REMOVE provider=" + provider + " by=driver-ui");
    }
    /** Stored kind ("apikey"/"oauth") or null when nothing is stored (legacy bare strings count). */
    @Nullable public String localKind(@NonNull String provider) {
        CredentialRecord rec = load(provider);
        return rec == null ? null : (rec.kind == CredentialRecord.Kind.OAUTH ? "oauth" : "apikey");
    }
    public List<String> localStoredProviders() { return storedProviders(); }

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