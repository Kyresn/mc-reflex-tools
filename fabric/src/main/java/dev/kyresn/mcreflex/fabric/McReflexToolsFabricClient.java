package dev.kyresn.mcreflex.fabric;

import dev.kyresn.mcreflex.api.VulkanProbeResult;
import dev.kyresn.mcreflex.vulkan.MinecraftVulkanContextResolver;
import dev.kyresn.mcreflex.vulkan.UnsupportedMinecraftVulkanContextResolver;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McReflexToolsFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "mc_reflex_tools";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        MinecraftVulkanContextResolver resolver = new UnsupportedMinecraftVulkanContextResolver();
        VulkanProbeResult result = resolver.probe();
        LOGGER.info("NVIDIA SDK probe: status={}, renderer={}, detail={}",
                result.status(), result.renderer(), result.detail());
    }
}
