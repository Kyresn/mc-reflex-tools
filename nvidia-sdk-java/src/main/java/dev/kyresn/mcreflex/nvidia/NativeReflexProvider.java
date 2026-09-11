package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NativeBridgeStatus;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.ReflexLatencyReport;
import dev.kyresn.mcreflex.api.ReflexMode;
import dev.kyresn.mcreflex.api.ReflexProvider;
import dev.kyresn.mcreflex.api.ReflexState;

import java.util.List;

/** JNI boundary for the official NVIDIA implementation. */
public final class NativeReflexProvider implements ReflexProvider {
    private boolean initialized;

    public NativeBridgeStatus bridgeStatus() {
        if (!NativeLibraryLoader.tryLoad()) {
            return NativeBridgeStatus.unavailable("MC Reflex Tools native library could not be loaded: " + NativeLibraryLoader.getLoadErrorDetail());
        }
        return nativeBridgeStatus();
    }

    /**
     * Initializes the Streamline SDK only. Call this before Minecraft creates its
     * graphics device; the device is registered later by [initialize].
     */
    public NvidiaFeatureStatus initializeSdk() {
        if (!NativeLibraryLoader.tryLoad()) {
            return NvidiaFeatureStatus.MISSING_NATIVE_LIBRARY;
        }
        int result = nativeInitSdk();
        return result == 0 ? NvidiaFeatureStatus.AVAILABLE : NvidiaFeatureStatus.SDK_INITIALIZATION_FAILED;
    }

    @Override
    public NvidiaFeatureStatus initialize(MinecraftVulkanContext context) {
        if (!NativeLibraryLoader.tryLoad()) {
            return NvidiaFeatureStatus.MISSING_NATIVE_LIBRARY;
        }

        int result = nativeInitialize(
                context.instance(),
                context.physicalDevice(),
                context.device(),
                context.graphicsQueue(),
                context.graphicsQueueFamilyIndex(),
                context.swapchain()
        );
        initialized = result == 0;
        return initialized ? NvidiaFeatureStatus.AVAILABLE : NvidiaFeatureStatus.SDK_INITIALIZATION_FAILED;
    }

    /** Returns the live Reflex plugin state; only meaningful after a successful initialize(). */
    public ReflexState getReflexState() {
        if (!initialized) {
            return ReflexState.unavailable();
        }
        return nativeGetReflexState();
    }

    /** Number of extra RenderSubmit markers dropped by the per-frame dedupe. */
    public int suppressedMarkerCount() {
        if (!initialized) {
            return 0;
        }
        return nativeGetSuppressedMarkerCount();
    }

    /** Number of markers that arrived out of the expected per-frame order. */
    public int markerOrderViolationCount() {
        if (!initialized) {
            return 0;
        }
        return nativeGetMarkerOrderViolationCount();
    }

    /** Number of markers dropped because no frame token was available. */
    public int staleMarkerCount() {
        if (!initialized) {
            return 0;
        }
        return nativeGetStaleMarkerCount();
    }

    /** Number of slReflexSleep calls; should advance by one per rendered frame. */
    public int sleepCount() {
        if (!initialized) {
            return 0;
        }
        return nativeGetSleepCount();
    }

    /**
     * Answers the driver's periodic latency ping so it can measure input sampling
     * latency. {@code windowHandle} is the GLFW window handle; measured latency
     * without this stays incomplete.
     *
     * @return true when the window was hooked and the ping marker is being sent
     */
    public boolean installPclPingHook(long windowHandle) {
        if (!initialized) {
            return false;
        }
        return nativeInstallPclPingHook(windowHandle) == 0;
    }

    /** Number of ePCLatencyPing markers sent in answer to the driver's message. */
    public int pclPingCount() {
        if (!initialized) {
            return 0;
        }
        return nativeGetPclPingCount();
    }

    /** Number of pings seen with no frame token, so they could not be attributed. */
    public int pclPingMissedCount() {
        if (!initialized) {
            return 0;
        }
        return nativeGetPclPingMissedCount();
    }

    /**
     * Returns and clears any Streamline plugin messages queued since the last
     * call, oldest first. Empty when nothing new was reported.
     */
    public String drainSdkMessages() {
        if (!NativeLibraryLoader.tryLoad()) {
            return "";
        }
        return nativeDrainSdkMessages();
    }

    /** Latest driver-published latency report, or an unavailable placeholder. */
    public ReflexLatencyReport getLatencyReport() {
        if (!initialized) {
            return ReflexLatencyReport.unavailable();
        }
        ReflexLatencyReport report = nativeGetLatencyReport();
        return report != null ? report : ReflexLatencyReport.unavailable();
    }

    /** Returns the last non-OK Streamline result code as a string, for diagnostics. */
    public String lastSdkError() {
        if (!NativeLibraryLoader.tryLoad()) {
            return "native library not loaded";
        }
        return nativeLastSdkError();
    }

