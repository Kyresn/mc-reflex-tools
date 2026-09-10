package dev.kyresn.mcreflex.nvidia;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

final class NativeLibraryLoader {
    private static boolean attempted;
    private static boolean loaded;
    private static String loadErrorDetail = "";

    private NativeLibraryLoader() {
    }

    static synchronized String getLoadErrorDetail() {
        return loadErrorDetail;
    }

    static synchronized boolean tryLoad() {
        if (attempted) {
            return loaded;
        }

        // 1. Try extracting and loading embedded DLL from classpath resources
        if (tryExtractAndLoadResource()) {
            loaded = true;
            attempted = true;
            return true;
        }

        // 2. Try development workspace relative paths
        if (tryLoadFromDevWorkspace()) {
            loaded = true;
            attempted = true;
            return true;
        }

        // 3. Fallback to standard java.library.path
        try {
            System.loadLibrary("mc_reflex_tools_native");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loadErrorDetail += " | System.loadLibrary fallback failed: " + e.getMessage();
            loaded = false;
        }

        attempted = true;
        return loaded;
    }

    private static boolean tryExtractAndLoadResource() {
        String resourcePath = "/natives/windows-x64/mc_reflex_tools_native.dll";
        try (InputStream stream = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                loadErrorDetail = "Embedded resource not found: " + resourcePath;
                return false;
            }

            File tempDir = new File(System.getProperty("java.io.tmpdir"), "mc_reflex_tools");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            File extractedFile = new File(tempDir, "mc_reflex_tools_native.dll");
            try (OutputStream out = new FileOutputStream(extractedFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            preloadStreamlineDependency();
            System.load(extractedFile.getCanonicalPath());
            return true;
        } catch (Throwable e) {
            loadErrorDetail = "Resource extraction load failed: " + e.toString();
            return false;
        }
    }

    private static boolean tryLoadFromDevWorkspace() {
        try {
            String userDir = System.getProperty("user.dir");
            File dir = new File(userDir);
            File found = null;
            for (int i = 0; i < 4 && dir != null; i++) {
                File candidate = new File(dir, "nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll");
                if (candidate.exists()) {
                    found = candidate;
                    break;
                }
                dir = dir.getParentFile();
            }

            if (found != null && found.exists()) {
                preloadStreamlineDependency();
                System.load(found.getCanonicalPath());
                return true;
            }
        } catch (Throwable e) {
            loadErrorDetail += " | Dev workspace load failed: " + e.toString();
        }
        return false;
    }

    private static void preloadStreamlineDependency() {
        String streamlineRoot = System.getenv("NVIDIA_STREAMLINE_ROOT");
        File interposerDll = null;
        if (streamlineRoot != null && !streamlineRoot.isEmpty()) {
            interposerDll = new File(streamlineRoot, "bin/x64/sl.interposer.dll");
        }
        if (interposerDll == null || !interposerDll.exists()) {
            interposerDll = new File("<path-to-streamline-sdk-v2.14.1>/bin/x64/sl.interposer.dll");
        }
        if (interposerDll.exists()) {
            try {
                System.load(interposerDll.getCanonicalPath());
            } catch (Throwable ignored) {
            }
        }
    }
}
