package dev.kyresn.mcreflex.fabric.mixin;

import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.systems.GpuSurface")
public interface GpuSurfaceAccessor {
    @Accessor("backend")
    GpuSurfaceBackend mcReflexTools$getBackend();
}
