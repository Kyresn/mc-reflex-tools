package dev.kyresn.mcreflex.fabric.mixin;

import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanGpuSurface")
public interface VulkanGpuSurfaceAccessor {
    @Accessor("swapchain")
    long mcReflexTools$getSwapchain();

    @Accessor("presentQueue")
    VkQueue mcReflexTools$getPresentQueue();
}
