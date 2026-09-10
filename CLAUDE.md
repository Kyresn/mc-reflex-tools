# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Minecraft Java Edition **26.2** client mod that integrates the **NVIDIA Streamline SDK** (not the standalone Reflex SDK) to provide Reflex Low Latency, DLSS Super Resolution, DLSS-G Frame Generation, and G-SYNC/VRR-aware frame limiting. Fabric is the only active loader. Windows x64 + Minecraft's native Vulkan renderer only. No legacy MC, no OpenGL path, no self-written AntiLag/scheduler/upscaler fallbacks.

The NVIDIA Streamline SDK (v2.14.1) is **not vendored**. It lives at `<path-to-streamline-sdk-v2.14.1>` and is located for native builds via the `NVIDIA_STREAMLINE_ROOT` env var. The native bridge compiles against `sl.interposer.lib` and the `include/` headers; at runtime the Streamline plugin DLLs (`sl.interposer.dll`, `sl.common.dll`, `sl.reflex.dll`, etc.) must be reachable.

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

The compiled `mc_reflex_tools_native.dll` must be copied to `nvidia-sdk-java/src/main/resources/natives/windows-x64/` to be embedded in the jar. `NativeLibraryLoader` extracts it to `java.io.tmpdir/mc_reflex_tools/` at runtime and pre-loads `sl.interposer.dll` from the Streamline SDK.

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

- Simulation: `Minecraft.runTick` HEAD/RETURN.
- Render submit: `VulkanQueue$Submission.close()` — this is where Minecraft calls `vkQueueSubmit2KHR`.
- Present: `VulkanGpuSurface.present()` HEAD/RETURN — where Minecraft calls `vkQueuePresentKHR`.

The Mixins forward to the active `ReflexProvider` via `McReflexToolsFabricClient.getReflexProvider()`. A component must not be trusted to be non-null in Mixin code early in startup.

### Feature wiring

`McReflexToolsFabricClient.onInitializeClient()` runs a per-tick probe. Once `FabricMinecraftVulkanContextResolver`+`FabricVulkanContextValidator` report an eligible context (Vulkan backend, exact `26.2`, non-zero swapchain), it initializes the native Reflex → DLSS → DLSS-G providers, reads `config/mc_reflex_tools.json`, and applies Reflex mode + G-SYNC frame limit.

Config (`config/mc_reflex_tools.json`) is auto-generated by `ModConfig` with Gson. Default: Reflex `ON_PLUS_BOOST`, auto G-SYNC limit at **95% of monitor refresh rate** via Reflex `frameLimitUs`.

### Streamline call surface (all in `nvidia-sdk-native/src/jni_exports.cpp`)

- `slInit` / `slShutdown`
- Reflex: `slReflexSetOptions` (mode + `frameLimitUs`), `slReflexSleep`, markers via `slPCLSetMarker` (`sl::PCLMarker` enum — 2.14.1 has no `eInputSample`)
- DLSS SR: `slDLSSGetOptimalSettings`, `slDLSSSetOptions`
- DLSS-G: `slDLSSGGetState`, `slDLSSGSetOptions`

The bridge currently does **not** call `slSetVulkanInfo`; this is the likely reason Reflex latency reduction and the `frameLimitUs` driver-level limiter are not yet observed to take effect. The Reflex Programming Guide requires calling the Vulkan equivalent of `slSetD3DDevice` after `slInit`, and full Reflex checklists require fullscreen-exclusive with the frame limiter not overridden by `sl.reflex.json`.

## Conventions

- Commit messages use conventional prefixes (`feat:`, `chore:`, `test:`).
- Architecture decisions are recorded in `docs/architecture.md` (AD-001 onward). Update them when the design shifts.
- All NVIDIA-feature integration is additive behind graceful "unavailable" fallback: on any failure, the game keeps running unchanged.
- `git status` currently tracks a `build/streamline-reference/` copy of the Streamline repo docs; treat it as read-only reference material, not project code.