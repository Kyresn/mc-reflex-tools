package dev.kyresn.mcreflex.nvidia;

final class NativeLibraryLoader {
    private static boolean attempted;
    private static boolean loaded;

    private NativeLibraryLoader() {
    }

    private static String loadErrorDetail = "";

    static synchronized String getLoadErrorDetail() {
        return loadErrorDetail;
    }

    static synchronized boolean tryLoad() {
        if (attempted) {
            return loaded;
        }

        try {
            System.loadLibrary("mc_reflex_tools_native");
            loaded = true;
        } catch (UnsatisfiedLinkError e1) {
            loadErrorDetail = "loadLibrary failed: " + e1.getMessage();
            try {
                String userDir = System.getProperty("user.dir");
                java.io.File dir = new java.io.File(userDir);
                java.io.File found = null;
                for (int i = 0; i < 4 && dir != null; i++) {
                    java.io.File candidate = new java.io.File(dir, "nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll");
                    if (candidate.exists()) {
                        found = candidate;
                        break;
                    }
                    dir = dir.getParentFile();
                }

                if (found != null && found.exists()) {
                    // Pre-load Streamline interposer dependency
                    String streamlineRoot = System.getenv("NVIDIA_STREAMLINE_ROOT");
                    java.io.File interposerDll = null;
                    if (streamlineRoot != null && !streamlineRoot.isEmpty()) {
                        interposerDll = new java.io.File(streamlineRoot, "bin/x64/sl.interposer.dll");
                    }
                    if (interposerDll == null || !interposerDll.exists()) {
                        interposerDll = new java.io.File("<path-to-streamline-sdk-v2.14.1>/bin/x64/sl.interposer.dll");
                    }
                    if (interposerDll.exists()) {
                        try {
                            System.load(interposerDll.getCanonicalPath());
                        } catch (Throwable ignored) {}
                    }

                    System.load(found.getCanonicalPath());
                    loaded = true;
                } else {
                    loaded = false;
                    loadErrorDetail += " | candidate null or not found (userDir=" + userDir + ")";
                }
            } catch (Throwable e2) {
                loaded = false;
                loadErrorDetail += " | load exception: " + e2.toString();
            }
        }
        attempted = true;
        return loaded;
    }
}
