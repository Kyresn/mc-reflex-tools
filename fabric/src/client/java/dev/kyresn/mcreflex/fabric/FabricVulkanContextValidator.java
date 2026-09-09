package dev.kyresn.mcreflex.fabric;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.kyresn.mcreflex.fabric.mixin.GpuDeviceAccessor;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.VulkanProbeResult;
import net.minecraft.SharedConstants;

/** Verifies that a context belongs to the expected Minecraft 26.2 Vulkan runtime. */
final class FabricVulkanContextValidator {
    private static final String EXPECTED_MINECRAFT_VERSION = "26.2";

    private FabricVulkanContextValidator() {
    }

    static VulkanProbeResult validate(String renderer, MinecraftVulkanContext context) {
        if (!EXPECTED_MINECRAFT_VERSION.equals(SharedConstants.getCurrentVersion().name())) {
            return unavailable(renderer, "unsupported Minecraft version: " + SharedConstants.getCurrentVersion().name());
        }
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null || !(((GpuDeviceAccessor) gpuDevice).mcReflexTools$getBackend() instanceof VulkanDevice)) {
            return unavailable(renderer, "Minecraft GPU device is no longer backed by Vulkan");
        }
        if (context.graphicsQueueFamilyIndex() < 0) {
            return unavailable(renderer, "graphics queue family is invalid");
        }
        return VulkanProbeResult.available(renderer, context);
    }

    private static VulkanProbeResult unavailable(String renderer, String detail) {
        return VulkanProbeResult.unavailable(
                NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT,
                renderer,
                detail
        );
    }
}
