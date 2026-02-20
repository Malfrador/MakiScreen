package de.erethon.mccinema.video;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.screen.Screen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class VideoPlayer {

    public enum State {
        IDLE, LOADING, PLAYING, PAUSED, STOPPED
    }

    private final MCCinema plugin;
    private final Screen screen;
    private final FrameProcessor frameProcessor;
    private final PacketDispatcher packetDispatcher;

    private File videoFile;
    private FFmpegFrameGrabber grabber;
    private Java2DFrameConverter converter;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> playbackTask;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicLong currentFrame = new AtomicLong(0);
    private final AtomicLong totalFrames = new AtomicLong(0);

    private double frameRate = 30.0;
    private long videoDurationMs;

    private Consumer<VideoPlayer> onComplete;
    private Consumer<VideoPlayer> onStateChange;

    private AudioManager audioManager;
    private long playbackStartTime;
    private long pausedAtFrame;

    // Stats
    private final AtomicLong framesProcessed = new AtomicLong(0);
    private final AtomicLong framesSkipped = new AtomicLong(0);
    private long lastFrameTime;

    // Debug metrics
    private final Set<UUID> debugEnabledPlayers = Collections.synchronizedSet(new HashSet<>());
    private long debugLastUpdateTime = System.nanoTime();
    private int debugFrameCount = 0;
    private double debugCurrentFps = 0.0;
    private long debugAverageFrameProcessTime = 0;
    private final PerformanceMetrics performanceMetrics;

    // Reusable objects
    private BufferedImage resizedImageBuffer;
    private Graphics2D resizedGraphics;

    public VideoPlayer(MCCinema plugin, Screen screen) {
        this.plugin = plugin;
        this.screen = screen;
        this.frameProcessor = new FrameProcessor(screen, plugin);
        this.packetDispatcher = new PacketDispatcher(plugin);
        this.performanceMetrics = new PerformanceMetrics();
        this.converter = new Java2DFrameConverter();
    }

    public void setAudioManager(AudioManager audioManager) {
        this.audioManager = audioManager;
    }

    public boolean load(File videoFile) {
        if (state.get() == State.PLAYING) {
            stop();
        }

        this.videoFile = videoFile;
        state.set(State.LOADING);
        notifyStateChange();

        try {
            if (grabber != null) {
                grabber.close();
            }

            grabber = new FFmpegFrameGrabber(videoFile);
            grabber.start();

            frameRate = grabber.getFrameRate();
            if (frameRate <= 0 || frameRate > 120) {
                frameRate = 20.0;
            }

            packetDispatcher.setFrameRate(frameRate);

            totalFrames.set(grabber.getLengthInVideoFrames());
            videoDurationMs = grabber.getLengthInTime() / 1000;
            int videoWidth = grabber.getImageWidth();
            int videoHeight = grabber.getImageHeight();

            currentFrame.set(0);
            state.set(State.IDLE);
            notifyStateChange();

            // Pre-allocate reusable buffer
            int targetWidth = screen.getPixelWidth();
            int targetHeight = screen.getPixelHeight();
            if (videoWidth != targetWidth || videoHeight != targetHeight) {
                if (resizedImageBuffer == null ||
                    resizedImageBuffer.getWidth() != targetWidth ||
                    resizedImageBuffer.getHeight() != targetHeight) {

                    if (resizedGraphics != null) {
                        resizedGraphics.dispose();
                    }

                    resizedImageBuffer = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR);
                    resizedGraphics = resizedImageBuffer.createGraphics();

                    boolean isUpscaling = videoWidth < targetWidth || videoHeight < targetHeight;
                    if (isUpscaling) {
                        resizedGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    } else {
                        resizedGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    }
                }
            }

            plugin.getLogger().info("Loaded video: " + videoFile.getName());
            plugin.getLogger().info("  Resolution: " + videoWidth + "x" + videoHeight);
            plugin.getLogger().info("  Frame rate: " + String.format("%.2f", frameRate) + " fps");

            if (Math.abs(frameRate - 20.0) > 0.5) {
                plugin.getLogger().warning("  Video framerate is not 20 FPS - playback may not be optimal!");
                plugin.getLogger().warning("  For YouTube videos, download without --no-convert flag to auto-convert to 20 FPS");
            }

            plugin.getLogger().info("  Duration: " + formatDuration(videoDurationMs));
            plugin.getLogger().info("  Total frames: " + totalFrames.get());

            // Check aspect ratio mismatch and inform about letterboxing/pillarboxing
            double videoAspect = (double) videoWidth / videoHeight;
            double screenAspect = (double) screen.getPixelWidth() / screen.getPixelHeight();
            if (Math.abs(videoAspect - screenAspect) > 0.01) {
                plugin.getLogger().info("  Video aspect ratio: " + String.format("%.2f", videoAspect) +
                                      " (" + videoWidth + ":" + videoHeight + ")");
                plugin.getLogger().info("  Screen aspect ratio: " + String.format("%.2f", screenAspect) +
                                      " (" + screen.getPixelWidth() + ":" + screen.getPixelHeight() + ")");
                if (videoAspect > screenAspect) {
                    plugin.getLogger().info("  Applying letterboxing (black bars top/bottom) to maintain aspect ratio");
                } else {
                    plugin.getLogger().info("  Applying pillarboxing (black bars left/right) to maintain aspect ratio");
                }
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load video: " + e.getMessage());
            state.set(State.IDLE);
            notifyStateChange();
            return false;
        }
    }

    public void play() {
        if (grabber == null) {
            plugin.getLogger().warning("No video loaded");
            return;
        }
        State currentState = state.get();
        if (currentState == State.PLAYING) {
            return;
        }
        if (currentState == State.PAUSED) {
            resume();
            return;
        }

        // Check for valid screen origin before starting playback
        if (!screen.hasValidOrigin()) {
            plugin.getLogger().warning("Cannot play: Screen '" + screen.getName() + "' has no valid origin location");
            return;
        }

        state.set(State.PLAYING);
        notifyStateChange();

        // Start viewer cache updater on main thread (updates every 10 ticks = 500ms)
        screen.startViewerCacheUpdater(plugin, 10L);

        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MCCinema-VideoPlayer");
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
        }
        playbackStartTime = System.nanoTime();
        lastFrameTime = playbackStartTime;
        framesProcessed.set(0);
        framesSkipped.set(0);
        if (audioManager != null) {
            audioManager.play(screen.getCenterLocation());
        }
        // Start with frame -1 so first frame will be frame 0
        scheduleNextFrame(-1);
        plugin.getLogger().info("Started playback of " + videoFile.getName());
    }

    public void pause() {
        if (state.get() != State.PLAYING) {
            return;
        }
        state.set(State.PAUSED);
        notifyStateChange();
        if (playbackTask != null) {
            playbackTask.cancel(false);
        }
        pausedAtFrame = currentFrame.get();
        if (audioManager != null) {
            audioManager.pause();
        }
        plugin.getLogger().info("Paused playback at frame " + pausedAtFrame);
    }

    public void resume() {
        if (state.get() != State.PAUSED) {
            return;
        }
        state.set(State.PLAYING);
        notifyStateChange();
        playbackStartTime = System.nanoTime() - (long)(pausedAtFrame / frameRate * 1_000_000_000L);
        if (audioManager != null) {
            audioManager.resume(screen.getCenterLocation());
        }
        // Schedule from pausedAtFrame - 1 because processNextFrame will increment currentFrame
        scheduleNextFrame(pausedAtFrame - 1);
        plugin.getLogger().info("Resumed playback from frame " + pausedAtFrame);
    }

    public void stop() {
        State previousState = state.get();
        if (previousState == State.STOPPED || previousState == State.IDLE) {
            return; // Already stopped
        }

        state.set(State.STOPPED);
        notifyStateChange();

        // Stop the viewer cache updater
        screen.stopViewerCacheUpdater();

        if (playbackTask != null) {
            playbackTask.cancel(false);
            playbackTask = null;
        }
        if (audioManager != null) {
            audioManager.stop();
        }

        clearDebugActionBars();

        try {
            if (grabber != null) {
                grabber.setFrameNumber(0);
            }
        } catch (Exception ignored) {}
        currentFrame.set(0);

        if (previousState == State.PLAYING || previousState == State.PAUSED) {
            plugin.getLogger().info("Stopped playback");
        }

        state.set(State.IDLE);
        notifyStateChange();
    }

    public void seek(long frameNumber) {
        if (grabber == null) return;
        try {
            frameNumber = Math.max(0, Math.min(frameNumber, totalFrames.get() - 1));
            grabber.setFrameNumber((int) frameNumber);
            currentFrame.set(frameNumber);
            if (audioManager != null) {
                long timeMs = (long) (frameNumber / frameRate * 1000);
                audioManager.seekTo(timeMs, screen.getCenterLocation());
            }
            State currentState = state.get();
            if (currentState == State.PAUSED || currentState == State.PLAYING) {
                playbackStartTime = System.nanoTime() - (long)(frameNumber / frameRate * 1_000_000_000L);
            }
            // If playing, we need to reschedule the next frame from the new position
            if (currentState == State.PLAYING) {
                // Cancel the currently scheduled frame
                if (playbackTask != null) {
                    playbackTask.cancel(false);
                }
                // Schedule from frameNumber - 1 because processNextFrame will increment currentFrame
                scheduleNextFrame(frameNumber - 1);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to seek: " + e.getMessage());
        }
    }

    public void seekToTime(long milliseconds) {
        long targetFrame = (long) (milliseconds / 1000.0 * frameRate);
        seek(targetFrame);
    }


    private void scheduleNextFrame(long currentFrameNumber) {
        if (state.get() != State.PLAYING) {
            return;
        }
        long nextFrameNumber = currentFrameNumber + 1;
        long nextFrameIdealTimeNanos = playbackStartTime + (long)(nextFrameNumber * 1_000_000_000.0 / frameRate);
        long currentTimeNanos = System.nanoTime();
        long delayNanos = nextFrameIdealTimeNanos - currentTimeNanos;
        if (delayNanos < 0) {
            // process next frame immediately
            delayNanos = 0;
        }
        long delayMicros = delayNanos / 1000;
        playbackTask = scheduler.schedule(
            this::processNextFrameAdaptive,
            delayMicros,
            TimeUnit.MICROSECONDS
        );
    }

    private void processNextFrameAdaptive() {
        long currentFrameNumber = processNextFrame();
        scheduleNextFrame(currentFrameNumber);
    }

    private long processNextFrame() {
        if (state.get() != State.PLAYING) {
            return currentFrame.get();
        }
        long frameStartTime = System.nanoTime();
        try {
            // Stage 1: Decode frame from video
            long decodeStart = System.nanoTime();
            Frame frame = grabber.grabImage();
            long decodeEnd = System.nanoTime();
            if (frame == null || frame.image == null) {
                onVideoComplete();
                return currentFrame.get();
            }
            performanceMetrics.recordFrameDecode(decodeEnd - decodeStart);
            long frameNum = currentFrame.incrementAndGet();

            // Stage 2: Convert to BufferedImage
            long conversionStart = System.nanoTime();
            BufferedImage image = converter.convert(frame);
            long conversionEnd = System.nanoTime();
            if (image == null) {
                framesSkipped.incrementAndGet();
                return frameNum;
            }
            performanceMetrics.recordImageConversion(conversionEnd - conversionStart);
            FrameProcessor.ProcessedFrame processedFrame = frameProcessor.processFrame(
                image, screen.getPixelWidth(), screen.getPixelHeight(), performanceMetrics
            );

            // Stage 3: Dispatch packets
            long dispatchStart = System.nanoTime();
            packetDispatcher.dispatchFrame(screen, processedFrame.updates(), performanceMetrics);
            long dispatchEnd = System.nanoTime();
            performanceMetrics.recordPacketDispatch(dispatchEnd - dispatchStart);
            framesProcessed.incrementAndGet();
            lastFrameTime = System.nanoTime();
            performanceMetrics.recordTotalFrame(lastFrameTime - frameStartTime);
            updateDebugMetrics(frameStartTime);
            return frameNum;

        } catch (Exception e) {
            plugin.getLogger().warning("Error processing frame: " + e.getMessage());
            e.printStackTrace();
            framesSkipped.incrementAndGet();
            return currentFrame.get();
        }
    }

    private void onVideoComplete() {
        stop();
        plugin.getScreenManager().fillScreenWithColor(screen, (byte) 34);
        plugin.getLogger().info("Video playback complete");
        if (onComplete != null) {
            onComplete.accept(this);
        }
    }

    private void notifyStateChange() {
        if (onStateChange != null) {
            onStateChange.accept(this);
        }
    }

    public void shutdown() {
        stop();
        screen.stopViewerCacheUpdater();
        frameProcessor.shutdown();
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (audioManager != null) {
            audioManager.cleanup();
        }
        if (resizedGraphics != null) {
            resizedGraphics.dispose();
            resizedGraphics = null;
        }
        try {
            if (grabber != null) {
                grabber.close();
            }
        } catch (Exception ignored) {}
    }

    // Getters

    public State getState() {
        return state.get();
    }

    public long getCurrentFrame() {
        return currentFrame.get();
    }

    public long getTotalFrames() {
        return totalFrames.get();
    }

    public double getProgress() {
        long total = totalFrames.get();
        return total > 0 ? (double) currentFrame.get() / total : 0;
    }

    public long getCurrentTimeMs() {
        return (long) (currentFrame.get() / frameRate * 1000);
    }

    public long getTotalDurationMs() {
        return videoDurationMs;
    }

    public double getFrameRate() {
        return frameRate;
    }

    public Screen getScreen() {
        return screen;
    }

    public PacketDispatcher getPacketDispatcher() {
        return packetDispatcher;
    }

    public FrameProcessor getFrameProcessor() {
        return frameProcessor;
    }

    public PerformanceMetrics getPerformanceMetrics() {
        return performanceMetrics;
    }

    public long getFramesProcessed() {
        return framesProcessed.get();
    }

    public long getFramesSkipped() {
        return framesSkipped.get();
    }

    public File getVideoFile() {
        return videoFile;
    }

    public void setOnComplete(Consumer<VideoPlayer> onComplete) {
        this.onComplete = onComplete;
    }

    public void setOnStateChange(Consumer<VideoPlayer> onStateChange) {
        this.onStateChange = onStateChange;
    }

    public static String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60);
        } else {
            return String.format("%d:%02d", minutes, seconds % 60);
        }
    }

    // Debug metrics methods

    public void enableDebug(UUID playerId) {
        debugEnabledPlayers.add(playerId);
    }

    public void disableDebug(UUID playerId) {
        debugEnabledPlayers.remove(playerId);
    }

    public boolean isDebugEnabled(UUID playerId) {
        return debugEnabledPlayers.contains(playerId);
    }

    private void updateDebugMetrics(long frameStartTime) {
        long frameEndTime = System.nanoTime();
        long debugLastFrameProcessTime = (frameEndTime - frameStartTime) / 1_000_000; // ms

        if (debugAverageFrameProcessTime == 0) {
            debugAverageFrameProcessTime = debugLastFrameProcessTime;
        } else {
            debugAverageFrameProcessTime = (debugAverageFrameProcessTime * 9 + debugLastFrameProcessTime) / 10;
        }

        debugFrameCount++;

        long currentTime = System.nanoTime();
        long timeSinceLastUpdate = currentTime - debugLastUpdateTime;

        if (timeSinceLastUpdate >= 1_000_000_000L) { // 1 second
            debugCurrentFps = debugFrameCount / (timeSinceLastUpdate / 1_000_000_000.0);
            debugFrameCount = 0;
            debugLastUpdateTime = currentTime;
            sendDebugActionBar();
        }
    }

    private void sendDebugActionBar() {
        if (debugEnabledPlayers.isEmpty()) {
            return;
        }
        long bytesPerSecond = (long) (debugCurrentFps * packetDispatcher.getLastFrameBytesSent());
        long decode = performanceMetrics.getLastFrameDecodeUs();
        long dither = performanceMetrics.getLastDitheringUs();
        long upscale = performanceMetrics.getLastUpscalingUs();
        long tiles = performanceMetrics.getLastTileExtractionUs();
        long total = performanceMetrics.getLastTotalFrameUs();
        float stability = performanceMetrics.getLastOutputStabilityPercent();
        int dirtyTiles = performanceMetrics.getLastDirtyTileCount();
        int skippedTiles = performanceMetrics.getLastSkippedTileCount();
        int totalTiles = dirtyTiles + skippedTiles;

        String message = String.format(
            "<gray>FPS: <white>%.1f</white> <dark_gray>|</dark_gray> " +
            "T: <white>%.1fms</white> <dark_gray>[</dark_gray>" +
            "<yellow>D:%.1f</yellow> <gold>Di:%.1f</gold> <gold>U:%.1f</gold> <aqua>Ti:%.1f</aqua>" +
            "<dark_gray>]</dark_gray> " +
            "<dark_gray>|</dark_gray> BW: <white>%s/s</white> " +
            "<dark_gray>|</dark_gray> <light_purple>Stab:<white>%.0f%%</white> Dirty:<white>%d</white>/<green>%d</green></light_purple>",
            debugCurrentFps,
            total / 1000.0,
            decode / 1000.0,
            dither / 1000.0,
            upscale / 1000.0,
            tiles / 1000.0,
            formatBytes(bytesPerSecond),
            stability,
            dirtyTiles,
            totalTiles
        );
        Component component = MiniMessage.miniMessage().deserialize(message);
        for (UUID playerId : debugEnabledPlayers) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(component);
            }
        }
    }

    private void clearDebugActionBars() {
        if (debugEnabledPlayers.isEmpty()) {
            return;
        }
        Component empty = Component.empty();
        for (UUID playerId : debugEnabledPlayers) {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(empty);
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else {
            return String.format("%.2fMB", bytes / (1024.0 * 1024.0));
        }
    }
}

