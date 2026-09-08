package dev.kyresn.mcreflex.fabric;

import dev.kyresn.mcreflex.api.VulkanProbeResult;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class McReflexToolsFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "mc_reflex_tools";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        FabricMinecraftVulkanContextResolver resolver = new FabricMinecraftVulkanContextResolver();
        AtomicBoolean loggedAvailableContext = new AtomicBoolean();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (loggedAvailableContext.get()) {
                return;
            }

            VulkanProbeResult result = resolver.probe();
            if (!result.isAvailable() || !loggedAvailableContext.compareAndSet(false, true)) {
                return;
            }

            LOGGER.info("NVIDIA SDK probe: status={}, renderer={}, detail={}, device=0x{}, queueFamily={}, swapchain=0x{}",
                    result.status(),
                    result.renderer(),
                    result.detail(),
                    Long.toUnsignedString(result.context().device(), 16),
                    result.context().graphicsQueueFamilyIndex(),
                    Long.toUnsignedString(result.context().swapchain(), 16));
        });
    }
}
