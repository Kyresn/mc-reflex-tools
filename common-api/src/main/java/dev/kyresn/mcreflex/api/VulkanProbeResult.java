package dev.kyresn.mcreflex.api;

/** Immutable result of one Vulkan capability probe. */
public record VulkanProbeResult(
        NvidiaFeatureStatus status,
        String renderer,
        String detail,
        MinecraftVulkanContext context
) {
    public static VulkanProbeResult unavailable(NvidiaFeatureStatus status, String renderer, String detail) {
        return new VulkanProbeResult(status, renderer, detail, null);
    }

    public static VulkanProbeResult available(String renderer, MinecraftVulkanContext context) {
        return new VulkanProbeResult(NvidiaFeatureStatus.AVAILABLE, renderer, "Minecraft Vulkan context is available", context);
    }

    public boolean isAvailable() {
        return status == NvidiaFeatureStatus.AVAILABLE && context != null;
    }
}
