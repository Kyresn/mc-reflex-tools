package dev.kyresn.mcreflex.fabric.mixin;

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
        // slReflexSleep is not called here: this hook runs after the frame's input
        // has already been sampled. See RenderSystemPollEventsMixin for the sleep.
        McReflexToolsFabricClient.getReflexProvider().markSimulationStart();
    }

    /**
     * Simulation end must land before rendering starts. {@code runTick} wraps the
     * whole frame — its RETURN happens after {@code renderFrame} has already
     * submitted and presented — so hooking RETURN would emit SimulationEnd after
     * PresentEnd and break the required marker order on every frame.
     */
    @Inject(method = "runTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
    private void mcReflexTools$onSimulationEnd(boolean advanceGameTime, CallbackInfo callbackInfo) {
        McReflexToolsFabricClient.getReflexProvider().markSimulationEnd();
    }
}
