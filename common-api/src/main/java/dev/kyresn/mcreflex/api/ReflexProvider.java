package dev.kyresn.mcreflex.api;

/** The only latency-control calls exposed to Minecraft code. */
public interface ReflexProvider extends AutoCloseable {
    NvidiaFeatureStatus initialize(MinecraftVulkanContext context);

    void sleep();

    void markInputSample();

    void markSimulationStart();

    void markSimulationEnd();

    void markRenderSubmitStart();

    void markRenderSubmitEnd();

    void markPresentStart();

    void markPresentEnd();

    @Override
    void close();
}
