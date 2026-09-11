package dev.kyresn.mcreflex.fabric;

import dev.kyresn.mcreflex.api.ReflexLatencyReport;
import dev.kyresn.mcreflex.fabric.config.ModConfig;
import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only trace of Minecraft's real Vulkan submit and present boundaries, plus
 * the Reflex marker counters reported by the native bridge. Used as evidence that
 * the Reflex/PCL integration behaves as the SDK contract requires.
 */
public final class VulkanLifecycleTrace {
    /** Frames between diagnostic dumps; tightened when debug mode is on. */
    private static final long REPORT_INTERVAL_FRAMES = 300;
    private static final long DEBUG_REPORT_INTERVAL_FRAMES = 30;

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
        long interval = ModConfig.get().debug ? DEBUG_REPORT_INTERVAL_FRAMES : REPORT_INTERVAL_FRAMES;
        long currentFrame = frame.get();
        if (currentFrame == 0 || currentFrame % interval != 0) {
            return;
        }

        long elapsedNs = System.nanoTime() - presentStartNs;
        long submits = submitCount.getAndSet(0);

        if (!(McReflexToolsFabricClient.getReflexProvider() instanceof NativeReflexProvider provider)) {
            McReflexToolsFabricClient.LOGGER.info(
                    "Vulkan lifecycle: frame={}, submits={}, presentDurationNs={}, reflex=inactive",
                    currentFrame, submits, elapsedNs);
            return;
        }

        int sleepCount = provider.sleepCount();
        McReflexToolsFabricClient.LOGGER.info(
                "Vulkan lifecycle: frame={}, submits={}, presentDurationNs={}, sleeps={}, pclPings={}, "
                        + "pclPingsMissed={}, suppressedRenderSubmit={}, orderViolations={}, staleMarkers={}",
                currentFrame,
                submits,
                elapsedNs,
                sleepCount,
                provider.pclPingCount(),
                provider.pclPingMissedCount(),
                provider.suppressedMarkerCount(),
                provider.markerOrderViolationCount(),
                provider.staleMarkerCount()
        );

        ReflexLatencyReport report = provider.getLatencyReport();
        if (!report.available()) {
            McReflexToolsFabricClient.LOGGER.warn(
                    "Reflex latency report unavailable: the driver has not published a frame report yet. "
                            + "Marker order violations={}, stale markers={}, last SDK error={}",
                    provider.markerOrderViolationCount(),
                    provider.staleMarkerCount(),
                    provider.lastSdkError());
            return;
        }

        // inputSampleTime is always zero on Vulkan: Streamline removed the input
        // sample marker and VK_NV_low_latency2 has no marker for the latency ping.
        // The ping round trip is the measurable signal for input sampling latency.
        McReflexToolsFabricClient.LOGGER.info(
                "Reflex latency report: frameId={}, simStartToPresentUs={}, simUs={}, renderSubmitUs={}, "
                        + "presentUs={}, gpuActiveUs={}, gpuFrameUs={}",
                report.frameId(),
                report.simStartToPresentUs(),
                report.simulationUs(),
                report.renderSubmitUs(),
                report.presentUs(),
                report.gpuActiveRenderTimeUs(),
                report.gpuFrameTimeUs()
        );
    }
}
