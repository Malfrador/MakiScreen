package cat.maki.makiscreen.video;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.audio.AudioManager;
import cat.maki.makiscreen.screen.Screen;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
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

    private final MakiScreen plugin;
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
    private long frameIntervalMicros;
    private long videoDurationMs;
    private int videoWidth;
    private int videoHeight;

    private Consumer<VideoPlayer> onComplete;
    private Consumer<VideoPlayer> onStateChange;

    private AudioManager audioManager;
    private long playbackStartTime;
    private long pausedAtFrame;

    // Stats
    private final AtomicLong framesProcessed = new AtomicLong(0);
    private final AtomicLong framesSkipped = new AtomicLong(0);
    private long lastFrameTime;

    public VideoPlayer(MakiScreen plugin, Screen screen) {
        this.plugin = plugin;
        this.screen = screen;
        this.frameProcessor = new FrameProcessor(screen);
        this.packetDispatcher = new PacketDispatcher(plugin);
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

            // Update packet dispatcher with frame rate for adaptive limiting
            packetDispatcher.setFrameRate(frameRate);

            frameIntervalMicros = (long) (1_000_000.0 / frameRate);
            totalFrames.set(grabber.getLengthInVideoFrames());
            videoDurationMs = grabber.getLengthInTime() / 1000;
            videoWidth = grabber.getImageWidth();
            videoHeight = grabber.getImageHeight();

            currentFrame.set(0);
            state.set(State.IDLE);
            notifyStateChange();

            plugin.getLogger().info("Loaded video: " + videoFile.getName());
            plugin.getLogger().info("  Resolution: " + videoWidth + "x" + videoHeight);
            plugin.getLogger().info("  Frame rate: " + String.format("%.2f", frameRate) + " fps");

            if (Math.abs(frameRate - 20.0) > 0.5) {
                plugin.getLogger().warning("  Video framerate is not 20 FPS - playback may not be optimal!");
                plugin.getLogger().warning("  For YouTube videos, download without --no-convert flag to auto-convert to 20 FPS");
            }

            plugin.getLogger().info("  Duration: " + formatDuration(videoDurationMs));
            plugin.getLogger().info("  Total frames: " + totalFrames.get());

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

        state.set(State.PLAYING);
        notifyStateChange();

        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MakiScreen-VideoPlayer");
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
            audioManager.play();
        }

        playbackTask = scheduler.scheduleAtFixedRate(
            this::processNextFrame,
            0,
            frameIntervalMicros,
            TimeUnit.MICROSECONDS
        );

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
            audioManager.resume();
        }

        playbackTask = scheduler.scheduleAtFixedRate(
            this::processNextFrame,
            0,
            frameIntervalMicros,
            TimeUnit.MICROSECONDS
        );

        plugin.getLogger().info("Resumed playback from frame " + pausedAtFrame);
    }

    public void stop() {
        State previousState = state.getAndSet(State.STOPPED);
        notifyStateChange();

        if (playbackTask != null) {
            playbackTask.cancel(false);
            playbackTask = null;
        }

        if (audioManager != null) {
            audioManager.stop();
        }

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
    }

    public void seek(long frameNumber) {
        if (grabber == null) return;

        try {
            frameNumber = Math.max(0, Math.min(frameNumber, totalFrames.get() - 1));
            grabber.setFrameNumber((int) frameNumber);
            currentFrame.set(frameNumber);

            if (audioManager != null) {
                long timeMs = (long) (frameNumber / frameRate * 1000);
                audioManager.seekTo(timeMs);
            }

            if (state.get() == State.PAUSED) {
                playbackStartTime = System.nanoTime() - (long)(frameNumber / frameRate * 1_000_000_000L);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to seek: " + e.getMessage());
        }
    }

    public void seekToTime(long milliseconds) {
        long targetFrame = (long) (milliseconds / 1000.0 * frameRate);
        seek(targetFrame);
    }

    private void processNextFrame() {
        if (state.get() != State.PLAYING) {
            return;
        }

        try {
            Frame frame = grabber.grabImage();

            if (frame == null || frame.image == null) {
                onVideoComplete();
                return;
            }

            long frameNum = currentFrame.incrementAndGet();

            BufferedImage image = converter.convert(frame);
            if (image == null) {
                framesSkipped.incrementAndGet();
                return;
            }

            BufferedImage resized = resizeImage(image, screen.getPixelWidth(), screen.getPixelHeight());
            FrameProcessor.ProcessedFrame processedFrame = frameProcessor.processFrame(resized);
            packetDispatcher.dispatchFrame(screen, processedFrame.updates());

            framesProcessed.incrementAndGet();
            lastFrameTime = System.nanoTime();

        } catch (Exception e) {
            plugin.getLogger().warning("Error processing frame: " + e.getMessage());
            framesSkipped.incrementAndGet();
        }
    }

    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        if (original.getWidth() == targetWidth && original.getHeight() == targetHeight) {
            return original;
        }

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = resized.createGraphics();

        // Use nearest neighbor for upscaling (source smaller than target)
        // This creates clean "pixel blocks" that work better with dirty region detection
        // For a 1080p video on a cinema-sized screen, each source pixel becomes a clean NxN block
        boolean isUpscaling = original.getWidth() < targetWidth || original.getHeight() < targetHeight;
        if (isUpscaling) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }

        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resized;
    }

    private void onVideoComplete() {
        stop();
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
        frameProcessor.shutdown();

        if (scheduler != null) {
            scheduler.shutdown();
        }

        if (audioManager != null) {
            audioManager.cleanup();
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
}

