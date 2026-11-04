# AOHP Driver — design

A replacement for the AOHPAgentDriver demo app, organised around four areas:
Runtime · Harness · Terminal · Web. Client-only: it talks to the AOHP framework
services over Binder and does **not** host the ws://:6666 agent bridge (the
stock AOHPAgentDriver keeps doing that for the agent inside the container).

## Facts this design rests on (verified 2026-10-02 on OnePlus 13 / AOHP GSI)

- Framework services (all `enforceCallingOrSelfPermission(MANAGE_AOHP_VIRTUAL_DISPLAY)`,
  protectionLevel `signature|privileged`):
  `aohp_container`, `aohp_virtual_display`, `aohp_agent_view`, `aohp_event_stream`,
  `aohp_file_bridge`, `aohp_security_bridge`, `aohp_ad`.
  AIDL sources: `/aosp/aohp/AOSP/frameworks/base/core/java/com/android/internal/aohp/*.aidl`
  (copies also in AOHPAgentDriverApp/app/src/main/aidl/).
- Signing the APK with the tree's platform key
  (`/aosp/aohp/AOSP/build/make/target/product/security/platform.{pk8,x509.pem}`)
  grants signature-level permissions **and** hidden-API access
  (`isSignedWithPlatformKey()`), so `Class.forName("android.os.ServiceManager")
  .getMethod("getService")` works and the app can be plain `adb install`-ed —
  no priv-app allowlist, no reflash.
- `IAohpContainer`: listContainers, createContainer(name, template), destroyContainer,
  resetContainer, execSync(name, cmd, timeoutMs) → JSON, openShell(name) → PFD,
  templateInfo, startService(name, id, cmd) → pid, stopService, listServices → JSON,
  serviceLog(name, id, tailBytes), diagnose(name) → JSON.
- openShell's PFD is a **socket relay** to a pty master held by aohp-containerd
  (forkpty). The app cannot TIOCSWINSZ it. v1 workaround: after connect send
  `stty cols C rows R` (and again on resize). Proper fix later: `resizeShell`
  in containerd+framework+AIDL (we already carry system/core + sepolicy forks).
- OpenClaw gateway inside the container listens on 127.0.0.1:18789 with
  `gateway.auth.mode=none`, and the container shares the host network namespace
  (containerd only unshares CLONE_NEWNS) → the Android-side WebView can load
  http://127.0.0.1:18789/ directly (verified: adb forward → HTTP 200).
- Toolchain on chex: `/aosp/android-sdk` (platforms 36, build-tools 36.0.0), Java 21,
  Gradle 8.7 wrapper, AGP 8.5.1 known-good. `~/.gradle` cache is warm (800 MB).
- Container paths on device: `/data/aohp/envs/<name>/{rootfs,services,.template}`,
  templates **`/system/etc/aohp/rootfs-templates/<tpl>.tar.gz`** (containerd
  `TEMPLATE_DIR`; world-readable, so the app lists templates by reading the
  directory — there is no listTemplates AIDL). The only template on the device
  is `debian` (605 MB). Our Debian env is named `oc` (template `debian`,
  recorded in `.template`), harness bootstrap = `aohp-bootstrap <user>/<repo>`
  from https://github.com/gramarye-os/aohp-agents ; secrets via `aohp-secrets`
  (age | keystore | paste).
- The stock app's service id for the gateway is `openclaw-gateway`, command
  `openclaw gateway` (our launcher wrapper handles --jitless + secrets).

### Corrections found while building v1 (2026-10-02)

- Template dir is `/system/etc/aohp/rootfs-templates`, not `/data/aohp/templates`;
  template name is `debian`, not `debian-oc`.
- `diagnose()` JSON: `{container, template, rootfsExists, npmCacheHostDir,
  openclawDevHostDir, cgroup:{cgroupV2Detected, cgroupEnabled, cgroupPath,
  memoryMaxConfigured}}`. `getUsage()` JSON: `{cgroupEnabled, cgroupPath,
  memoryCurrent, memoryMax, memoryPeak, cpuUsageUsec, pidsCurrent}`.
  `listServices()`: array of `{serviceId, pid, alive, startTime, uptimeSec, command}`.
- On the test phone `diagnose` reports cgroup **enabled** but `getUsage` says
  the cgroup dir `/sys/fs/cgroup/aohp-oc` is **missing** → no mem/cpu/pids
  figures. Containerd issue (cgroup create failed or dir vanished), not an
  app bug; the Runtime card shows "usage: cgroup dir missing".
