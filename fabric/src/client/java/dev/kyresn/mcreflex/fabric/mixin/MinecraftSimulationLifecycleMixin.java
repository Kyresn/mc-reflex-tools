package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.api.ReflexProvider;
import dev.kyresn.mcreflex.fabric.McReflexToolsFabricClient;
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
        ReflexProvider provider = McReflexToolsFabricClient.getReflexProvider();
        provider.sleep();
        provider.markSimulationStart();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void mcReflexTools$onSimulationEnd(boolean renderLevel, CallbackInfo callbackInfo) {
        McReflexToolsFabricClient.getReflexProvider().markSimulationEnd();
    }
}
