package dev.kyresn.mcreflex.api;

/** Loader-specific mapping of Minecraft's real input, submit, and present lifecycle. */
public interface RenderLifecycleAdapter {
    void install();

    void uninstall();
}
