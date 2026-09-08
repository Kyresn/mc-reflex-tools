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
