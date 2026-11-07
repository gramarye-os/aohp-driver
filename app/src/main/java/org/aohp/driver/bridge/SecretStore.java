package org.aohp.driver.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Named secrets (provider API keys, tokens) for agents running in the Linux sandbox.
 *
 * <p>Values live in EncryptedSharedPreferences backed by an Android Keystore master key, so they
 * never have to be written into the container rootfs. Containers read them on demand through the
 * JSON-RPC methods {@code secret.get / secret.set / secret.delete / secret.list} (CLI:
 * {@code aohp secret ...}); e.g. an OpenClaw launcher exports {@code ANTHROPIC_API_KEY} from
 * {@code aohp secret get ANTHROPIC_API_KEY} right before starting the gateway.
 *
 * <p>Names are environment-variable style: {@code [A-Z][A-Z0-9_]{0,63}}.
 */
public final class SecretStore {
    private static final String PREFS = "aohp_secrets_secure";
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final int MAX_VALUE_LEN = 16 * 1024;

    private final SharedPreferences mPrefs;

    public SecretStore(@NonNull Context context) {
        mPrefs = createPrefs(context.getApplicationContext());
    }

    private static SharedPreferences createPrefs(Context appContext) {
        try {
            MasterKey masterKey =
                    new MasterKey.Builder(appContext)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build();
            return EncryptedSharedPreferences.create(
                    appContext,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Same fallback as UdaConfigStore: still app-private, just not hardware-backed.
            return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * Reserved internal keys (e.g. the credential ACL, {@code __cred_acl__}). They deliberately do
     * not match {@link #NAME}, so the legacy {@code secret.*} bridge methods — which validate with
     * {@link #isValidName} before calling in here — can never read or write them; but the Driver's
     * own components must still be able to persist them. Bug found on dodge cred.2: the consent
     * screen's grant hit {@code set()}'s name check and was silently dropped.
     */
    public static boolean isReservedName(@Nullable String name) {
        if (name == null || name.length() > 64) return false;
        if (name.startsWith("__") && name.endsWith("__")) return true;
        // Credential broker records: cred:<provider> (CredentialBroker.KEY_PREFIX). Found on dodge
        // cred.4: cred.configure returned ok but set() had rejected the key, so nothing was stored.
        return name.startsWith("cred:") && CRED_PROVIDER.matcher(name.substring(5)).matches();
    }
    private static final Pattern CRED_PROVIDER = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    /** Reserved names starting with {@code prefix} (e.g. "cred:") — for the broker's own enumeration. */
    public List<String> reservedNames(@NonNull String prefix) {
        List<String> names = new ArrayList<>();
        for (String n : mPrefs.getAll().keySet()) if (isReservedName(n) && n.startsWith(prefix)) names.add(n);
        Collections.sort(names);
        return names;
    }

    @Nullable
    public String get(@NonNull String name) {
        return mPrefs.getString(name, null);
    }

    public boolean has(@NonNull String name) {
        return mPrefs.contains(name);
    }

    /** @return false if the name or value is not acceptable. */
    public boolean set(@NonNull String name, @NonNull String value) {
        if (!(isValidName(name) || isReservedName(name)) || value.length() > MAX_VALUE_LEN) return false;
        return mPrefs.edit().putString(name, value).commit();
    }

    public boolean delete(@NonNull String name) {
        if (!mPrefs.contains(name)) return false;
        return mPrefs.edit().remove(name).commit();
    }

    /** Names only — values are never listed. */
    @NonNull
    /** Public secret names only: reserved internal keys (the credential ACL etc.) are never listed. */
    public List<String> list() {
        List<String> names = new ArrayList<>();
        for (String n : mPrefs.getAll().keySet()) if (!isReservedName(n)) names.add(n);
        Collections.sort(names);
        return names;
    }
}
