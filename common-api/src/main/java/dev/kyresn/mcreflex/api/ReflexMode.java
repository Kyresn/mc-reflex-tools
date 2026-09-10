package dev.kyresn.mcreflex.api;

public enum ReflexMode {
    OFF(0),
    ON(1),
    ON_PLUS_BOOST(2);

    private final int nativeValue;

    ReflexMode(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int getNativeValue() {
        return nativeValue;
    }
}
