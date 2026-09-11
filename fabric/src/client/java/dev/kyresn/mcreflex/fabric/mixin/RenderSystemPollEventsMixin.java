package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.fabric.McReflexToolsFabricClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflex sleep point.
 *
 * <p>Streamline requires {@code slReflexSleep} to run after the previous frame
 * has been presented and before the next frame samples its input. In 26.2 the
 * main loop is {@code RenderSystem.pollEvents(); Minecraft.runTick(...)}, and
 * {@code pollEvents} is the single call that wraps {@code GLFW.glfwPollEvents()}.
 * Sleeping at its head is therefore the last moment before input is read, which
 * is exactly where Reflex needs to be.
 *
 * <p>Sleeping inside {@code runTick} instead would happen <em>after</em> input
 * had already been sampled, which neutralises the latency reduction Reflex is
 * meant to provide.
 */
@Mixin(targets = "com.mojang.blaze3d.systems.RenderSystem")
abstract class RenderSystemPollEventsMixin {
    @Inject(method = "pollEvents", at = @At("HEAD"))
    private static void mcReflexTools$sleepBeforeInputSampling(CallbackInfo callbackInfo) {
        McReflexToolsFabricClient.getReflexProvider().sleep();
    }
}
