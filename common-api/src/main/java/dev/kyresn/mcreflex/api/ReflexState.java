package dev.kyresn.mcreflex.api;

/** Result of querying the Streamline Reflex plugin state. */
public record ReflexState(
        boolean lowLatencyAvailable,
        boolean flashIndicatorDriverControlled
) {
    public static ReflexState unavailable() {
        return new ReflexState(false, false);
    }
}
