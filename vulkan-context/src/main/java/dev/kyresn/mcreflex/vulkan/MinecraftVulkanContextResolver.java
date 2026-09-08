package dev.kyresn.mcreflex.vulkan;

import dev.kyresn.mcreflex.api.VulkanProbeResult;

/**
 * Resolves Vulkan handles owned by Minecraft's native renderer.
 *
 * <p>M0 deliberately returns an unavailable result. The actual resolver is
 * enabled only after the target Minecraft renderer exposes stable, verified
 * access to its Vulkan device, queue, and swapchain.</p>
 */
public interface MinecraftVulkanContextResolver {
    VulkanProbeResult probe();
}
