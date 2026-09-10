package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.DlssGMode;
import dev.kyresn.mcreflex.api.DlssGProvider;
import dev.kyresn.mcreflex.api.DlssGState;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;

public final class UnavailableDlssGProvider implements DlssGProvider {
    private final NvidiaFeatureStatus status;

    public UnavailableDlssGProvider(NvidiaFeatureStatus status) {
        this.status = status;
    }

    @Override
    public NvidiaFeatureStatus initialize(MinecraftVulkanContext context) {
        return status;
    }

    @Override
    public DlssGState getState() {
        return DlssGState.unavailable();
    }

    @Override
    public void setOptions(DlssGMode mode, int numFramesToGenerate) {
    }

    @Override
    public void close() {
    }
}
