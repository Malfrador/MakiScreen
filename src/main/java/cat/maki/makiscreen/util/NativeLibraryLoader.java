package cat.maki.makiscreen.util;

import java.io.*;
import java.nio.file.*;

/**
 * Automatically extracts and loads native libraries bundled in the plugin JAR.
 * No JVM flags required - works out of the box!
 */
public class NativeLibraryLoader {

    private static final String LIB_NAME = "makiscreen_native";
    private static boolean loaded = false;
    private static Throwable loadError = null;

    /**
     * Load the native library. Safe to call multiple times.
     *
     * @return true if loaded successfully, false otherwise
     */
    public static synchronized boolean loadNativeLibrary() {
        if (loaded) {
            return true;
        }

        if (loadError != null) {
            return false; // Already tried and failed
        }

        try {
            // Detect OS and architecture
            String os = detectOS();
            String arch = detectArchitecture();

            if (os == null || arch == null) {
                throw new UnsupportedOperationException("Unsupported platform: " +
                    System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            }

            // Determine library file name
            String libFileName = getLibraryFileName(os);

            // Path inside JAR: /native/<os>/<arch>/libname
            String resourcePath = "/native/" + os + "/" + arch + "/" + libFileName;

            // Try to load from JAR
            InputStream libStream = NativeLibraryLoader.class.getResourceAsStream(resourcePath);

            if (libStream == null) {
                // Library not bundled, try system library path as fallback
                System.out.println("[MakiScreen] Native library not bundled in JAR, trying system library path...");
                System.loadLibrary(LIB_NAME);
                loaded = true;
                return true;
            }

            // Extract to temp directory
            Path tempDir = Files.createTempDirectory("makiscreen_native_");
            tempDir.toFile().deleteOnExit();

            Path libFile = tempDir.resolve(libFileName);
            libFile.toFile().deleteOnExit();

            // Copy from JAR to temp file
            Files.copy(libStream, libFile, StandardCopyOption.REPLACE_EXISTING);
            libStream.close();

            // Make executable (Linux/macOS)
            libFile.toFile().setExecutable(true);

            // Load the library
            System.load(libFile.toAbsolutePath().toString());

            loaded = true;
            System.out.println("[MakiScreen] Native library loaded from: " + libFile);
            return true;

        } catch (UnsatisfiedLinkError e) {
            loadError = e;
            System.err.println("[MakiScreen] Failed to load native library: " + e.getMessage());
            System.err.println("[MakiScreen] Falling back to pure Java implementation");
            return false;
        } catch (Exception e) {
            loadError = e;
            System.err.println("[MakiScreen] Error loading native library: " + e.getMessage());
            return false;
        }
    }

    /**
     * Detect operating system
     * @return "windows", "linux", "macos", or null if unsupported
     */
    private static String detectOS() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("nux") || osName.contains("nix")) {
            return "linux";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return "macos";
        }

        return null;
    }

    /**
     * Detect CPU architecture
     * @return "x86_64", "aarch64", "x86", or null if unsupported
     */
    private static String detectArchitecture() {
        String osArch = System.getProperty("os.arch").toLowerCase();

        // Normalize architecture names
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            return "x86_64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            return "aarch64";
        } else if (osArch.contains("x86") || osArch.contains("i386") || osArch.contains("i686")) {
            return "x86";
        }

        return null;
    }

    /**
     * Get platform-specific library file name
     */
    private static String getLibraryFileName(String os) {
        switch (os) {
            case "windows":
                return LIB_NAME + ".dll";
            case "linux":
                return "lib" + LIB_NAME + ".so";
            case "macos":
                return "lib" + LIB_NAME + ".dylib";
            default:
                throw new IllegalArgumentException("Unknown OS: " + os);
        }
    }

    /**
     * Check if native library is loaded
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Get the error that occurred during loading, if any
     */
    public static Throwable getLoadError() {
        return loadError;
    }
}

