// Ported from AOHPAgentDriverApp (https://github.com/aohp-os/AOHPAgentDriverApp), which states
// no licence: this file is NOT under this repository's Apache-2.0 licence, and its upstream
// authors keep all rights in their code. See NOTICE. What was changed or added here is
// Copyright (C) 2026 Chris Anderson, under Apache-2.0 as far as it can be told apart.

package org.aohp.driver.bridge;

import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.aohp.IAohpVirtualDisplay;

import org.json.JSONObject;

/**
 * Binder client for {@code aohp_virtual_display} system service (AOSP / Cuttlefish AOHP builds).
 */
public final class AohpVdClient {
    private static final String TAG = "[AohpVdClient]";
    public static final String SERVICE_NAME = "aohp_virtual_display";

    /** 与 {@link ShellExecutor} / {@link MultiVirtualDisplayManager} 共用，保证 AOHP 会话状态一致。 */
    private static volatile AohpVdClient sInstance;

    private final Context mAppContext;
    private volatile IAohpVirtualDisplay mService;

    /**
     * 最近一次成功 {@link #registerSession} 的逻辑屏 id；与之一致时可跳过重复 register，避免系统侧重置会话并清掉
     * {@link #setFocusPackage}（否则每次注入前的 register 会导致 inject* 恒为 false）。
     */
    private volatile int mRegisteredDisplayId = Integer.MIN_VALUE;

    /** 最近一次成功 {@link #setFocusPackage} 的包名；在 (re)registerSession 之后会再次下发。 */
    private volatile String mCachedFocusPackage = "";

    private AohpVdClient(Context context) {
        mAppContext = context.getApplicationContext();
    }