- `getDisplayRuntimeSnapshotJson` returns
  `{timestamp, displays:[{displayId, display:{name,type,logicalWidth,logicalHeight,state,…}, topRunningActivity, focusedActivity, rootTasks}]}`.
- containerd does not reap exited `openShell` children: closed shells stay as
  zombies (`[sh] Z`) under aohp-containerd. Cosmetic, upstream fix wanted.
- xterm.js on Android emits `onData("")` around IME focus changes; treat
  empty input as a no-op (it once killed our writer thread).
- Keeping the terminal WebView alive per env (not just the pty) is required
  for the scrollback to survive tab switches; done via `TerminalHolder`.
- Because the netns is shared, the HTTP probe of :18789 is **device-wide**:
  a second env's `openclaw gateway` fails with EADDRINUSE while `oc`'s is up.
  The Harness pill therefore says UP only when *this* env's service is alive
  and HTTP answers; HTTP-up-but-service-dead shows PORT BUSY.
- The `debian` template already contains an openclaw install + config
  (auth mode none) but **not** `aohp-bootstrap`/`aohp-secrets`; those were
  added to `oc` by hand. Bootstrap in a fresh env therefore exits 127 until
  the template grows them.
- `resetContainer` re-extracts rootfs but keeps `services/` (meta/pid/log),
  so a reset env still lists its old services as stopped.
- `execSync` runs the command through `eval` in `/bin/sh`; errors read
  `/bin/sh: 1: eval: <cmd>: not found`.

## App structure

Kotlin, Jetpack Compose (Material 3), single Activity, bottom navigation with
four destinations. Package `org.aohp.driver`, app name "AOHP Driver",
minSdk 34, compileSdk/targetSdk 36.

```
app/src/main/aidl/com/android/internal/aohp/   copied AIDL (IAohpContainer, IAohpVirtualDisplay, IAohpEventStream)
app/src/main/java/org/aohp/driver/
  binder/ServiceManagerCompat.kt    reflection getService()
  binder/ContainerService.kt        typed Kotlin wrapper over IAohpContainer (suspend funs, JSON parsing)
  binder/VirtualDisplayService.kt   list/snapshot/destroy (status only)
  runtime/   RuntimeScreen + RuntimeViewModel     envs: list/create/reset/destroy/diagnose, cgroup stats, template info
  harness/   HarnessScreen + HarnessViewModel     per env: svc list/start/stop/log, gateway health (HTTP GET :18789), bootstrap action
  terminal/  TerminalScreen + PtySession          WebView + bundled xterm.js, bytes <-> openShell PFD, stty-resize hack
  web/       WebScreen                            WebView → http://127.0.0.1:18789/ (Control UI); JS enabled, DOM storage, loopback only
  ui/        theme, shared composables
```

### Why these choices (for non-Android readers)

- **Activity** = one window/screen host. **Fragment/Composable** = a piece of UI
  inside it. We use Compose (declarative UI in Kotlin) instead of XML layouts —
  less boilerplate, easier for an agent to maintain.
- **ViewModel** = the object that owns a screen's state and survives rotation.
  All Binder calls happen off the main thread inside ViewModels (coroutines on
  Dispatchers.IO); the UI just renders a `StateFlow`.
- **Binder** = Android's IPC. `IAohpContainer.Stub.asInterface(binder)` gives a
  typed proxy; calls are synchronous RPCs into system_server.
- **WebView** = embedded Chromium. Used twice: xterm.js terminal, and the
  OpenClaw Control UI. Both load only loopback/asset URLs.
