# Local LLM runtime (llm.*)

Since 0.8.0 the Driver ships **llama-server** and its ggml backends as native libraries and manages it as a
unit of an environment. The split is deliberate: the *runtime* is device-specific and small (23 MB, built
per Hexagon generation), so it belongs with the OS / the Driver and is updated with it; the *models* are
large, change often and carry their own licences, so they stay in `/data`.

## What is in the APK

`app/src/main/jniLibs/arm64-v8a/` (packaged with `extractNativeLibs` + legacy packaging so they are real files):

| file | what |
|---|---|
| `libllama-server.so` | the `llama-server` executable (renamed so the APK packer takes it) |
| `libllama-server-impl.so`, `libllama.so`, `libllama-common.so`, `libmtmd.so` | llama.cpp |
| `libggml.so`, `libggml-base.so`, `libggml-cpu.so` | ggml core + CPU backend |
| `libggml-hexagon.so` | Hexagon NPU backend (CPU side, talks FastRPC) |
| `libggml-htp-v73/v75/v79/v81/v85.so` | NPU-side skels, one per Hexagon generation |

Built from llama.cpp with `scripts/snapdragon/build.py --preset arm64-android-aohp-release` (the Snapdragon
preset with `GGML_OPENCL=OFF`) and stripped with the AOSP `llvm-strip`. Two local patches matter:

* `ggml/src/ggml-hexagon/libdl.h`: when `dlopen("libcdsprpc.so")` fails, retry through
  `android_load_sphal_library()` (libvndksupport). A process whose executable lives under `/data` (except
  `/data/local/tmp`) or `/system/bin` runs in the linker's isolated *system* section and cannot see
  `/vendor/lib64`; the sphal namespace is the sanctioned way in.
* OpenCL is off: `libggml-opencl.so` links `libOpenCL.so` directly, which the same rule forbids. GPU support
  would need a dlopen shim; on the devices measured so far NPU > CPU > GPU anyway.

## How it runs

The Driver does **not** run the server itself. `llm.configure` writes
`/etc/aohp/system/llama-server.service` into the env with `HostExec=yes`: aohp-containerd spawns it as an
Android host process (bionic, root, in the env's cgroup) and supervises it like any unit — `Restart=`,
stop/restart, env start/stop all apply, and `aohp unit status/log oc llama-server` work. OpenClaw in the env
sees an OpenAI-compatible endpoint on `127.0.0.1:8082`.

Because the daemon's SELinux domain may neither execute the APK's extracted libraries nor
`/system/bin/linker64`, the unit's `ExecStartPre` copies the runtime once per Driver version to
`/data/aohp/shared/llm/<versionCode>/` (its own data label, 0755) and executes from there.

Models live in `/opt/llm/models` inside the env rootfs (`/data/aohp/envs/ENV/rootfs/opt/llm/models` on the
host), visible to both sides.

## RPC methods

| method | params | notes |
|---|---|---|
| `llm.info` | `env?` | lib dir, SoC, Hexagon arch, accelerators (`htp` when a skel for this SoC exists and `/vendor/lib64/libcdsprpc.so` is present), models + config + unit status when `env` is given |
| `llm.models` | `env` | GGUF files and sizes |
| `llm.configure` | `env, model, alias?, device? (auto|htp|cpu), ctx?, port?, threads?, parallel?, thinking?, extra?[], autostart?, start?` | regenerates the unit + `/etc/aohp/llm.json`, enables/disables it, restarts it |
| `llm.start` / `llm.stop` / `llm.restart` | `env` | unit control |
| `llm.status` | `env` | unit state, config, `GET /health` |
| `llm.log` | `env, tailBytes?` | unit log tail |

CLI: `aohp llm info [env]`, `aohp llm models oc`, `aohp llm use oc Qwen3.5-4B-Q4_K_M.gguf --alias qwen3.5-4b
[--device htp|cpu] [--ctx N] [--threads N] [--thinking]`, `aohp llm start|stop|restart|status|log oc`.

## Hexagon NPU and SELinux

`device=htp` needs the daemon domain to reach FastRPC: as a root process libcdsprpc opens
`/dev/fastrpc-cdsp-secure` and the DSP manager HAL. LineageOS images from build-23 carry the grants
(`vendor/aohp/sepolicy/vendor/aohp_container_daemon.te`, included by the qcom device BoardConfigs); on older
images `htp` fails at session open and `cpu` is the fallback. The Driver also exports
`GGML_HEXAGON_ARCH` from the SoC model because ggml falls back to v73 when its capability query is denied.
