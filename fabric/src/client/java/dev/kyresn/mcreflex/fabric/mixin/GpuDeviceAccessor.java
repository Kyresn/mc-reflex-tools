package dev.kyresn.mcreflex.fabric.mixin;

import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.systems.GpuDevice")
public interface GpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend mcReflexTools$getBackend();
}
