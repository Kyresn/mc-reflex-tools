package dev.kyresn.mcreflex.api;

public enum DlssMode {
    OFF(0),
    MAX_PERFORMANCE(1),
    BALANCED(2),
    MAX_QUALITY(3),
    ULTRA_PERFORMANCE(4),
    ULTRA_QUALITY(5),
    DLAA(6);

    private final int nativeValue;

    DlssMode(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int getNativeValue() {
        return nativeValue;
    }
}
