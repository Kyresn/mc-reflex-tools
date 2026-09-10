package dev.kyresn.mcreflex.api;

public interface DlssGProvider extends AutoCloseable {
    NvidiaFeatureStatus initialize(MinecraftVulkanContext context);

    DlssGState getState();

    void setOptions(DlssGMode mode, int numFramesToGenerate);

    @Override
    void close();
}
