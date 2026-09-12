# Architecture decisions

## AD-001: NVIDIA SDK only

The project uses the official NVIDIA SDK path or reports a feature as unavailable. It does not reuse or implement a Reflex-like CPU/GPU queue estimator, custom frame scheduler, GL timestamp controller, or generic super-resolution algorithm.

## AD-002: Minecraft 26.2 only

The target is Minecraft Java Edition `26.2`, the current native Vulkan line. Fabric and NeoForge are the only supported loaders for M0–M3.

## AD-003: Minecraft owns the Vulkan context

The Mod may borrow verified handles from Minecraft's active Vulkan renderer. It may not create a separate `VkInstance`, `VkDevice`, graphics queue, swapchain, or presentation path.

## AD-004: Official SDK behavior takes precedence

When official Reflex is active, only NVIDIA's SDK controls Reflex sleep and markers. The Mod does not apply any second scheduling or waiting policy.

## AD-005: Safe failure is a product feature

Missing SDK files, unsupported hardware/driver, incompatible renderer changes, or handle validation failure produce an unavailable status and preserve vanilla behavior.

## AD-006: M1 uses Fabric client-only source sets

Minecraft's rendering and Vulkan classes are client-only. The Fabric module uses Loom's split environment source sets so that the Vulkan resolver and Mixins compile only in `src/client/java`. This prevents common code from accidentally depending on the client renderer.

## AD-007: M1 verified renderer map for Minecraft 26.2

The initial context probe uses the following Minecraft-owned path:

```text
RenderSystem.getDevice()
  -> GpuDevice.backend
  -> VulkanDevice
     -> VulkanInstance.vkInstance
     -> VulkanDevice.vkDevice
     -> VulkanDevice.graphicsQueue

Minecraft.windowSurface()
  -> GpuSurface.backend
  -> VulkanGpuSurface.swapchain
```

Minecraft's real submit boundary is `VulkanQueue.Submission.close()`, which calls `vkQueueSubmit2KHR`. Minecraft's real presentation boundary is `VulkanGpuSurface.present()`, which calls `vkQueuePresentKHR`. M1 observes handles only; M2 must add lifecycle markers at these exact paths without creating replacement Vulkan objects.

## AD-008: Presentation mode is measured from GLFW state

M1.1 logs the requested and actual fullscreen state, the `exclusiveFullscreen` preference, GLFW monitor attachment, framebuffer dimensions, swapchain handle, and Vulkan present mode. On Windows, Minecraft explicitly treats `fullscreen=true` with `exclusiveFullscreen=false` as a soft-screen (borderless) path. Only an exclusive preference combined with a non-null `glfwGetWindowMonitor` result is classified as `exclusive-fullscreen`.

Initial Vulkan validation passed on the reference Windows 11 / RTX 5070 system in three modes:

```text
windowed:               854x480,  IMMEDIATE
borderless-fullscreen:  1920x1080, IMMEDIATE, display attached
exclusive-fullscreen:   1920x1080@280Hz, IMMEDIATE, display attached
```

This establishes the presentation baseline only. M1 also validated runtime windowed → borderless fullscreen → windowed transitions: Minecraft replaced the `VkSwapchainKHR` when entering fullscreen and again when returning to windowed mode, while preserving the Minecraft-owned `VkDevice`. The resolver rereads the active handle on every probe and therefore does not retain either stale value. Runtime exclusive-fullscreen switching must be tested through Minecraft's settings screen; mutating the exclusive option directly at runtime only selects the borderless path.

The trace does not load native code, sleep, inject commands, or change queue ownership. On the reference system it observed one submission per rendered frame for more than 2,400 frames without errors. M2 must forward these markers to `slPCLSetMarker` using a valid Streamline `FrameToken`; it must not replace these hooks with a Java-side scheduler.

## AD-010: Context eligibility uses runtime type validation

`RenderSystem.getBackendDescription()` is a human-readable LWJGL version string after startup, not a reliable Vulkan backend identifier. M1 validates the runtime `GpuDevice.backend` type as `VulkanDevice`, verifies the Minecraft version is exactly `26.2`, and waits until Minecraft configures a non-zero `VkSwapchainKHR`. The client emits each unavailable state once, then reports availability only after the full context is eligible.

The native bridge stays disabled by default until a licensed Streamline package and native toolchain are present.

## AD-011: M2 marker contract tracks Streamline PCL 2.14.1

Streamline 2.14.1 moved latency boundaries to `sl::PCLMarker`; it no longer has an `eInputSample` marker. The Java `ReflexProvider` now maps only these SDK-defined boundaries:

```text
SimulationStart
SimulationEnd
RenderSubmitStart
RenderSubmitEnd
PresentStart
PresentEnd
```

The M2 readiness bridge reports whether a native DLL loaded and whether official Streamline headers were present at native build time. It deliberately does not call `slInit`, `slSetVulkanInfo`, `slReflexSleep`, or `slPCLSetMarker`. This avoids claiming SDK support before the correct device-creation proxy and runtime distribution plan are implemented.

## AD-012: Reflex sleep belongs at the input-sampling boundary

`slReflexSleep` must run after the previous frame's present and before input sampling. In 26.2 that boundary is `RenderSystem.pollEvents()` HEAD, which wraps `GLFW.glfwPollEvents()`. `RenderSystemPollEventsMixin` owns this call, and it is also where the per-frame Streamline `FrameToken` is acquired, so every marker for that frame shares one token.

Calling sleep at the simulation boundary instead is not merely suboptimal — it places the wait after the input the driver is trying to throttle against, and the driver-side measurement reports `I>S = 0.00` rather than a degraded value.

## AD-013: SimulationEnd anchors at the `renderFrame` call site

