package org.aohp.driver.bridge;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local LLM runtime (RPC methods llm.*).
 *
 * The Driver APK ships llama-server and its ggml backends (CPU + Hexagon NPU) as native libraries, so
 * they land in the APK's native library dir on install (/system/priv-app/AOHPDriver/lib/arm64 in the
 * image, /data/app/.../lib/arm64 after an adb install). The server itself is not run by the app: it runs
 * as a HostExec= unit of an environment, i.e. an Android host process (bionic, /vendor access through
 * the sphal namespace) supervised by aohp-containerd like any other unit of that env - stop/restart,
 * Restart=, the env cgroup and the env lifecycle all apply. The env's OpenClaw reaches it on
 * 127.0.0.1:PORT as an OpenAI-compatible endpoint.
 *
 * Models are data, not part of the image: they live under /opt/llm/models inside the env rootfs
 * (host path /data/aohp/envs/ENV/rootfs/opt/llm/models) so both sides can see them.
 *
 * llm.configure writes /etc/aohp/system/llama-server.service in the env from a handful of parameters
 * (model, device, ctx, thinking, extra args) and (re)starts it; the generated unit is plain text and can
 * be edited by hand like any other unit, llm.configure just regenerates it.
 */
public final class LlmManager {
    private static final String TAG = "AohpLlm";

    public static final String UNIT = "llama-server";
    public static final String MODELS_DIR = "/opt/llm/models";
    public static final String STATE_FILE = "/etc/aohp/llm.json";
    private static final String EXE = "libllama-server.so";
    private static final String VENDOR_FASTRPC = "/vendor/lib64/libcdsprpc.so";
    /** Daemon-executable staging copies of the runtime, one directory per Driver versionCode. */
    private static final String STAGE_ROOT = "/data/aohp/shared/llm";
    private static final int DEFAULT_PORT = 8082;
    private static final int DEFAULT_CTX = 16384;

    private final Context mContext;
    private final AohpContainerClient mContainer;

    public LlmManager(Context context, AohpContainerClient container) {
        mContext = context.getApplicationContext();
        mContainer = container;
    }

    // ---- environment / hardware -------------------------------------------------------------

    /** Image copy of the runtime (PRODUCT_COPY_FILES): PackageManager does not extract a /system priv-app's jniLibs. */
    private static final String IMAGE_LIB_DIR = "/system/etc/aohp/llm";

    private String libDir() {
        String app = mContext.getApplicationInfo().nativeLibraryDir;
        if (app != null && new File(app, EXE).exists()) return app;   // adb-installed update: extracted to /data/app
        if (new File(IMAGE_LIB_DIR, EXE).exists()) return IMAGE_LIB_DIR;
        return app;
    }

    /**
     * Which of the two CPU builds in the APK fits this SoC. The Snapdragon build is -march=armv8.7a+i8mm
     * (SIGILL on Tensor / Cortex-A78: no i8mm); the generic one is armv8.2-a+fp16+dotprod and shipped
     * with a libg_ prefix, renamed to canonical names when staged.
     */
    static String cpuVariant() {
        try {
            String cpuinfo = new String(readAll(new java.io.FileInputStream("/proc/cpuinfo")), StandardCharsets.UTF_8);
            for (String line : cpuinfo.split("\n")) {
                if (line.startsWith("Features")) {
                    return (" " + line + " ").contains(" i8mm ") ? "snapdragon" : "generic";
                }
            }
        } catch (Exception ignored) { }
        return "generic";
    }

