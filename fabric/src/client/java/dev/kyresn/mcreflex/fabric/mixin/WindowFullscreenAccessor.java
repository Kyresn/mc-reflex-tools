package dev.kyresn.mcreflex.fabric.mixin;

import dev.kyresn.mcreflex.fabric.access.WindowFullscreenAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.platform.Window")
public interface WindowFullscreenAccessor extends WindowFullscreenAccess {
    @Override
    @Accessor("actuallyFullscreen")
    boolean mcReflexTools$isActuallyFullscreen();

    @Override
    @Accessor("exclusiveFullscreen")
    boolean mcReflexTools$isExclusiveFullscreen();
}