`present()` runs inside `renderFrame()`, which runs inside `runTick()`. Hooking `runTick`'s RETURN therefore emits `SimulationEnd` after `PresentEnd` on every frame. `MinecraftSimulationLifecycleMixin` anchors `SimulationEnd` at the `renderFrame` INVOKE site so the marker order is `SimulationStart → SimulationEnd → RenderSubmit* → Present*`.

An IDE warning that the `@At` target cannot be mapped is a false alarm here; the mixin applies correctly at runtime and `orderViolations` is the authority.

## AD-014: The Streamline interposer is in Minecraft's Vulkan path

Minecraft creates its own `VkDevice`, so Streamline's `vkCreateDevice` proxy never sees it and `slSetVulkanInfo` is what binds the device. That does **not** mean Minecraft's Vulkan calls bypass `sl.interposer.dll`: the interposer installs inline hooks on the loader's entry points, and the Reflex plugin's swapchain hooks are listed as `- OK` in `sl.log`.

The `interposer 'no'` field on the plugin load lines means the plugin does not *require* interposer-only features. It is not evidence that hooks are absent.

Consequence: the Reflex plugin only learns about the swapchain through `slHookVkCreateSwapchainKHRBefore` → `notifyCreateSwapchain`, and `vknvll2.cpp` returns an empty report when no swapchain is known. A populated `ReflexReport` is therefore proof that the hook fires.

## AD-015: Driver-side overlays are out of scope

The NVIDIA Reflex HUD is drawn by the driver, not by Streamline and not by this Mod. `ReflexTestEnable.exe` enables it through NVAPI DRS and `NvAPI_Reflex_FlashIndicatorSet`, with no Streamline involvement. Whether it composites over a given window is therefore not a function of anything in this repository, and the Mod must not attempt to influence it.

Because that overlay is also observed to render in its unbound state — `FrameID = 4294967295`, `App_Called_Sleep = 0`, all timestamps zero — it is not a dependable readout even when it is visible. Any in-game latency display this project needs should be rendered from `ReflexLatencyReport` and the native counters, which are populated from data we verify.

## AD-016: M2 evidence is recorded in `docs/reflex-verification.md`

AD-011 describes the pre-M2 state and is superseded on its last point: `slInit`, `slSetVulkanInfo`, `slReflexSleep`, and `slPCLSetMarker` are now called, and Reflex Low Latency is verified end-to-end against NVIDIA's own Reflex Test Utility.

`docs/reflex-verification.md` is the living record: verified/unverified status, the four defects that were fixed and the evidence for each, the precision and failure signatures of every measurement, the conclusions that were tried and disproven, and the open issues. Update it rather than re-deriving the analysis.

## AD-017: Do not inject Streamline's device extensions into Minecraft's device

Minecraft creates its own `VkInstance` and `VkDevice`, so it never passes through the interposer's `vkCreateInstance`/`vkCreateDevice` proxies and the extensions Streamline's features request are never added. `ProgrammingGuideManualHooking.md` makes the host responsible for them and mandates `slGetFeatureRequirements` before device creation.

That fix is implemented in `VulkanBackendDeviceExtensionsMixin` and it does what it says: with it, `VK_NV_low_latency2` is enabled, `CreateVkNvLowLatency2` succeeds and the LL2 backend initializes. The project still does not use it by default, because it measurably makes Reflex worse — the driver stops publishing `ReflexReport` frames and stops sending its latency ping, so PC latency measurement is lost where it previously worked.

An earlier hypothesis blamed the LL2 backend having no swapchain (it learns one only through the interposer's swapchain hooks, which are installed after Minecraft has already created its device and swapchain). Recreating the swapchain five times mid-run did not recover it, so that hypothesis is wrong and recorded as such.

The switch is `-Dmc_reflex_tools.vulkanLowLatency2=true`, off by default. Re-enabling it requires a way to observe `vkSetLatencySleepModeNV`'s `VkResult` and the driver's view of the swapchain. The verified-working configuration is the one where the extension is absent and Streamline takes its fallback path.

## AD-018: Re-apply Reflex options after every swapchain change

`slReflexSetOptions` is re-sent whenever the desired mode or frame limit changes **and** whenever Minecraft replaces its swapchain. Streamline's LL2 backend only forwards latency mode to the driver through the swapchain handle it last saw created, and `setSleepMode` silently returns `eOk` while it has none, so an options call must follow a swapchain (re)creation for the driver to be engaged at all.

This did not fix the regression AD-017 describes, but it is correct on its own terms and it also logs the reason, which makes the two triggers distinguishable in the field.

## AD-019: Settings extend Minecraft's own option screens

The Reflex settings UI is an `OptionsSubScreen` (`ReflexOptionsScreen`) reached from a button injected into `VideoSettingsScreen.addOptions` by a Mixin, not a screen built from raw widgets. It therefore inherits the `HeaderAndFooterLayout` / `OptionsList` machinery: scrolling, GUI-scale handling, keyboard focus traversal, narration, and the standard Done button all behave as they do on vanilla screens.

Widgets are `OptionInstance` values whose `ValueUpdateListener` writes `ModConfig` and calls `ModConfig.save()`. This is the same write path the `/reflex` command uses, so the per-tick apply loop (AD-018) picks up both without knowing which one changed the config. Nothing in the screen talks to Streamline directly.

The frame-limit input box applies its value on Enter, on focus loss, and when the screen closes — deliberately not through `EditBox.setResponder`, which fires per keystroke and would write a partially typed number (`7`, `70`, `700`) to the driver. Applying calls `resetOption` so the paired slider reflects the typed value immediately instead of only after the screen is reopened.
