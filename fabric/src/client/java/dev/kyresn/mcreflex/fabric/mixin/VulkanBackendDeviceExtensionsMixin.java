package dev.kyresn.mcreflex.fabric.mixin;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import dev.kyresn.mcreflex.fabric.McReflexToolsFabricClient;
import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Adds the Vulkan device extensions Streamline's features require to Minecraft's own
 * {@code VkDeviceCreateInfo}.
 *
 * <p>Streamline normally enables these itself: when an application creates its device
 * through the {@code sl.interposer} proxies, the interposer merges the plugin's
 * {@code external/vk/device/extensions} list into the create-info. Minecraft creates its own
 * device through the loader, so that path never runs and the host is responsible for them -
 * see the SDK's {@code ProgrammingGuideManualHooking.md}, "Instance and device additions".
 *
 * <p>Without {@code VK_NV_low_latency2} the Reflex LL2 backend returns
 * {@code eNoImplementation} from {@code CreateVkNvLowLatency2}, {@code vkLatencySleepNV}
 * resolves to null, and {@code slHookVkCreateSwapchainKHRBefore} silently skips injecting
 * {@code VkSwapchainLatencyCreateInfoNV}, so the swapchain is never created in latency mode.
 *
 * <p><b>Inert by default.</b> {@link NativeReflexProvider#requiredDeviceExtensions()} returns
 * an empty list unless {@code -Dmc_reflex_tools.vulkanLowLatency2=true} is set: enabling the
 * extension measurably makes Reflex worse on the reference system. See that method and
 * {@code docs/reflex-verification.md} section 7.3 for the evidence.
 *
 * <p>Minecraft already uses this exact pattern for {@code VK_AMD_buffer_marker},
 * {@code VK_NV_device_diagnostic_checkpoints} and {@code VK_EXT_multi_draw}, so this
 * extends an existing mechanism rather than introducing a new one. The borrowed device is
 * still created by Minecraft and still owned by Minecraft (AD-003).
 */
@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanBackend")
abstract class VulkanBackendDeviceExtensionsMixin {
    @Inject(
            method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD")
    )
    private static void mcReflexTools$addStreamlineDeviceExtensions(
            Collection<String> deviceExtensions,
            VulkanPhysicalDevice physicalDevice,
            Set<?> vulkanFeatures,
            CallbackInfoReturnable<VkDevice> callbackInfo
    ) {
        List<String> required = NativeReflexProvider.requiredDeviceExtensions();
        if (required.isEmpty()) {
            return;
        }

        for (String name : required) {
            // Never add an extension the driver does not advertise: vkCreateDevice
            // rejects an unknown name and Minecraft would fail to start.
            if (!physicalDevice.hasDeviceExtension(name)) {
                McReflexToolsFabricClient.LOGGER.warn(
                        "Streamline requires Vulkan device extension {} but the physical device does not "
                                + "expose it; skipping it and continuing without that feature",
                        name);
                continue;
            }

            if (deviceExtensions.add(name)) {
                McReflexToolsFabricClient.LOGGER.info(
                        "Enabled Streamline-required Vulkan device extension {}", name);
            }
        }
    }
}
