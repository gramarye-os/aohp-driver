package org.aohp.driver.bridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Target of the RemoteInput reply action and the delete intent that {@code sys.notify} attaches.
 * Not exported; the PendingIntents are created by this app, so only the system can fire them.
 * Reply: record it, then re-post the notification without the action (Android otherwise leaves
 * the inline-reply spinner on). Dismiss: record it.
 */
public class NotifyReplyReceiver extends BroadcastReceiver {
    public static final String ACTION_REPLY = "org.aohp.driver.NOTIFY_REPLY";
    public static final String ACTION_DISMISS = "org.aohp.driver.NOTIFY_DISMISS";
    public static final String KEY_TEXT = "text";
    public static final String EXTRA_TAG = "tag";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_CORRELATION = "correlationId";
    public static final String EXTRA_ENV = "env";
    public static final String EXTRA_CHANNEL = "channel";
    public static final String EXTRA_TITLE = "title";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String tag = intent.getStringExtra(EXTRA_TAG);
        int id = intent.getIntExtra(EXTRA_ID, 0);
        String corr = intent.getStringExtra(EXTRA_CORRELATION);
        String env = intent.getStringExtra(EXTRA_ENV);
        if (ACTION_DISMISS.equals(intent.getAction())) {
            NotifyReplies.add("dismiss", tag, id, null, corr, env, null);
            return;
        }
        if (!ACTION_REPLY.equals(intent.getAction())) return;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence cs = results != null ? results.getCharSequence(KEY_TEXT) : null;
        String text = cs != null ? cs.toString() : "";
        // Wearables and the shade both arrive here; a watch reply is a canned choice, which is
        // indistinguishable from a typed one at this layer, so no source is claimed.
        NotifyReplies.add("reply", tag, id, text, corr, env, null);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        String channel = intent.getStringExtra(EXTRA_CHANNEL);
        if (nm != null && channel != null) {
            String title = intent.getStringExtra(EXTRA_TITLE);
            Notification n = new Notification.Builder(context, channel)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title != null ? title : "AOHP")
                    .setContentText("\u2713 " + text)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(15_000)
                    .build();
            nm.notify(tag, id, n);
        }
    }
}
