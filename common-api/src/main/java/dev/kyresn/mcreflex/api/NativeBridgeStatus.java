package dev.kyresn.mcreflex.api;

/** Reports the load and readiness state of the optional official native bridge. */
public record NativeBridgeStatus(
        boolean libraryLoaded,
        boolean streamlineSdkDetected,
        String detail
) {
    public static NativeBridgeStatus unavailable(String detail) {
        return new NativeBridgeStatus(false, false, detail);
    }
}
