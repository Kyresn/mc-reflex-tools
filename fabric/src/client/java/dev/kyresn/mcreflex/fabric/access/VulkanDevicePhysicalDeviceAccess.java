package dev.kyresn.mcreflex.fabric.access;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;

/** Captures the physical device that Minecraft otherwise does not retain. */
public interface VulkanDevicePhysicalDeviceAccess {
    VulkanPhysicalDevice mcReflexTools$getPhysicalDevice();
}
