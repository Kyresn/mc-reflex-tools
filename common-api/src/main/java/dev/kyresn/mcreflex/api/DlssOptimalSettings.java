package dev.kyresn.mcreflex.api;

public record DlssOptimalSettings(
        int optimalRenderWidth,
        int optimalRenderHeight,
        float optimalSharpness,
        int renderWidthMin,
        int renderHeightMin,
        int renderWidthMax,
        int renderHeightMax
) {
    public static DlssOptimalSettings disabled(int width, int height) {
        return new DlssOptimalSettings(width, height, 0.0f, width, height, width, height);
    }
}
