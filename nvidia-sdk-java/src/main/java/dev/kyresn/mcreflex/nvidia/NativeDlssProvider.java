package dev.kyresn.mcreflex.nvidia;

import dev.kyresn.mcreflex.api.DlssMode;
import dev.kyresn.mcreflex.api.DlssOptimalSettings;
import dev.kyresn.mcreflex.api.DlssProvider;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;

public final class NativeDlssProvider implements DlssProvider {
    private boolean initialized;

    @Override
    public NvidiaFeatureStatus initialize(MinecraftVulkanContext context) {
        if (!NativeLibraryLoader.tryLoad()) {
            return NvidiaFeatureStatus.MISSING_NATIVE_LIBRARY;
        }

        int result = nativeDlssInitialize(
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
    public DlssOptimalSettings getOptimalSettings(DlssMode mode, int outputWidth, int outputHeight) {
        if (!initialized) {
            return DlssOptimalSettings.disabled(outputWidth, outputHeight);
        }
        return nativeGetOptimalSettings(mode.getNativeValue(), outputWidth, outputHeight);
    }

    @Override
    public void setOptions(DlssMode mode, int outputWidth, int outputHeight) {
        if (initialized) {
            nativeSetOptions(mode.getNativeValue(), outputWidth, outputHeight);
        }
    }

    @Override
    public void close() {
        if (initialized) {
            nativeDlssShutdown();
            initialized = false;
        }
    }

    private static native int nativeDlssInitialize(long instance, long physicalDevice, long device,
                                                    long graphicsQueue, int queueFamilyIndex, long swapchain);
    private static native DlssOptimalSettings nativeGetOptimalSettings(int mode, int outputWidth, int outputHeight);
    private static native void nativeSetOptions(int mode, int outputWidth, int outputHeight);
    private static native void nativeDlssShutdown();
}
