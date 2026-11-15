// Ported from AOHPAgentDriverApp (https://github.com/aohp-os/AOHPAgentDriverApp), which states
// no licence: this file is NOT under this repository's Apache-2.0 licence, and its upstream
// authors keep all rights in their code. See NOTICE. What was changed or added here is
// Copyright (C) 2026 Chris Anderson, under Apache-2.0 as far as it can be told apart.

package org.aohp.driver.bridge;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ShellExecutor - 通过 AOHP 系统服务执行虚拟屏上的输入/启动等动作；其余命令通过应用进程内 {@code sh -c} 执行。
 */
public class ShellExecutor {
    private static final String TAG = "[ShellExecutor]";

    private static ShellExecutor instance;
    private final ExecutorService executor;
    private volatile Context mHostContext;

    private ShellExecutor() {
        executor = Executors.newCachedThreadPool();
    }

    public static synchronized ShellExecutor getInstance() {
        if (instance == null) {
            instance = new ShellExecutor();
        }
        return instance;
    }

    private static CommandResult aohpUnavailable(String message) {
        CommandResult r = new CommandResult();
        r.success = false;
        r.error = message != null ? message : "AOHP 虚拟显示服务不可用";
        r.exitCode = -1;
        return r;
    }

    private CompletableFuture<CommandResult> aohpUnavailableFuture(String message) {
        return CompletableFuture.completedFuture(aohpUnavailable(message));
    }

    /**
     * 绑定 Application/Service 上下文，用于 AOHP 系统服务。
     */
    public void bindHostContext(Context context) {
        if (context == null) {
            return;
        }
        mHostContext = context.getApplicationContext();
        AohpVdClient.getInstance(mHostContext);
    }

