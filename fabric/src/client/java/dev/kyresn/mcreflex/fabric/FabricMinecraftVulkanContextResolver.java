package dev.kyresn.mcreflex.fabric;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.VulkanProbeResult;
import dev.kyresn.mcreflex.fabric.mixin.GpuDeviceAccessor;
import dev.kyresn.mcreflex.fabric.mixin.GpuSurfaceAccessor;
import dev.kyresn.mcreflex.fabric.access.VulkanDevicePhysicalDeviceAccess;
import dev.kyresn.mcreflex.fabric.mixin.VulkanGpuSurfaceAccessor;
import dev.kyresn.mcreflex.vulkan.MinecraftVulkanContextResolver;
import net.minecraft.client.Minecraft;

/**
 * M1 read-only resolver for Minecraft 26.2's own Vulkan objects.
 *
 * <p>It creates no Vulkan objects and only reports a context after every handle
 * required by the native bridge belongs to Minecraft's active renderer.</p>
 */
public final class FabricMinecraftVulkanContextResolver implements MinecraftVulkanContextResolver {
    private static final String RENDERER = "Minecraft 26.2 Vulkan";

    @Override
    public VulkanProbeResult probe() {
        if (!RenderSystem.isOnRenderThread()) {
            return unavailable("probe must run on Minecraft's render thread");
        }

        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null || !(((GpuDeviceAccessor) gpuDevice).mcReflexTools$getBackend() instanceof VulkanDevice device)) {
            return VulkanProbeResult.unavailable(
                    NvidiaFeatureStatus.UNSUPPORTED_RENDERER,
                    RenderSystem.getBackendDescription(),
                    "Minecraft is not using its Vulkan device backend"
            );
        }

        GpuSurface surface = Minecraft.getInstance().windowSurface();
        if (!(surface instanceof GpuSurfaceAccessor accessor)) {
            return unavailable("GpuSurface accessor was not applied");
        }

        GpuSurfaceBackend backend = accessor.mcReflexTools$getBackend();
        if (!(backend instanceof VulkanGpuSurface vulkanSurface)
                || !(vulkanSurface instanceof VulkanGpuSurfaceAccessor surfaceAccess)) {
            return unavailable("Minecraft window surface is not backed by VulkanGpuSurface");
        }

        VulkanPhysicalDevice physicalDevice = ((VulkanDevicePhysicalDeviceAccess) device)
                .mcReflexTools$getPhysicalDevice();
        if (physicalDevice == null) {
            return unavailable("physical device capture is not ready");
        }

        long swapchain = surfaceAccess.mcReflexTools$getSwapchain();
        if (swapchain == 0L) {
            return unavailable("window swapchain has not been configured");
        }

        MinecraftVulkanContext context = new MinecraftVulkanContext(
                device.instance().vkInstance().address(),
                physicalDevice.vkPhysicalDevice().address(),
                device.vkDevice().address(),
                device.graphicsQueue().vkQueue().address(),
                device.graphicsQueue().queueFamilyIndex(),
                swapchain
        );
        return FabricVulkanContextValidator.validate(RENDERER, context);
    }

    private static VulkanProbeResult unavailable(String detail) {
        return VulkanProbeResult.unavailable(
                NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT,
                RENDERER,
                detail
        );
    }
}
