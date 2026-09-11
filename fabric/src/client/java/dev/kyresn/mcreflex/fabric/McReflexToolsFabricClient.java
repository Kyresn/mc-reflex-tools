package dev.kyresn.mcreflex.fabric;

import dev.kyresn.mcreflex.api.DlssGProvider;
import dev.kyresn.mcreflex.api.DlssGState;
import dev.kyresn.mcreflex.api.DlssOptimalSettings;
import dev.kyresn.mcreflex.api.DlssProvider;
import dev.kyresn.mcreflex.api.NativeBridgeStatus;
import dev.kyresn.mcreflex.api.NvidiaFeatureStatus;
import dev.kyresn.mcreflex.api.ReflexProvider;
import dev.kyresn.mcreflex.api.VulkanProbeResult;
import dev.kyresn.mcreflex.fabric.config.ModConfig;
import dev.kyresn.mcreflex.nvidia.NativeDlssGProvider;
import dev.kyresn.mcreflex.nvidia.NativeDlssProvider;
import dev.kyresn.mcreflex.nvidia.NativeReflexProvider;
import dev.kyresn.mcreflex.nvidia.UnavailableDlssGProvider;
import dev.kyresn.mcreflex.nvidia.UnavailableDlssProvider;
import dev.kyresn.mcreflex.nvidia.UnavailableReflexProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class McReflexToolsFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "mc_reflex_tools";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile ReflexProvider activeReflexProvider = new UnavailableReflexProvider(NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT);
    private static volatile DlssProvider activeDlssProvider = new UnavailableDlssProvider(NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT);
    private static volatile DlssGProvider activeDlssGProvider = new UnavailableDlssGProvider(NvidiaFeatureStatus.MISSING_VULKAN_CONTEXT);

    public static ReflexProvider getReflexProvider() {
        return activeReflexProvider;
    }

    public static DlssProvider getDlssProvider() {
        return activeDlssProvider;
    }

    public static DlssGProvider getDlssGProvider() {
        return activeDlssGProvider;
    }

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        FabricMinecraftVulkanContextResolver resolver = new FabricMinecraftVulkanContextResolver();
        NativeReflexProvider nativeReflex = new NativeReflexProvider();
        NativeDlssProvider nativeDlss = new NativeDlssProvider();
        NativeDlssGProvider nativeDlssG = new NativeDlssGProvider();

        // Initialize the Streamline SDK as early as possible, before Minecraft's
        // Vulkan device is used in earnest. The device itself is registered later,
        // once the client reports an eligible Vulkan context.
        NvidiaFeatureStatus sdkStatus = nativeReflex.initializeSdk();
        LOGGER.info("Streamline SDK early initialization result: {}", sdkStatus);
        AtomicReference<FabricPresentationSnapshot> lastPresentation = new AtomicReference<>();
        AtomicReference<String> lastUnavailableDetail = new AtomicReference<>();
        AtomicReference<String> lastAppliedReflexOptions = new AtomicReference<>();
        AtomicBoolean initializedNative = new AtomicBoolean();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            logPendingSdkMessages(nativeReflex);

            VulkanProbeResult result = resolver.probe();
            if (!result.isAvailable()) {
                String unavailableDetail = result.status() + ":" + result.renderer() + ":" + result.detail();
                if (!unavailableDetail.equals(lastUnavailableDetail.getAndSet(unavailableDetail))) {
                    LOGGER.warn("NVIDIA SDK Vulkan probe unavailable: status={}, renderer={}, detail={}",
                            result.status(), result.renderer(), result.detail());
                }
                activeReflexProvider = new UnavailableReflexProvider(result.status());
                activeDlssProvider = new UnavailableDlssProvider(result.status());
                activeDlssGProvider = new UnavailableDlssGProvider(result.status());
                return;
            }
            lastUnavailableDetail.set(null);

            if (!initializedNative.get()) {
                NativeBridgeStatus bridgeStatus = nativeReflex.bridgeStatus();
                LOGGER.info("Native bridge status: libraryLoaded={}, streamlineSdkDetected={}, detail={}",
                        bridgeStatus.libraryLoaded(), bridgeStatus.streamlineSdkDetected(), bridgeStatus.detail());

                if (bridgeStatus.libraryLoaded()) {
                    NvidiaFeatureStatus reflexStatus = nativeReflex.initialize(result.context());
                    LOGGER.info("NVIDIA Reflex native initialization result: {}", reflexStatus);
                    if (reflexStatus == NvidiaFeatureStatus.AVAILABLE) {
                        activeReflexProvider = nativeReflex;
                        dev.kyresn.mcreflex.api.ReflexState reflexState = nativeReflex.getReflexState();
                        LOGGER.info("NVIDIA Reflex state: lowLatencyAvailable={}, flashIndicatorDriverControlled={}",
                                reflexState.lowLatencyAvailable(), reflexState.flashIndicatorDriverControlled());
                        installPclPingHook(client, nativeReflex);
                    } else {
                        LOGGER.warn("NVIDIA Reflex failed to initialize, SDK error: {}", nativeReflex.lastSdkError());
                    }

                    NvidiaFeatureStatus dlssStatus = nativeDlss.initialize(result.context());
                    LOGGER.info("NVIDIA DLSS native initialization result: {}", dlssStatus);
                    if (dlssStatus == NvidiaFeatureStatus.AVAILABLE) {
                        activeDlssProvider = nativeDlss;
                        DlssOptimalSettings settings = nativeDlss.getOptimalSettings(ModConfig.get().dlssMode, 1920, 1080);
                        LOGGER.info("NVIDIA DLSS Settings (1080p target): optimalRender={}x{}, minRender={}x{}, maxRender={}x{}",
                                settings.optimalRenderWidth(), settings.optimalRenderHeight(),
                                settings.renderWidthMin(), settings.renderHeightMin(),
                                settings.renderWidthMax(), settings.renderHeightMax());
                    }

                    NvidiaFeatureStatus dlssGStatus = nativeDlssG.initialize(result.context());
                    LOGGER.info("NVIDIA DLSS-G (Frame Generation) native initialization result: {}", dlssGStatus);
                    if (dlssGStatus == NvidiaFeatureStatus.AVAILABLE) {
                        activeDlssGProvider = nativeDlssG;
                        DlssGState gState = nativeDlssG.getState();
                        LOGGER.info("NVIDIA DLSS-G State: supported={}, maxFramesToGenerate={}, dynamicMfgSupported={}, estimatedVRAMUsage={} bytes",
                                gState.supported(), gState.maxFramesToGenerate(),
                                gState.dynamicMfgSupported(), gState.estimatedVramUsageInBytes());
                    }

                    initializedNative.set(true);
                }
            }

            Optional<FabricPresentationSnapshot> captured = FabricPresentationSnapshot.capture(result);
            FabricPresentationSnapshot presentation = captured.orElse(null);
            FabricPresentationSnapshot previous = lastPresentation.getAndSet(presentation);
            boolean presentationChanged = presentation != null && !presentation.equals(previous);

            // Reflex options are applied whenever the desired configuration changes, and
            // again after every swapchain (re)creation. Streamline's LL2 backend only
            // forwards latency mode to the driver through the swapchain handle it last saw
            // created, and silently no-ops while it has none, so the driver stays unengaged
            // until an options call follows a swapchain creation. Minecraft's first
            // swapchain is created before the SDK's swapchain hooks are installed, which is
            // exactly that case. The SDK also requires slReflexSetOptions to be called at
            // least once even when Reflex is off, and again after a runtime option change.
            if (activeReflexProvider instanceof NativeReflexProvider) {
                ModConfig config = ModConfig.get();
                int targetLimitFps = config.customFrameLimitFps;
                String desiredOptions = config.reflexMode + "@" + targetLimitFps;
                boolean optionsChanged = !desiredOptions.equals(lastAppliedReflexOptions.get());
                if (optionsChanged || presentationChanged) {
                    lastAppliedReflexOptions.set(desiredOptions);
                    activeReflexProvider.setOptions(config.reflexMode, targetLimitFps);
                    LOGGER.info(
                            "Applied Reflex options: mode={}, frameLimitFps={}, swapchain=0x{}, reason={}",
                            config.reflexMode,
                            targetLimitFps,
                            presentation == null ? "none" : Long.toUnsignedString(presentation.swapchain(), 16),
                            optionsChanged ? "options" : "swapchain");
                }
            }

            if (!presentationChanged) {
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

    /**
     * Subscribes to the driver's periodic latency ping.
     *
     * <p>The driver measures input sampling latency by posting a message to the
     * game window, which the application has to answer with an
     * {@code ePCLatencyPing} marker. Minecraft leaves the window procedure to
     * GLFW, so the native bridge subclasses it. Without this the input sampling
     * component of PC latency is never measured and Reflex reports 0.0.
     */
    private static void installPclPingHook(Minecraft client, NativeReflexProvider provider) {
        long glfwWindow = client.getWindow().handle();
        long win32Window = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        if (win32Window == 0L) {
            LOGGER.warn("PCL latency ping hook skipped: no Win32 window for GLFW handle 0x{}",
                    Long.toHexString(glfwWindow));
            return;
        }

        if (provider.installPclPingHook(win32Window)) {
            LOGGER.info("PCL latency ping hook installed on window 0x{}", Long.toHexString(win32Window));
        } else {
            LOGGER.warn("PCL latency ping hook not installed: the driver is not publishing a ping message yet. "
                    + "Input sampling latency will stay unmeasured until Reflex measurement starts.");
        }
    }

    /**
     * Forwards warnings and errors the Streamline plugins reported on their own
     * threads into the game log. Without this a plugin that silently declines to
     * engage is invisible.
     */
    private static void logPendingSdkMessages(NativeReflexProvider provider) {
        String messages = provider.drainSdkMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (String line : messages.split("\n")) {
            if (!line.isBlank()) {
                LOGGER.info("{}", line);
            }
        }
    }
}