- **Foreground service**: deliberately **none** in v1. The app is a viewer;
  nothing has to outlive the screen. (The 6-hour FGS kill that bites the stock
  app's bridge is therefore not our problem.)

### Terminal

`assets/term/index.html` loads xterm.js 6.0.0 + fit addon 0.11.0 (vendored
from npm @xterm/xterm, @xterm/addon-fit; served through WebViewAssetLoader at
https://appassets.androidplatform.net/assets/term/). Kotlin side:
`PtySession` owns the ParcelFileDescriptor from `openShell`; a reader thread
pushes bytes to JS via `evaluateJavascript("termWrite(<base64>)")`, a writer
thread drains a queue into the fd, and `TermBridge` (`window.AohpTerm`)
receives keystrokes/resize. Ctrl/Alt are sticky modifiers in the extra key
row applied to the next character. On fit/resize JS reports cols/rows and
Kotlin sends `stty cols C rows R\n` debounced 400 ms (known gap: echoes in the
shell until containerd grows resizeShell). One `PtySession` **and one
WebView** per env (`TerminalHolder`), kept alive across tab switches while
the Activity lives; output that arrives while detached is buffered and
replayed.

### Harness screen

Per selected env, shows: gateway service state (from listServices + HTTP probe
of 127.0.0.1:18789 → up/down + version from /health if present), buttons
Start/Stop/Restart (startService "openclaw-gateway" "openclaw gateway"), Logs
(serviceLog tail, auto-refresh), "Bootstrap…" (execSync of
`aohp-bootstrap <repo>` with the repo entered in a dialog; prefill
`gramarye-os/aohp-config-chris`), "Secrets" (list names only via
`aohp-secrets list` if present). Nothing here stores secret values in the app.

### Runtime screen

Cards per env from listContainers + diagnose(name) (pids, cgroup cpu/mem,
template). Actions: Select (sets the app-wide selected env, persisted in
DataStore), Reset (confirm), Destroy (confirm, typed name), Create (dialog:
name + template from templateInfo/known list). Containerd health = whether
getService("aohp_container") is non-null and listContainers succeeds.

### Web screen

WebView on http://127.0.0.1:18789/ with JS, DOM storage, and
`setMixedContentMode` default; intercepts navigation to non-loopback hosts and
hands them to the system browser (OAuth callbacks come back to loopback and
stay inside). Pull-to-refresh and a "gateway down" placeholder that links to
the Harness screen.

## Verified on device (2026-10-02, OnePlus 13 / AOHP GSI, build 7)

Runtime: list/diagnose/usage, Select (DataStore), Create `scratch` from
`debian` (~2 min extraction), Reset, Destroy (typed confirm), VD list.
Harness: UP/HTTP 200//health live for `oc`; Start on `scratch` → pid, log
tail shows EADDRINUSE; Bootstrap dialog with polled output (exit 127 in
scratch); secrets card ("not installed" in scratch). Not exercised on the
device: Stop/Restart on `oc` (deliberately — it is the live gateway), the
"gateway down" Web placeholder, external-link hand-off.
Terminal: typing, Enter, Ctrl-C via sticky Ctrl, arrows/Esc/Tab keys, survives
Runtime→Web→Terminal tab switches with scrollback. Web: Control UI loads,
title propagates, back navigates the WebView.
Screenshots in `docs/screenshots/`.

## Build / install (headless, from chex)

```
export ANDROID_HOME=/aosp/android-sdk JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
cd /aosp/aohp/aohp-app/aohp-driver && echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -q assembleDebug          # ~1 min warm
./scripts/sign-platform.sh app/build/outputs/apk/debug/app-debug.apk /tmp/aohp-driver.apk
adb -s e6d11a7c install -r -g /tmp/aohp-driver.apk
adb -s e6d11a7c shell am start -n org.aohp.driver/.MainActivity
```
`scripts/sign-platform.sh` wraps apksigner with the tree's platform key
(never copy the key into the repo). Gradle's own signingConfig stays debug.

### Insets

The root Scaffold hides the bottom tab bar while the IME is visible and applies
`consumeWindowInsets(padding).imePadding()` to the content, so the terminal and
the Control UI's chat input sit directly above the keyboard without
double-counting the navigation bar.

## Agent bridge (v2)

Goal: make the stock `AOHPAgentDriver` removable. The only thing the agent
really needs from it is the ws JSON-RPC bridge the `aohp` CLI talks to
(plus the Keystore secrets behind `secret.*`). Everything in
`/aosp/templates/oc-state/workspace/skills/*/SKILL.md` resolves to these
method families: `sandbox.*`, `display.*`, `shot.*`, `ui.*`, `act.*`,
`app.*`, `sys.*`, `event.*`, `secret.*`, `meta.version`.

### What was ported, how

The stock executor was copied, not rewritten: `JsonCommandHandler`,
`MyWebSocketServer` (-> `BridgeWebSocketServer`), `ShellExecutor`,
`AohpVdClient`, `AohpAgentViewClient`, `AohpContainerClient`,
`AohpEventStreamClient`, `AohpSecurityBridgeClient`, `CgroupUsage`,
`SecretStore` moved into `org.aohp.driver.bridge` (Java, mixed with the
Kotlin app), plus the `IAohpAgentView` / `IAohpSecurityBridge` AIDLs.
`scripts`-free: the port is `/tmp/port_bridge.py`-style text surgery on the
stock sources; the wire format is byte-identical (`meta.version` adds
`"bridge":"aohp-driver"`).

| family | status | note |
|---|---|---|
| meta.version, sandbox.*, display.*, shot.full/region/node, ui.tree/find, act.* (+ *_node), app.*, event.*, secret.* | ported | unchanged code paths (framework Binder services + shell) |
| sys.screen_info/device_info/battery/network/notifications/wake/sleep/unlock | ported | wake/sleep/unlock = `input keyevent` as the app uid (`INJECT_EVENTS`), not yet exercised |
| sys.clipboard, ui.focused, ui.input_text | **not ported** | implemented in the stock `MyAccessibilityService`; answer `{"error":{"code":"no_a11y"}}` exactly like stock does when its service is off |
| file.*, uda.*, overlay.*, sms.send, sensor.camera.capture, ads | **dropped** | not used by the skills/CLI paths we support; `unknown_method` |

### Three things the stock app got for free that the port had to solve

1. **`ui.tree` returned 0 windows.** `AccessibilityManagerService.dumpUiTreeForDisplayInternal`
   reads the a11y window list and app view-hierarchy connections, which the
   framework only maintains while an accessibility service is enabled. The
   stock app enabled its own `MyAccessibilityService` via
   `WRITE_SECURE_SETTINGS`. The Driver ships `BridgeAccessibilityService`
   (handles nothing) and `A11yKeepalive.ensureEnabled()` adds it to
   `enabled_accessibility_services` when the bridge starts; Stop removes it.
   Verified: 0 windows/0 nodes before, 3 windows/96 nodes after.
2. **Java-WebSocket 1.3.6 crashed the app** (`AssertionError` in
   `WebSocketImpl.decode`; debug builds keep `assert`). Upgraded to 1.5.7
   (`getConnections()`, `setReuseAddr`, `onStart`).
3. **Handlers ran on the ws worker threads.** Java-WebSocket pins
   connections to N workers; a blocking `sandbox.exec` starved every other
   connection on its worker — including the nested `aohp` calls made by the
   command being executed, which then hung until the exec timeout. The
   Driver dispatches each message to a cached thread pool
   (`BridgeWebSocketServer.dispatchPool`). The stock app has the same latent
   bug.

### Service shape

`BridgeService`: `foregroundServiceType="specialUse"` with
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (platform-signed, so allowed) — the stock
app used `dataSync`, whose 6-hour cap killed its bridge. `START_STICKY`,
partial wake lock while listening (parity with stock), notification channel
`aohp_bridge`. Bind: `InetSocketAddress("127.0.0.1", 6666)` (shows as
`[::ffff:127.0.0.1]:6666` in `ss` — dual-stack socket, still loopback-only).
State is a `StateFlow<BridgeState>` (running, port, clients, error, a11y
flags, last autostart log) consumed by the Runtime card.
`DriverApp.onCreate` starts it unless the user pressed Stop
(`bridge_enabled` in DataStore).

### Boot sequence

`RECEIVE_BOOT_COMPLETED` -> `BootReceiver` -> `BridgeService.start(autostart=true)`:
ws server first (the gateway launcher needs `secret.get`), then poll
`listContainers()` every 2 s for up to 3 min, then for each env in
not in `autostart_off_envs` (DataStore string set; the Runtime card switch is an opt-out, default on since 0.5.0 - `autostart_envs` is only kept in step for older readers) replay the
env's **ServiceRegistry** (0.3.0): every `{serviceId, command}` that was
started through HarnessViewModel / SetupViewModel / the bridge's
`sandbox.svc_start` and not stopped since (`sandbox.svc_stop`, Harness
Stop, sandbox destroy remove entries). SharedPreferences file `services`,
key `env:<name>`, JSON array in first-start order; the Java bridge needs a
synchronous store, hence not DataStore. Start order: non-gateway services
first (500 ms apart), `openclaw-gateway` last; anything `listServices`
reports alive is skipped. Empty registry -> just `openclaw-gateway` with
`openclaw gateway` (pre-0.3.0 behaviour). containerd keeps no service
definitions across reboots, so this registry is the only record.

Reboot test 2026-10-02 (OnePlus 13, stock app disabled, no app opened):
`sys.boot_completed` -> +0 s bridge listening + a11y keepalive enabled ->
+0.02 s `autostart: oc: started openclaw-gateway pid 5506` -> +11 s
`127.0.0.1:18789` owned by `openclaw-gateway` in `oc`, `HTTP 200`,
`/health {"ok":true,"status":"live"}`, `ANTHROPIC_API_KEY` present in the
gateway's environment (fetched through the bridge).

### Units (0.4.0)

See [UNITS.md](UNITS.md) for the full design. Summary of what changed in the app:

- `IAohpContainer.unitControl(env, op, jsonArgs)` — one generic Binder method (appended last in
  the AIDL, so the compiled-in copy stays wire-compatible with 0.3.0 images; on those the call
  throws and `ContainerService.unitsSupported` is pinned to `false`).
- `ContainerService.listUnits/unitOp/unitLog/unitEnvOp`, `UnitInfo` (parsed UnitJson).
- Harness: `UnitsCard` replaces `ServicesCard` when the daemon reports units; gateway
  start/stop/restart go through the unit when `openclaw-gateway.service` exists (containerd also
  maps the legacy `startService("openclaw-gateway")` onto that unit file, so nothing else had to
  change).
- Boot: `autostartGateways()` first tries `env-start` per env (units in dependency order, supervised),
  and only falls back to the ServiceRegistry replay when the env has no unit files or the image has
  no `unitControl`.
- Bridge: `sandbox.unit` + `sandbox.unit_<op>` aliases forward every param except `name`/`op` as the
  op's JSON args; daemon `{"error":true,"message"}` becomes a JSON-RPC error.

### Secret migration

`LegacySecretImport` is a Java-WebSocket *client*: connects to
`ws://127.0.0.1:6666` while the stock app still owns it, refuses to import
from itself (`meta.version.app`), `secret.list` -> `secret.get` ->
`SecretStore.set` per name. Values never hit a log or the screen; the
Runtime card only lists names.

## First-run wizard (v2)

`setup/SetupWizard.kt` (one Compose screen per step, Back/Next) over
`SetupViewModel`. Entry points: Harness "Set up OpenClaw" card (no env, or
selected env has no `openclaw-gateway` service) and the ⊕ action in the
Harness top bar.

- **Env**: `createContainer(name, template)` (sub-minute on this device
  because containerd caches the extracted template) or reuse.
- **Credentials / Paste**: `SecretStore.set(<PROVIDER>_API_KEY)`; then a
  static launcher is installed as `/usr/local/bin/openclaw` in the env. It
  resolves keys via `aohp secret get` or — because the template's `aohp`
  0.1.0 predates `secret` — a 6-line Node client using openclaw's bundled
  `ws` (Node's undici `WebSocket` fails Java-WebSocket's handshake with a
  `TypeError` in `processResponse`). The script is sent base64 on one line
  because **containerd `execSync` only executes the first line** of the
  command string. For OpenAI the primary model in `openclaw.json` is
  switched with a `node -e` JSON edit.
- **Credentials / Git**: `aohp-bootstrap <user>/<repo>` (fetched from
  `gramarye-os/aohp-agents` if the env lacks it). Passphrase and GitHub token are
  parked in the Keystore as `AOHP_SETUP_*`, pulled by the env with
  `aohp secret get` into `umask 077` files, passed as `--passphrase-file` /
  `--token-file`, and both the files and the temp secrets are deleted after.
  Output is tee'd to `/tmp/aohp-setup-bootstrap.log` and polled every 2 s.
- **Start**: `setAutostart`, `startService`, poll `http://127.0.0.1:18789/`
  up to 4 min; a first start runs `npm install` for plugin deps
  (`~/.openclaw/npm`) and took ~90 s on the OnePlus 13.

Verified 2026-10-02 on env `fresh` with a dummy OpenAI key: Control UI
loaded ("No models available", as expected without a valid key); the key
name appeared in the gateway's environment; no key material in the rootfs.

## Out of scope for v1

UDA, ads, overlay, MediaProjection recording, File Bridge UI, the Contacts
"CLI contact test". Virtual displays appear only as a
read-only list on the Runtime screen.

## Roadmap after v1

1. `resizeShell` across containerd/framework/AIDL (+ PR upstream).
2. (done in v2 as an app-hosted bridge) — longer term, move it into a
   system service with a Unix socket bind-mounted into the rootfs.
4. containerd: `stopService` must kill the process group (today the `node`
   child outlives the `sh -c` wrapper and keeps :18789); `execSync` should
   accept multi-line commands; ship a newer `aohp` CLI (with `secret`) in
   the template.
3. Harness plugins: Claude Code / Codex / OpenCode status alongside OpenClaw.

