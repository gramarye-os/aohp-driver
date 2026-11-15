// Ported from AOHPAgentDriverApp (https://github.com/aohp-os/AOHPAgentDriverApp), which states
// no licence: this file is NOT under this repository's Apache-2.0 licence, and its upstream
// authors keep all rights in their code. See NOTICE. What was changed or added here is
// Copyright (C) 2026 Chris Anderson, under Apache-2.0 as far as it can be told apart.

package org.aohp.driver.bridge;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.aohp.IAohpAgentView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Binder client for {@code aohp_agent_view} system service (AOSP AOHP builds).
 */
public final class AohpAgentViewClient {
    private static final String TAG = "[AohpAgentViewClient]";
    public static final String SERVICE_NAME = "aohp_agent_view";

    private final Context mAppContext;
    private volatile IAohpAgentView mService;

    public AohpAgentViewClient(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public boolean isServiceAvailable() {
        return getService() != null;
    }

    private IAohpAgentView getService() {
        IAohpAgentView s = mService;
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
                s = IAohpAgentView.Stub.asInterface(b);
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

    /**
     * Full-display screenshot to file. Returns path on success or null on failure.
     */
    public ShellExecutor.CommandResult captureDisplayToFile(int displayId, String outputPath,
            int quality) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpAgentView svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP agent view service not found";
                return r;
            }
            byte[] data = svc.captureDisplay(displayId, quality);
            if (data == null || data.length == 0) {
                r.success = false;
                r.error = "captureDisplay returned empty";
                return r;
            }
            writeBytes(data, outputPath);
            r.success = true;
            r.output = outputPath;
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        } catch (IOException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    /**
     * Region screenshot to file.
     */
    public ShellExecutor.CommandResult captureRegionToFile(int displayId, int left, int top,
            int right, int bottom, String outputPath, int quality) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpAgentView svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "AOHP agent view service not found";
                return r;
            }
            byte[] data = svc.captureRegion(displayId, left, top, right, bottom, quality);
            if (data == null || data.length == 0) {
                r.success = false;
                r.error = "captureRegion returned empty";
                return r;
            }
            writeBytes(data, outputPath);
            r.success = true;
            r.output = outputPath;
        } catch (SecurityException e) {
            r.success = false;
            r.error = "Permission denied: " + e.getMessage();
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        } catch (IOException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    private static void writeBytes(byte[] data, String outputPath) throws IOException {
        File f = new File(outputPath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
            fos.flush();
        }
    }
}
