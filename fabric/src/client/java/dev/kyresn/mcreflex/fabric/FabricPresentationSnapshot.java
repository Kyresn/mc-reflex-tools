package dev.kyresn.mcreflex.fabric;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuSurface;
import dev.kyresn.mcreflex.api.MinecraftVulkanContext;
import dev.kyresn.mcreflex.api.VulkanProbeResult;
import dev.kyresn.mcreflex.fabric.access.WindowFullscreenAccess;
import net.minecraft.client.Minecraft;

import java.util.Optional;

/** A stable, non-invasive snapshot of the active window and Minecraft-owned Vulkan surface. */
public record FabricPresentationSnapshot(
        boolean requestedFullscreen,
        boolean actualFullscreen,
        boolean exclusiveFullscreen,
        boolean displayAttached,
        String mode,
        String monitor,
        int monitorWidth,
        int monitorHeight,
        int monitorRefreshRate,
        int framebufferWidth,
        int framebufferHeight,
        String presentMode,
        long swapchain
) {
    public static Optional<FabricPresentationSnapshot> capture(VulkanProbeResult probe) {
        if (!probe.isAvailable()) {
            return Optional.empty();
        }

        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        GpuSurface surface = minecraft.windowSurface();
        WindowFullscreenAccess fullscreen = (WindowFullscreenAccess) (Object) window;
        Monitor monitor = window.findBestMonitor();
        VideoMode mode = monitor == null ? null : monitor.currentMode();
        MinecraftVulkanContext context = probe.context();

        boolean requestedFullscreen = window.isFullscreen();
        boolean actualFullscreen = fullscreen.mcReflexTools$isActuallyFullscreen();
        boolean exclusiveFullscreen = fullscreen.mcReflexTools$isExclusiveFullscreen();
        boolean displayAttached = org.lwjgl.glfw.GLFW.glfwGetWindowMonitor(window.handle()) != 0L;
        String presentationMode = requestedFullscreen
                ? (exclusiveFullscreen && displayAttached ? "exclusive-fullscreen" : "borderless-fullscreen")
                : "windowed";

        return Optional.of(new FabricPresentationSnapshot(
                requestedFullscreen,
                actualFullscreen,
                exclusiveFullscreen,
                displayAttached,
                presentationMode,
                monitor == null ? "unavailable" : monitor.monitorName(),
                mode == null ? 0 : mode.getWidth(),
                mode == null ? 0 : mode.getHeight(),
                mode == null ? 0 : mode.getRefreshRate(),
                window.getWidth(),
                window.getHeight(),
                surface.currentConfiguration().map(configuration -> configuration.presentMode().name()).orElse("UNCONFIGURED"),
                context.swapchain()
        ));
    }
}
