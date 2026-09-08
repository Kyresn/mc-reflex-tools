package dev.kyresn.mcreflex.fabric.access;

import com.mojang.blaze3d.platform.Window;

/** Exposes fullscreen state without putting a non-Mixin type in the Mixin package. */
public interface WindowFullscreenAccess {
    boolean mcReflexTools$isActuallyFullscreen();

    boolean mcReflexTools$isExclusiveFullscreen();
}