    private long versionCode() {
        try {
            return mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Hexagon HTP architecture for a Snapdragon SoC model (Build.SOC_MODEL), null when ggml has no skel for it. */
    static String htpArch(String soc) {
        if (soc == null) return null;
        String s = soc.toUpperCase(Locale.ROOT);
        if (s.startsWith("SM8850") || s.startsWith("SM8845")) return "v81";   // Snapdragon 8 Elite Gen 5
        if (s.startsWith("SM8750") || s.startsWith("SM8735")) return "v79";   // Snapdragon 8 Elite
        if (s.startsWith("SM8650") || s.startsWith("SM8635")) return "v75";   // Snapdragon 8 Gen 3
        if (s.startsWith("SM8550")) return "v73";                             // Snapdragon 8 Gen 2
        return null;                                                          // SM8475/SM8450 (v69) and older: no skel
    }

    private boolean htpAvailable(String arch) {
        return arch != null && new File(VENDOR_FASTRPC).exists()
                && new File(libDir(), "libggml-htp-" + arch + ".so").exists();
    }

    private static String envName(JSONObject p) throws JSONException {
        String e = p.optString("env", p.optString("name", ""));
        if (e.isEmpty()) throw new IllegalArgumentException("missing env");
        return e;
    }

    private static String rootfs(String env) {
        return "/data/aohp/envs/" + env + "/rootfs";
    }

    // ---- RPC methods ------------------------------------------------------------------------

    public JSONObject info(JSONObject p) throws JSONException {
        JSONObject o = new JSONObject();
        String lib = libDir();
        String soc = Build.SOC_MODEL;
        String arch = htpArch(soc);
        o.put("libDir", lib);
        o.put("exe", new File(lib, EXE).exists() ? new File(lib, EXE).getAbsolutePath() : JSONObject.NULL);
        o.put("soc", soc);
        o.put("htpArch", arch != null ? arch : JSONObject.NULL);
        o.put("fastrpc", new File(VENDOR_FASTRPC).exists());
        JSONArray acc = new JSONArray();
        if (htpAvailable(arch)) acc.put("htp");
        acc.put("cpu");
        o.put("accelerators", acc);
        o.put("cpuVariant", cpuVariant());
        o.put("recommendedDevice", acc.getString(0));
        o.put("unit", UNIT);
        o.put("modelsDir", MODELS_DIR);
        String env = p.optString("env", p.optString("name", ""));
        if (!env.isEmpty()) {
            o.put("env", env);
            o.put("models", listModels(env));
            JSONObject st = readState(env);
            if (st != null) o.put("config", st);
            o.put("status", unitStatusQuiet(env));
        }
        return o;
    }

    public JSONObject models(JSONObject p) throws JSONException {
        String env = envName(p);
        JSONObject o = new JSONObject();
        o.put("env", env);
        o.put("dir", MODELS_DIR);
        o.put("hostDir", rootfs(env) + MODELS_DIR);
        o.put("models", listModels(env));
        return o;
    }

    private JSONArray listModels(String env) {
        JSONArray a = new JSONArray();
        ShellExecutor.CommandResult r = mContainer.execSync(env,
                "cd " + MODELS_DIR + " 2>/dev/null && stat -c '%s %n' *.gguf 2>/dev/null", 10000);
        if (r == null || r.output == null) return a;
        for (String line : r.output.split("\n")) {
            line = line.trim();
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            try {
                JSONObject m = new JSONObject();
                m.put("file", line.substring(sp + 1));
                m.put("bytes", Long.parseLong(line.substring(0, sp)));
                a.put(m);
            } catch (Exception ignored) { }
        }
        return a;
    }

    /**
     * Generate /etc/aohp/system/llama-server.service in the env and (re)start it.
     * Params: env, model (file in /opt/llm/models or an absolute host path), alias, device (auto|htp|cpu),
     * ctx, port, threads, parallel, thinking (bool), extra (array of extra args), autostart (bool), start (bool).
     */
    public JSONObject configure(JSONObject p) throws JSONException {
        String env = envName(p);
        String model = p.optString("model", "");
        if (model.isEmpty()) throw new IllegalArgumentException("missing model");
        String lib = libDir();
        File exe = new File(lib, EXE);
        if (!exe.exists()) throw new IllegalStateException("llama-server not shipped in this Driver build (" + exe + ")");

        String hostModel = model.startsWith("/") ? model : rootfs(env) + MODELS_DIR + "/" + model;
        String base = model.substring(model.lastIndexOf('/') + 1);
        String alias = p.optString("alias", "");
        if (alias.isEmpty()) alias = base.toLowerCase(Locale.ROOT).replaceAll("\\.gguf$", "");

        String arch = htpArch(Build.SOC_MODEL);
        String device = p.optString("device", "auto").toLowerCase(Locale.ROOT);
        if (device.equals("auto")) device = htpAvailable(arch) ? "htp" : "cpu";
        if (device.equals("htp") && !htpAvailable(arch)) {
            throw new IllegalArgumentException("htp not available on this device (soc=" + Build.SOC_MODEL
                    + ", arch=" + arch + ", fastrpc=" + new File(VENDOR_FASTRPC).exists() + ")");
        }
        int ctx = p.optInt("ctx", DEFAULT_CTX);
        int port = p.optInt("port", DEFAULT_PORT);
        int threads = p.optInt("threads", 0);
        int parallel = p.optInt("parallel", 1);
        boolean thinking = p.optBoolean("thinking", false);
        boolean autostart = p.optBoolean("autostart", true);
        boolean start = p.optBoolean("start", true);

        List<String> args = new ArrayList<>();
        // The daemon may neither exec the APK's extracted libs (apk_data_file: execute_no_trans denied) nor
        // /system/bin/linker64 (same), and the /system/priv-app copy is 0644 anyway - but it can read both
        // and execute from its own data dir. So the unit stages a copy of the runtime per Driver version
        // under /data/aohp/shared/llm/<versionCode> (aohp_container_data_file, 0755) in ExecStartPre.
        String variant = cpuVariant();
        String stage = STAGE_ROOT + "/" + versionCode() + "-" + variant;
        args.add(stage + "/" + EXE);
        args.add("-m"); args.add(hostModel);
        args.add("--alias"); args.add(alias);
        if (device.equals("htp")) { args.add("--device"); args.add("HTP0"); args.add("-ngl"); args.add("99"); }
        // cpu: '--device none' - with only '-ngl 0' llama still tries to place the context on the
        // registered Hexagon device (session open fails on images without the FastRPC grants, and
        // on non-Qualcomm SoCs) and dies with 'failed to create llama_context'.
        else { args.add("--device"); args.add("none"); args.add("-ngl"); args.add("0"); }
        if (threads > 0) { args.add("-t"); args.add(String.valueOf(threads)); }
        args.add("--host"); args.add("127.0.0.1");
        args.add("--port"); args.add(String.valueOf(port));
        args.add("-c"); args.add(String.valueOf(ctx));
        args.add("--parallel"); args.add(String.valueOf(parallel));
        args.add("-fa"); args.add("on");
        args.add("--jinja");
        args.add("--no-webui");
        args.add("--reasoning-format"); args.add("auto");
        if (!thinking) { args.add("--reasoning-budget"); args.add("0"); }
        JSONArray extra = p.optJSONArray("extra");
        if (extra != null) for (int i = 0; i < extra.length(); i++) args.add(extra.getString(i));

        StringBuilder exec = new StringBuilder();
        for (String a : args) { if (exec.length() > 0) exec.append(' '); exec.append(shq(a)); }
        // aohp-containerd (and so everything it spawns, HostExec= units included) lives in the
        // system-background cpuset = the little cores only (0-3: four A55 on the Pixel 6, where the 4B
        // decoded at 0.17 tok/s). Move the server to top-app (all cores) before exec; the daemon domain
        // may write the cpuset tasks file.
        // Daemons from build-23 on place every spawned process themselves (POWER mode, sandbox.power);
        // only older images need the unit to escape the little cores by hand.
        boolean daemonPlaces = mContainer.powerMode("") != null;
        String execStart = daemonPlaces ? exec.toString()
                : "/system/bin/sh -c " + shq("echo $$ > /dev/cpuset/top-app/tasks; exec " + exec);

        String unit = "# Generated by the AOHP Driver (llm.configure / aohp llm use). Edit freely; the next llm.configure\n"
                + "# regenerates it. llama-server runs on the Android host (HostExec=), supervised by aohp-containerd.\n"
                + "[Unit]\n"
                + "Description=llama-server (" + alias + ", " + device + ") - local LLM endpoint on 127.0.0.1:" + port + "\n"
                + "\n[Service]\n"
                + "Type=simple\n"
                + "HostExec=yes\n"
                + "Environment=LD_LIBRARY_PATH=" + stage + "\n"
                + "Environment=ADSP_LIBRARY_PATH=" + stage + "\n"
                // ggml-hexagon's FastRPC capability query is the first thing SELinux can trip on; without an
                // answer it silently assumes v73, so hand it the arch derived from the SoC.
                + (device.equals("htp") ? "Environment=GGML_HEXAGON_ARCH=" + arch.substring(1) + "\n" : "")
                + "ExecStartPre=/system/bin/sh -c '[ -x " + stage + "/" + EXE + " ] || { rm -rf " + stage
                + " && mkdir -p " + stage + " && " + stageCopy(lib, stage, variant) + " && chmod 755 " + stage + "/*.so; }'\n"
                + "ExecStart=" + execStart + "\n"
                + "Restart=on-failure\n"
                + "RestartSec=3\n"
                + "TimeoutStopSec=15\n"
                + "\n[Install]\n"
                + "WantedBy=aohp.target\n";

        JSONObject state = new JSONObject();
        state.put("model", model); state.put("hostModel", hostModel); state.put("alias", alias);
        state.put("device", device); state.put("ctx", ctx); state.put("port", port); state.put("threads", threads);
        state.put("parallel", parallel); state.put("thinking", thinking); state.put("autostart", autostart);
        state.put("extra", extra != null ? extra : new JSONArray()); state.put("libDir", lib); state.put("stageDir", stage);
        state.put("cpuVariant", variant);
        state.put("endpoint", "http://127.0.0.1:" + port + "/v1");

        String cmd = "mkdir -p /etc/aohp/system && cat > /etc/aohp/system/" + UNIT + ".service <<'AOHP_LLM_EOF'\n"
                + unit + "AOHP_LLM_EOF\n"
                + "cat > " + STATE_FILE + " <<'AOHP_LLM_EOF'\n" + state.toString(2) + "\nAOHP_LLM_EOF\n";
        ShellExecutor.CommandResult w = mContainer.execSync(env, cmd, 15000);
        if (w == null || !w.success) {
            throw new IllegalStateException("writing unit failed: " + (w != null ? w.error + w.output : "no result"));
        }
        unit(env, "daemon-reload", new JSONObject());
        unit(env, autostart ? "enable" : "disable", new JSONObject().put("unit", UNIT));
        JSONObject o = new JSONObject();
        o.put("env", env); o.put("unit", UNIT + ".service"); o.put("config", state); o.put("execStart", exec.toString());
        if (start) {
            o.put("status", unit(env, "restart", new JSONObject().put("unit", UNIT)));
        }
        return o;
    }

    public JSONObject control(JSONObject p, String op) throws JSONException {
        String env = envName(p);
        JSONObject o = new JSONObject();
        o.put("env", env);
        o.put("status", unit(env, op, new JSONObject().put("unit", UNIT)));
        return o;
    }

    public JSONObject status(JSONObject p) throws JSONException {
        String env = envName(p);
        JSONObject o = new JSONObject();
        o.put("env", env);
        JSONObject st = readState(env);
        if (st != null) o.put("config", st);
        o.put("unit", unitStatusQuiet(env));
        int port = st != null ? st.optInt("port", DEFAULT_PORT) : DEFAULT_PORT;
        o.put("health", health(port));
        return o;
    }

    public JSONObject log(JSONObject p) throws JSONException {
        String env = envName(p);
        int tail = p.optInt("tailBytes", 16384);
        return unit(env, "log", new JSONObject().put("unit", UNIT).put("tailBytes", tail));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private JSONObject unit(String env, String op, JSONObject args) throws JSONException {
        String j = mContainer.unitControl(env, op, args.toString());
        JSONObject o;
        try {
            o = new JSONObject(j == null ? "{}" : j);
        } catch (JSONException e) {
            throw new IllegalStateException("bad unit response: " + j);
        }
        if (o.optBoolean("error", false)) {
            throw new IllegalStateException(o.optString("message", "unit " + op + " failed"));
        }
        return o;
    }

    private Object unitStatusQuiet(String env) {
        try {
            return unit(env, "status", new JSONObject().put("unit", UNIT));
        } catch (Exception e) {
            return "not configured (" + e.getMessage() + ")";
        }
    }

    private JSONObject readState(String env) {
        ShellExecutor.CommandResult r = mContainer.execSync(env, "cat " + STATE_FILE + " 2>/dev/null", 5000);
        if (r == null || r.output == null || r.output.trim().isEmpty()) return null;
        try {
            return new JSONObject(r.output.trim());
        } catch (JSONException e) {
            return null;
        }
    }

    /** GET /health on the loopback endpoint: "ok", "loading", "down" or the raw body. */
    private String health(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in != null ? new String(readAll(in), StandardCharsets.UTF_8) : "";
            if (code == 200 && body.contains("\"ok\"")) return "ok";
            if (code == 503) return "loading";
            return code + " " + body.trim();
        } catch (Exception e) {
            return "down";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** The copy half of the staging ExecStartPre (runs under /system/bin/sh on the host, as root). */
    private static String stageCopy(String lib, String stage, String variant) {
        if (variant.equals("generic")) {
            // libg_X.so -> X.so, plus the (variant-independent) NPU skels under their own names
            return "for f in " + lib + "/libg_*.so; do cp $f " + stage + "/lib${f##*/libg_} || exit 1; done && cp "
                    + lib + "/libggml-htp-*.so " + stage + "/";
        }
        return "cp " + lib + "/libllama*.so " + lib + "/libmtmd.so " + lib + "/libggml*.so " + stage + "/";
    }

    /** POSIX single-quote shell quoting. */
    static String shq(String s) {
        if (s.matches("[A-Za-z0-9_./=:,+-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