    /**
     * Default display real pixel size and {@link DisplayMetrics#densityDpi}, from
     * {@link Display#getRealSize} / {@link Display#getRealMetrics}.
     * <p>
     * Used when AOHP / RPC creates a virtual display without explicit width/height so the VD
     * matches the built-in screen geometry.
     *
     * @return {@code int[]{width, height, densityDpi}} or {@code null} if no usable context
     */
    public int[] getBuiltinDisplayRealMetricsPx() {
        Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
        if (ctx == null) {
            return null;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return null;
            }
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                return null;
            }
            Point size = new Point();
            display.getRealSize(size);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int w = size.x;
            int h = size.y;
            if (w <= 0 || h <= 0) {
                w = metrics.widthPixels;
                h = metrics.heightPixels;
            }
            int dpi = metrics.densityDpi > 0 ? metrics.densityDpi : 320;
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new int[]{w, h, dpi};
        } catch (Throwable t) {
            Log.w(TAG, "getBuiltinDisplayRealMetricsPx: " + t.getMessage());
            return null;
        }
    }

    private AohpVdClient aohp() {
        Context ctx = mHostContext;
        if (ctx == null) {
            ctx = applicationContextOrNull();
        }
        if (ctx == null) {
            return null;
        }
        mHostContext = ctx.getApplicationContext();
        return AohpVdClient.getInstance(mHostContext);
    }

    private static Context applicationContextOrNull() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return ((Context) app).getApplicationContext();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applicationContextOrNull: " + t.getMessage());
        }
        return null;
    }

    /** 虚拟屏相关操作走 Binder（系统已注册 {@code aohp_virtual_display}）。 */
    private boolean useAohpVd() {
        AohpVdClient c = aohp();
        return c != null && c.useAohpForDisplayOps();
    }

    /** 释放虚拟屏等清理：系统注册了 aohp_virtual_display 即可走 Binder unregister。 */
    private boolean useAohpCleanup() {
        AohpVdClient c = aohp();
        return c != null && c.isServiceAvailable();
    }

    /** 系统是否已注册 {@code aohp_virtual_display}（绑定过 {@link #bindHostContext} 时探测更可靠）。 */
    public boolean isAohpVirtualDisplayServiceAvailable() {
        AohpVdClient c = aohp();
        return c != null && c.isServiceAvailable();
    }

    /**
     * 执行 Shell 命令（同步），经应用 uid 下的 {@code sh -c}。
     * <p>
     * stderr 合并到 stdout（{@link ProcessBuilder#redirectErrorStream(boolean)}），避免子进程先写满
     * stderr 管道而父进程仍阻塞在读取 stdout 时的经典死锁（例如 {@code dumpsys battery}）。
     */
    public CommandResult executeSync(String command) {
        CommandResult result = new CommandResult();
        Process process = null;
        BufferedReader outReader = null;

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            process = pb.start();

            outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder merged = new StringBuilder();
            String line;
            while ((line = outReader.readLine()) != null) {
                merged.append(line).append("\n");
            }
            result.output = merged.toString().trim();
            result.error = "";

            result.exitCode = process.waitFor();
            result.success = (result.exitCode == 0);

            Log.d(TAG, "命令执行完成: " + command + ", exitCode: " + result.exitCode);

        } catch (Exception e) {
            result.success = false;
            result.error = e.getMessage();
            Log.e(TAG, "命令执行失败: " + command, e);
        } finally {
            try {
                if (outReader != null) outReader.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
                Log.e(TAG, "关闭流失败", e);
            }
        }

        return result;
    }

    /**
     * 执行 Shell 命令（异步）
     */
    public CompletableFuture<CommandResult> executeAsync(String command) {
        return CompletableFuture.supplyAsync(() -> executeSync(command), executor);
    }

    /**
     * 电量信息（不调用 {@code dumpsys battery}：部分设备/镜像上应用内执行 dumpsys 会长时间阻塞）。
     */
    public CommandResult getBatteryInfoSync() {
        CommandResult r = new CommandResult();
        Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
        if (ctx == null) {
            r.success = false;
            r.exitCode = -1;
            r.error = "context unavailable";
            return r;
        }
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent = ctx.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                intent = ctx.registerReceiver(null, filter);
            }
            if (intent == null) {
                r.success = false;
                r.exitCode = -1;
                r.error = "BATTERY_CHANGED sticky intent unavailable";
                return r;
            }
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
            int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            String tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            boolean ac = (plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0;
            boolean usb = (plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0;
            boolean wireless = (plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0;

            StringBuilder sb = new StringBuilder();
            sb.append("Current Battery Service state (framework, not dumpsys):\n");
            sb.append("  AC powered: ").append(ac).append("\n");
            sb.append("  USB powered: ").append(usb).append("\n");
            sb.append("  Wireless powered: ").append(wireless).append("\n");
            sb.append("  status: ").append(status).append("\n");
            sb.append("  health: ").append(health).append("\n");
            sb.append("  present: ").append(present).append("\n");
            sb.append("  level: ").append(level).append("\n");
            sb.append("  scale: ").append(scale).append("\n");
            sb.append("  temperature: ").append(temp).append("\n");
            sb.append("  voltage: ").append(voltage).append("\n");
            if (tech != null) {
                sb.append("  technology: ").append(tech).append("\n");
            }
            r.output = sb.toString().trim();
            r.error = "";
            r.exitCode = 0;
            r.success = true;
        } catch (Exception e) {
            r.success = false;
            r.exitCode = -1;
            r.error = e.getMessage() != null ? e.getMessage() : "battery";
            Log.e(TAG, "getBatteryInfoSync", e);
        }
        return r;
    }

    /**
     * 网络摘要（不调用 {@code dumpsys connectivity}，避免部分环境下 dumpsys 阻塞）。
     */
    public CommandResult getNetworkInfoSync() {
        CommandResult r = new CommandResult();
        Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
        if (ctx == null) {
            r.success = false;
            r.exitCode = -1;
            r.error = "context unavailable";
            return r;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            StringBuilder sb = new StringBuilder();
            sb.append("Network summary (framework, not dumpsys):\n");
            if (cm == null) {
                sb.append("  ConnectivityManager unavailable\n");
            } else {
                Network n = cm.getActiveNetwork();
                if (n == null) {
                    sb.append("  activeNetwork: null\n");
                } else {
                    NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                    if (cap != null) {
                        sb.append("  transports: ");
                        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) sb.append("CELLULAR ");
                        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append("WIFI ");
                        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) sb.append("ETHERNET ");
                        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) sb.append("BLUETOOTH ");
                        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append("VPN ");
                        sb.append("\n");
                        sb.append("  internet: ").append(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).append("\n");
                        sb.append("  validated: ").append(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append("\n");
                    }
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        sb.append("  interface: ").append(lp.getInterfaceName()).append("\n");
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            sb.append("  address: ").append(la).append("\n");
                        }
                    }
                }
            }
            r.output = sb.toString().trim();
            r.error = "";
            r.exitCode = 0;
            r.success = true;
        } catch (Exception e) {
            r.success = false;
            r.exitCode = -1;
            r.error = e.getMessage() != null ? e.getMessage() : "network";
            Log.e(TAG, "getNetworkInfoSync", e);
        }
        return r;
    }

    /**
     * 显示设备摘要（不调用 {@code dumpsys display}）。
     */
    public CommandResult getScreenInfoSync() {
        CommandResult r = new CommandResult();
        Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
        if (ctx == null) {
            r.success = false;
            r.exitCode = -1;
            r.error = "context unavailable";
            return r;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            StringBuilder sb = new StringBuilder();
            sb.append("Display summary (framework, not dumpsys):\n");
            if (dm == null) {
                sb.append("  DisplayManager unavailable\n");
            } else {
                Display[] displays = dm.getDisplays();
                for (Display d : displays) {
                    Point size = new Point();
                    d.getRealSize(size);
                    sb.append("  id=").append(d.getDisplayId())
                            .append(" name=").append(d.getName())
                            .append(" realSize=").append(size.x).append("x").append(size.y)
                            .append(" state=").append(d.getState())
                            .append(" rotation=").append(d.getRotation())
                            .append("\n");
                }
            }
            r.output = sb.toString().trim();
            r.error = "";
            r.exitCode = 0;
            r.success = true;
        } catch (Exception e) {
            r.success = false;
            r.exitCode = -1;
            r.error = e.getMessage() != null ? e.getMessage() : "display";
            Log.e(TAG, "getScreenInfoSync", e);
        }
        return r;
    }

    /**
     * 设备/构建信息（不调用 {@code dumpsys package}：部分镜像上应用内执行 dumpsys 会长时间阻塞）。
     */
    public CommandResult getDeviceInfoSync() {
        CommandResult r = new CommandResult();
        Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
        if (ctx == null) {
            r.success = false;
            r.exitCode = -1;
            r.error = "context unavailable";
            return r;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Device info (framework, not dumpsys):\n");
            sb.append("  hostPackage: ").append(ctx.getPackageName()).append("\n");
            sb.append("  manufacturer: ").append(Build.MANUFACTURER).append("\n");
            sb.append("  brand: ").append(Build.BRAND).append("\n");
            sb.append("  model: ").append(Build.MODEL).append("\n");
            sb.append("  device: ").append(Build.DEVICE).append("\n");
            sb.append("  product: ").append(Build.PRODUCT).append("\n");
            sb.append("  hardware: ").append(Build.HARDWARE).append("\n");
            sb.append("  release: ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("  sdk: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("  fingerprint: ").append(Build.FINGERPRINT).append("\n");
            sb.append("  incremental: ").append(Build.VERSION.INCREMENTAL).append("\n");

            PackageManager pm = ctx.getPackageManager();
            try {
                PackageInfo pi = pm.getPackageInfo("com.android.shell", 0);
                sb.append("Package com.android.shell:\n");
                sb.append("  versionName: ").append(pi.versionName != null ? pi.versionName : "null").append("\n");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    sb.append("  longVersionCode: ").append(pi.getLongVersionCode()).append("\n");
                } else {
                    sb.append("  versionCode: ").append(pi.versionCode).append("\n");
                }
            } catch (PackageManager.NameNotFoundException e) {
                sb.append("Package com.android.shell: (not found)\n");
            }

            r.output = sb.toString().trim();
            r.error = "";
            r.exitCode = 0;
            r.success = true;
        } catch (Exception e) {
            r.success = false;
            r.exitCode = -1;
            r.error = e.getMessage() != null ? e.getMessage() : "device_info";
            Log.e(TAG, "getDeviceInfoSync", e);
        }
        return r;
    }

    // ==================== 虚拟屏幕操作方法 ====================

    /**
     * 在指定显示器上启动 App
     *
     * @param displayId 显示器 ID
     * @param packageName 包名
     * @param activityName Activity 名称（可为 null，自动使用启动 Activity）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> launchAppOnDisplay(int displayId, String packageName, String activityName) {
        if (activityName != null && !activityName.isEmpty()) {
            return aohpUnavailableFuture("AOHP 仅支持按包名启动 Launcher Activity，请传 activityName=null");
        }
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上启动应用: " + packageName);
        return CompletableFuture.completedFuture(aohp().startLauncherOnDisplay(displayId, packageName));
    }

    /**
     * Start an app via an explicit intent (e.g. {@code ACTION_VIEW} with a file URI).
     * Falls back to {@link #launchAppOnDisplay} when action or data are omitted.
     */
    public CompletableFuture<CommandResult> startAppWithIntent(
            int displayId,
            String packageName,
            String action,
            String dataUri,
            String mimeType) {
        return CompletableFuture.supplyAsync(() -> {
            CommandResult result = new CommandResult();
            Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
            if (ctx == null) {
                result.success = false;
                result.error = "no host context";
                result.exitCode = -1;
                return result;
            }
            try {
                Intent intent = new Intent(action);
                Uri uri = Uri.parse(dataUri);
                if (mimeType != null && !mimeType.isEmpty()) {
                    intent.setDataAndType(uri, mimeType);
                } else {
                    intent.setData(uri);
                }
                if (packageName != null && !packageName.isEmpty()) {
                    intent.setPackage(packageName);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (displayId != Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(displayId);
                    ctx.startActivity(intent, options.toBundle());
                } else {
                    ctx.startActivity(intent);
                }
                result.success = true;
                result.output = "OK";
                result.exitCode = 0;
                Log.i(TAG, "[Intent] display=" + displayId + " pkg=" + packageName
                        + " action=" + action + " data=" + dataUri);
            } catch (Exception e) {
                result.success = false;
                result.error = e.getMessage() != null ? e.getMessage() : "startAppWithIntent";
                result.exitCode = 1;
                Log.e(TAG, "startAppWithIntent failed", e);
            }
            return result;
        }, executor);
    }

    /**
     * 在指定显示器上模拟点击
     *
     * @param displayId 显示器 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> tapOnDisplay(int displayId, int x, int y) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上点击: (" + x + ", " + y + ")");
        return CompletableFuture.completedFuture(aohp().injectTap(displayId, x, y));
    }

    /**
     * 在指定显示器上模拟长按
     *
     * @param displayId 显示器 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @param duration 持续时间（毫秒）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> longPressOnDisplay(int displayId, int x, int y, int duration) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上长按: (" + x + ", " + y + "), 持续: " + duration + "ms");
        return CompletableFuture.completedFuture(
                aohp().injectSwipe(displayId, x, y, x, y, duration));
    }

    /**
     * 在指定显示器上模拟滑动
     *
     * @param displayId 显示器 ID
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param endX 结束 X 坐标
     * @param endY 结束 Y 坐标
     * @param duration 持续时间（毫秒）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> swipeOnDisplay(int displayId, int startX, int startY, int endX, int endY, int duration) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上滑动: (" + startX + ", " + startY + ") -> (" + endX + ", " + endY + ")");
        return CompletableFuture.completedFuture(
                aohp().injectSwipe(displayId, startX, startY, endX, endY, duration));
    }

    /**
     * 在指定显示器上输入文字
     * 注意：input text 命令不支持中文，需要使用其他方法
     *
     * @param displayId 显示器 ID
     * @param text 要输入的文字（仅支持 ASCII 字符）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> inputTextOnDisplay(int displayId, String text) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上输入文字: " + text);
        CommandResult binder = aohp().injectText(displayId, text);
        if (binder.success) {
            return CompletableFuture.completedFuture(binder);
        }
        Log.w(TAG, "[AOHP] injectText via Binder failed (" + binder.error
                + "); falling back to shell input");
        String escaped = escapeInputTextForShell(text);
        String command = String.format("input -d %d text \"%s\"", displayId, escaped);
        return executeAsync(command);
    }

    /**
     * Escape text for {@code adb shell input text}: spaces become {@code %s}; quotes are escaped.
     */
    private static String escapeInputTextForShell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(" ", "%s")
                .replace("\"", "\\\"")
                .replace("'", "\\'");
    }

    /**
     * 在指定显示器上模拟按键
     *
     * @param displayId 显示器 ID
     * @param keyCode 按键代码 (如 KEYCODE_BACK = 4, KEYCODE_HOME = 3)
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> keyEventOnDisplay(int displayId, int keyCode) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上发送按键: " + keyCode);
        return CompletableFuture.completedFuture(aohp().injectKeyEvent(displayId, keyCode));
    }

    /**
     * 获取指定显示器的 UI 层级（通过 uiautomator dump）
     *
     * @param displayId 显示器 ID
     * @return UI 层级 XML
     */
    public CompletableFuture<CommandResult> dumpUiHierarchy(int displayId) {
        String outputPath = "/data/local/tmp/ui_dump_display_" + displayId + ".xml";
        String dumpCommand = String.format("uiautomator dump --display %d %s", displayId, outputPath);
        String readCommand = String.format("cat %s", outputPath);

        Log.i(TAG, "获取显示器 " + displayId + " 的 UI 层级");

        return executeAsync(dumpCommand).thenCompose(result -> {
            if (result.success) {
                return executeAsync(readCommand);
            } else {
                return CompletableFuture.completedFuture(result);
            }
        });
    }

    /**
     * 获取所有显示器信息
     *
     * @return 显示器信息
     */
    public CompletableFuture<CommandResult> getDisplayInfo() {
        String command = "dumpsys display | grep -E 'mDisplayId|mBaseDisplayInfo'";
        Log.i(TAG, "获取显示器信息");
        return executeAsync(command);
    }

    /**
     * 对指定显示器进行截图
     * <p>
     * 优先走 {@code aohp_agent_view}（AOHP 系统服务，与 {@code shot.full} 一致）；服务不可用时
     * 回退到 {@code screencap -d}。在应用 uid 下对次级屏执行 {@code screencap} 通常会因权限失败
     * （exit≠0），因此 AOHP 可用时直接走 Binder 路径，避免无谓的子进程与告警。
     *
     * @param displayId 显示器 ID
     * @param outputPath 输出路径
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> screenshotOnDisplay(int displayId, String outputPath) {
        Log.i(TAG, "对显示器 " + displayId + " 截图: " + outputPath);
        Context ctx = mHostContext != null ? mHostContext : applicationContextOrNull();
        if (ctx != null) {
            AohpAgentViewClient av = new AohpAgentViewClient(ctx);
            if (av.isServiceAvailable()) {
                return CompletableFuture.supplyAsync(
                        () -> av.captureDisplayToFile(displayId, outputPath, 90), executor);
            }
        }
        String command = String.format("screencap -d %d -p %s", displayId, outputPath);
        return executeAsync(command);
    }

    /**
     * 命令执行结果类
     */
    public static class CommandResult {
        public boolean success;
        public String output;
        public String error;
        public int exitCode;

        @Override
        public String toString() {
            return "CommandResult{" +
                    "success=" + success +
                    ", exitCode=" + exitCode +
                    ", output='" + output + '\'' +
                    ", error='" + error + '\'' +
                    '}';
        }
    }

    // ==================== 应用管理 ====================

    /**
     * 强制停止应用
     *
     * @param displayId 显示器 ID（暂未使用，预留）
     * @param packageName 包名
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> forceStopApp(int displayId, String packageName) {
        String command = String.format("am force-stop %s", packageName);
        Log.i(TAG, "强制停止应用: " + packageName);
        return executeAsync(command);
    }

    /**
     * 获取所有已安装应用的包名列表
     *
     * @return 执行结果，output 包含包名列表
     */
    public CompletableFuture<CommandResult> getAllInstalledPackages() {
        Log.i(TAG, "获取所有已安装应用包名");
        return CompletableFuture.supplyAsync(
                () -> {
                    CommandResult r = listPackagesLikePm(false);
                    if (r != null) {
                        return r;
                    }
                    return executeSync("pm list packages");
                },
                executor);
    }

    /**
     * 获取第三方应用包名列表
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getThirdPartyPackages() {
        Log.i(TAG, "获取第三方应用包名");
        return CompletableFuture.supplyAsync(
                () -> {
                    CommandResult r = listPackagesLikePm(true);
                    if (r != null) {
                        return r;
                    }
                    return executeSync("pm list packages -3");
                },
                executor);
    }

    /**
     * 使用 {@link PackageManager} 列出包名，输出格式与 {@code pm list packages} 一致。
     * 避免在子进程中执行 {@code pm}（会单独打开 /dev/binder；部分环境下子进程无节点导致 abort）。
     */
    private CommandResult listPackagesLikePm(boolean thirdPartyOnly) {
        Context ctx = mHostContext;
        if (ctx == null) {
            ctx = applicationContextOrNull();
        }
        if (ctx == null) {
            return null;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs;
            if (Build.VERSION.SDK_INT >= 33) {
                pkgs = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
            } else {
                pkgs = pm.getInstalledPackages(0);
            }
            StringBuilder sb = new StringBuilder();
            for (PackageInfo pi : pkgs) {
                if (pi == null || pi.applicationInfo == null) {
                    continue;
                }
                ApplicationInfo ai = pi.applicationInfo;
                if (thirdPartyOnly && isSystemOnlyApp(ai)) {
                    continue;
                }
                sb.append("package:").append(pi.packageName).append('\n');
            }
            CommandResult r = new CommandResult();
            r.success = true;
            r.exitCode = 0;
            r.output = sb.toString().trim();
            r.error = "";
            Log.i(TAG, "listPackagesLikePm thirdParty=" + thirdPartyOnly + " count=" + pkgs.size());
            return r;
        } catch (Exception e) {
            Log.w(TAG, "listPackagesLikePm failed, will try pm shell", e);
            return null;
        }
    }

    /** 与 {@code pm list packages -3} 大致一致：排除仅系统分区预装、未由用户更新的包。 */
    private static boolean isSystemOnlyApp(ApplicationInfo ai) {
        int f = ai.flags;
        return (f & ApplicationInfo.FLAG_SYSTEM) != 0 && (f & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    /**
     * 获取应用的主 Activity
     *
     * @param packageName 包名
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getPackageMainActivity(String packageName) {
        String command = String.format("cmd package resolve-activity --brief %s", packageName);
        Log.i(TAG, "获取应用主 Activity: " + packageName);
        return executeAsync(command);
    }

    /**
     * 获取当前前台应用包名
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getCurrentForegroundPackage() {
        String command = "dumpsys activity activities | grep mResumedActivity";
        Log.i(TAG, "获取当前前台应用包名");
        return executeAsync(command);
    }

    /**
     * 获取当前焦点窗口信息
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getCurrentFocusedWindow() {
        String command = "dumpsys window | grep mCurrentFocus";
        Log.i(TAG, "获取当前焦点窗口");
        return executeAsync(command);
    }

    // ==================== 文本输入增强 ====================

    /**
     * 通过 system_server 无障碍 {@link android.view.accessibility.AccessibilityNodeInfo#ACTION_SET_TEXT}
     * 清空文本（不发送退格键）。{@code nodeId > 0} 与 {@code ui.tree} 中节点 id 一致；{@code nodeId <= 0}
     * 表示清空当前获得焦点的可编辑控件。
     *
     * @param displayId 显示器 ID
     * @param nodeId 节点 id；0 表示焦点控件
     * @param flags {@code dumpUiTree} 用标志位（如 {@code FLAG_APPLICATION_ONLY}）
     */
    public CompletableFuture<CommandResult> clearTextOnDisplay(int displayId, int nodeId, int flags) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] clearEditableText displayId=" + displayId + " nodeId=" + nodeId
                + " flags=" + flags);
        return CompletableFuture.completedFuture(aohp().clearEditableText(displayId, nodeId, flags));
    }

    /**
     * Set text on the focused editable (or {@code nodeId} from ui.tree) via {@code ACTION_SET_TEXT}.
     */
    public CompletableFuture<CommandResult> setTextOnDisplay(
            int displayId, int nodeId, String text, int flags) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] setEditableText displayId=" + displayId + " nodeId=" + nodeId
                + " flags=" + flags + " len=" + (text != null ? text.length() : 0));
        return CompletableFuture.completedFuture(
                aohp().setEditableText(displayId, nodeId, text, flags));
    }

    /**
     * 发送回车键
     *
     * @param displayId 显示器 ID
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> pressEnterOnDisplay(int displayId) {
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上发送回车键");
        return keyEventOnDisplay(displayId, KeyCode.KEYCODE_ENTER);
    }

    /**
     * 发送粘贴键 (KEYCODE_PASTE = 279)
     *
     * @param displayId 显示器 ID
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> pressPasteOnDisplay(int displayId) {
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上发送粘贴键");
        return keyEventOnDisplay(displayId, KeyCode.KEYCODE_PASTE);
    }

    // ==================== 系统功能 ====================

    /**
     * 展开通知栏
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> expandNotificationPanel() {
        String command = "cmd statusbar expand-notifications";
        Log.i(TAG, "展开通知栏");
        return executeAsync(command);
    }

    /**
     * 收起通知栏
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> collapseNotificationPanel() {
        String command = "cmd statusbar collapse";
        Log.i(TAG, "收起通知栏");
        return executeAsync(command);
    }

    /**
     * 展开快捷设置面板
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> expandSettingsPanel() {
        String command = "cmd statusbar expand-settings";
        Log.i(TAG, "展开快捷设置面板");
        return executeAsync(command);
    }

    /**
     * 获取屏幕分辨率和密度
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getScreenInfo() {
        String command = "wm size && wm density";
        Log.i(TAG, "获取屏幕信息");
        return executeAsync(command);
    }

    /**
     * 唤醒屏幕
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> wakeUpScreen() {
        String command = "input keyevent KEYCODE_WAKEUP";
        Log.i(TAG, "唤醒屏幕");
        return executeAsync(command);
    }

    /**
     * 熄灭屏幕
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> sleepScreen() {
        String command = "input keyevent KEYCODE_SLEEP";
        Log.i(TAG, "熄灭屏幕");
        return executeAsync(command);
    }

    /**
     * 解锁屏幕（发送 MENU 键）
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> unlockScreen() {
        String command = "input keyevent KEYCODE_MENU";
        Log.i(TAG, "解锁屏幕");
        return executeAsync(command);
    }

    // ==================== 文件操作 ====================

    /**
     * 推送文件到设备
     *
     * @param localPath 本地路径
     * @param remotePath 设备路径
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> pushFile(String localPath, String remotePath) {
        String command = String.format("cp %s %s", localPath, remotePath);
        Log.i(TAG, "推送文件: " + localPath + " -> " + remotePath);
        return executeAsync(command);
    }

    /**
     * 拉取文件从设备
     *
     * @param remotePath 设备路径
     * @param localPath 本地路径
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> pullFile(String remotePath, String localPath) {
        String command = String.format("cp %s %s", remotePath, localPath);
        Log.i(TAG, "拉取文件: " + remotePath + " -> " + localPath);
        return executeAsync(command);
    }

    /**
     * 删除文件
     *
     * @param path 文件路径
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> deleteFile(String path) {
        String command = String.format("rm -f %s", path);
        Log.i(TAG, "删除文件: " + path);
        return executeAsync(command);
    }

    /**
     * 创建目录
     *
     * @param path 目录路径
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> makeDir(String path) {
        String command = String.format("mkdir -p %s", path);
        Log.i(TAG, "创建目录: " + path);
        return executeAsync(command);
    }

    /**
     * 列出目录内容
     *
     * @param path 目录路径
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> listDir(String path) {
        String command = String.format("ls -la %s", path);
        Log.i(TAG, "列出目录: " + path);
        return executeAsync(command);
    }

    // ==================== 设备信息 ====================

    /**
     * 获取设备信息（品牌、型号等）
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getDeviceInfo() {
        String command = "getprop ro.product.brand && getprop ro.product.model && getprop ro.build.version.release";
        Log.i(TAG, "获取设备信息");
        return executeAsync(command);
    }

    /**
     * 获取 Android 版本
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getAndroidVersion() {
        String command = "getprop ro.build.version.release";
        Log.i(TAG, "获取 Android 版本");
        return executeAsync(command);
    }

    /**
     * 获取 SDK 版本
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getSdkVersion() {
        String command = "getprop ro.build.version.sdk";
        Log.i(TAG, "获取 SDK 版本");
        return executeAsync(command);
    }

    /**
     * 获取电池信息
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getBatteryInfo() {
        Log.i(TAG, "获取电池信息 (framework)");
        return CompletableFuture.completedFuture(getBatteryInfoSync());
    }

    /**
     * 获取网络信息
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> getNetworkInfo() {
        Log.i(TAG, "获取网络信息 (framework)");
        return CompletableFuture.completedFuture(getNetworkInfoSync());
    }

    // ==================== 多点触控和高级手势 ====================

    /**
     * 执行双击
     *
     * @param displayId 显示器 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> doubleTapOnDisplay(int displayId, int x, int y) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上双击: (" + x + ", " + y + ")");
        return CompletableFuture.supplyAsync(() -> {
            CommandResult r1 = aohp().injectTap(displayId, x, y);
            if (!r1.success) {
                return r1;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return aohp().injectTap(displayId, x, y);
        }, executor);
    }

    /**
     * 执行拖拽操作
     *
     * @param displayId 显示器 ID
     * @param startX 起始 X
     * @param startY 起始 Y
     * @param endX 结束 X
     * @param endY 结束 Y
     * @param duration 持续时间（毫秒）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> dragOnDisplay(int displayId, int startX, int startY, int endX, int endY, int duration) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "[AOHP] 在显示器 " + displayId + " 上拖拽: (" + startX + ", " + startY + ") -> (" + endX + ", " + endY + ")");
        return CompletableFuture.completedFuture(
                aohp().injectSwipe(displayId, startX, startY, endX, endY, duration));
    }

    // ==================== Activity/Task 管理（用于虚拟屏幕修复） ====================

    /**
     * 获取指定 display 上的前台 Activity 信息
     * 返回格式: packageName/activityName
     *
     * @param displayId 显示器 ID
     * @return 执行结果，output 包含 Activity 信息
     */
    public CompletableFuture<CommandResult> getTopActivityOnDisplay(int displayId) {
        // 使用 dumpsys activity 获取指定 display 上的前台 Activity
        String command = String.format(
            "dumpsys activity activities | grep -A 5 'Display #%d' | grep 'mResumedActivity' | head -1",
            displayId
        );
        Log.i(TAG, "获取显示器 " + displayId + " 上的前台 Activity");
        return executeAsync(command);
    }

    /**
     * 获取主屏幕（display 0）上的前台 Activity 及其 TaskId
     * 
     * @return 执行结果，包含 Activity 和 TaskId 信息
     */
    public CompletableFuture<CommandResult> getMainDisplayTopActivity() {
        // 获取 display 0 上的前台 Activity 信息
        String command = "dumpsys activity activities | grep -A 20 'Display #0' | grep -E 'mResumedActivity|taskId=' | head -5";
        Log.i(TAG, "获取主屏幕上的前台 Activity 信息");
        return executeAsync(command);
    }

    /**
     * 将 Task 移动到指定的 display（经 shell，依赖系统对 {@code cmd activity} 的权限策略）。
     *
     * @param taskId Task ID
     * @param displayId 目标显示器 ID
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> moveTaskToDisplay(int taskId, int displayId) {
        // Android 10+ 使用 am display move-stack 或 wm
        // 尝试使用多种方式
        String command = String.format(
            // Android 12/13+ 更常见的命令
            "cmd activity task move-to-display %d %d 2>/dev/null || " +
            // 兼容部分版本的旧写法
            "cmd activity move-task %d %d 2>/dev/null || " +
            "am task move-to-display %d %d 2>/dev/null || " +
            // 极旧版本（注意：move-stack 的第一个参数可能是 stackId；放最后兜底）
            "am display move-stack %d %d 2>/dev/null",
            taskId, displayId,
            taskId, displayId,
            taskId, displayId,
            taskId, displayId
        );
        Log.i(TAG, "将 Task " + taskId + " 移动到显示器 " + displayId);
        return executeAsync(command);
    }

    /**
     * 获取 dumpsys activity activities 的完整输出（便于在 Java 侧解析）
     */
    public CompletableFuture<CommandResult> dumpsysActivityActivities() {
        String command = "dumpsys activity activities";
        Log.i(TAG, "获取 activity activities dumpsys");
        return executeAsync(command);
    }

    /**
     * best-effort：把某个 task 尽量恢复为“全屏 + 填满目标尺寸”。
     *
     * 背景：
     * - 某些 ROM 在次级/虚拟 display 上会把 task 默认放进 freeform 小窗
     * - move-to-display 后如果保持 freeform windowing mode，就会出现你描述的“小窗/显示异常”
     *
     * 这里用多条命令兜底，不同 Android 版本/ROM 支持的子命令不同：
     * - set-windowing-mode 1 (fullscreen)
     * - resize 0,0,w,h
     */
    public CompletableFuture<CommandResult> forceTaskFullscreenAndResize(int taskId, int width, int height) {
        // 注意：cmd activity task 的子命令在不同版本差异很大，全部加 2>/dev/null 且用 || true 避免失败影响主流程
        String command = String.format(
                "cmd activity task set-windowing-mode %d 1 2>/dev/null || true; " +
                        "cmd activity task resize %d 0 0 %d %d 2>/dev/null || true; " +
                        // 有些版本使用 task resizeable/resize-task 之类；放到最后兜底
                        "cmd activity resize-task %d 0 0 %d %d 2>/dev/null || true; " +
                        "echo OK",
                taskId,
                taskId, width, height,
                taskId, width, height
        );
        Log.i(TAG, "尝试将 taskId=" + taskId + " 设为全屏并 resize 到 " + width + "x" + height);
        return executeAsync(command);
    }

    /**
     * 将主屏幕上属于指定包名的 Activity 移动到虚拟屏幕
     * 这是一个综合方法，会自动查找并移动
     *
     * @param packageName 包名
     * @param targetDisplayId 目标显示器 ID
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> movePackageActivityToDisplay(String packageName, int targetDisplayId) {
        // 先获取该包名在 display 0 上的 taskId，然后移动（尽量兼容不同 dumpsys 输出格式）
        String command = String.format(
                "taskId=$(dumpsys activity activities | " +
                        "sed -n '/Display #0/,/Display #[0-9]/p' | " +
                        "grep -m 1 '%s' | " +
                        "sed -n 's/.*taskId=\\([0-9][0-9]*\\).*/\\1/p; s/.* t\\([0-9][0-9]*\\).*/\\1/p'); " +
                        "if [ -n \"$taskId\" ]; then " +
                        "  echo \"Found taskId: $taskId\"; " +
                        "  cmd activity task move-to-display $taskId %d 2>/dev/null || " +
                        "  cmd activity move-task $taskId %d 2>/dev/null || " +
                        "  am task move-to-display $taskId %d 2>/dev/null || " +
                        "  am display move-stack $taskId %d 2>/dev/null; " +
                        "else " +
                        "  echo \"No task found for %s on display 0\"; " +
                        "fi",
                packageName,
                targetDisplayId, targetDisplayId, targetDisplayId, targetDisplayId,
                packageName
        );
        Log.i(TAG, "将包名 " + packageName + " 的 Activity 从 display 0 移动到 display " + targetDisplayId);
        return executeAsync(command);
    }

    /**
     * 将 display 0 上的顶部 Activity 移动到指定 display
     * 使用更直接的方法
     *
     * @param targetDisplayId 目标显示器 ID
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> moveTopActivityFromMainDisplay(int targetDisplayId) {
        // 获取 display 0 上的 resumed task 并移动到目标 display（兼容 taskId=/t123 两种格式）
        String command = String.format(
                "taskId=$(dumpsys activity activities | " +
                        "sed -n '/Display #0/,/Display #[0-9]/p' | " +
                        "grep -m 1 'mResumedActivity' | " +
                        "sed -n 's/.*taskId=\\([0-9][0-9]*\\).*/\\1/p; s/.* t\\([0-9][0-9]*\\).*/\\1/p'); " +
                        "if [ -n \"$taskId\" ]; then " +
                        "  echo \"Moving taskId: $taskId to display %d\"; " +
                        "  cmd activity task move-to-display $taskId %d 2>/dev/null || " +
                        "  cmd activity move-task $taskId %d 2>/dev/null || " +
                        "  am task move-to-display $taskId %d 2>/dev/null || " +
                        "  am display move-stack $taskId %d 2>&1; " +
                        "else " +
                        "  echo \"No resumed activity task found on display 0\"; " +
                        "fi",
                targetDisplayId,
                targetDisplayId, targetDisplayId, targetDisplayId, targetDisplayId
        );
        Log.i(TAG, "将主屏幕顶部 Activity 移动到显示器 " + targetDisplayId);
        return executeAsync(command);
    }

    /**
     * 在指定 display 上重新启动应用的 Activity
     * 如果应用的 Activity 跑到了主屏幕，通过重新启动将其拉回虚拟屏幕
     *
     * @param displayId 目标显示器 ID
     * @param packageName 包名
     * @param activityName Activity 名称（完整名称）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> restartActivityOnDisplay(int displayId, String packageName, String activityName) {
        String command = String.format(
                "am start --display %d --windowingMode 1 -n %s/%s -f 0x10000000 2>/dev/null || " +
                        "am start --display %d -n %s/%s -f 0x10000000",
                displayId, packageName, activityName,
                displayId, packageName, activityName
        );
        Log.i(TAG, "在显示器 " + displayId + " 上重启 Activity: " + packageName + "/" + activityName);
        return executeAsync(command);
    }

    /**
     * 获取 display 0 上的 mResumedActivity 的完整组件名称
     *
     * @return 执行结果，output 包含组件名称 (如 "com.package/.Activity")
     */
    public CompletableFuture<CommandResult> getMainDisplayResumedActivity() {
        String command = "dumpsys activity activities | grep -A 50 'Display #0' | " +
                        "grep 'mResumedActivity' | head -1 | " +
                        "sed 's/.*mResumedActivity: ActivityRecord{[^ ]* [^ ]* \\([^ ]*\\).*/\\1/'";
        Log.i(TAG, "获取主屏幕上的 ResumedActivity");
        return executeAsync(command);
    }

    // ==================== 录屏相关 ====================

    /**
     * 开始屏幕录制
     *
     * @param outputPath 输出路径
     * @param duration 最大时长（秒）
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> startScreenRecord(String outputPath, int duration) {
        String command = String.format("screenrecord --time-limit %d %s &", duration, outputPath);
        Log.i(TAG, "开始录屏: " + outputPath + ", 最大时长: " + duration + "s");
        return executeAsync(command);
    }

    /**
     * 停止屏幕录制
     *
     * @return 执行结果
     */
    public CompletableFuture<CommandResult> stopScreenRecord() {
        String command = "pkill -SIGINT screenrecord";
        Log.i(TAG, "停止录屏");
        return executeAsync(command);
    }

    // ==================== 多显示器/虚拟屏幕兼容（AOHP） ====================

    /**
     * 通过 AOHP 系统服务开启多显示器相关开发者设置（best-effort）。
     */
    public CompletableFuture<CommandResult> enableMultiDisplayFriendlySettings() {
        if (aohp() == null || !aohp().isServiceAvailable()) {
            return aohpUnavailableFuture(null);
        }
        Log.i(TAG, "尝试开启多显示器兼容设置（AOHP 系统服务）");
        return CompletableFuture.completedFuture(aohp().applyMultiDisplayDeveloperSettings());
    }

    /**
     * 向 AOHP 注册当前虚拟屏会话（displayId + 调用方 uid/包名）。
     */
    public CompletableFuture<CommandResult> publishVirtualDisplayId(int displayId) {
        if (!useAohpVd()) {
            return aohpUnavailableFuture(null);
        }
        String pkg = mHostContext != null ? mHostContext.getPackageName() : "";
        return CompletableFuture.completedFuture(
                aohp().registerSession(displayId, android.os.Process.myUid(), pkg));
    }

    /**
     * 注销 AOHP 虚拟屏会话。
     */
    public CompletableFuture<CommandResult> clearVirtualDisplayId() {
        if (!useAohpCleanup()) {
            return aohpUnavailableFuture(null);
        }
        return CompletableFuture.completedFuture(aohp().unregisterSession());
    }

    /**
     * 通知 AOHP 当前应固定到虚拟屏的目标包名。
     */
    public CompletableFuture<CommandResult> publishVirtualDisplayPackage(String packageName) {
        if (!useAohpCleanup()) {
            return aohpUnavailableFuture(null);
        }
        String pkg = (packageName == null) ? "" : packageName.trim();
        return CompletableFuture.completedFuture(aohp().setFocusPackage(pkg));
    }

    /**
     * 清除 AOHP 侧目标包名。
     */
    public CompletableFuture<CommandResult> clearVirtualDisplayPackage() {
        if (!useAohpCleanup()) {
            return aohpUnavailableFuture(null);
        }
        return CompletableFuture.completedFuture(aohp().setFocusPackage(""));
    }

    /**
     * 常用按键代码
     */
    public static class KeyCode {
        public static final int KEYCODE_HOME = 3;
        public static final int KEYCODE_BACK = 4;
        public static final int KEYCODE_CALL = 5;
        public static final int KEYCODE_ENDCALL = 6;
        public static final int KEYCODE_VOLUME_UP = 24;
        public static final int KEYCODE_VOLUME_DOWN = 25;
        public static final int KEYCODE_POWER = 26;
        public static final int KEYCODE_CAMERA = 27;
        public static final int KEYCODE_TAB = 61;
        public static final int KEYCODE_SPACE = 62;
        public static final int KEYCODE_ENTER = 66;
        public static final int KEYCODE_DEL = 67;               // Backspace
        public static final int KEYCODE_MENU = 82;
        public static final int KEYCODE_SEARCH = 84;
        public static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;
        public static final int KEYCODE_MEDIA_STOP = 86;
        public static final int KEYCODE_MEDIA_NEXT = 87;
        public static final int KEYCODE_MEDIA_PREVIOUS = 88;
        public static final int KEYCODE_PAGE_UP = 92;
        public static final int KEYCODE_PAGE_DOWN = 93;
        public static final int KEYCODE_MOVE_HOME = 122;
        public static final int KEYCODE_MOVE_END = 123;
        public static final int KEYCODE_FORWARD_DEL = 112;      // Delete
        public static final int KEYCODE_ESCAPE = 111;
        public static final int KEYCODE_SYSRQ = 120;            // PrintScreen
        public static final int KEYCODE_BREAK = 121;            // Pause/Break
        public static final int KEYCODE_APP_SWITCH = 187;       // Recent apps
        public static final int KEYCODE_ASSIST = 219;           // Google Assistant
        public static final int KEYCODE_BRIGHTNESS_DOWN = 220;
        public static final int KEYCODE_BRIGHTNESS_UP = 221;
        public static final int KEYCODE_SLEEP = 223;
        public static final int KEYCODE_WAKEUP = 224;
        public static final int KEYCODE_SOFT_SLEEP = 276;
        public static final int KEYCODE_CUT = 277;
        public static final int KEYCODE_COPY = 278;
        public static final int KEYCODE_PASTE = 279;
        public static final int KEYCODE_ALL_APPS = 284;

        // 方向键
        public static final int KEYCODE_DPAD_UP = 19;
        public static final int KEYCODE_DPAD_DOWN = 20;
        public static final int KEYCODE_DPAD_LEFT = 21;
        public static final int KEYCODE_DPAD_RIGHT = 22;
        public static final int KEYCODE_DPAD_CENTER = 23;

        // 数字键
        public static final int KEYCODE_0 = 7;
        public static final int KEYCODE_1 = 8;
        public static final int KEYCODE_2 = 9;
        public static final int KEYCODE_3 = 10;
        public static final int KEYCODE_4 = 11;
        public static final int KEYCODE_5 = 12;
        public static final int KEYCODE_6 = 13;
        public static final int KEYCODE_7 = 14;
        public static final int KEYCODE_8 = 15;
        public static final int KEYCODE_9 = 16;
    }

    /**
     * 清理资源
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
