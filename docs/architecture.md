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
