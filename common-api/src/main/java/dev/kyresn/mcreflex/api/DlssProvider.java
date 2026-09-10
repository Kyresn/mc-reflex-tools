package dev.kyresn.mcreflex.api;

public interface DlssProvider extends AutoCloseable {
    NvidiaFeatureStatus initialize(MinecraftVulkanContext context);

    DlssOptimalSettings getOptimalSettings(DlssMode mode, int outputWidth, int outputHeight);

    void setOptions(DlssMode mode, int outputWidth, int outputHeight);

    @Override
    void close();
}
