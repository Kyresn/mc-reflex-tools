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

## AD-009: M1 lifecycle markers are trace-only

M1 now traces the actual boundaries that M2 will use for Streamline PCL markers:

```text
Minecraft.runTick()                    → simulation start
VulkanQueue.Submission.close()         → vkQueueSubmit2KHR boundary
VulkanGpuSurface.present(), HEAD       → vkQueuePresentKHR start
VulkanGpuSurface.present(), RETURN     → vkQueuePresentKHR end
```

The trace does not load native code, sleep, inject commands, or change queue ownership. On the reference system it observed one submission per rendered frame for more than 2,400 frames without errors. M2 must forward these markers to `slPCLSetMarker` using a valid Streamline `FrameToken`; it must not replace these hooks with a Java-side scheduler.
