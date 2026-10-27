package de.erethon.mccinema.download;

import de.erethon.mccinema.MCCinema;
import org.bytedeco.javacpp.Loader;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FfmpegProvider {

    private final MCCinema plugin;
    private String executable;
    private String location;
    private Map<String, String> environment = Map.of();

    FfmpegProvider(MCCinema plugin) {
        this.plugin = plugin;
    }

    void detect() {
        String platform = Loader.getPlatform();
        String executableName = platform.startsWith("windows") ? "ffmpeg.exe" : "ffmpeg";
        try {
            File[] resources = Loader.cacheResources("org/bytedeco/ffmpeg/" + platform + "/");
            File bundled = findExecutable(resources, executableName);
            if (bundled != null) {
                if (!platform.startsWith("windows")) {
                    bundled.setExecutable(true, true);
                }
                Map<String, String> nativeEnvironment = nativeLibraryEnvironment(platform, bundled.getParentFile());
                ExternalProcess.Result result = ExternalProcess.run(
                    List.of(bundled.getAbsolutePath(), "-version"), Duration.ofSeconds(10), nativeEnvironment
                );
                if (result.successful()) {
                    executable = bundled.getAbsolutePath();
                    location = bundled.getParentFile().getAbsolutePath();
                    environment = nativeEnvironment;
                    plugin.getLogger().info("Using bundled JavaCV ffmpeg executable");
                    return;
                }
                plugin.getLogger().warning("Bundled ffmpeg health check failed: " + firstLine(result.output()));
            }
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not prepare bundled ffmpeg: " + concise(error));
        }

        try {
            ExternalProcess.Result system = ExternalProcess.run(
                List.of(executableName, "-version"), Duration.ofSeconds(10)
            );
            if (system.successful()) {
                executable = executableName;
                location = null;
                environment = Map.of();
                plugin.getLogger().info("Using ffmpeg from PATH");
                return;
            }
        } catch (Exception ignored) {
        }

        executable = null;
        location = null;
        environment = Map.of();
        plugin.getLogger().warning("No executable ffmpeg installation is available; merging and FPS conversion will use fallbacks.");
    }

    private File findExecutable(File[] resources, String name) {
        if (resources == null) {
            return null;
        }
        for (File resource : resources) {
            if (resource != null && resource.isFile() && resource.getName().equalsIgnoreCase(name)) {
                return resource;
            }
            if (resource != null && resource.isDirectory()) {
                File child = new File(resource, name);
                if (child.isFile()) {
                    return child;
                }
            }
        }
        return null;
    }

    private Map<String, String> nativeLibraryEnvironment(String platform, File directory) {
        if (directory == null || platform.startsWith("windows")) {
            return Map.of();
        }
        String variable = platform.startsWith("macosx") ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
        String existing = System.getenv(variable);
        String value = directory.getAbsolutePath();
        if (existing != null && !existing.isBlank()) {
            value += File.pathSeparator + existing;
        }
        Map<String, String> result = new HashMap<>();
        result.put(variable, value);
        return Map.copyOf(result);
    }

    void configure(ProcessBuilder builder) {
        builder.environment().putAll(environment);
    }

    String executable() {
        return executable;
    }

    String ffprobeExecutable() {
        if (executable == null) return null;
        String name = Loader.getPlatform().startsWith("windows") ? "ffprobe.exe" : "ffprobe";
        File sibling = new File(executable).getParentFile();
        return sibling == null ? name : new File(sibling, name).getAbsolutePath();
    }

    String location() {
        return location;
    }

    Map<String, String> environment() {
        return environment;
    }

    boolean available() {
        return executable != null;
    }

    private static String firstLine(String output) {
        if (output == null) {
            return "no output";
        }
        return output.lines().map(String::strip).filter(line -> !line.isBlank()).findFirst().orElse("no output");
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
