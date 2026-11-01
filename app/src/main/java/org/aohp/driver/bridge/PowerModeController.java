package org.aohp.driver.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Which cores the environments run on (RPC sandbox.power).
 *
 * aohp-containerd places every env process in an Android cpuset: "performance" = top-app (all cores),
 * "saver" = system-background (little cores only). The setting here is auto | performance | saver;
 * auto follows Android Battery Saver (PowerManager.isPowerSaveMode) and is the default, so a phone
 * that goes into Battery Saver moves its envs to the little cores and comes back when it leaves.
 */
public final class PowerModeController {
    private static final String TAG = "AohpPower";
    private static final String PREFS = "power";
    private static final String KEY = "mode";

    private final Context mContext;
    private final AohpContainerClient mContainer;
    private final PowerManager mPm;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            apply("battery saver " + (mPm.isPowerSaveMode() ? "on" : "off"));
        }
    };

    public PowerModeController(Context context, AohpContainerClient container) {
        mContext = context.getApplicationContext();
        mContainer = container;
        mPm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }

    private static PowerModeController sRegistered;

    /** Applies the current setting and follows Battery Saver changes; one registration per process. */
    public synchronized void start() {
        synchronized (PowerModeController.class) {
            if (sRegistered != null) return;
            sRegistered = this;
        }
        mContext.registerReceiver(mReceiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        new Thread(() -> apply("bridge start"), "aohp-power").start();  // Binder call: off the constructor path
    }

    public void stop() {
        try { mContext.unregisterReceiver(mReceiver); } catch (IllegalArgumentException ignored) { }
    }

    public String setting() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto");
    }

    /** auto when the setting is auto: Battery Saver on -> saver, else performance. */
    public String effective() {
        String s = setting();
        if (s.equals("auto")) return mPm != null && mPm.isPowerSaveMode() ? "saver" : "performance";
        return s;
    }

    /** Push the effective mode to the daemon; null daemon reply = old image without POWER support. */
    public String apply(String why) {
        String mode = effective();
        String r = mContainer.powerMode(mode);
        Log.i(TAG, "power mode " + mode + " (" + setting() + ", " + why + ") -> " + r);
        return r;
    }

    /** RPC sandbox.power {mode?: auto|performance|saver} -> {setting, effective, batterySaver, daemon} */
    public JSONObject rpc(JSONObject p) throws JSONException {
        String mode = p.optString("mode", "");
        if (!mode.isEmpty()) {
            if (!mode.equals("auto") && !mode.equals("performance") && !mode.equals("saver")) {
                throw new IllegalArgumentException("mode must be auto, performance or saver");
            }
            mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply();
        }
        String daemon = apply(mode.isEmpty() ? "query" : "set " + mode);
        JSONObject o = new JSONObject();
        o.put("setting", setting());
        o.put("effective", effective());
        o.put("batterySaver", mPm != null && mPm.isPowerSaveMode());
        o.put("daemon", daemon == null ? JSONObject.NULL : daemon.startsWith("{") ? new JSONObject(daemon) : daemon);
        if (daemon == null) o.put("note", "container service has no powerMode (image older than build-23): envs stay where the daemon put them");
        return o;
    }
}
