package de.erethon.mccinema.download;

import de.erethon.mccinema.MCCinema;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeDownloadManager {

    private final MCCinema plugin;
    private final File videosDirectory;
    private final Map<UUID, DownloadTask> activeDownloads = new ConcurrentHashMap<>();
    private final MediaToolchain toolchain;
    private final FfmpegProvider ffmpegProvider;
    private volatile boolean ffmpegDetectionComplete;
    private long lastAutomaticRepairMillis;

    public YoutubeDownloadManager(MCCinema plugin) {
        this.plugin = plugin;
        this.videosDirectory = new File(plugin.getDataFolder(), "videos");

        if (!videosDirectory.exists()) {
            videosDirectory.mkdirs();
        }

        this.toolchain = new MediaToolchain(plugin);
        this.ffmpegProvider = new FfmpegProvider(plugin);

        if (hasUserConsent()) {
            toolchain.initializeAsync().exceptionally(error -> {
                plugin.getLogger().warning("Media resolver initialization failed: " + rootMessage(error));
                return null;
            });
        }
    }

    /**
     * Check if user has given consent to download yt-dlp
     */
    public boolean hasUserConsent() {
        if (!plugin.getConfig().getBoolean("youtube.require-consent", true)) {
            return true;
        }
        if (toolchain.executable().toFile().exists()) {
            return true;
        }
        File dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (dataFile.exists()) {
            org.bukkit.configuration.file.YamlConfiguration data =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
            return data.getBoolean("ytdlp-consent-given", false);
        }

        return false;
    }

    /**
     * Save user consent to data file
     */
    public void saveUserConsent(boolean consent) {
        File dataFile = new File(plugin.getDataFolder(), "data.yml");
        org.bukkit.configuration.file.YamlConfiguration data =
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);

        data.set("ytdlp-consent-given", consent);
        data.set("ytdlp-consent-timestamp", System.currentTimeMillis());

        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save consent data: " + e.getMessage());
        }
    }

    /** Starts initialization if consent has been granted. */
    public boolean ensureInitialized() {
        if (!hasUserConsent()) {
            return false;
        }
        initializeAsync();
        return true;
    }

    public CompletableFuture<Void> initializeAsync() {
        if (!hasUserConsent()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Media tool download consent is required"));
        }
        return toolchain.initializeAsync();
    }

    public CompletableFuture<Void> repairAsync() {
        if (!hasUserConsent()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Media tool download consent is required"));
        }
        return toolchain.repairAsync();
    }

    public RepairRequest requestRepairAsync() {
        if (!hasUserConsent()) {
            return new RepairRequest(
                CompletableFuture.failedFuture(new IllegalStateException("Media tool download consent is required")), true
            );
        }
        MediaToolchain.RepairRequest request = toolchain.requestRepairAsync();
        return new RepairRequest(request.completion(), request.started());
    }

    public record RepairRequest(CompletableFuture<Void> completion, boolean started) {
    }

    private synchronized void ensureFfmpegDetected() {
        if (!ffmpegDetectionComplete) {
            ffmpegProvider.detect();
            ffmpegDetectionComplete = true;
        }
    }

    private ExternalProcess.Result executeYtDlp(List<String> arguments, Duration timeout, boolean useCookies)
        throws Exception {
        List<String> command = createYtDlpBaseCommand(useCookies);
        command.addAll(arguments);
        ExternalProcess.Result result;
        try {
            result = ExternalProcess.run(command, timeout,
                ffmpegDetectionComplete ? ffmpegProvider.environment() : Map.of());
        } catch (IOException executableFailure) {
            if (!claimAutomaticRepair()) {
                throw executableFailure;
            }
            plugin.getLogger().info("Repairing media tools after the resolver could not start...");
            toolchain.repairAsync().get(6, TimeUnit.MINUTES);
            command = createYtDlpBaseCommand(useCookies);
            command.addAll(arguments);
            return ExternalProcess.run(command, timeout,
                ffmpegDetectionComplete ? ffmpegProvider.environment() : Map.of());
        }
        if (!result.successful()) {
            YtDlpException failure = YtDlpException.fromProcess(result, useCookies);
            if (failure.reason() == YtDlpException.Reason.JAVASCRIPT_RUNTIME ||
                failure.reason() == YtDlpException.Reason.TOOL_UNAVAILABLE) {
                if (!claimAutomaticRepair()) {
                    return result;
                }
                plugin.getLogger().info("Repairing media tools after a resolver health failure...");
                toolchain.repairAsync().get(6, TimeUnit.MINUTES);
                command = createYtDlpBaseCommand(useCookies);
                command.addAll(arguments);
                return ExternalProcess.run(command, timeout,
                    ffmpegDetectionComplete ? ffmpegProvider.environment() : Map.of());
            }
        }
        return result;
    }

    private synchronized boolean claimAutomaticRepair() {
        long now = System.currentTimeMillis();
        if (now - lastAutomaticRepairMillis < TimeUnit.HOURS.toMillis(1)) {
            return false;
        }
        lastAutomaticRepairMillis = now;
        return true;
    }

    /**
     * Get video information from YouTube
     */
    public VideoInfo getVideoInfo(String videoIdOrUrl) throws Exception {
        try {
            return getVideoInfo(videoIdOrUrl, false);
        } catch (YtDlpException error) {
            if (error.reason() == YtDlpException.Reason.AUTHENTICATION_REQUIRED) {
                MediaToolchain.CookieStatus cookies = cookieStatus();
                if (cookies.usable()) {
                    plugin.getLogger().info("Media requires authentication; retrying with the configured YouTube cookies.");
                    return getVideoInfo(videoIdOrUrl, true);
                }
                if (!cookies.description().equals("Not configured")) {
                    throw YtDlpException.fromProcess(new ExternalProcess.Result(
                        1, false, "Cookie file is invalid: " + cookies.description()), true);
                }
            }
            throw error;
        }
    }

    private VideoInfo getVideoInfo(String videoIdOrUrl, boolean useCookies) throws Exception {
        if (!toolchain.isReady()) {
            throw new IllegalStateException("Media resolver is not ready");
        }
        List<String> arguments = new ArrayList<>(List.of(
            "--dump-single-json", "--no-playlist", videoIdOrUrl
        ));
        ExternalProcess.Result result = executeYtDlp(arguments, Duration.ofSeconds(45), useCookies);
        String jsonString = extractJson(result.output());
        if (!result.successful() || jsonString == null) {
            throw YtDlpException.fromProcess(result, useCookies);
        }
        if (jsonString.isEmpty()) {
            throw YtDlpException.fromProcess(result, useCookies);
        }

        JSONObject json = JSON.parseObject(jsonString);
        return new VideoInfo(
            json.getString("id"),
            json.getString("title"),
            json.getString("uploader"),
            json.getIntValue("duration"),
            useCookies
        );
    }

    /**
     * Download a video from YouTube using yt-dlp
     */
    public DownloadTask downloadVideo(String videoIdOrUrl, String customName, boolean convertToFps, DownloadProgressCallback callback) {
        UUID downloadId = UUID.randomUUID();
        DownloadTask task = new DownloadTask(downloadId, videoIdOrUrl, customName, callback);
        activeDownloads.put(downloadId, task);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!toolchain.isReady()) {
                    throw new IllegalStateException("Media resolver is not ready");
                }

                task.setState(DownloadState.FETCHING_INFO);
                VideoInfo videoInfo = getVideoInfo(videoIdOrUrl);
                task.setVideoInfo(videoInfo);

                task.setState(DownloadState.DOWNLOADING);

                String sourceName = customName != null ? customName :
                    (videoInfo.title == null || videoInfo.title.isBlank() ? videoInfo.id : videoInfo.title);
                String fileName = sanitizeFilename(sourceName);
                File outputFile = new File(videosDirectory, fileName + ".mp4");

                plugin.getLogger().info("Downloading video: " + videoInfo.title);

                if (convertToFps) {
                    plugin.getLogger().info("Video will be converted to 20 FPS for optimal playback");
                } else {
                    plugin.getLogger().info("FPS conversion disabled - video will keep original framerate");
                }

                ensureFfmpegDetected();
                List<String> command = createYtDlpBaseCommand(videoInfo.useCookies);
                String ytDlpFfmpegLocation = ffmpegProvider.location();
                if (ytDlpFfmpegLocation != null) {
                    command.add("--ffmpeg-location");
                    command.add(ytDlpFfmpegLocation);
                }
                command.add("-f");
                if (ffmpegProvider.available()) {
                    command.add("bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4][height<=1080]/best[height<=1080]/best");
                    command.add("--merge-output-format");
                    command.add("mp4");
                } else {
                    command.add("best[ext=mp4][height<=1080]/best[height<=1080]/best");
                }
                command.add("-o");
                command.add(outputFile.getAbsolutePath());
                command.add("--no-playlist");
                command.add("--progress");
                command.add("--newline");
                command.add(videoIdOrUrl);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                ffmpegProvider.configure(pb);
                Process process = pb.start();

                Pattern progressPattern = Pattern.compile("\\[download\\]\\s+(\\d+\\.\\d+)%");
                StringBuilder errorOutput = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Capture output for debugging
                        if (line.contains("ERROR") || line.contains("WARNING")) {
                            errorOutput.append(line).append("\n");
                        }

                        Matcher matcher = progressPattern.matcher(line);
                        if (matcher.find()) {
                            int progress = (int) Double.parseDouble(matcher.group(1));
                            task.setProgress(progress);
                            if (callback != null) {
                                callback.onProgress(progress);
                            }
                        }
                    }
                }

                int exitCode = process.waitFor();

                if (exitCode == 0 && outputFile.exists()) {
                    File finalFile = outputFile;

                    // Convert video to 20 FPS if requested
                    if (convertToFps && ffmpegProvider.available()) {
                        plugin.getLogger().info("Download complete, converting to 20 FPS...");
                        if (callback != null) {
                            callback.onConversionStart("Download complete! Converting video to 20 FPS for optimal playback...");
                        }
                        task.setProgress(95);
                        if (callback != null) {
                            callback.onProgress(95);
                        }

                        try {
                            finalFile = convertVideoTo20FPS(outputFile);
                            plugin.getLogger().info("Conversion method returned file: " + finalFile.getName());
                        } catch (Exception conversionException) {
                            plugin.getLogger().log(Level.SEVERE, "Error during video conversion: ", conversionException);
                            plugin.getLogger().warning("Using original video without FPS conversion");
                        }
                    } else if (!convertToFps) {
                        plugin.getLogger().info("Download complete (FPS conversion skipped)");
                    } else {
                        plugin.getLogger().warning("Download complete, but FPS conversion was skipped because ffmpeg is unavailable.");
                    }

                    task.setState(DownloadState.COMPLETED);
                    task.setDownloadedFile(finalFile);
                    if (callback != null) {
                        callback.onComplete(finalFile);
                    }
                } else {
                    String errorMsg = !errorOutput.isEmpty() ? errorOutput.toString() : "yt-dlp exited with code: " + exitCode;
                    throw YtDlpException.fromProcess(
                        new ExternalProcess.Result(exitCode, false, errorMsg), videoInfo.useCookies
                    );
                }

            } catch (Exception e) {
                task.setState(DownloadState.ERROR);
                task.setError(e);
                if (callback != null) {
                    callback.onError(e);
                }
                logYtDlpFailure("download video", videoIdOrUrl, e);
            } finally {
                activeDownloads.remove(downloadId);
            }
        });

        return task;
    }

    /**
     * Convert a video file to 20 FPS using ffmpeg
     */
    private File convertVideoTo20FPS(File inputFile) throws Exception {
        if (!ffmpegProvider.available()) {
            plugin.getLogger().warning("ffmpeg not available, skipping framerate conversion");
            return inputFile;
        }

        // Try multiple codecs in order of preference
        String[] codecsToTry = {"mpeg4", "h264", "libx264", "libx265"};

        for (String codec : codecsToTry) {
            try {
                File result = tryConvertWithCodec(inputFile, codec);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                plugin.getLogger().info("Codec " + codec + " failed, trying next...");
            }
        }

        // All codecs failed
        plugin.getLogger().warning("Failed to convert video to 20 FPS with any available codec");
        plugin.getLogger().warning("JavaCV's ffmpeg build may not support video encoding. Using original video.");
        plugin.getLogger().warning("Video playback may not be optimal at original framerate");
        return inputFile;
    }

    /**
     * Try to convert video with a specific codec
     */
    private File tryConvertWithCodec(File inputFile, String codec) throws Exception {
        File tempOutput = new File(inputFile.getParent(), inputFile.getName() + ".temp.mp4");

        plugin.getLogger().info("Trying to convert with codec: " + codec);

        // ffmpeg command to convert to 20 FPS
        List<String> command = new ArrayList<>();
        command.add(ffmpegProvider.executable());
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-r");
        command.add("20");
        command.add("-c:v");
        command.add(codec);

        if (codec.contains("264") || codec.contains("265")) {
            command.add("-crf");
            command.add("23");  // Constant rate factor for h264/h265
        } else {
            command.add("-qscale:v");
            command.add("5");
        }

        command.add("-c:a");
        command.add("copy");
        command.add("-y");
        command.add(tempOutput.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        ffmpegProvider.configure(pb);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        boolean hasError = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (line.toLowerCase().contains("error") || line.toLowerCase().contains("encoder")) {
                    plugin.getLogger().warning("ffmpeg: " + line);
                    hasError = true;
                }
            }
        }

        int exitCode = process.waitFor();

        plugin.getLogger().info("ffmpeg exit code: " + exitCode + ", temp file exists: " + tempOutput.exists() +
                              ", temp file size: " + (tempOutput.exists() ? tempOutput.length() : 0));

        if (exitCode == 0 && tempOutput.exists() && tempOutput.length() > 0) {
            plugin.getLogger().info("Conversion successful, replacing original file...");
            try {
                Files.move(tempOutput.toPath(), inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Successfully converted video to 20 FPS using codec: " + codec);
                return inputFile;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to replace file: " + e.getMessage());

                // manual delete and rename with retry because Windows can be stubborn about file locks
                int retries = 3;
                for (int i = 0; i < retries; i++) {
                    try {
                        if (inputFile.exists() && !inputFile.delete()) {
                            Thread.sleep(100);
                            continue;
                        }
                        if (tempOutput.renameTo(inputFile)) {
                            plugin.getLogger().info("Successfully converted video to 20 FPS using codec: " + codec);
                            return inputFile;
                        }
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                plugin.getLogger().warning("Could not replace original file after conversion. Keeping temp file.");
                return tempOutput; // Return temp file if we can't replace
            }
        } else {
            // This codec failed
            if (hasError || exitCode != 0) {
                plugin.getLogger().info("Codec " + codec + " conversion failed (exit code: " + exitCode + ")");
            }
            if (tempOutput.exists()) {
                tempOutput.delete();
            }
            return null;
        }
    }


    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "video";
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9.-]", "_");
        sanitized = sanitized.replaceAll("^\\.+", "");
        if (sanitized.isBlank()) {
            sanitized = "video";
        }
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }
        return sanitized;
    }

    public boolean isReady() {
        return toolchain.isReady();
    }

    public List<String> createYtDlpBaseCommand() {
        return createYtDlpBaseCommand(false);
    }

    public List<String> createYtDlpBaseCommand(boolean useCookies) {
        return toolchain.baseCommand(useCookies);
    }

    ExternalProcess.Result executeResolver(List<String> arguments, Duration timeout, boolean useCookies)
        throws Exception {
        return executeYtDlp(arguments, timeout, useCookies);
    }

    MediaToolchain.CookieStatus cookieStatus() {
        return toolchain.cookieStatus();
    }

    public boolean hasUsableCookies() {
        return toolchain.cookieStatus().usable();
    }

    public String cookieStatusDescription() {
        return toolchain.cookieStatus().description();
    }

    public String ytDlpVersion() {
        return toolchain.ytDlpVersion();
    }

    public String jsRuntimeVersion() {
        return toolchain.jsRuntimeVersion();
    }

    public String initializationFailure() {
        return toolchain.failureMessage();
    }

    public boolean isInitializing() {
        return toolchain.isInitializing();
    }

    public String userMessage(Throwable error) {
        Throwable root = rootCause(error);
        if (root instanceof YtDlpException ytDlp) {
            return ytDlp.getMessage();
        }
        return "The media service is currently unavailable. Please try again shortly.";
    }

    public void logYtDlpFailure(String operation, String source, Throwable error) {
        Throwable root = rootCause(error);
        if (root instanceof YtDlpException ytDlp) {
            plugin.getLogger().warning("Failed to " + operation + " (" + ytDlp.reason() + ") for " + source +
                ": " + summarizeDiagnostics(ytDlp.diagnostics()));
        } else {
            plugin.getLogger().log(Level.WARNING, "Failed to " + operation + " for " + source, root);
        }
    }

    private String extractJson(String output) {
        if (output == null) {
            return null;
        }
        return output.lines().map(String::trim)
            .filter(line -> line.startsWith("{") && line.endsWith("}"))
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null &&
            (current instanceof java.util.concurrent.CompletionException ||
                current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = rootCause(error);
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String summarizeDiagnostics(String diagnostics) {
        if (diagnostics == null || diagnostics.isBlank()) {
            return "no diagnostic output";
        }
        String summary = diagnostics.lines().map(String::trim).filter(line -> !line.isBlank())
            .reduce((first, second) -> second).orElse("no diagnostic output");
        return summary.length() > 500 ? summary.substring(0, 500) + "..." : summary;
    }

    public static class VideoInfo {
        public final String id;
        public final String title;
        public final String uploader;
        public final int duration;
        private final boolean useCookies;

        public VideoInfo(String id, String title, String uploader, int duration, boolean useCookies) {
            this.id = id;
            this.title = title;
            this.uploader = uploader;
            this.duration = duration;
            this.useCookies = useCookies;
        }
    }
}

