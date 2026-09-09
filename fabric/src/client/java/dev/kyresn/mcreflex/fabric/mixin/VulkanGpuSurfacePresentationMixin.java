package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.fabric.McReflexToolsFabricClient;
import dev.kyresn.mcreflex.fabric.VulkanLifecycleTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanGpuSurface")
abstract class VulkanGpuSurfacePresentationMixin {
    @Inject(method = "present", at = @At("HEAD"))
    private void mcReflexTools$onPresentStart(CallbackInfo callbackInfo) {
        VulkanLifecycleTrace.onPresentStart();
        McReflexToolsFabricClient.getReflexProvider().markPresentStart();
    }

    @Inject(method = "present", at = @At("RETURN"))
    private void mcReflexTools$onPresentEnd(CallbackInfo callbackInfo) {
        VulkanLifecycleTrace.onPresentEnd();
        McReflexToolsFabricClient.getReflexProvider().markPresentEnd();
    }
}
