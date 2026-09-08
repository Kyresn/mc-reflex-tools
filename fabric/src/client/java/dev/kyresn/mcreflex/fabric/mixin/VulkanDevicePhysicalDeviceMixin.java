package dev.kyresn.mcreflex.fabric.mixin;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.kyresn.mcreflex.fabric.access.VulkanDevicePhysicalDeviceAccess;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanDevice")
abstract class VulkanDevicePhysicalDeviceMixin implements VulkanDevicePhysicalDeviceAccess {
    @Unique
    private VulkanPhysicalDevice mcReflexTools$physicalDevice;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mcReflexTools$capturePhysicalDevice(
            ShaderSource shaderSource,
            VulkanInstance instance,
            VulkanPhysicalDevice physicalDevice,
            Set<String> enabledExtensions,
            VkDevice vkDevice,
            long vma,
            CheckpointExtension checkpointExtension,
            CallbackInfo callbackInfo
    ) {
        mcReflexTools$physicalDevice = physicalDevice;
    }

    @Override
    public VulkanPhysicalDevice mcReflexTools$getPhysicalDevice() {
        return mcReflexTools$physicalDevice;
    }
}
