package dev.kyresn.mcreflex.api;

/** Explains why an NVIDIA SDK feature can or cannot be activated. */
public enum NvidiaFeatureStatus {
    AVAILABLE,
    DISABLED_BY_USER,
    UNSUPPORTED_PLATFORM,
    UNSUPPORTED_RENDERER,
    MISSING_VULKAN_CONTEXT,
    UNSUPPORTED_GPU,
    UNSUPPORTED_DRIVER,
    MISSING_NATIVE_LIBRARY,
    SDK_INITIALIZATION_FAILED,
    INCOMPATIBLE_MOD
}
