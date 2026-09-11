# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Minecraft Java Edition **26.2** client mod that integrates the **NVIDIA Streamline SDK** (not the standalone Reflex SDK) to provide Reflex Low Latency, DLSS Super Resolution, DLSS-G Frame Generation, and G-SYNC/VRR-aware frame limiting. Fabric is the only active loader. Windows x64 + Minecraft's native Vulkan renderer only. No legacy MC, no OpenGL path, no self-written AntiLag/scheduler/upscaler fallbacks.

The NVIDIA Streamline SDK (v2.14.1) is **not vendored**. Download it from NVIDIA (RTX SDKs) and point `NVIDIA_STREAMLINE_ROOT` at the extracted root; the native build locates it through that env var. The native bridge compiles against `sl.interposer.lib` and the `include/` headers; at runtime the Streamline plugin DLLs (`sl.interposer.dll`, `sl.common.dll`, `sl.reflex.dll`, etc.) must be reachable.

## Build

Requires **JDK 25**. Fabric Loom resolves the JDK toolchain automatically.

```bash
# Build the mod jar (runs all Java modules via the fabric artifact)
./gradlew :fabric:build

# Run the development client
./gradlew :fabric:runClient

# Re-generate decompiled Minecraft sources (for exploring the 26.2 Gpu/Vulkan APIs)
./gradlew :fabric:genSources
```

Native bridge (`nvidia-sdk-native`) is CMake + MSVC, built separately:

```bash
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
cmake -S nvidia-sdk-native -B nvidia-sdk-native/build -G "Visual Studio 18 2026" -A x64
cmake --build nvidia-sdk-native/build --config Release
```

The compiled `mc_reflex_tools_native.dll` must be copied to `nvidia-sdk-java/src/main/resources/natives/windows-x64/` to be embedded in the jar. `NativeLibraryLoader` extracts it to `java.io.tmpdir/mc_reflex_tools/` at runtime and pre-loads `sl.interposer.dll` from the Streamline SDK. That one DLL is a build *input*, so `.gitignore` re-includes it after the blanket `*.dll` rule; the `fabric` module nests the sibling jars via Loom `include(...)` so it actually reaches a distributed jar's classpath.

Streamline writes its plugin logs to `mc_reflex_tools_logs/` relative to the process working directory (the run directory under Gradle, the game directory otherwise).

## Running in Vulkan mode

Minecraft 26.2 defaults to OpenGL. The mod reports `UNSUPPORTED_RENDERER` and every feature becomes unavailable unless the client actually starts with Vulkan. Set this in `fabric/run/options.txt`:

```
preferredGraphicsBackend:"vulkan"
```

The client saves over this file on abnormal shutdown (it resets to OpenGL), so re-assert it before each `runClient` if needed.

## Module layout

Multi-module Gradle (`settings.gradle.kts`). Only `fabric` is wired in (NeoForge was removed).

- `common-api` — version- and loader-agnostic contracts: `ReflexProvider`, `DlssProvider`, `DlssGProvider`, `MinecraftVulkanContext` (borrowed Vk handles), enums (`ReflexMode`, `DlssMode`, `DlssGMode`), and result/status records. Nothing here touches Minecraft or JNI.
- `vulkan-context` — `MinecraftVulkanContextResolver` interface + a generic `Unsupported` impl produced early on; the real Mandatory resolver lives in `fabric`.
- `nvidia-sdk-java` — JNI boundary classes (`NativeReflexProvider`, `NativeDlssProvider`, `NativeDlssGProvider`), no-op `Unavailable*Provider`s for safe fallback, and `NativeLibraryLoader`.
- `fabric` — loader entrypoint (`McReflexToolsFabricClient`), the Vulkan context resolver + validator, config (`config/ModConfig`), and all Mixins.
- `nvidia-sdk-native` — single `src/jni_exports.cpp` C++ JNI bridge (guarded by `MC_REFLEX_TOOLS_HAS_STREAMLINE`).