    /**
     * 进程内单例；创建虚拟屏与注入输入必须使用同一实例，否则 registerSession / 焦点缓存会分裂。
     */
    public static AohpVdClient getInstance(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }
        if (sInstance == null) {
            synchronized (AohpVdClient.class) {
                if (sInstance == null) {
                    sInstance = new AohpVdClient(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private static void cacheFocusPackage(AohpVdClient c, String packageName) {
        c.mCachedFocusPackage = packageName != null ? packageName : "";
    }

    public boolean isAohpPolicyEnabled() {
        if ("true".equals(getSystemProperty("ro.aohp.virtual_display_policy", ""))) {
            return true;
        }
        // vendor default.prop 未带上 ro.aohp.* 时，AOHP Cuttlefish 产品 fingerprint 仍含 aohp
        String fp = getSystemProperty("ro.build.fingerprint", "");
        return fp != null && fp.contains("aohp");
    }

    public boolean isServiceAvailable() {
        return getService() != null;
    }

    private IAohpVirtualDisplay getService() {
        IAohpVirtualDisplay s = mService;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            s = mService;
            if (s != null) {
                return s;
            }
            try {
                IBinder b = getServiceManagerBinder(SERVICE_NAME);
                if (b == null) {
                    return null;
                }
                s = IAohpVirtualDisplay.Stub.asInterface(b);
                mService = s;
            } catch (Throwable t) {
                Log.w(TAG, "getService failed: " + t.getMessage());
                return null;
            }
            return s;
        }
    }

    private static IBinder getServiceManagerBinder(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 虚拟屏相关操作是否应走 AOHP Binder（以系统是否注册 {@link #SERVICE_NAME} 为准）。
     */
    public boolean useAohpForDisplayOps() {
        return isServiceAvailable();
    }

    /**
     * 在 {@link #injectTap} / {@link #injectSwipe} 等之前绑定 AOHP 会话到目标逻辑屏。
     * 系统会校验 displayId 与调用方 uid 是否已 {@link #registerSession}，否则会报
     * {@code Permission denied: displayId/uid not registered for AOHP session}（例如先前仅为
     * 虚拟屏 id 注册过会话，却对主屏 {@code displayId == 0} 注入）。
     * <p>
     * 对同一 {@code displayId} 不重复调用 {@code registerSession}，并在会话就绪后重放
     * {@link #mCachedFocusPackage}，避免系统侧因重复 register 清空焦点导致 inject 恒为 false。
     */
    private ShellExecutor.CommandResult ensureSessionForDisplay(int displayId) {
        String pkg = mAppContext != null ? mAppContext.getPackageName() : "";
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            if (mRegisteredDisplayId != displayId) {
                svc.registerSession(displayId, Process.myUid(), pkg != null ? pkg : "");
                mRegisteredDisplayId = displayId;
            }
            reapplyCachedFocusPackage(svc);
            r.success = true;
            r.output = "ensureSession OK";
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    private void reapplyCachedFocusPackage(IAohpVirtualDisplay svc) {
        if (mCachedFocusPackage.isEmpty()) {
            return;
        }
        try {
            svc.setFocusPackage(mCachedFocusPackage);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "reapplyCachedFocusPackage: " + e.getMessage());
        }
    }

    public ShellExecutor.CommandResult registerSession(int displayId, int ownerUid, String ownerPackage) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            svc.registerSession(displayId, ownerUid, ownerPackage != null ? ownerPackage : "");
            mRegisteredDisplayId = displayId;
            reapplyCachedFocusPackage(svc);
            r.success = true;
            r.output = "registerSession OK";
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult unregisterSession() {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            svc.unregisterSession();
            mRegisteredDisplayId = Integer.MIN_VALUE;
            mService = null;
            r.success = true;
            r.output = "unregisterSession OK";
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult setFocusPackage(String packageName) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            svc.setFocusPackage(packageName != null ? packageName : "");
            r.success = true;
            cacheFocusPackage(this, packageName);
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult startLauncherOnDisplay(int displayId, String packageName) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            r.success = svc.startLauncherOnDisplay(displayId, packageName);
            r.output = r.success ? "OK" : "startLauncherOnDisplay returned false";
            if (r.success && packageName != null && !packageName.isEmpty()) {
                try {
                    svc.setFocusPackage(packageName);
                    cacheFocusPackage(this, packageName);
                } catch (RemoteException | SecurityException e) {
                    Log.w(TAG, "setFocusPackage after startLauncherOnDisplay: " + e.getMessage());
                }
            }
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult injectTap(int displayId, int x, int y) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        return boolOp(() -> getService().injectTap(displayId, x, y));
    }

    public ShellExecutor.CommandResult injectSwipe(int displayId, int x1, int y1, int x2, int y2,
            int durationMs) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        return boolOp(() -> getService().injectSwipe(displayId, x1, y1, x2, y2, durationMs));
    }

    public ShellExecutor.CommandResult injectText(int displayId, String text) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        return boolOp(() -> getService().injectText(displayId, text));
    }

    public ShellExecutor.CommandResult injectKeyEvent(int displayId, int keyCode) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        return boolOp(() -> getService().injectKeyEvent(displayId, keyCode));
    }

    public ShellExecutor.CommandResult applyMultiDisplayDeveloperSettings() {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            svc.applyMultiDisplayDeveloperSettings();
            r.success = true;
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult setNodeProgress(int displayId, int nodeId, float percent,
            int flags) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            String json = svc.setNodeProgress(displayId, nodeId, percent, flags);
            r.output = json != null ? json : "";
            try {
                JSONObject parsed = new JSONObject(r.output);
                r.success = parsed.optBoolean("success", false);
                if (!r.success) {
                    // Message + error code already live in JSON stdout; avoid duplicating in stderr.
                    r.error = "";
                }
            } catch (Exception e) {
                r.success = false;
                r.error = "invalid setNodeProgress result: " + e.getMessage();
            }
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        } catch (LinkageError e) {
            r.success = false;
            r.error = aohpApiMismatchMessage("setNodeProgress", e);
        }
        return r;
    }

    /** Set text via system_server accessibility {@code ACTION_SET_TEXT} (see IAohpVirtualDisplay). */
    public ShellExecutor.CommandResult setEditableText(int displayId, int nodeId, String text,
            int flags) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            String json = svc.setEditableText(displayId, nodeId, text, flags);
            return parseAohpJsonActionResult(r, json);
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        } catch (LinkageError e) {
            r.success = false;
            r.error = aohpApiMismatchMessage("setEditableText", e);
        }
        return r;
    }

    /** Clear text via system_server accessibility {@code ACTION_SET_TEXT} (see IAohpVirtualDisplay). */
    public ShellExecutor.CommandResult clearEditableText(int displayId, int nodeId, int flags) {
        ShellExecutor.CommandResult reg = ensureSessionForDisplay(displayId);
        if (!reg.success) {
            return reg;
        }
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            String json = svc.clearEditableText(displayId, nodeId, flags);
            return parseAohpJsonActionResult(r, json);
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        } catch (LinkageError e) {
            r.success = false;
            r.error = aohpApiMismatchMessage("clearEditableText", e);
        }
        return r;
    }

    private static ShellExecutor.CommandResult parseAohpJsonActionResult(
            ShellExecutor.CommandResult r, String json) {
        r.output = json != null ? json : "";
        try {
            JSONObject parsed = new JSONObject(r.output);
            r.success = parsed.optBoolean("success", false);
            if (!r.success) {
                String msg = parsed.optString("message", "");
                String code = parsed.optString("error", "");
                r.error = !msg.isEmpty() ? msg : code;
            }
        } catch (Exception e) {
            r.success = false;
            r.error = "invalid AOHP action result: " + e.getMessage();
        }
        return r;
    }

    private static String aohpApiMismatchMessage(String api, Throwable e) {
        return api + " unavailable on this system image (rebuild AOSP with AOHP IAohpVirtualDisplay): "
                + (e != null ? e.getMessage() : "api_mismatch");
    }

    /**
     * 全逻辑屏 + RootTask 运行时快照（JSON），需 {@link android.Manifest.permission#MANAGE_AOHP_VIRTUAL_DISPLAY}。
     *
     * @param extraDisplayIds 应用已知的逻辑屏 id（例如其它方式创建的 VD），可传 null
     */
    public ShellExecutor.CommandResult getDisplayRuntimeSnapshotJson(int[] extraDisplayIds) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            String json = svc.getDisplayRuntimeSnapshotJson(extraDisplayIds);
            r.success = true;
            r.output = json != null ? json : "";
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    /**
     * Privileged UI tree dump for any logical display (JSON). Requires AOHP system image +
     * {@link android.Manifest.permission#MANAGE_AOHP_VIRTUAL_DISPLAY}.
     */
    public ShellExecutor.CommandResult dumpUiTree(int displayId, int flags) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            String json = svc.dumpUiTree(displayId, flags);
            r.success = json != null && !json.isEmpty();
            r.output = json != null ? json : "";
            if (!r.success) {
                r.error = "dumpUiTree returned empty";
            }
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    /**
     * Privileged VD creation (no MediaProjection). Returns displayId in {@code output} or error.
     */
    public ShellExecutor.CommandResult createVirtualDisplay(String name, int width, int height,
            int densityDpi, int flags) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            int id = svc.createVirtualDisplay(
                    name != null ? name : "aohp-vd", width, height, densityDpi, flags);
            r.success = id >= 0;
            r.output = String.valueOf(id);
            if (!r.success) {
                r.error = "createVirtualDisplay returned " + id;
            }
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult destroyVirtualDisplay(int displayId) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            boolean ok = svc.destroyVirtualDisplay(displayId);
            r.success = ok;
            if (ok) {
                r.output = "OK";
                r.error = "";
            } else {
                r.output = "";
                r.error = "destroyVirtualDisplay returned false";
            }
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    private interface BoolRemoteCall {
        boolean run() throws RemoteException;
    }

    private ShellExecutor.CommandResult boolOp(BoolRemoteCall call) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpVirtualDisplay svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP service not found";
                return r;
            }
            r.success = call.run();
            if (r.success) {
                r.output = "OK";
                r.error = "";
            } else {
                r.output = "";
                r.error = "operation returned false";
            }
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }
}
