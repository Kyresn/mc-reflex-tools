package dev.kyresn.mcreflex.api;

import java.util.Objects;

/**
 * Handles borrowed from Minecraft's active Vulkan renderer. A mod must never
 * create replacement Vulkan objects for NVIDIA SDK integration.
 */
public record MinecraftVulkanContext(
        long instance,
        long physicalDevice,
        long device,
        long graphicsQueue,
        int graphicsQueueFamilyIndex,
        long swapchain
) {
    public MinecraftVulkanContext {
        if (instance == 0L || physicalDevice == 0L || device == 0L
                || graphicsQueue == 0L || swapchain == 0L) {
            throw new IllegalArgumentException("Minecraft Vulkan handles must be non-zero");
        }
        if (graphicsQueueFamilyIndex < 0) {
            throw new IllegalArgumentException("Graphics queue family index must be non-negative");
        }
    }
}
