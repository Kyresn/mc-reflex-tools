package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NativeBridgeStatus;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.ReflexProvider;

/** JNI boundary for the official NVIDIA implementation. */
public final class NativeReflexProvider implements ReflexProvider {
    private boolean initialized;

    public NativeBridgeStatus bridgeStatus() {
        if (!NativeLibraryLoader.tryLoad()) {
            return NativeBridgeStatus.unavailable("MC Reflex Tools native library could not be loaded");
        }
        return nativeBridgeStatus();
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

    private static native int nativeInitialize(long instance, long physicalDevice, long device,
                                                long graphicsQueue, int queueFamilyIndex, long swapchain);
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
