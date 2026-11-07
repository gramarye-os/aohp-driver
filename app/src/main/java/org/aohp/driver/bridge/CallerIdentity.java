package org.aohp.driver.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Credentials;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Identity of a bridge caller, used by the credential broker to authorize {@code cred.*} calls.
 *
 * <p>Two sources, strongest first:
 * <ol>
 *   <li><b>SO_PEERCRED</b> — for callers on the AF_UNIX control channel
 *       ({@link android.net.LocalSocket#getPeerCredentials()}). The kernel-supplied peer uid/pid
 *       cannot be forged from user space. An installed app has a distinct uid, so the uid <i>is</i>
 *       the app's principal. An env (container) process is different: envs are chroots in the host
 *       uid space, every process in them runs as uid 0, and a uid-1000 process inside one would
 *       even resolve to the "android" package — so for env processes the principal is the env
 *       <i>name</i>, {@code env:<name>}, resolved from the peer pid by aohp-containerd (WHICHENV:
 *       /proc/&lt;pid&gt;/root vs. each env's rootfs). The env check runs first and wins.</li>
 *   <li><b>Capability token</b> — for callers on the legacy TCP WebSocket, which carries no peer
 *       credentials. A token minted by the Driver and bound to a principal is presented per call.
 *       Loopback address is never treated as identity.</li>
 * </ol>
 *
 * <p>A caller with neither is {@link #anonymous()} and is denied every {@code cred.*} method
 * (fail-closed).
 */
public final class CallerIdentity {

    private static final String TAG = "AohpCallerId";

    /** How the identity was established. */
    public enum Source { PEERCRED, CAPABILITY, ANONYMOUS }

    private final Source source;
    private final int uid;                 // valid when source == PEERCRED
    private final int pid;                 // best-effort, PEERCRED only
    @Nullable private final String packageName; // resolved from uid or token binding
    @Nullable private final String tokenPrincipal; // principal a capability token is bound to

    private CallerIdentity(Source source, int uid, int pid,
                           @Nullable String packageName, @Nullable String tokenPrincipal) {
        this.source = source;
        this.uid = uid;
        this.pid = pid;
        this.packageName = packageName;
        this.tokenPrincipal = tokenPrincipal;
    }

    /** Build from AF_UNIX peer credentials (no env resolution; principal is package or uid). */
    public static CallerIdentity fromPeerCredentials(@NonNull Context ctx, @NonNull Credentials creds) {
        return fromPeerCredentials(ctx, creds, null);
    }

    /**
     * Build from AF_UNIX peer credentials. When {@code containers} is given, the peer pid is first
     * checked against the envs; an env process gets the principal {@code env:<name>} regardless of
     * its uid. Otherwise the uid's package, else {@code uid:<n>}.
     */
    public static CallerIdentity fromPeerCredentials(@NonNull Context ctx, @NonNull Credentials creds,
                                                     @Nullable AohpContainerClient containers) {
        int uid = creds.getUid();
        int pid = creds.getPid();
        if (containers != null && pid > 1) {
            String env = containers.whichEnv(pid);
            if (env != null) return new CallerIdentity(Source.PEERCRED, uid, pid, "env:" + env, null);
        }
        String pkg = resolvePackage(ctx, uid);
        return new CallerIdentity(Source.PEERCRED, uid, pid, pkg, null);
    }

    /** Build from a verified capability token's bound principal. */
    public static CallerIdentity fromCapability(@NonNull String principal) {
        return new CallerIdentity(Source.CAPABILITY, -1, -1, principal, principal);
    }

    public static CallerIdentity anonymous() {
        return new CallerIdentity(Source.ANONYMOUS, -1, -1, null, null);
    }

    public Source source() { return source; }
    public int uid() { return uid; }

    /**
     * Stable principal string used by {@link CredentialAcl}. For PEERCRED this is {@code env:<name>}
     * for an env process, else the package name when resolvable, else {@code "uid:<n>"}; for
     * CAPABILITY it is the token's bound principal.
     */
    @NonNull
    public String principal() {
        if (packageName != null) return packageName;
        if (tokenPrincipal != null) return tokenPrincipal;
        if (source == Source.PEERCRED) return "uid:" + uid;
        return "anonymous";
    }

    public boolean isAnonymous() { return source == Source.ANONYMOUS; }

    @Nullable
    private static String resolvePackage(@NonNull Context ctx, int uid) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null && pkgs.length == 1) return pkgs[0];
            // Shared-uid or container uid: no single package. Caller is identified by uid only.
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "resolvePackage(" + uid + ") failed: " + t.getMessage());
            return null;
        }
    }

    @Override
    public String toString() {
        return "CallerIdentity{" + source + " principal=" + principal()
                + (source == Source.PEERCRED ? " uid=" + uid + " pid=" + pid : "") + "}";
    }
}
