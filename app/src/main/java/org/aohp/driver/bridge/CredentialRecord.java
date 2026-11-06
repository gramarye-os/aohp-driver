package org.aohp.driver.bridge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Typed credential for one provider, persisted (as JSON) in {@link SecretStore} under the key
 * {@code cred:&lt;provider&gt;}. Replaces the opaque bare-string secret for provider credentials.
 *
 * <p>For {@code kind=apikey} only {@link #apikey} is meaningful. For {@code kind=oauth} the
 * {@link #refreshToken} is the crown-jewel at-rest secret; {@link #accessToken}/{@link #expiresAt}
 * are a cache the broker refreshes. The refresh token never leaves the Driver process.
 */
public final class CredentialRecord {

    public enum Kind { APIKEY, OAUTH }

    public final String provider;
    public final Kind kind;

    @Nullable public final String apikey;        // kind=apikey

    @Nullable public String refreshToken;         // kind=oauth (mutable: rotated on refresh)
    @Nullable public String accessToken;          // cache
    public long expiresAt;                         // epoch ms, 0 if unknown
    @Nullable public final String tokenUrl;
    @Nullable public final String clientId;
    @Nullable public final String scopes;
    @Nullable public final String accountLabel;
    public boolean needsReauth;

    public CredentialRecord(String provider, Kind kind, @Nullable String apikey,
                            @Nullable String refreshToken, @Nullable String accessToken, long expiresAt,
                            @Nullable String tokenUrl, @Nullable String clientId, @Nullable String scopes,
                            @Nullable String accountLabel, boolean needsReauth) {
        this.provider = provider;
        this.kind = kind;
        this.apikey = apikey;
        this.refreshToken = refreshToken;
        this.accessToken = accessToken;
        this.expiresAt = expiresAt;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.scopes = scopes;
        this.accountLabel = accountLabel;
        this.needsReauth = needsReauth;
    }

    public static CredentialRecord apiKey(@NonNull String provider, @NonNull String apikey) {
        return new CredentialRecord(provider, Kind.APIKEY, apikey, null, null, 0,
                null, null, null, null, false);
    }

    public static CredentialRecord oauth(@NonNull String provider, @NonNull String refreshToken,
                                         @Nullable String accessToken, long expiresAt,
                                         @NonNull String tokenUrl, @NonNull String clientId,
                                         @Nullable String scopes, @Nullable String accountLabel) {
        return new CredentialRecord(provider, Kind.OAUTH, null, refreshToken, accessToken, expiresAt,
                tokenUrl, clientId, scopes, accountLabel, false);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("provider", provider);
        o.put("kind", kind == Kind.OAUTH ? "oauth" : "apikey");
        if (apikey != null) o.put("apikey", apikey);
        if (refreshToken != null) o.put("refresh_token", refreshToken);
        if (accessToken != null) o.put("access_token", accessToken);
        o.put("expires_at", expiresAt);
        if (tokenUrl != null) o.put("token_url", tokenUrl);
        if (clientId != null) o.put("client_id", clientId);
        if (scopes != null) o.put("scopes", scopes);
        if (accountLabel != null) o.put("account_label", accountLabel);
        o.put("needs_reauth", needsReauth);
        return o;
    }

    @NonNull
    public static CredentialRecord fromJson(@NonNull JSONObject o) throws JSONException {
        Kind k = "oauth".equals(o.optString("kind", "apikey")) ? Kind.OAUTH : Kind.APIKEY;
        return new CredentialRecord(
                o.getString("provider"), k,
                o.has("apikey") ? o.getString("apikey") : null,
                o.has("refresh_token") ? o.getString("refresh_token") : null,
                o.has("access_token") ? o.getString("access_token") : null,
                o.optLong("expires_at", 0),
                o.has("token_url") ? o.getString("token_url") : null,
                o.has("client_id") ? o.getString("client_id") : null,
                o.has("scopes") ? o.getString("scopes") : null,
                o.has("account_label") ? o.getString("account_label") : null,
                o.optBoolean("needs_reauth", false));
    }

    /** Back-compat: an existing bare-string secret is read as an implicit API key. */
    public static CredentialRecord fromLegacyBareString(@NonNull String provider, @NonNull String value) {
        return apiKey(provider, value);
    }
}
