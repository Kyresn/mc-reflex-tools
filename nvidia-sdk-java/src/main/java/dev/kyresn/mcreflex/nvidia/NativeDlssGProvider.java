package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.DlssGMode;
import dev.kyresn.mcreflex.api.DlssGProvider;
import dev.kyresn.mcreflex.api.DlssGState;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;

public final class NativeDlssGProvider implements DlssGProvider {
    private boolean initialized;

    @Override
    public NvidiaFeatureStatus initialize(MinecraftVulkanContext context) {
        if (!NativeLibraryLoader.tryLoad()) {
            return NvidiaFeatureStatus.MISSING_NATIVE_LIBRARY;
        }

        int result = nativeDlssGInitialize(
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

    @Override
    public DlssGState getState() {
        if (!initialized) {
            return DlssGState.unavailable();
        }
        return nativeDlssGGetState();
    }

    @Override
    public void setOptions(DlssGMode mode, int numFramesToGenerate) {
        if (initialized) {
            nativeDlssGSetOptions(mode.getNativeValue(), numFramesToGenerate);
        }
    }

    @Override
    public void close() {
        if (initialized) {
            nativeDlssGShutdown();
            initialized = false;
        }
    }

    private static native int nativeDlssGInitialize(long instance, long physicalDevice, long device,
                                                     long graphicsQueue, int queueFamilyIndex, long swapchain);
    private static native DlssGState nativeDlssGGetState();
    private static native void nativeDlssGSetOptions(int mode, int numFramesToGenerate);
    private static native void nativeDlssGShutdown();
}
