package org.aohp.driver;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.aohp.driver.bridge.CredentialAcl;
import org.aohp.driver.bridge.SecretStore;

import java.util.regex.Pattern;

/**
 * The credential broker's consent screen (docs/CREDENTIALS.md §4): granting a caller access to a
 * provider's credential is an explicit, user-visible decision. Nothing else in the Driver can
 * change the ACL — there is deliberately no {@code cred.grant} RPC, so neither an app nor an env
 * process can grant itself.
 *
 * <p>Launched with extras {@code principal} (e.g. {@code env:oc}, {@code org.aohp.search},
 * {@code uid:10123}) and {@code provider} (e.g. {@code anthropic}); {@code revoke=true} asks to
 * withdraw an existing grant instead. From a host:
 * <pre>
 * adb shell am start -n org.aohp.driver/.CredentialConsentActivity \
 *     --es principal env:oc --es provider anthropic
 * </pre>
 * The user must tap Allow on the phone. The activity is exported so settings UIs and scripts can
 * open it, which only costs the launcher a dialog the user can dismiss; it never grants silently.
 *
 * <p>The buttons are the activity's own content view (dialog theme), not a separate AlertDialog
 * window: on cred.2 the dialog leaked and the grant was lost whenever the activity was recreated
 * (screen dim, cover, rotation) before the tap.
 */
public final class CredentialConsentActivity extends Activity {

    private static final String TAG = "AohpCredAudit";
    private static final Pattern PRINCIPAL = Pattern.compile("(env:[A-Za-z0-9_.-]{1,64}|uid:[0-9]{1,6}|[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+)");
    private static final Pattern PROVIDER  = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String principal = getIntent().getStringExtra("principal");
        final String provider  = getIntent().getStringExtra("provider");
        final boolean revoke   = getIntent().getBooleanExtra("revoke", false);
        if (principal == null || provider == null
                || !PRINCIPAL.matcher(principal).matches() || !PROVIDER.matcher(provider).matches()) {
            Log.w(TAG, "consent: bad principal/provider (" + principal + ", " + provider + ")");
            finish();
            return;
        }
        final CredentialAcl acl = new CredentialAcl(new SecretStore(getApplicationContext()));
        final boolean already = acl.providersFor(principal).contains(provider);
        if (revoke && !already) { Log.i(TAG, "consent: nothing to revoke " + principal + " / " + provider); finish(); return; }
        if (!revoke && already) { Log.i(TAG, "consent: already granted " + principal + " / " + provider); finish(); return; }

        setTitle(revoke ? "Revoke credential access?" : "Allow credential access?");
        final String who = describe(principal);
        final String msg = revoke
                ? who + " will no longer be able to obtain the " + provider + " credential."
                : who + " wants to use the " + provider + " credential stored on this phone.\n\n"
                  + "It will receive a token for each request it makes; it never sees the stored secret itself.";

        final int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(12));

        TextView title = new TextView(this);
        title.setText(getTitle());
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(msg);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setPadding(0, dp(12), 0, dp(16));
        root.addView(body);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button deny = new Button(this, null, android.R.attr.borderlessButtonStyle);
        deny.setText("Deny");
        deny.setOnClickListener(v -> {
            Log.i(TAG, "DENY   consent provider=" + provider + " principal=" + principal + " by=user");
            finish();
        });
        Button allow = new Button(this, null, android.R.attr.borderlessButtonStyle);
        allow.setText(revoke ? "Revoke" : "Allow");
        allow.setOnClickListener(v -> {
            if (revoke) acl.revoke(principal, provider); else acl.grant(principal, provider);
            boolean now = acl.providersFor(principal).contains(provider);
            boolean ok = revoke ? !now : now;
            Log.i(TAG, (revoke ? "REVOKE" : "GRANT ") + " provider=" + provider + " principal=" + principal
                    + " by=user persisted=" + ok);
            finish();
        });
        row.addView(deny);
        row.addView(allow);
        root.addView(row);
        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static String describe(String principal) {
        if (principal.startsWith("env:")) return "The \"" + principal.substring(4) + "\" environment";
        if (principal.startsWith("uid:")) return "The process with uid " + principal.substring(4);
        return "The app " + principal;
    }
}
