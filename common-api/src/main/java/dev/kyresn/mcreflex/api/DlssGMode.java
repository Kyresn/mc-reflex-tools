package dev.kyresn.mcreflex.api;

public enum DlssGMode {
    OFF(0),
    ON(1),
    AUTO(2),
    DYNAMIC(3);

    private final int nativeValue;

    DlssGMode(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int getNativeValue() {
        return nativeValue;
    }
}
