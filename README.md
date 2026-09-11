# MC Reflex Tools

**English** | [简体中文](README.zh-CN.md)

NVIDIA Reflex Low Latency in Minecraft Java Edition 26.2, driven through Minecraft's native
Vulkan renderer and the official NVIDIA Streamline SDK.

> **Not affiliated with NVIDIA, Mojang, Microsoft, or FabricMC.** This repository contains no
> NVIDIA SDK code and no Minecraft code. You supply the Streamline SDK yourself — see
> [NOTICE.md](NOTICE.md).

## Status

Early, and honest about it. One feature works and is verified against NVIDIA's own tooling;
the rest is blocked.

| Feature | State |
| --- | --- |
| Reflex Low Latency | **Working.** Verified end-to-end against the NVIDIA Reflex Test Utility |
| Reflex frame limiting | Working — `customFrameLimitFps` is applied through Reflex |
| DLSS Super Resolution | **Not functional.** No NGX context, and no resource tagging |
| DLSS-G Frame Generation | **Not functional.** Same blocker; keep it `OFF` |

`PC Latency` in the Reflex Test Utility moves from `0.000` (no integration) to
`21.5–23.1 ms` at 280 FPS, with a non-zero input-to-simulation component. The full record —
evidence, how far each measurement can be trusted, and the conclusions that were tried and
disproven — is in [docs/reflex-verification.md](docs/reflex-verification.md). Read that before
re-investigating any Reflex behaviour.

## Requirements

- Windows x64
- An NVIDIA GPU and driver that support Reflex
- Minecraft Java Edition **26.2**, running its **Vulkan** renderer
- The **NVIDIA Streamline SDK v2.14.1** — not included, obtain it from NVIDIA
- To build: JDK 25. To rebuild the native bridge: CMake and an MSVC C++20 toolchain

Minecraft 26.2 defaults to OpenGL. Set this in `options.txt` in your game directory, or the
mod reports `UNSUPPORTED_RENDERER` and every feature stays unavailable:

```
preferredGraphicsBackend:"vulkan"
```

## Getting the Streamline SDK

Download the Streamline SDK from NVIDIA, extract it, and point `NVIDIA_STREAMLINE_ROOT` at the
extracted root:

```bash
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
```

The build reads it for headers and `sl.interposer.lib`; at runtime the Streamline plugin DLLs
must be reachable under `<root>/bin/x64`. Nothing machine-specific is baked into the jar — if
the variable is unset at runtime, the Mod reports itself unavailable and the game runs
unchanged.

## Build

```bash
./gradlew :fabric:build
```

The jar lands in `fabric/build/libs/`. It embeds a prebuilt native bridge, so this works
without a C++ toolchain. To rebuild that bridge from source — required after editing
`nvidia-sdk-native/src/jni_exports.cpp` — see [docs/native-build.md](docs/native-build.md).

## Automatic builds and releases

Every push to `main` is built on GitHub Actions, and the jar is uploaded as a run artifact you
can download from that run's summary page.

Pushing a tag such as `v0.1.0-SNAPSHOT` — it must equal `mod_version` in `gradle.properties`
— runs [release.yml](.github/workflows/release.yml), which builds and publishes a
**prerelease** with the jar attached. Every build is a snapshot for now.

## Run the development client

```bash
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
./gradlew :fabric:runClient
```

Configuration lives in `config/mc_reflex_tools.json`, generated on first run:

```json
{
  "reflexMode": "ON_PLUS_BOOST",
  "customFrameLimitFps": 0,
  "dlssMode": "MAX_QUALITY",
  "dlssGMode": "OFF"
}
```

`customFrameLimitFps = 0` disables the limit. **Keep `dlssGMode` at `OFF`** until the NGX
context exists — enabling it makes the Reflex Test Utility's Frame Gen cycles fail.

## How it works

Minecraft 26.2 renders through the `GpuDevice` / `GpuSurface` abstraction over
`VulkanDevice` / `VulkanGpuSurface`. The Mod borrows Minecraft's own `VkInstance`, `VkDevice`,
graphics queue and swapchain through Mixin accessors and hands them to Streamline. It never
creates a second device, queue, swapchain, or Present loop.

Reflex sleep runs at `RenderSystem.pollEvents()` HEAD — after the previous frame's present and
before input sampling — and the six PCL markers are emitted at the simulation, queue-submit, and
present boundaries. The driver's out-of-band latency ping is answered by subclassing the game
window's procedure, because Minecraft leaves it to GLFW.

The design decisions are recorded in [docs/architecture.md](docs/architecture.md), and
[docs/reflex-verification.md](docs/reflex-verification.md) covers what is and is not verified.

## Known blockers

- **DLSS needs an NGX context.** `slInit` is called without an `applicationId`, so `sl.common`
  creates none, and every `kFeatureDLSS` / `kFeatureDLSS_G` call returns "context is missing".
  Resource tagging, `slSetConstants`, and `slEvaluateFeature` are also not implemented, so even
  with a context DLSS would not affect the rendered image.
- **The NVIDIA Reflex HUD does not composite over the Minecraft window.** That overlay is drawn
  by the driver, not by Streamline and not by this Mod; nothing in this repository affects it.
- **`VK_NV_low_latency_2` is deliberately not enabled.** The documented manual-hooking fix works,
  but it measurably regresses Reflex, so it is gated behind
  `-Dmc_reflex_tools.vulkanLowLatency2=true` and off by default. The cause of the regression is
  not known. See [docs/reflex-verification.md](docs/reflex-verification.md) §7.3.

## Roadmap

- **M0–M1** — scaffold, safety gate, and Minecraft-owned Vulkan context discovery and validation. Complete.
- **M2** — official NVIDIA Reflex, verified end-to-end. Complete.
- **M3** — DLSS Super Resolution, once an NGX context and resource tagging exist.
- **M4** — Frame Generation evaluation, only after Reflex and DLSS are stable.

The itemised checklist is in [docs/roadmap.md](docs/roadmap.md).

## Legal

NVIDIA, Reflex, DLSS, G-SYNC and Streamline are trademarks of NVIDIA Corporation. Minecraft is
a trademark of Mojang Synergies AB. This project is not affiliated with, endorsed by, or
sponsored by NVIDIA, Mojang, Microsoft, or FabricMC. See [NOTICE.md](NOTICE.md).

## License

MIT — see [LICENSE](LICENSE). That covers this repository's source code only.
