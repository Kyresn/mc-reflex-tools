package dev.kyresn.mcreflex.fabric;

import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only M1 trace of Minecraft's real Vulkan submit and present boundaries.
 * No NVIDIA SDK or scheduling behavior is invoked here.
 */
public final class VulkanLifecycleTrace {
    private static final AtomicLong frame = new AtomicLong();
    private static final AtomicLong submitCount = new AtomicLong();
    private static volatile long presentStartNs;

    private VulkanLifecycleTrace() {
    }

    public static void onSimulationStart() {
        frame.incrementAndGet();
    }

    public static void onRenderSubmit() {
        submitCount.incrementAndGet();
    }

    public static void onPresentStart() {
        presentStartNs = System.nanoTime();
    }

    public static void onPresentEnd() {
        long currentFrame = frame.get();
        if (currentFrame == 0 || currentFrame % 300 != 0) {
            return;
        }

        long elapsedNs = System.nanoTime() - presentStartNs;
        int suppressedMarkers = 0;
        if (McReflexToolsFabricClient.getReflexProvider() instanceof NativeReflexProvider nativeProvider) {
            suppressedMarkers = nativeProvider.suppressedMarkerCount();
        }
        McReflexToolsFabricClient.LOGGER.info(
                "Vulkan lifecycle: frame={}, submits={}, presentDurationNs={}, suppressedReflexMarkers={}",
                currentFrame,
                submitCount.getAndSet(0),
                elapsedNs,
                suppressedMarkers
        );
    }
}
