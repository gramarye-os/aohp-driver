package org.aohp.driver.bridge;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * Capability tokens for bridge callers on the TCP WebSocket transport, which carries no peer
 * credentials. A token is an opaque random string the Driver mints and binds to a caller
 * principal; the app stores it in its own Keystore and presents it on {@code cred.*} calls.
 *
 * <p>Binding table lives in {@link SecretStore} under {@link #MAP_KEY} as JSON mapping the token's
 * SHA-256 (hex) to its principal — the raw token is never persisted, only its hash, so a dump of
 * the store does not reveal usable tokens. On the AF_UNIX control channel this is unnecessary:
 * SO_PEERCRED gives a kernel-verified uid and {@link CallerIdentity#fromPeerCredentials} is used
 * instead.
 */
public final class CapabilityToken {

    static final String MAP_KEY = "__cap_tokens__";
    private static final SecureRandom RNG = new SecureRandom();

    private CapabilityToken() {}

    /** Mint a token bound to {@code principal}; returns the raw token (shown once to the app). */
    public static String mint(@NonNull Context ctx, @NonNull SecretStore store,
                              @NonNull String principal) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = Base64.encodeToString(raw, Base64.NO_WRAP | Base64.URL_SAFE);
        try {
            JSONObject map = loadMap(store);
            map.put(sha256(token), principal);
            store.set(MAP_KEY, map.toString());
        } catch (JSONException ignored) { }
        return token;
    }

    /** @return the bound principal if the token is known, else null. Constant-ish time by hashing. */
    @Nullable
    public static String verify(@NonNull Context ctx, @NonNull SecretStore store,
                                @NonNull String token) {
        if (token.isEmpty()) return null;
        try {
            JSONObject map = loadMap(store);
            return map.optString(sha256(token), null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void revoke(@NonNull SecretStore store, @NonNull String token) {
        try {
            JSONObject map = loadMap(store);
            map.remove(sha256(token));
            store.set(MAP_KEY, map.toString());
        } catch (JSONException ignored) { }
    }

    private static JSONObject loadMap(SecretStore store) throws JSONException {
        String raw = store.has(MAP_KEY) ? store.get(MAP_KEY) : null;
        return (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
