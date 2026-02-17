package de.erethon.mccinema.download;

import de.erethon.mccinema.MCCinema;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeDownloadManager {

    private final MCCinema plugin;
    private final File videosDirectory;
    private final File ytDlpExecutable;
    private final Map<UUID, DownloadTask> activeDownloads = new ConcurrentHashMap<>();
    private boolean ytDlpReady = false;
    private boolean initializationInProgress = false;
    private String jsRuntimePath = null;
    private String ffmpegPath = null;

    public YoutubeDownloadManager(MCCinema plugin) {
        this.plugin = plugin;
        this.videosDirectory = new File(plugin.getDataFolder(), "videos");

        if (!videosDirectory.exists()) {
            videosDirectory.mkdirs();
        }

        // Determine OS and set executable path
        String os = System.getProperty("os.name").toLowerCase();
        String exeName = os.contains("win") ? "yt-dlp.exe" : "yt-dlp";
        this.ytDlpExecutable = new File(plugin.getDataFolder(), exeName);

        // Check if yt-dlp already exists
        if (ytDlpExecutable.exists()) {
            plugin.getLogger().info("yt-dlp found at: " + ytDlpExecutable.getAbsolutePath());
            // Initialize immediately if already downloaded
            initializeYtDlp();
        } else {
            plugin.getLogger().info("yt-dlp not found - will be downloaded when first needed");
            if (plugin.getConfig().getBoolean("youtube.require-consent", true)) {
                plugin.getLogger().info("User consent will be requested before downloading yt-dlp");
            }
        }
    }

    /**
     * Check if user has given consent to download yt-dlp
     */
    public boolean hasUserConsent() {
        if (!plugin.getConfig().getBoolean("youtube.require-consent", true)) {
            return true;
        }

        // Check if yt-dlp already exists
        if (ytDlpExecutable.exists()) {
            return true;
        }

        // Check data file for stored consent
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

    /**
     * Ensure yt-dlp is initialized (download if needed with consent)
     * @return true if ready or initialization started, false if consent needed
     */
    public boolean ensureInitialized() {
        if (ytDlpReady) {
            return true;
        }

        if (initializationInProgress) {
            return true;
        }

        if (!ytDlpExecutable.exists()) {
            if (!hasUserConsent()) {
                return false;
            }
        }

        // Start initialization
        initializeYtDlp();
        return true;
    }

    /**
     * Initialize yt-dlp - download if needed, setup if needed
     */
    private void initializeYtDlp() {
        if (initializationInProgress || ytDlpReady) {
            return;
        }

        initializationInProgress = true;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!ytDlpExecutable.exists()) {
                    plugin.getLogger().info("yt-dlp not found, downloading...");
                    downloadYtDlp();
                } else {
                    plugin.getLogger().info("yt-dlp found at: " + ytDlpExecutable.getAbsolutePath());
                }

                // Make executable on Unix systems
                if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                    ytDlpExecutable.setExecutable(true);
                }

                // Detect JavaScript runtime for signature solving
                detectJavaScriptRuntime();

                // Detect or extract ffmpeg from JavaCV
                detectFfmpeg();

                // Test yt-dlp
                if (testYtDlp()) {
                    ytDlpReady = true;
                    plugin.getLogger().info("yt-dlp is ready! Using EJS for YouTube signature solving.");

                    if (jsRuntimePath != null) {
                        plugin.getLogger().info("JavaScript runtime detected: " + jsRuntimePath);
                        plugin.getLogger().info("EJS will use this runtime for signature challenges.");
                    } else {
                        plugin.getLogger().warning("No JavaScript runtime found (Node.js recommended)");
                        plugin.getLogger().warning("Downloads may fail without a JS runtime for signature solving.");
                        plugin.getLogger().warning("Install Node.js from: https://nodejs.org/");
                    }

                    File cookiesFile = new File(plugin.getDataFolder(), "youtube_cookies.txt");
                    if (cookiesFile.exists()) {
                        plugin.getLogger().info("Found YouTube cookies file - downloads will use authentication (optional but may help with some restricted videos)");
                    } else {
                        plugin.getLogger().info("No YouTube cookies found (optional). Most videos should work without them.");
                        plugin.getLogger().info("If you encounter issues with age-restricted or private videos, export cookies:");
                        plugin.getLogger().info("1. Install 'Get cookies.txt LOCALLY' browser extension");
                        plugin.getLogger().info("2. Go to youtube.com and log in");
                        plugin.getLogger().info("3. Export cookies and save as: youtube_cookies.txt in " + plugin.getDataFolder().getAbsolutePath());
                    }
                } else {
                    plugin.getLogger().warning("yt-dlp test failed. Downloads may not work.");
                }

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to initialize yt-dlp", e);
            } finally {
                initializationInProgress = false;
            }
        });
    }

    /**
     * Download yt-dlp from GitHub releases
     */
    private void downloadYtDlp() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String downloadUrl;

        if (os.contains("win")) {
            downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
        } else if (os.contains("mac")) {
            downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos";
        } else {
            downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";
        }

        plugin.getLogger().info("Downloading yt-dlp from: " + downloadUrl);

        URL url = new URL(downloadUrl);
        try (ReadableByteChannel rbc = Channels.newChannel(url.openStream());
             FileOutputStream fos = new FileOutputStream(ytDlpExecutable)) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }

        plugin.getLogger().info("yt-dlp downloaded successfully!");
    }

    /**
     * Test if yt-dlp works
     */
    private boolean testYtDlp() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytDlpExecutable.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String version = reader.readLine();

            process.waitFor(10, TimeUnit.SECONDS);

            if (version != null && !version.isEmpty()) {
                plugin.getLogger().info("yt-dlp version: " + version);
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to test yt-dlp", e);
        }
        return false;
    }

    /**
     * Detect available JavaScript runtime for signature solving
     */
    private void detectJavaScriptRuntime() {
        // Try Node.js first
        String[] nodeCommands = {"node", "nodejs"};
        for (String cmd : nodeCommands) {
            if (testCommand(cmd, "--version")) {
                jsRuntimePath = cmd;
                return;
            }
        }

        // Try system PATH for node.exe on Windows
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String[] possiblePaths = {
                System.getenv("ProgramFiles") + "\\nodejs\\node.exe",
                System.getenv("ProgramFiles(x86)") + "\\nodejs\\node.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\nodejs\\node.exe"
            };

            for (String path : possiblePaths) {
                if (path != null) {
                    File nodeExe = new File(path);
                    if (nodeExe.exists() && testCommand(path, "--version")) {
                        jsRuntimePath = path;
                        return;
                    }
                }
            }
        }

        // Try PhantomJS
        if (testCommand("phantomjs", "--version")) {
            jsRuntimePath = "phantomjs";
            return;
        }

        plugin.getLogger().warning("No JavaScript runtime found. Some YouTube videos may fail to download due to YTs anti-bot measures.");
        plugin.getLogger().warning("Install Node.js from https://nodejs.org/ to fix this issue and enable EJS for signature solving.");
    }

    /**
     * Test if a command is available
     */
    private boolean testCommand(String command, String... args) {
        try {
            List<String> cmdList = new ArrayList<>();
            cmdList.add(command);
            if (args != null) {
                cmdList.addAll(Arrays.asList(args));
            }

            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Detect or extract ffmpeg from JavaCV
     */
    private void detectFfmpeg() {
        // Use ffmpeg from JavaCV's bundled native libraries
        try {
            String ffmpegExe = org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);

            if (ffmpegExe != null && new File(ffmpegExe).exists()) {
                ffmpegPath = ffmpegExe;
                plugin.getLogger().info("Using ffmpeg from JavaCV: " + ffmpegPath);
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load ffmpeg from JavaCV: " + e.getMessage());
        }

        // If JavaCV's ffmpeg fails, try system PATH as fallback
        if (testCommand("ffmpeg", "-version")) {
            ffmpegPath = "ffmpeg";
            plugin.getLogger().info("Using system ffmpeg from PATH (fallback)");
            return;
        }

        plugin.getLogger().warning("ffmpeg not found. Video merging may not work properly.");
        plugin.getLogger().warning("This shouldn't happen as ffmpeg is bundled with JavaCV.");
        plugin.getLogger().info("yt-dlp will attempt to use its own merging logic.");
    }

    /**
     * Get video information from YouTube
     */
    public VideoInfo getVideoInfo(String videoIdOrUrl) throws Exception {
        if (!ytDlpReady) {
            throw new Exception("yt-dlp is not ready yet or not enabled.");
        }

        List<String> command = new ArrayList<>();
        command.add(ytDlpExecutable.getAbsolutePath());

        File cookiesFile = new File(plugin.getDataFolder(), "youtube_cookies.txt");
        if (cookiesFile.exists()) {
            command.add("--cookies");
            command.add(cookiesFile.getAbsolutePath());
        }

        command.add("--remote-components");
        command.add("ejs:github");

        if (jsRuntimePath != null) {
            command.add("--js-runtimes");
            command.add(jsRuntimePath);
        }

        command.add("--ignore-errors");
        command.add("--no-abort-on-error");

        command.add("--dump-json");
        command.add("--no-playlist");
        command.add(videoIdOrUrl);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        StringBuilder jsonOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (!line.startsWith("WARNING:") && !line.startsWith("ERROR:") &&
                    !line.startsWith("[") && !line.contains("ffmpeg not found")) {
                    jsonOutput.append(line);
                }
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("Timeout while fetching video info");
        }

        if (process.exitValue() != 0) {
            String error = output.toString();
            if (error.contains("Sign in") || error.contains("login") || error.contains("bot")) {
                throw new Exception("YouTube requires authentication. Please provide cookies file.");
            }
            if (error.contains("Signature solving failed") || error.contains("n challenge solving failed")) {
                throw new Exception("YouTube signature decryption failed. Cookies are required for this video.");
            }
            throw new Exception("Failed to fetch video info: " + error);
        }

        String jsonString = jsonOutput.toString().trim();
        if (jsonString.isEmpty()) {
            throw new Exception("No JSON output received from yt-dlp. Full output: " + output.toString());
        }

        JSONObject json = JSON.parseObject(jsonString);
        return new VideoInfo(
            json.getString("id"),
            json.getString("title"),
            json.getString("uploader"),
            json.getInteger("duration")
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
                if (!ytDlpReady) {
                    throw new Exception("yt-dlp is not ready yet. Please wait and try again.");
                }

                task.setState(DownloadState.FETCHING_INFO);
                VideoInfo videoInfo = getVideoInfo(videoIdOrUrl);
                task.setVideoInfo(videoInfo);

                task.setState(DownloadState.DOWNLOADING);

                String fileName = customName != null ? customName : sanitizeFilename(videoInfo.title);
                File outputFile = new File(videosDirectory, fileName + ".mp4");

                plugin.getLogger().info("Downloading video: " + videoInfo.title);

                if (convertToFps) {
                    plugin.getLogger().info("Video will be converted to 20 FPS for optimal playback");
                } else {
                    plugin.getLogger().info("FPS conversion disabled - video will keep original framerate");
                }

                List<String> command = new ArrayList<>();
                command.add(ytDlpExecutable.getAbsolutePath());

                File cookiesFile = new File(plugin.getDataFolder(), "youtube_cookies.txt");
                if (cookiesFile.exists()) {
                    command.add("--cookies");
                    command.add(cookiesFile.getAbsolutePath());
                }
                command.add("--remote-components");
                command.add("ejs:github");
                if (jsRuntimePath != null) {
                    command.add("--js-runtimes");
                    command.add(jsRuntimePath);
                }
                if (ffmpegPath != null) {
                    command.add("--ffmpeg-location");
                    command.add(ffmpegPath);
                }
                command.add("-f");
                command.add("bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/best");
                command.add("--merge-output-format");
                command.add("mp4");
                command.add("-o");
                command.add(outputFile.getAbsolutePath());
                command.add("--no-playlist");
                command.add("--ignore-errors");
                command.add("--no-abort-on-error");
                command.add("--progress");
                command.add("--newline");
                command.add(videoIdOrUrl);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
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
                    if (convertToFps) {
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
                    } else {
                        plugin.getLogger().info("Download complete (FPS conversion skipped)");
                    }

                    task.setState(DownloadState.COMPLETED);
                    task.setDownloadedFile(finalFile);
                    if (callback != null) {
                        callback.onComplete(finalFile);
                    }
                } else {
                    String errorMsg = errorOutput.length() > 0 ? errorOutput.toString() : "yt-dlp exited with code: " + exitCode;
                    throw new Exception(errorMsg);
                }

            } catch (Exception e) {
                task.setState(DownloadState.ERROR);
                task.setError(e);
                if (callback != null) {
                    callback.onError(e);
                }
                plugin.getLogger().log(Level.WARNING, "Failed to download video: " + videoIdOrUrl, e);
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
        if (ffmpegPath == null) {
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
        command.add(ffmpegPath);
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


    public DownloadTask getDownloadTask(UUID downloadId) {
        return activeDownloads.get(downloadId);
    }

    public Map<UUID, DownloadTask> getActiveDownloads() {
        return new HashMap<>(activeDownloads);
    }

    public boolean cancelDownload(UUID downloadId) {
        DownloadTask task = activeDownloads.get(downloadId);
        if (task != null) {
            task.setState(DownloadState.CANCELLED);
            activeDownloads.remove(downloadId);
            return true;
        }
        return false;
    }

    private String sanitizeFilename(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9.-]", "_");
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }
        return sanitized;
    }

    public File getVideosDirectory() {
        return videosDirectory;
    }

    public boolean isReady() {
        return ytDlpReady;
    }

    public static class VideoInfo {
        public final String id;
        public final String title;
        public final String uploader;
        public final int duration;

        public VideoInfo(String id, String title, String uploader, int duration) {
            this.id = id;
            this.title = title;
            this.uploader = uploader;
            this.duration = duration;
        }
    }
}

