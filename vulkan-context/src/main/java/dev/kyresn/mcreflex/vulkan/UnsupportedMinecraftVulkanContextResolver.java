package dev.kyresn.mcreflex.vulkan;

import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.VulkanProbeResult;

/** M0 guard: do not claim SDK support until Minecraft Vulkan handles are verified. */
public final class UnsupportedMinecraftVulkanContextResolver implements MinecraftVulkanContextResolver {
    @Override
    public VulkanProbeResult probe() {
        return VulkanProbeResult.unavailable(
                NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT,
                "unresolved",
                "Minecraft Vulkan handles are not yet mapped for this game version"
        );
    }
}