The Fabric module uses **Loom split environment source sets**: anything referencing Minecraft client or Vulkan classes goes in `fabric/src/client/java` (not `src/main`), because those classes are client-only.

## Key architectural facts

### Minecraft 26.2 replaced the old GL stack

26.2 does not use `RenderSystem`/`GlStateManager`/`Framebuffer` for rendering. It uses the new `GpuDevice` / `GpuSurface` abstraction over `VulkanDevice` / `VulkanGpuSurface`. The mod borrows Minecraft's own Vulkan handles through Mixin accessors:

```
RenderSystem.getDevice() -> GpuDevice.backend -> VulkanDevice
  -> VulkanInstance.vkInstance / VulkanDevice.vkDevice / graphicsQueue

Minecraft.windowSurface() -> GpuSurface.backend -> VulkanGpuSurface
  -> swapchain
```

`VulkanGpuTexture.vkImage()` and `VulkanGpuTextureView.vkImageView()` are public — DLSS resource tagging can read them without private-field Mixins. `VulkanDevice` does not retain its `VulkanPhysicalDevice`, so one Mixin (`VulkanDevicePhysicalDeviceMixin`) captures it from the constructor.

Never create a second `VkInstance`/`VkDevice`/queue/swapchain — the mod must borrow and verify Minecraft's (see `docs/architecture.md` AD-003).

### Real lifecycle boundaries (where Mixins hook)

The client main loop in 26.2 is:

```java
while (running) {
    RenderSystem.pollEvents();   // wraps GLFW.glfwPollEvents() — input sampling
    runTick(advanceGameTime);    // simulation + renderFrame + present
}
```

- Reflex sleep: `RenderSystem.pollEvents()` HEAD (`RenderSystemPollEventsMixin`). `slReflexSleep` must run after the previous frame's present and before input sampling, which is exactly this boundary. It also fetches the frame token used by every marker that frame.
- Simulation: `Minecraft.runTick` HEAD/RETURN.
- Render submit: `VulkanQueue$Submission.close()` — this is where Minecraft calls `vkQueueSubmit2KHR`.
- Present: `VulkanGpuSurface.present()` HEAD/RETURN — where Minecraft calls `vkQueuePresentKHR`.

Everything runs on the game thread (`renderFrame` is called from `runTick`), so the native frame-token and marker state needs no locking. The Mixins forward to the active `ReflexProvider` via `McReflexToolsFabricClient.getReflexProvider()`. A component must not be trusted to be non-null in Mixin code early in startup.

Marker order per frame: SimulationStart → SimulationEnd → RenderSubmitStart → RenderSubmitEnd → PresentStart → PresentEnd. Minecraft can record several queue submissions per frame, so `nativeMarker` keeps the first RenderSubmit pair and drops the rest.

### Feature wiring

`McReflexToolsFabricClient.onInitializeClient()` calls `slInit` first (it must precede device creation) and then runs a per-tick probe. Once `FabricMinecraftVulkanContextResolver`+`FabricVulkanContextValidator` report an eligible context (Vulkan backend, exact `26.2`, non-zero swapchain), it initializes the native Reflex → DLSS → DLSS-G providers, reads `config/mc_reflex_tools.json`, and applies the Reflex mode and the manual `customFrameLimitFps`. Options are re-sent whenever the configured mode or limit changes, not just once.

Config (`config/mc_reflex_tools.json`) is auto-generated by `ModConfig` with Gson. Default: Reflex `ON_PLUS_BOOST`, `customFrameLimitFps = 0` (limiter off), DLSS `MAX_QUALITY`, DLSS-G `OFF`.

### Diagnosing Reflex

**Read [`docs/reflex-verification.md`](docs/reflex-verification.md) first.** It is the living record: the four defects that were fixed with the evidence for each, how far each measurement can be trusted, the conclusions that were tried and disproven, and the open issues. Do not re-derive that analysis from scratch.

The native bridge takes over three things the Reflex verification HUD would otherwise be the only source of, so Reflex can be validated without an NVIDIA Control Panel app profile:

