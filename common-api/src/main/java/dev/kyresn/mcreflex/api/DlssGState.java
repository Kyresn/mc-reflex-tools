package dev.kyresn.mcreflex.api;

public record DlssGState(
        boolean supported,
        int maxFramesToGenerate,
        boolean dynamicMfgSupported,
        long estimatedVramUsageInBytes
) {
    public static DlssGState unavailable() {
        return new DlssGState(false, 0, false, 0L);
    }
}
