package dev.kyresn.mcreflex.nvidia;

final class NativeLibraryLoader {
    private static boolean attempted;
    private static boolean loaded;

    private NativeLibraryLoader() {
    }

    static synchronized boolean tryLoad() {
        if (attempted) {
            return loaded;
        }

        attempted = true;
        try {
            System.loadLibrary("mc_reflex_tools_native");
            loaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            loaded = false;
        }
        return loaded;
    }
}