    @Override
    public void setOptions(ReflexMode mode, int frameLimitFps) {
        if (initialized) {
            nativeSetReflexOptions(mode.getNativeValue(), frameLimitFps);
        }
    }

    @Override public void sleep() { if (initialized) nativeSleep(); }
    @Override public void markSimulationStart() { if (initialized) nativeMarker(Marker.SIMULATION_START); }
    @Override public void markSimulationEnd() { if (initialized) nativeMarker(Marker.SIMULATION_END); }
    @Override public void markRenderSubmitStart() { if (initialized) nativeMarker(Marker.RENDER_SUBMIT_START); }
    @Override public void markRenderSubmitEnd() { if (initialized) nativeMarker(Marker.RENDER_SUBMIT_END); }
    @Override public void markPresentStart() { if (initialized) nativeMarker(Marker.PRESENT_START); }
    @Override public void markPresentEnd() { if (initialized) nativeMarker(Marker.PRESENT_END); }

    @Override
    public void close() {
        if (initialized) {
            nativeShutdown();
            initialized = false;
        }
    }

    /**
     * Opt-in switch for injecting Streamline's Vulkan device extensions into Minecraft's device.
     * Off by default - see {@link #requiredDeviceExtensions()}.
     */
    public static final String ENABLE_VULKAN_LL2_PROPERTY = "mc_reflex_tools.vulkanLowLatency2";

    /**
     * Vulkan device extensions the loaded Streamline features require on the host device,
     * as reported by {@code slGetFeatureRequirements}.
     *
     * <p>Streamline enables these itself when an application creates its device through the
     * {@code sl.interposer} proxies. Minecraft creates its own {@code VkDevice} directly, so
     * the host is responsible for them - see the SDK's {@code ProgrammingGuideManualHooking.md},
     * "Instance and device additions". Without {@code VK_NV_low_latency2} the Reflex LL2
     * backend fails to initialize with {@code eNoImplementation} and the plugin falls back to
     * its NvAPI path.
     *
     * <p><b>Opt-in, and it is not an improvement.</b> Measured on the reference system,
     * injecting the extensions makes Reflex strictly worse: the LL2 backend initializes, but
     * the driver then stops publishing frame reports and stops sending its latency ping
     * ({@code pclPings} 250/session to 0), so PC latency measurement is lost. Recreating the
     * swapchain mid-run does not recover it. The cause is a startup race - Streamline installs
     * its Vulkan swapchain hooks asynchronously about three seconds after {@code slInit}
     * returns, by which time Minecraft has already created its device and its only swapchain,
     * so the LL2 backend never sees a swapchain it can drive. The NvAPI fallback that runs
     * when the extension is absent does not depend on that handle and works end to end.
     *
     * <p>Set {@code -Dmc_reflex_tools.vulkanLowLatency2=true} to opt in and re-measure. Must be
     * resolved before Minecraft calls {@code vkCreateDevice}; returns an empty list when the
     * native runtime is unavailable, so a missing SDK degrades to vanilla device creation.
     */
    public static List<String> requiredDeviceExtensions() {
        if (!Boolean.getBoolean(ENABLE_VULKAN_LL2_PROPERTY)) {
            return List.of();
        }
        if (!NativeLibraryLoader.tryLoad()) {
            return List.of();
        }
        String[] names = nativeGetRequiredDeviceExtensions();
        return names == null || names.length == 0 ? List.of() : List.of(names);
    }

    private static native int nativeInitSdk();
    private static native String[] nativeGetRequiredDeviceExtensions();
    private static native int nativeInitialize(long instance, long physicalDevice, long device,
                                                long graphicsQueue, int queueFamilyIndex, long swapchain);
    private static native void nativeSetReflexOptions(int mode, int frameLimitFps);
    private static native ReflexState nativeGetReflexState();
    private static native ReflexLatencyReport nativeGetLatencyReport();
    private static native int nativeGetSuppressedMarkerCount();
    private static native int nativeGetMarkerOrderViolationCount();
    private static native int nativeGetStaleMarkerCount();
    private static native int nativeGetSleepCount();
    private static native int nativeInstallPclPingHook(long windowHandle);
    private static native int nativeGetPclPingCount();
    private static native int nativeGetPclPingMissedCount();
    private static native String nativeDrainSdkMessages();
    private static native String nativeLastSdkError();
    private static native NativeBridgeStatus nativeBridgeStatus();
    private static native void nativeSleep();
    private static native void nativeMarker(int marker);
    private static native void nativeShutdown();

    private static final class Marker {
        private static final int SIMULATION_START = 1;
        private static final int SIMULATION_END = 2;
        private static final int RENDER_SUBMIT_START = 3;
        private static final int RENDER_SUBMIT_END = 4;
        private static final int PRESENT_START = 5;
        private static final int PRESENT_END = 6;
    }
}