- `logMessageCallback` + `pathToLogsAndData` — Streamline plugin warnings are forwarded into the game log (`[SL WARN] …`) and the SDK writes its own file to `mc_reflex_tools_logs/sl.log` next to the run directory. **Start here when a plugin silently declines to engage.** Streamline opens that file with `_wfsopen` and disables file logging permanently if the open fails, so the directory is created in `resolveLogPath()` before `slInit`.
- `nativeGetLatencyReport()` — mirrors `sl::ReflexReport` (`ReflexLatencyReport` record): per-frame `frameId` and marker timestamps. The timestamps are absolute wall-clock microseconds, so only differences are meaningful.
- Counters for `sleepCount`, `pclPings`, `suppressedRenderSubmit`, `orderViolations`, `staleMarkers`, logged by `VulkanLifecycleTrace` every 300 frames.

A healthy integration shows `sleeps` advancing once per frame, zero order violations and zero stale markers, and a `frameId` that keeps changing instead of sticking at `4294967295`.

`ReflexLatencyReport.hasInputSample()` is **always false on Vulkan and that is not a defect.** The driver stamps `inputSampleTimeUs` from `VK_LATENCY_MARKER_INPUT_SAMPLE_NV`, but Streamline removed that marker (`PCLMarker::eInputSample` is commented out in `sl_pcl.h`) and `PCLMarker::ePCLatencyPing` deliberately maps to `VK_LATENCY_MARKER_MAX_ENUM_NV` — see `source/platforms/sl.chi/vknvll2.cpp`, `PCLMarker2VkLatencyMarker`. The input sampling component is measured out of band by the Reflex Test Utility through the ping round trip. Use `simStartToPresentUs()` for the latency component the application controls.

The driver measures input sampling latency by posting a periodic message to the game window (`sl::ReflexState::statsWindowMessage`), which the app answers with an `ePCLatencyPing` marker. Minecraft leaves the window procedure to GLFW, so `nativeInstallPclPingHook` subclasses it with `SetWindowLongPtr` and chains to GLFW's proc. `pclPings` counts those replies; it only becomes non-zero once the driver is actively measuring, so a permanently zero count means the app is not in the NVIDIA driver profile. **This is the fix that moved `I>S` from 0.00 to 0.68 in the external tool; do not remove it.**

`FrameID = 4294967295` (`0xFFFFFFFF`) is the sentinel for "no report". It is also what the driver's own HUD displays when it is running but not bound to a measured application.

### The driver's Reflex HUD is not ours

The Reflex HUD overlay is drawn by the NVIDIA driver, not by Streamline and not by this Mod — `ReflexTestEnable.exe` enables it with NVAPI DRS and `NvAPI_Reflex_FlashIndicatorSet`, with zero Streamline involvement. Whether it composites over the Minecraft window is therefore not a function of anything in this repository, and no change here can fix it. Do not propose interposer, present-hook, or swapchain explanations for it; those have been investigated and disproven. If an in-game readout is needed, render one from `ReflexLatencyReport` and the native counters — every field the driver HUD shows is available to us except the input-sampling leg, which is measured out of band.

Known-unexplained, outside the Mod: the HUD does not draw over the game window in windowed or exclusive-fullscreen mode, and `ReflexTest.exe curve` reproducibly fails at the `Reflex BOOST → Reflex OFF` transition in every run since 2026-09-10.

### Streamline call surface (all in `nvidia-sdk-native/src/jni_exports.cpp`)

- `slInit` / `slShutdown`
- Vulkan: `slSetVulkanInfo` (hand-declared `sl::VulkanInfo`, because `sl_helpers_vk.h` needs full Vulkan headers this project does not vendor)
- Reflex: `slReflexSetOptions` (mode + `frameLimitUs`), `slReflexSleep`, `slReflexGetState` (state + `frameReport[]` + `statsWindowMessage`), markers via `slPCLSetMarker` (`sl::PCLMarker` enum — 2.14.1 has no `eInputSample`; input sampling latency is measured through `ePCLatencyPing` instead)
- DLSS SR: `slDLSSGetOptimalSettings`, `slDLSSSetOptions`
- DLSS-G: `slDLSSGGetState`, `slDLSSGSetOptions`

