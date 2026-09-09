package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.fabric.VulkanLifecycleTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanQueue$Submission")
abstract class VulkanQueueSubmissionMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void mcReflexTools$onRenderSubmit(CallbackInfo callbackInfo) {
        VulkanLifecycleTrace.onRenderSubmit();
    }
}
