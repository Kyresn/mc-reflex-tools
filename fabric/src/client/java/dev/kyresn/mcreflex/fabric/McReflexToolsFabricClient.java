package dev.kyresn.mcreflex.fabric;

import dev.kyresn.mcreflex.api.NativeBridgeStatus;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.ReflexProvider;
import dev.kyresn.mcreflex.api.VulkanProbeResult;
import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;
import dev.kyresn.mcreflex.nvidia.UnavailableReflexProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class McReflexToolsFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "mc_reflex_tools";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile ReflexProvider activeReflexProvider = new UnavailableReflexProvider(NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT);

    public static ReflexProvider getReflexProvider() {
        return activeReflexProvider;
    }

    @Override
    public void onInitializeClient() {
        FabricMinecraftVulkanContextResolver resolver = new FabricMinecraftVulkanContextResolver();
        NativeReflexProvider nativeProvider = new NativeReflexProvider();
        AtomicReference<FabricPresentationSnapshot> lastPresentation = new AtomicReference<>();
        AtomicReference<String> lastUnavailableDetail = new AtomicReference<>();
        AtomicBoolean initializedNative = new AtomicBoolean();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VulkanProbeResult result = resolver.probe();
            if (!result.isAvailable()) {
                String unavailableDetail = result.status() + ":" + result.renderer() + ":" + result.detail();
                if (!unavailableDetail.equals(lastUnavailableDetail.getAndSet(unavailableDetail))) {
                    LOGGER.warn("NVIDIA SDK Vulkan probe unavailable: status={}, renderer={}, detail={}",
                            result.status(), result.renderer(), result.detail());
                }
                activeReflexProvider = new UnavailableReflexProvider(result.status());
                return;
            }
            lastUnavailableDetail.set(null);

            if (!initializedNative.get()) {
                NativeBridgeStatus bridgeStatus = nativeProvider.bridgeStatus();
                LOGGER.info("Native bridge status: libraryLoaded={}, streamlineSdkDetected={}, detail={}",
                        bridgeStatus.libraryLoaded(), bridgeStatus.streamlineSdkDetected(), bridgeStatus.detail());

                if (bridgeStatus.libraryLoaded()) {
                    NvidiaFeatureStatus initStatus = nativeProvider.initialize(result.context());
                    LOGGER.info("NVIDIA Reflex native initialization result: {}", initStatus);
                    if (initStatus == NvidiaFeatureStatus.AVAILABLE) {
                        activeReflexProvider = nativeProvider;
                        initializedNative.set(true);
                    }
                }
            }

            Optional<FabricPresentationSnapshot> captured = FabricPresentationSnapshot.capture(result);
            if (captured.isEmpty()) {
                return;
            }

            FabricPresentationSnapshot presentation = captured.get();
            FabricPresentationSnapshot previous = lastPresentation.getAndSet(presentation);
            if (presentation.equals(previous)) {
                return;
            }

            LOGGER.info(
                    "NVIDIA SDK Vulkan probe: status={}, renderer={}, device=0x{}, queueFamily={}, swapchain=0x{}",
                    result.status(),
                    result.renderer(),
                    Long.toUnsignedString(result.context().device(), 16),
                    result.context().graphicsQueueFamilyIndex(),
                    Long.toUnsignedString(presentation.swapchain(), 16)
            );
            LOGGER.info(
                    "Presentation: mode={}, requestedFullscreen={}, actualFullscreen={}, displayAttached={}, monitor={} {}x{}@{}Hz, framebuffer={}x{}, presentMode={}",
                    presentation.mode(),
                    presentation.requestedFullscreen(),
                    presentation.actualFullscreen(),
                    presentation.displayAttached(),
                    presentation.monitor(),
                    presentation.monitorWidth(),
                    presentation.monitorHeight(),
                    presentation.monitorRefreshRate(),
                    presentation.framebufferWidth(),
                    presentation.framebufferHeight(),
                    presentation.presentMode()
            );
        });
    }
}