Minecraft creates its own `VkDevice`, so Streamline's `vkCreateDevice` proxy never sees it; `slSetVulkanInfo` is what binds the device and without it every feature call fails. `sl::Preferences::featuresToLoad` is likewise mandatory — an unset list loads no plugins at all and every call returns `eErrorFeatureMissing`.

That does **not** mean Minecraft's Vulkan calls bypass the interposer. The interposer installs inline hooks on the loader's entry points, and `sl.log` lists `sl.reflex:slHookVkCreateSwapchainKHRBefore/After` as `- OK`. The `interposer 'no'` field on the plugin load lines means the plugin does not *require* interposer-only features; it is not evidence that hooks are absent. A populated `ReflexReport` is proof the swapchain hook fires, because the Reflex plugin learns the swapchain only through `notifyCreateSwapchain` and returns an empty report without one.

`sl.log` also reports `Failed to init VkNvLowLatency2: 2` (`eNoImplementation`), which means at least one `VK_NV_low_latency_2` entry point was null on Minecraft's device — the extension was not enabled at `vkCreateDevice`. **This is the working configuration; leave it alone.** The root cause is that Minecraft creates its own device outside the interposer's `vkCreateDevice` proxy, so the extensions the SDK would inject are never added.

`VulkanBackendDeviceExtensionsMixin` implements the documented manual-hooking fix (`slGetFeatureRequirements` → add what `VulkanPhysicalDevice.hasDeviceExtension` confirms). It works — LL2 then initializes — but it **measurably regresses Reflex**: the driver stops publishing frame reports and stops sending its latency ping. It is therefore gated behind `-Dmc_reflex_tools.vulkanLowLatency2=true` and off by default. Do not enable it unless you can observe `vkSetLatencySleepModeNV`'s `VkResult`. Evidence and the disproven swapchain hypothesis are in `docs/reflex-verification.md` §7.3.

DLSS still only initializes, and not even that: `slInit` is called without an `applicationId`, so `sl.common` creates no NGX context, `dlssEntry.cpp` and `dlss_gEntry.cpp` both fail with "Missing NGX context", and every `kFeatureDLSS`/`kFeatureDLSS_G` call returns `context is missing`. Resource tagging (depth/motion vectors/colour), `slSetConstants`, and `slEvaluateFeature` are also not implemented, so DLSS does not affect the rendered image.

**Keep `dlssGMode` at `OFF`** until the NGX context exists. Frame Generation cannot work in this state, and enabling it makes the Reflex Test Utility's Frame Gen cycles fail.

## Conventions

- Commit messages use conventional prefixes (`feat:`, `chore:`, `test:`).
- Architecture decisions are recorded in `docs/architecture.md` (AD-001 onward). Update them when the design shifts.
- All NVIDIA-feature integration is additive behind graceful "unavailable" fallback: on any failure, the game keeps running unchanged.
- **Never commit NVIDIA SDK files.** No Streamline headers, import libraries, or plugin DLLs. `NOTICE.md` explains why and the `redistribution-guard` CI job enforces it. The one permitted binary is our own `mc_reflex_tools_native.dll`, built from this repository's source.
- Editing `nvidia-sdk-native/src/jni_exports.cpp` or its `CMakeLists.txt` means rebuilding and recommitting the DLL, then updating `nvidia-sdk-native/native-artifact.sha256`, or the `native-artifact-integrity` CI job fails. The toolchain and the local verification script are in `docs/native-build.md`.
- Releasing is a tag push: `git tag v$mod_version && git push origin v$mod_version`. `release.yml` refuses a tag that does not match `mod_version` and publishes a prerelease with the jar attached. Pushes to `main` are built and uploaded as a run artifact by `build.yml`.
- A local `build/streamline-reference/` copy of the Streamline repo docs may exist for reference. `build/` is gitignored, so it is never committed; treat it as read-only.