package dev.kyresn.mcreflex.neoforge;

import dev.kyresn.mcreflex.api.VulkanProbeResult;
import dev.kyresn.mcreflex.vulkan.MinecraftVulkanContextResolver;
import dev.kyresn.mcreflex.vulkan.UnsupportedMinecraftVulkanContextResolver;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = McReflexToolsNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class McReflexToolsNeoForge {
    public static final String MOD_ID = "mc_reflex_tools";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public McReflexToolsNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        MinecraftVulkanContextResolver resolver = new UnsupportedMinecraftVulkanContextResolver();
        VulkanProbeResult result = resolver.probe();
        LOGGER.info("NVIDIA SDK probe: status={}, renderer={}, detail={}",
                result.status(), result.renderer(), result.detail());
    }
}
