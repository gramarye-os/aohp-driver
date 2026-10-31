package org.aohp.driver.bridge;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.aohp.IAohpContainer;

import org.json.JSONObject;

/**
 * Binder client for the {@code aohp_container} system service.
 * Mirrors the pattern of {@link AohpVdClient}.
 */
public final class AohpContainerClient {
    private static final String TAG = "[AohpContainerClient]";
    public static final String SERVICE_NAME = "aohp_container";

    private final Context mAppContext;
    private volatile IAohpContainer mService;

    public AohpContainerClient(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public boolean isServiceAvailable() {
        return getService() != null;
    }

    private IAohpContainer getService() {
        IAohpContainer s = mService;
        if (s != null) return s;
        synchronized (this) {
            s = mService;
            if (s != null) return s;
            try {
                IBinder b = getServiceManagerBinder(SERVICE_NAME);
                if (b == null) return null;
                s = IAohpContainer.Stub.asInterface(b);
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

    // ---- Public API ----

    public String[] listContainers() {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return new String[0];
            String[] result = svc.listContainers();
            return result != null ? result : new String[0];
        } catch (RemoteException e) {
            Log.e(TAG, "listContainers failed", e);
            return new String[0];
        }
    }

    public ShellExecutor.CommandResult createContainer(String name, String template) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpContainer svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "Container service not available";
                return r;
            }
            String createErr = svc.createContainer(name, template);
            r.success = (createErr == null || createErr.isEmpty());
            if (r.success) {
                r.output = "Container created: " + name;
            } else {
                r.error = createErr;
                r.output = createErr;
            }
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult destroyContainer(String name) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpContainer svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "Container service not available";
                return r;
            }
            r.success = svc.destroyContainer(name);
            r.output = r.success ? "Container destroyed: " + name : "Failed to destroy container";
            if (!r.success) r.error = r.output;
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    public ShellExecutor.CommandResult resetContainer(String name) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpContainer svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "Container service not available";
                return r;
            }
            r.success = svc.resetContainer(name);
            r.output = r.success ? "Container reset: " + name : "Failed to reset container";
            if (!r.success) r.error = r.output;
        } catch (RemoteException e) {
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    /**
     * Execute a command synchronously inside a container.
     *
     * @return CommandResult with parsed stdout/stderr from the daemon's JSON response.
     */
    public ShellExecutor.CommandResult execSync(String containerName, String command, int timeoutMs) {
        ShellExecutor.CommandResult r = new ShellExecutor.CommandResult();
        try {
            IAohpContainer svc = getService();
            if (svc == null) {
                r.success = false;
                r.error = "Container service not available";
                return r;
            }
            // The daemon protocol is line based (EXEC <name> <timeout> <command>\n, one response line per
            // request): a command containing a newline is read as several requests, the daemon answers
            // each one on a socket system_server has already closed, takes SIGPIPE and init kills it -
            // and every unit of every env with it (dodge 2026-10-31). Ship multi-line scripts as base64.
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                String b64 = android.util.Base64.encodeToString(
                        command.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                command = "echo " + b64 + " | base64 -d | sh";
            }
            String json = svc.execSync(containerName, command, timeoutMs);
            JSONObject obj = new JSONObject(json);
            r.exitCode = obj.optInt("exitCode", -1);
            r.output = obj.optString("stdout", "");
            r.error = obj.optString("stderr", "");
            r.success = (r.exitCode == 0);
        } catch (RemoteException e) {
            r.success = false;
            r.error = "Remote: " + e.getMessage();
        } catch (Exception e) {
            r.success = false;
            r.error = "Parse: " + e.getMessage();
        }
        return r;
    }

    /**
     * Open an interactive shell session inside a container.
     *
     * @return A ParcelFileDescriptor for bidirectional I/O, or null on failure.
     */
    public ParcelFileDescriptor openShell(String containerName) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return null;
            return svc.openShell(containerName);
        } catch (RemoteException e) {
            Log.e(TAG, "openShell failed", e);
            return null;
        }
    }

    public String templateInfo(String containerName) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return "";
            return svc.templateInfo(containerName);
        } catch (RemoteException e) {
            Log.e(TAG, "templateInfo failed", e);
            return "";
        }
    }

    public long startService(String containerName, String serviceId, String command) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return -1L;
            return svc.startService(containerName, serviceId, command);
        } catch (RemoteException e) {
            Log.e(TAG, "startService failed", e);
            return -1L;
        }
    }

    public boolean stopService(String containerName, String serviceId) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return false;
            return svc.stopService(containerName, serviceId);
        } catch (RemoteException e) {
            Log.e(TAG, "stopService failed", e);
            return false;
        }
    }

    public String listServicesJson(String containerName) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return "[]";
            String s = svc.listServices(containerName);
            return s != null ? s : "[]";
        } catch (RemoteException e) {
            Log.e(TAG, "listServices failed", e);
            return "[]";
        }
    }

    public String serviceLog(String containerName, String serviceId, int tailBytes) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return "";
            String s = svc.serviceLog(containerName, serviceId, tailBytes);
            return s != null ? s : "";
        } catch (RemoteException e) {
            Log.e(TAG, "serviceLog failed", e);
            return "";
        }
    }

    /** Units (docs/UNITS.md): the daemon's JSON, or {"error":true,"message":..} (also for binder failures / old images). */
    public String unitControl(String containerName, String op, String jsonArgs) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return unitErr("aohp_container service not available");
            String s = svc.unitControl(containerName, op, jsonArgs);
            return s != null ? s : unitErr("empty response");
        } catch (RemoteException e) {
            Log.e(TAG, "unitControl failed", e);
            return unitErr("unitControl not available on this image: " + e);
        } catch (RuntimeException e) {
            // AbstractMethodError-like failures surface as UnsupportedOperationException on old images
            Log.e(TAG, "unitControl failed", e);
            return unitErr("unitControl not available on this image: " + e);
        }
    }

    private static String unitErr(String msg) {
        return "{\"error\":true,\"message\":" + org.json.JSONObject.quote(msg) + "}";
    }

    public CgroupUsage getUsage(String containerName) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return new CgroupUsage();
            String json = svc.getUsage(containerName);
            return CgroupUsage.fromJson(json);
        } catch (RemoteException e) {
            Log.e(TAG, "getUsage failed", e);
            return new CgroupUsage();
        }
    }

    public String diagnose(String containerName) {
        try {
            IAohpContainer svc = getService();
            if (svc == null) return "{}";
            String s = svc.diagnose(containerName);
            return s != null ? s : "{}";
        } catch (RemoteException e) {
            Log.e(TAG, "diagnose failed", e);
            return "{}";
        }
    }
}
