package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.DlssMode;
import dev.kyresn.mcreflex.api.DlssOptimalSettings;
import dev.kyresn.mcreflex.api.DlssProvider;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;

public final class UnavailableDlssProvider implements DlssProvider {
    private final NvidiaFeatureStatus status;

    public UnavailableDlssProvider(NvidiaFeatureStatus status) {
        this.status = status;
    }

    @Override
    public NvidiaFeatureStatus initialize(MinecraftVulkanContext context) {
        return status;
    }

    @Override
    public DlssOptimalSettings getOptimalSettings(DlssMode mode, int outputWidth, int outputHeight) {
        return DlssOptimalSettings.disabled(outputWidth, outputHeight);
    }

    @Override
    public void setOptions(DlssMode mode, int outputWidth, int outputHeight) {
    }

    @Override
    public void close() {
    }
}
