package dev.kyresn.mcreflex.api;

/**
 * The most recent per-frame latency report published by the Streamline Reflex
 * plugin, mirroring {@code sl::ReflexReport}.
 *
 * <p>The timestamps are absolute microseconds on the same clock (wall-clock
 * microseconds since the Unix epoch), so only their differences are meaningful.
 *
 * <p>This is the in-application equivalent of the Reflex verification HUD. It is
 * filled in by the driver from the PCL markers, so it proves whether the markers
 * and {@code slReflexSleep} actually reached the driver.
 */
public record ReflexLatencyReport(
        boolean available,
        long frameId,
        long inputSampleTimeUs,
        long simStartTimeUs,
        long simEndTimeUs,
        long renderSubmitStartTimeUs,
        long renderSubmitEndTimeUs,
        long presentStartTimeUs,
        long presentEndTimeUs,
        long driverStartTimeUs,
        long driverEndTimeUs,
        long osRenderQueueStartTimeUs,
        long osRenderQueueEndTimeUs,
        long gpuRenderStartTimeUs,
        long gpuRenderEndTimeUs,
        long gpuActiveRenderTimeUs,
        long gpuFrameTimeUs
) {    public static ReflexLatencyReport unavailable() {
        return new ReflexLatencyReport(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    /** Duration of the simulation phase for this frame. */
    public long simulationUs() {
        return simEndTimeUs - simStartTimeUs;
    }

    /** Duration of the render submission phase for this frame. */
    public long renderSubmitUs() {
        return renderSubmitEndTimeUs - renderSubmitStartTimeUs;
    }

    /** Duration of the present call for this frame. */
    public long presentUs() {
        return presentEndTimeUs - presentStartTimeUs;
    }

    /**
     * Simulation start to end of present, in microseconds. This is the component
     * of PC latency the application controls, and unlike
     * {@link #inputSampleTimeUs()} it is populated on every backend.
     */
    public long simStartToPresentUs() {
        return presentEndTimeUs - simStartTimeUs;
    }

    /**
     * Whether the driver stamped an input sampling time on this frame.
     *
     * <p>Always false on the Vulkan backend, and not a defect. The input sample
     * marker was removed from Streamline ({@code PCLMarker::eInputSample} is
     * commented out in the SDK) and {@code VK_NV_low_latency2} rev 1 has no
     * marker for the latency ping, so {@code inputSampleTimeUs} has no source on
     * this path. The Reflex Test Utility measures input sampling latency out of
     * band, through the {@code ePCLatencyPing} round trip, and reports it
     * separately.
     */
    public boolean hasInputSample() {
        return inputSampleTimeUs != 0;
    }
}
