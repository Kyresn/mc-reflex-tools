package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.ReflexProvider;

/**
 * Safe placeholder until the approved NVIDIA Streamline runtime is integrated.
 * It never emulates Reflex and never schedules frames itself.
 */
public final class UnavailableReflexProvider implements ReflexProvider {
    private final NvidiaFeatureStatus status;

    public UnavailableReflexProvider(NvidiaFeatureStatus status) {
        this.status = status;
    }

    @Override
    public NvidiaFeatureStatus initialize(MinecraftVulkanContext context) {
        return status;
    }

    @Override
    public void sleep() {
    }

    @Override
    public void markInputSample() {
    }

    @Override
    public void markSimulationStart() {
    }

    @Override
    public void markSimulationEnd() {
    }

    @Override
    public void markRenderSubmitStart() {
    }

    @Override
    public void markRenderSubmitEnd() {
    }

    @Override
    public void markPresentStart() {
    }

    @Override
    public void markPresentEnd() {
    }

    @Override
    public void close() {
    }
}
