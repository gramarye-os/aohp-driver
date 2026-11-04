# AOHP Driver

A small Android app for driving an AOHP (Android Open Harness Project) device:
the Linux container runtime, the OpenClaw harness inside it, an interactive
terminal, and the OpenClaw Control UI — four bottom tabs.

It is a **client** of the AOHP framework services (`aohp_container`,
`aohp_virtual_display`, `aohp_event_stream`, `aohp_security_bridge`) over
Binder, and since v2 it also **hosts the agent bridge**: the JSON-RPC-over-
WebSocket server on `127.0.0.1:6666` that the `aohp` CLI inside a container
talks to. With v2 installed the stock `org.aohp.agentdriver` app can be
disabled (see [Agent bridge](#agent-bridge-v2)).

| Tab | What it does |
|---|---|
| **Runtime** | containerd health, **Agent bridge card** (state, bind, clients, a11y keepalive, secret names, Start/Stop, Import legacy secrets), one card per env (template, rootfs, services, cgroup, usage, **Autostart gateway on boot** switch), Select / Reset / Destroy (typed confirm) / Create (template picker), read-only virtual display list |
| **Harness** | OpenClaw gateway state (listServices + HTTP probe of 127.0.0.1:18789 and /health), Start / Stop / Restart, service list, log tail with auto-refresh, Bootstrap dialog, secret *names*; **“Set up OpenClaw” card / ⊕ button** opens the first-run wizard |
| **Terminal** | `openShell` → xterm.js in a WebView; extra key row (Esc Tab Ctrl Alt arrows Home End PgUp PgDn …); one shell per env, survives tab switches |
| **Web** | WebView on http://127.0.0.1:18789/ (Control UI); JS + DOM storage; non-loopback links open in the system browser; "gateway down" placeholder |

See [docs/DESIGN.md](docs/DESIGN.md) for the design and the device facts it rests on.

## Agent bridge (v2)

The bridge is a foreground service (`bridge/BridgeService.kt`,
`foregroundServiceType="specialUse"` — *not* `dataSync`, whose 6-hour limit
killed the stock app's bridge) that runs the stock app's
`JsonCommandHandler` (ported, same wire format) behind a Java-WebSocket
server bound to **127.0.0.1:6666 only** (the stock app bound 0.0.0.0).
It starts with the app process, on `BOOT_COMPLETED`, and from the Runtime
card. RPCs run on their own thread pool so a long `sandbox.exec` cannot
block other connections (nested `aohp` calls from inside an exec used to
deadlock on the stock design).

**Ported** (`meta.version`, `sandbox.*`, `display.*`, `shot.*`, `ui.*`,
`act.*` incl. `*_node`, `app.*`, `sys.*`, `event.*`, `secret.*`) /
**not ported** (`file.*`, `uda.*`, `overlay.*`, `sms.send`,
`sensor.camera.capture`, ads; `sys.clipboard`, `ui.focused`,
`ui.input_text` answer `no_a11y` because they need the stock app's
AccessibilityService logic) — details and reasons in
[docs/DESIGN.md](docs/DESIGN.md#agent-bridge-v2).

**UI tree needs an enabled accessibility service.** `ui.tree` and the
node actions are served by the framework's AccessibilityManagerService
hooks, which only track windows while *some* accessibility service is
enabled. The Driver ships a no-op one (`BridgeAccessibilityService`) and
enables it itself through `WRITE_SECURE_SETTINGS` when the bridge starts
(the stock app did the same with `MyAccessibilityService`).

**Secrets** live in the Android Keystore (`SecretStore`, same format as the
stock fork). `Import legacy secrets` on the Runtime card connects to the
stock app's bridge as a client and copies every `secret.list` name over
without showing a value.

### Cutover from the stock app

```
# 1. install v2, open Runtime -> Agent bridge -> Import legacy secrets (while the stock app still owns :6666)
# 2. disable the stock app, confirm :6666 is free, start the Driver bridge
adb shell pm disable-user --user 0 org.aohp.agentdriver
adb shell ss -ltnp | grep 6666          # nothing
# Runtime -> Agent bridge -> Start   (or just relaunch the app)
adb shell ss -ltnp | grep 6666          # [::ffff:127.0.0.1]:6666 users:(("org.aohp.driver",...))
# 3. inside the env: aohp connect / aohp secret list / aohp shot full -d 0 / aohp ui tree -d 0
```

To go back: Runtime -> Agent bridge -> **Stop** (frees :6666 and removes the
a11y keepalive from secure settings), then
`adb shell pm enable org.aohp.agentdriver` and launch it.

### Boot sequence

`BOOT_COMPLETED` -> `BootReceiver` -> `BridgeService.start(autostart=true)`
-> ws server up -> poll `aohp_container.listContainers` (up to 3 min) ->
for every env whose **Autostart** switch is on (**on by default since 0.5.0**; the switch is an opt-out stored as `autostart_off_envs`): start again **every service
recorded for that env** (since 0.3.0; `ServiceRegistry`, SharedPreferences
`services`, one `{serviceId, command}` list per env, written whenever a
service is started/stopped through the Harness tab, the setup wizard or
`aohp sandbox svc-start/svc-stop` over the bridge; cleared on sandbox
destroy). Order = first-started first, with `openclaw-gateway` moved last so
network helpers (e.g. a `net-watchdog` that brings up wg0 + sshd) are up
before the gateway; a service already alive is skipped. An env with nothing
recorded falls back to the old behaviour, `startService(env,
"openclaw-gateway", "openclaw gateway")`. The result is logged (`logcat -s
AohpDriver`, `autostart:`) and shown on the bridge card. The gateway's
launcher reads the provider key through the bridge, so the bridge comes up
first.

## Units (0.4.0) — supervised services and timers per env

With a ROM whose aohp-containerd has the `UNIT` ops (LineageOS oriole build-5 / dodge build-3,
2026-10-05) the Harness tab shows a **Units** card instead of the plain Services list: every
`/etc/aohp/system/*.service|*.timer` of the env (systemd subset — `Restart=`, `After=`/`Requires=`,
`ExecStartPre`, `EnvironmentFile=`, timers with `OnBootSec/OnUnitActiveSec/OnCalendar`) with state
badge, sub-state, pid/uptime or next elapse, restart count, and start/stop/restart/enable/disable/
log buttons, plus *Reload* (daemon-reload), *Start env*, *Stop env*. **Autostart at boot = env-start**:
containerd starts the env's enabled units in dependency order; envs without unit files fall back to
the 0.3.0 ServiceRegistry replay. The Gateway card's buttons drive `openclaw-gateway.service` when the
template ships it (the aohp-agents templates do, enabled by default).

Bridge: `sandbox.unit {name, op, unit?, now?, tailBytes?}` (+ `sandbox.unit_<op>` aliases) → the
`aohp unit <env> …` / `aohp timer <env> list` CLI and the container-side `systemctl`/`journalctl`
shims. Spec: [docs/UNITS.md](docs/UNITS.md). On older images `IAohpContainer.unitControl` is missing;
the app detects that once and keeps the 0.3.0 behaviour.

## First-run wizard (Harness -> “Set up OpenClaw…”)

1. **Environment** — name (default `oc`), template picker; existing name =
   "Use existing".
2. **Credentials** — *Paste an API key* (provider Anthropic/OpenAI;
   stored with `secret.set` semantics in the Keystore, never in the rootfs;
   installs a launcher `/usr/local/bin/openclaw` in the env that exports
   the key from the bridge at start and strips `--jitless`), *Import from a
   git config repo* (`aohp-bootstrap <user>/<repo>`; age passphrase /
   GitHub token are parked in the Keystore and pulled by the env with
   `aohp secret get` into 0600 temp files — never on a command line; log
   streamed), or *Skip*.
3. **Start the gateway** — autostart switch, `startService`, waits up to
   4 min for HTTP on 18789 (first start runs `npm install` for plugin deps).
4. **Ready** — “Open Control UI” switches to the Web tab.

## Requirements

- Device running the AOHP GSI (framework services present) with the AOSP
  tree's **platform key** — the APK must be signed with it to get
  `MANAGE_AOHP_VIRTUAL_DISPLAY` (signature-level) and hidden-API access.
  No priv-app allowlist and no reflash needed: plain `adb install`.
- Build host: Android SDK (platforms 36, build-tools 36.0.0), JDK 21, network
  for Maven on the first build. Gradle 8.7 wrapper, AGP 8.5.1, Kotlin 2.0.21,
  Jetpack Compose (BOM 2024.10.01).

## Build / sign / install

```bash
export ANDROID_HOME=/aosp/android-sdk
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
cd /aosp/aohp/aohp-app/aohp-driver
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew --no-daemon assembleDebug                      # ~1 min warm, several min cold
./scripts/sign-platform.sh app/build/outputs/apk/debug/app-debug.apk /tmp/aohp-driver.apk
adb -s e6d11a7c install -r -g /tmp/aohp-driver.apk
adb -s e6d11a7c shell am start -n org.aohp.driver/.MainActivity
```

Or all of the above in one go: `scripts/install.sh [adb-serial]`.

`scripts/sign-platform.sh` reads the key from
`/aosp/aohp/AOSP/build/make/target/product/security/platform.{pk8,x509.pem}`
(override with `AOHP_PLATFORM_KEYDIR`). The key is never copied into this repo;
Gradle's own signingConfig stays the default debug key.

Debugging: `adb -s e6d11a7c logcat -s AndroidRuntime:E AohpDriver:* chromium:I`
(`AohpDriver` is the single log tag; the Binder probe logs
`getService(aohp_container)` and `listContainers` at startup).

## Layout

```
app/src/main/aidl/com/android/internal/aohp/   IAohpContainer / IAohpVirtualDisplay / IAohpEventStream (copied from frameworks/base)
app/src/main/assets/term/                      xterm.js 6.0.0 + addon-fit 0.11.0 (vendored from npm) + index.html
app/src/main/java/org/aohp/driver/
  binder/     ServiceManagerCompat (reflection getService), ContainerService, VirtualDisplayService
  bridge/     BridgeService (specialUse FGS), BridgeWebSocketServer, JsonCommandHandler + ShellExecutor + Aohp*Client (ported from the stock app), SecretStore, LegacySecretImport, BootReceiver, A11yKeepalive
  setup/      SetupWizard + SetupViewModel (first-run flow)
  runtime/    RuntimeScreen + RuntimeViewModel
  harness/    HarnessScreen + HarnessViewModel
  terminal/   PtySession/PtySessionRegistry (fd I/O), TerminalScreen (TermBridge, TerminalView, TerminalHolder)
  web/        WebScreen (+ WebHolder: single app-wide WebView)
  ui/         theme, shared composables
scripts/      sign-platform.sh, install.sh
docs/         DESIGN.md
```

## Screenshots (verified on OnePlus 13, Android 16 AOHP GSI)

Captured with `adb -s e6d11a7c exec-out screencap -p > file.png`, stored in `docs/screenshots/`:

- `runtime.png` — containerd card, `oc` env card (selected), virtual displays
- `harness.png` — gateway UP / HTTP 200 / `/health live`, services, log tail
- `terminal.png` — shell in `oc`, extra key row, Ctrl-C and tab-switch survival
- `web.png` — OpenClaw Control UI loaded from 127.0.0.1:18789
- `create-dialog.png`, `create-progress.png`, `destroy-dialog.png` — throwaway env lifecycle
- `harness-scratch-eaddrinuse.png` — gateway started in a 2nd env; log tail shows EADDRINUSE (shared netns)
- `bootstrap-result.png` — Bootstrap dialog after a run (exit 127: tool absent in the fresh template)

v2 (bridge + wizard):

- `v2-bridge-bindfail.png` — bridge card while the stock app still owns :6666 (bind error, Import button)
- `v2-import.png` — after *Import legacy secrets* (1 name copied, value never shown)
- `v2-bridge-up.png` — bridge LISTENING on 127.0.0.1:6666 after the stock app was disabled
- `v2-harness-setup.png` — Harness with the ⊕ setup action
- `v2-wizard-env.png`, `v2-wizard-creds.png`, `v2-wizard-start.png`, `v2-wizard-done.png` — the four wizard steps on a throwaway env `fresh`
- `v2-web-fresh.png` — Control UI served by the gateway the wizard started in `fresh`

## Shipping the Driver in the AOHP GSI (image integration)

Since 2026-10-02 the `aosp_arm64_aohp` GSI ships **AOHP Driver + the OpenClaw Android
app** and no longer the stock `AOHPAgentDriver` demo app. The AOSP-side pieces are small
and the two prebuilt APKs are generated, not committed:

| where | what |
|---|---|
| `AOSP/packages/apps/AOHPDriver/Android.bp` (copy in [aosp/AOHPDriver/](aosp/AOHPDriver/Android.bp)) | `android_app_import` of `AOHPDriver.apk`, `certificate: "platform"`, **`privileged: false`** → `system/app/AOHPDriver`. Soong re-signs the Gradle output with the tree's platform key, so no key is needed at Gradle time. |
| `AOSP/packages/apps/OpenClawAndroid/Android.bp` (copy in [aosp/OpenClawAndroid/](aosp/OpenClawAndroid/Android.bp)) | `android_app_import` of `OpenClawAndroid.apk`, `preprocessed: true` (presigned; Soong validates alignment/uncompressed .so and copies, never re-signs), `privileged: false` → `system/app/OpenClawAndroid`. |
| `AOSP/build/make/target/product/aosp_arm64_aohp.mk` | `PRODUCT_PACKAGES`: `AOHPDriver OpenClawAndroid aohp-containerd aohp-rootfs-debian aohp_cgroup_conf` (dropped `AOHPAgentDriver`, `privapp-permissions-aohp`); `PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST` has `system/app/AOHPDriver/{AOHPDriver.apk,oat/%}` and `system/app/OpenClawAndroid/{OpenClawAndroid.apk,oat/%,lib/%}`. |
| `AOSP/build/make/target/product/gsi/Android.bp` | `aosp_arm64_aohp_system_image` deps list the two apps (Soong-defined image: every installed file must come from a listed module). |
| `AOSP/frameworks/base/.../AohpUiTreeDumper.java` | the only package-specific framework reference: the ui-tree overlay filter now covers `org.aohp.driver` as well as `org.aohp.agentdriver`. |

**Why system/app and not priv-app.** Everything the Driver needs is signature-level
(`MANAGE_AOHP_VIRTUAL_DISPLAY`, `INJECT_EVENTS`, `WRITE_SECURE_SETTINGS`,
`FORCE_STOP_PACKAGES`, `REAL_GET_TASKS`) and is granted by the platform signature,
which also unlocks hidden-API access. A priv-app would additionally need a
`privapp-permissions-*.xml` allowlist for every privileged permission (a missing entry
aborts system_server at `systemReady` on userdebug) — nothing to gain. In sepolicy the
platform-signed app lands in `platform_app`, which `aohp_agent_app.te` already allows to
find the `aohp_*` services.

**Why the OpenClaw app is not platform-signed.** It is a normal third-party app; signing it
with the platform key would give it platform uid-level trust it does not need and would
block later updates from the upstream release key. It is signed with a dedicated keystore
kept outside every repo (`/aosp/keys/aohp-apps.jks`, alias `aohp-apps`, password in
`/aosp/keys/aohp-apps.pass` mode 600) so a newer build signed with the same key can
update it in place.

**First-boot stopped state.** AOSP/GSI builds scan every system package they meet for
the first time as *stopped* (`config_stopSystemPackagesByDefault`), and a stopped package
gets no `BOOT_COMPLETED` — so the bridge and the gateway autostart would only work after
the user had opened the app once. `aosp/AOHPDriver/initial-package-stopped-states-aohp.xml`
(`prebuilt_etc`, `/system/etc/sysconfig/`) exempts `org.aohp.driver`; the OpenClaw app keeps
the default. Verified on the Pixel 6 by creating a fresh secondary user: Driver
`stopped=false`, OpenClaw app `stopped=true`.

**Verified first-boot flow (Pixel 6, 2026-10-02, image built from this tree):** launcher
shows *AOHP Driver* + *OpenClaw*, no *AOHP Agent Driver*; Driver opens with the bridge
already LISTENING on 127.0.0.1:6666 and the a11y keepalive connected, no permission
prompts; wizard: create `oc` from `debian` (~1 min) → *Skip for now* → *Start gateway*
(autostart on by default; HTTP 200 after ~50 s, first start runs `npm install`) → *Open
Control UI*. OpenClaw app: *Set up manually* → host `127.0.0.1`, port `18789`,
*Unencrypted*, no token → *Test connection* → "Pairing Gateway" shows
`openclaw devices approve <id>` — run it in the Driver's **Terminal** tab (~20 s) → *Retry
connection* → "Gateway paired" → *Continue* → "Approve node access" shows
`openclaw nodes approve <requestId>` — Terminal tab again → *I have approved* → optional
permissions → connected (green dot). Two approvals total, both done on the phone without
adb. After `adb reboot` with no app opened: bridge up at boot, `autostart: oc: started
openclaw-gateway`, `:18789` serving `/health` ≈ 60 s after boot, and the OpenClaw app
shows *Online* / Nodes 1 online when opened.

Refreshing the prebuilts:

```bash
scripts/update-aosp-prebuilt.sh                    # Gradle assembleRelease (unsigned) -> AOSP/packages/apps/AOHPDriver/AOHPDriver.apk
scripts/update-aosp-openclaw-app.sh <signed.apk>   # validates + copies the release-signed OpenClaw APK (build recipe in the script header)
cd /aosp/aohp/AOSP && source build/envsetup.sh && lunch aosp_arm64_aohp-trunk_staging-userdebug && m -j10 systemimage
```

Releases: pushing a tag `v<versionName>` (e.g. `v0.2.0`, matching `versionName` in
`app/build.gradle.kts`) runs `.github/workflows/release.yml`, which builds the same unsigned
`assembleRelease` APK on GitHub Actions and attaches `AOHPDriver.apk` + `AOHPDriver.apk.sha256`
to https://github.com/gramarye-os/aohp-driver/releases. The LineageOS + AOHP tree fetches that asset
with `vendor/aohp/fetch-prebuilts.sh` (pinned tag + sha256) instead of copying it from a
developer machine; `ci.yml` just assembles on every push/PR.

The Debian container template that goes into the same image is built by
`/aosp/templates/build-debian-template.sh arm64` with `OPENCLAW=1` and now also bakes in
the newest `aohp` CLI (with `secret`), `gh`/`age`, and the aohp-agents layer
(`/opt/aohp-agents`, `aohp-bootstrap`/`aohp-secrets`/`aohp-update` on PATH), so the
wizard's *Import from a git config repo* works in a fresh env.

## Known gaps

v2:

- **containerd `stopService` leaves the gateway running.** It kills the
  `sh -c` wrapper only; the `node` child survives (reparented to init) and
  keeps :18789, so Stop/Restart from the Harness cannot free the port until
  containerd kills the process group. Seen twice on 2026-10-02.
- **containerd `execSync` runs only the first line** of the command string.
  Multi-line scripts must be sent base64-encoded on one line (the wizard
  does this for the launcher).
- The template's `aohp` CLI (0.1.0) has no `secret` subcommand; the
  wizard's launcher therefore carries a Node fallback client (openclaw's
  bundled `ws`). Node's built-in undici `WebSocket` rejects
  Java-WebSocket's handshake.
- `sys.wake/sleep/unlock` run `input keyevent` as the app uid
  (`INJECT_EVENTS` declared, platform-signed) — not exercised yet.

v1:

- **Resize**: the shell PFD is a socket relay, so the app cannot TIOCSWINSZ
  the pty. It sends `stty cols C rows R` after connect and on (debounced)
  size changes, which echoes in the terminal. Needs `resizeShell` in
  aohp-containerd + framework + AIDL.
- No `listTemplates` AIDL; templates are read directly from the world-readable
  `/system/etc/aohp/rootfs-templates/*.tar.gz`.
- `createContainer` is a single long Binder call (no progress); the UI just
  shows a spinner until it returns.
- The terminal's "Enter" is the IME's enter key; there is no Enter in the extra
  key row.
