package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.fabric.VulkanLifecycleTrace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftSimulationLifecycleMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mcReflexTools$onSimulationStart(boolean renderLevel, CallbackInfo callbackInfo) {
        VulkanLifecycleTrace.onSimulationStart();
    }
}
