package de.erethon.mccinema.video;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.screen.Screen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public class VideoPlayer {

    public enum State {
        IDLE, LOADING, PLAYING, PAUSED, STOPPED
    }

    private final MCCinema plugin;
    private final Screen screen;
    private final FrameProcessor frameProcessor;
    private final PacketDispatcher packetDispatcher;
    private de.erethon.mccinema.video.film.FilmPlaybackGate filmGate;
    private boolean fullscreenPlayback;

    public void enableExperimentalFilm(org.bukkit.command.CommandSender sender, boolean fullscreen) {
        plugin.getScreenManager().attachScreenRenderers(screen);
        frameProcessor.setExperimentalFilm(true);
        frameProcessor.setFullscreenFilm(fullscreen);
        packetDispatcher.setExperimentalFilm(true);
        packetDispatcher.setFullscreenFilm(fullscreen);
        if (fullscreen) packetDispatcher.setFrameRate(Math.min(frameRate, LIVE_TARGET_FRAME_RATE));
        fullscreenPlayback = fullscreen;
        filmGate = new de.erethon.mccinema.video.film.FilmPlaybackGate(plugin, this, sender, fullscreen);
        if (audioManager != null) audioManager.setPlaybackFilter(this::canSendTo);
    }

    public void enableFullscreenMaps(org.bukkit.command.CommandSender sender) {
        plugin.getScreenManager().attachScreenRenderers(screen);
        frameProcessor.setFullscreenMaps(true);
        packetDispatcher.setFullscreenMaps(true);
        packetDispatcher.setFrameRate(Math.min(frameRate, LIVE_TARGET_FRAME_RATE));
        fullscreenPlayback = true;
        filmGate = new de.erethon.mccinema.video.film.FilmPlaybackGate(plugin, this, sender, true);
        if (audioManager != null) audioManager.setPlaybackFilter(this::canSendTo);
    }

    private File videoFile;
    private String sourceName;
    private boolean liveStream;
    private volatile VideoTransform videoTransform = VideoTransform.DEFAULT;
    private volatile FFmpegFrameGrabber grabber;
    private Java2DFrameConverter converter;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> playbackTask;
    private ScheduledExecutorService liveDecoderExecutor;
    private LiveStreamRefreshHandler liveStreamRefreshHandler;
    private String liveStreamUrl;
    private Map<String, String> liveStreamHeaders = Map.of();
    private final Object liveDecoderLock = new Object();
    private final AtomicLong liveDecoderEpoch = new AtomicLong();
    private FFmpegFrameGrabber pendingLiveGrabber;
    private Thread liveDecoderThread;
    private boolean restartLiveDecoderWhenStopped;
    private volatile boolean disposed;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicLong currentFrame = new AtomicLong(0);
    private final AtomicLong totalFrames = new AtomicLong(0);

    private double frameRate = 30.0;
    private double sourceFrameRate = 30.0;
    private long lastFullscreenOutputSlot = Long.MIN_VALUE;
    private long videoDurationMs;
    private volatile boolean liveDecoderRunning;
    private volatile boolean liveBufferReady;
    private final Object liveFrameLock = new Object();
    private final Queue<BufferedImage> liveFrameQueue = new ArrayDeque<>();

    private static final double LIVE_TARGET_FRAME_RATE = 20.0;
    private static final int LIVE_START_BUFFER_FRAMES = 20;
    private static final int LIVE_MAX_BUFFER_FRAMES = 50;

    private Consumer<VideoPlayer> onComplete;
    private Consumer<VideoPlayer> onStateChange;
    private Set<UUID> targetPlayerIds;

    private AudioManager audioManager;
    private long playbackStartTime;
    private long pausedAtFrame;
    private boolean pausedSeekFrameRendered;

    private static final long DEFAULT_AV_SYNC_THRESHOLD_NS = 150_000_000L;
    private static final int DEFAULT_MAX_DROP_FRAMES_PER_CYCLE = 8;
    private static final int DEFAULT_HARD_SEEK_THRESHOLD_FRAMES = 120;

    private final long avSyncThresholdNanos;
    private final int maxDropFramesPerCycle;
    private final int hardSeekThresholdFrames;
    private final boolean avSyncDebugLogging;
    private volatile long lastAvSyncLogNanos;

    private final AtomicLong playbackEpoch = new AtomicLong(0);
    private final AtomicLong seekEpoch = new AtomicLong(0);

    private final AtomicLong framesProcessed = new AtomicLong(0);
    private final AtomicLong framesSkipped = new AtomicLong(0);
    private final AtomicLong driftFramesDropped = new AtomicLong(0);
    private int consecutiveLateFrames;
    private long lastFrameTime;
    private volatile long lastDriftNanos = 0;

    private final Set<UUID> debugEnabledPlayers = Collections.synchronizedSet(new HashSet<>());
    private long debugLastUpdateTime = System.nanoTime();
    private int debugFrameCount = 0;
    private double debugCurrentFps = 0.0;
    private long debugAverageFrameProcessTime = 0;
    private final PerformanceMetrics performanceMetrics;

    public VideoPlayer(MCCinema plugin, Screen screen) {
        this.plugin = plugin;
        this.screen = screen;
        this.frameProcessor = new FrameProcessor(screen, plugin);
        this.packetDispatcher = new PacketDispatcher(plugin);
        this.performanceMetrics = new PerformanceMetrics();
        this.converter = new Java2DFrameConverter();

        long configuredThresholdMs = Math.max(10L,
            plugin.getConfig().getLong("performance.av-sync.threshold-ms", DEFAULT_AV_SYNC_THRESHOLD_NS / 1_000_000L));
        this.avSyncThresholdNanos = configuredThresholdMs * 1_000_000L;
        this.maxDropFramesPerCycle = Math.max(1,
            plugin.getConfig().getInt("performance.av-sync.max-drop-frames-per-cycle", DEFAULT_MAX_DROP_FRAMES_PER_CYCLE));
        this.hardSeekThresholdFrames = Math.max(1,
            plugin.getConfig().getInt("performance.av-sync.hard-seek-threshold-frames", DEFAULT_HARD_SEEK_THRESHOLD_FRAMES));
        this.avSyncDebugLogging = plugin.getConfig().getBoolean("performance.av-sync.debug-logging", false);
        this.lastAvSyncLogNanos = 0L;
    }

    public void setAudioManager(AudioManager audioManager) {
        this.audioManager = audioManager;
        if (audioManager != null && filmGate != null) audioManager.setPlaybackFilter(this::canSendTo);
        if (audioManager != null && hasTargetPlayerLimit()) {
            audioManager.setTargetPlayerIds(targetPlayerIds);
        }
    }

    public AudioManager getAudioManager() {
        return audioManager;
    }

    public void setTargetPlayerIds(Collection<UUID> targetPlayerIds) {
        if (targetPlayerIds == null || targetPlayerIds.isEmpty()) {
            this.targetPlayerIds = null;
            return;
        }
        this.targetPlayerIds = new LinkedHashSet<>(targetPlayerIds);
        if (audioManager != null) {
            audioManager.setTargetPlayerIds(this.targetPlayerIds);
        }
    }

    public boolean hasTargetPlayerLimit() {
        return targetPlayerIds != null && !targetPlayerIds.isEmpty();
    }

    public boolean canSendTo(Player player) {
        return (!hasTargetPlayerLimit() || targetPlayerIds.contains(player.getUniqueId()))
            && (filmGate == null || plugin.getFilmResourcePack().isLoaded(player, fullscreenPlayback, screen,
                frameProcessor.isExperimentalFilm()));
    }

    public Collection<Player> getPlaybackViewers() {
        if (!hasTargetPlayerLimit()) {
            return screen.getViewers();
        }

        List<Player> players = new ArrayList<>(targetPlayerIds.size());
        for (UUID playerId : targetPlayerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private Collection<? extends Player> getPacketRecipients() {
        if (filmGate != null) {
            return getPlaybackViewers().stream().filter(this::canSendTo).toList();
        }
        if (!hasTargetPlayerLimit()) {
            return screen.getViewers();
        }
        return getPlaybackViewers();
    }

    public boolean load(File videoFile) {
        if (disposed) return false;
        if (state.get() == State.PLAYING) {
            stop();
            return false;
        }

        this.videoFile = videoFile;
        this.sourceName = videoFile.getName();
        this.liveStream = false;
        this.videoTransform = plugin.getVideoTransformManager().get(videoFile);
        state.set(State.LOADING);
        notifyStateChange();

        try {
            if (grabber != null) {
                grabber.close();
            }

            grabber = new FFmpegFrameGrabber(videoFile);
            grabber.start();
            return initializeStartedGrabber(videoFile.getName(), false);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load video: " + e.getMessage());
            state.set(State.IDLE);
            notifyStateChange();
            return false;
        }
    }

    public boolean loadLiveStream(String streamUrl, String displayName) {
        return loadLiveStream(streamUrl, displayName, Map.of());
    }

    public boolean loadLiveStream(String streamUrl, String displayName, Map<String, String> headers) {
        if (disposed) return false;
        if (state.get() == State.PLAYING) {
            stop();
            return false;
        }

        this.videoFile = null;
        this.sourceName = displayName;
        this.liveStream = true;
        this.liveStreamUrl = streamUrl;
        this.liveStreamHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        this.videoTransform = VideoTransform.DEFAULT;
        state.set(State.LOADING);
        notifyStateChange();

        try {
            if (grabber != null) {
                grabber.close();
            }

            grabber = createLiveGrabber(streamUrl, headers);
            grabber.start();
            return initializeStartedGrabber(displayName, true);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load livestream: " + e.getMessage());
            try {
                if (grabber != null) {
                    grabber.close();
                }
            } catch (Exception ignored) {
            }
            grabber = null;
            state.set(State.IDLE);
            notifyStateChange();
            return false;
        }
    }

    private FFmpegFrameGrabber createLiveGrabber(String streamUrl, Map<String, String> headers) {
        FFmpegFrameGrabber liveGrabber = new FFmpegFrameGrabber(streamUrl);
        liveGrabber.setOption("reconnect", "1");
        liveGrabber.setOption("reconnect_streamed", "1");
        liveGrabber.setOption("reconnect_at_eof", "1");
        liveGrabber.setOption("reconnect_delay_max", "5");
        liveGrabber.setOption("rw_timeout", "15000000");
        applyHttpHeaders(liveGrabber, headers);
        return liveGrabber;
    }

    private void applyHttpHeaders(FFmpegFrameGrabber target, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        StringBuilder customHeaders = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().replace("\r", "").replace("\n", "").strip();
            String value = entry.getValue().replace("\r", "").replace("\n", "").strip();
            if (name.isBlank() || value.isBlank()) {
                continue;
            }
            if (name.equalsIgnoreCase("User-Agent")) {
                target.setOption("user_agent", value);
            } else if (name.equalsIgnoreCase("Referer")) {
                target.setOption("referer", value);
            } else {
                customHeaders.append(name).append(": ").append(value).append("\r\n");
            }
        }
        if (!customHeaders.isEmpty()) {
            target.setOption("headers", customHeaders.toString());
        }
    }

    private boolean initializeStartedGrabber(String label, boolean live) {
        try {
            sourceFrameRate = grabber.getFrameRate();
            if (sourceFrameRate <= 0 || sourceFrameRate > 120) {
                sourceFrameRate = 20.0;
            }
            frameRate = live ? LIVE_TARGET_FRAME_RATE : sourceFrameRate;

            packetDispatcher.setFrameRate(frameRate);

            totalFrames.set(live ? 0 : grabber.getLengthInVideoFrames());
            videoDurationMs = live ? 0 : grabber.getLengthInTime() / 1000;
            int videoWidth = grabber.getImageWidth();
            int videoHeight = grabber.getImageHeight();

            currentFrame.set(0);
            state.set(State.IDLE);
            notifyStateChange();

            int targetWidth = screen.getPixelWidth();
            int targetHeight = screen.getPixelHeight();
            configureDecodeResolution(videoWidth, videoHeight, targetWidth, targetHeight);

            plugin.getLogger().info((live ? "Loaded livestream: " : "Loaded video: ") + label);
            plugin.getLogger().info("  Resolution: " + videoWidth + "x" + videoHeight);
            if (live) {
                plugin.getLogger().info("  Source frame rate: " + String.format("%.2f", sourceFrameRate) + " fps");
                plugin.getLogger().info("  Playback frame rate: " + String.format("%.2f", frameRate) + " fps");
            } else {
                plugin.getLogger().info("  Frame rate: " + String.format("%.2f", frameRate) + " fps");
            }

            if (!live && Math.abs(frameRate - 20.0) > 0.5) {
                plugin.getLogger().warning("  Video framerate is not 20 FPS - playback may not be optimal!");
                plugin.getLogger().warning("  For YouTube videos, download without --no-convert flag to auto-convert to 20 FPS");
            }

            if (live) {
                plugin.getLogger().info("  Duration: LIVE");
            } else {
                plugin.getLogger().info("  Duration: " + formatDuration(videoDurationMs));
                plugin.getLogger().info("  Total frames: " + totalFrames.get());
            }

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
            plugin.getLogger().severe("Failed to initialize video source: " + e.getMessage());
            state.set(State.IDLE);
            notifyStateChange();
            return false;
        }
    }

    /**
     * Downscale in FFmpeg's native YUV-to-BGR conversion before Java receives the frame.
     * The returned image contains only the fitted video content; FrameProcessor adds any
     * required letter/pillar-boxing before dithering at the final screen resolution.
     *
     * Sources smaller than the screen stay at source resolution. Dithering those before
     * nearest-neighbour palette upscaling is substantially cheaper and preserves the
     * intentional large-screen pixel aesthetic.
     */
    private void configureDecodeResolution(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        configureDecodeResolution(grabber, sourceWidth, sourceHeight, targetWidth, targetHeight);
    }

    private void configureDecodeResolution(FFmpegFrameGrabber decoder, int sourceWidth, int sourceHeight,
                                           int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return;
        }

        double fitScale = Math.min(
            (double) targetWidth / sourceWidth,
            (double) targetHeight / sourceHeight
        ) * videoTransform.zoom();
        fitScale = Math.min(1.0, fitScale);
        if (fitScale >= 1.0) {
            return;
        }

        int decodeWidth = Math.max(1, (int) Math.round(sourceWidth * fitScale));
        int decodeHeight = Math.max(1, (int) Math.round(sourceHeight * fitScale));
        decoder.setImageWidth(decodeWidth);
        decoder.setImageHeight(decodeHeight);
        decoder.setImageScalingFlags(org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR);

        plugin.getLogger().info("Downscaling video during decode from " +
            sourceWidth + "x" + sourceHeight + " to " + decodeWidth + "x" + decodeHeight +
            " before dithering for screen " + screen.getName());
    }

    public void play() {
        if (disposed) return;
        State currentState = state.get();
        if (currentState == State.PLAYING) {
            return;
        }
        if (currentState == State.PAUSED) {
            resume();
            return;
        }
        if (grabber == null) {
            plugin.getLogger().warning("No video loaded");
            return;
        }

        if (!screen.hasValidOrigin()) {
            plugin.getLogger().warning("Cannot play: Screen '" + screen.getName() + "' has no valid origin location");
            return;
        }

        if (filmGate != null && !filmGate.beforePlay()) return;

        screen.updateViewerCache();
        plugin.getScreenManager().fillScreenWithPlaybackBackground(screen, getPacketRecipients());

        state.set(State.PLAYING);
        notifyStateChange();

        screen.startViewerCacheUpdater(plugin, 10L);

        ensureScheduler();
        playbackStartTime = System.nanoTime();
        lastFullscreenOutputSlot = Long.MIN_VALUE;
        lastFrameTime = playbackStartTime;
        framesProcessed.set(0);
        framesSkipped.set(0);
        driftFramesDropped.set(0);
        consecutiveLateFrames = 0;
        if (audioManager != null) {
            audioManager.play(screen.getCenterLocation());
        }
        if (liveStream) {
            startLiveDecoder();
        }
        scheduleNextFrame(-1);
        plugin.getLogger().info("Started playback of " + sourceName);
    }

    private void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MCCinema-VideoPlayer");
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
        }
    }

    public synchronized void pause() {
        if (state.get() != State.PLAYING) {
            return;
        }
        state.set(State.PAUSED);
        notifyStateChange();
        if (playbackTask != null) {
            playbackTask.cancel(false);
        }
        if (liveStream) {
            stopLiveDecoder();
        }
        pausedAtFrame = currentFrame.get();
        pausedSeekFrameRendered = false;
        if (audioManager != null) {
            audioManager.pause();
        }
        if (framesProcessed.get() > 0) {
            packetDispatcher.flushPendingFrame(screen, getPacketRecipients());
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
        consecutiveLateFrames = 0;
        if (audioManager != null) {
            audioManager.resume(screen.getCenterLocation());
        }
        if (liveStream) {
            startLiveDecoder();
        }
        if (pausedSeekFrameRendered) {
            // The requested frame is already visible. Continue with the following
            // frame after one frame interval instead of drawing it a second time.
            scheduleNextFrame(pausedAtFrame);
        } else {
            // currentFrame points at the first frame that has not been rendered yet.
            scheduleNextFrame(pausedAtFrame - 1);
        }
        pausedSeekFrameRendered = false;
        plugin.getLogger().info("Resumed playback from frame " + pausedAtFrame);
    }

    public synchronized void stop() {
        if (disposed) return;
        seekEpoch.incrementAndGet();
        if (filmGate != null) filmGate.cancel();
        State previousState = state.get();
        state.set(State.STOPPED);
        notifyStateChange();

        // Stop the viewer cache updater
        screen.stopViewerCacheUpdater();

        if (playbackTask != null) {
            playbackTask.cancel(false);
            playbackTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        if (audioManager != null) {
            audioManager.stop();
        }
        stopLiveDecoder();

        clearDebugActionBars();

        disposed = true;
        frameProcessor.shutdown();
        closeGrabber();
        screen.clearViewerCacheIfStopped();
        currentFrame.set(0);
        pausedSeekFrameRendered = false;

        if (previousState == State.PLAYING || previousState == State.PAUSED) {
            plugin.getLogger().info("Stopped playback");
        }

        state.set(State.IDLE);
        notifyStateChange();
    }

    private void closeGrabber() {
        FFmpegFrameGrabber oldGrabber = grabber;
        grabber = null;
        if (oldGrabber != null) {
            try {
                oldGrabber.close();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not close video decoder", e);
            }
        }
    }

    public void seek(long frameNumber) {
        long timeMs = Math.round(frameNumber / frameRate * 1000.0);
        seek(frameNumber, timeMs, null);
    }

    private void seek(long frameNumber, long audioTimeMs, Consumer<Boolean> completion) {
        if (grabber == null || disposed || liveStream) {
            plugin.getLogger().warning("Cannot seek: no local video is available");
            if (completion != null) completion.accept(false);
            return;
        }
        State currentState = state.get();
        if (audioManager != null) {
            audioManager.stop();
        }
        if (playbackTask != null) playbackTask.cancel(false);
        playbackEpoch.incrementAndGet();
        long request = seekEpoch.incrementAndGet();
        long targetFrame = Math.max(0, Math.min(frameNumber, totalFrames.get() - 1));
        ensureScheduler();
        try {
            scheduler.execute(() -> {
                boolean positioned = false;
                try {
                    synchronized (this) {
                        if (request != seekEpoch.get() || disposed || grabber == null) return;
                        grabber.setFrameNumber((int) targetFrame);
                        currentFrame.set(targetFrame);
                        lastFullscreenOutputSlot = Long.MIN_VALUE;
                        consecutiveLateFrames = 0;
                        if (fullscreenPlayback && frameProcessor.isExperimentalFilm()) frameProcessor.requestFilmAnchor();
                        if (state.get() == State.PAUSED) {
                            pausedAtFrame = targetFrame;
                            pausedSeekFrameRendered = renderPausedSeekFrame(targetFrame);
                            currentFrame.set(pausedSeekFrameRendered ? targetFrame + 1 : targetFrame);
                        }
                        positioned = true;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to seek", e);
                }
                boolean success = positioned;
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (request != seekEpoch.get() || disposed) return;
                    if (success) {
                        boolean resumePlayback = currentState == State.PLAYING && state.get() == State.PLAYING;
                        if (audioManager != null) {
                            audioManager.completeSeek(audioTimeMs, screen.getCenterLocation(), resumePlayback);
                        }
                        if (resumePlayback) {
                            playbackStartTime = System.nanoTime()
                                - (long)(targetFrame / frameRate * 1_000_000_000L);
                            pausedSeekFrameRendered = false;
                            scheduleNextFrame(targetFrame - 1);
                        } else if (state.get() == State.PAUSED) {
                            pausedAtFrame = targetFrame;
                        }
                    } else if (state.get() == State.PLAYING) {
                        state.set(State.PAUSED);
                        pausedAtFrame = currentFrame.get();
                        notifyStateChange();
                    }
                    if (completion != null) completion.accept(success);
                });
            });
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule seek", e);
            if (completion != null) completion.accept(false);
        }
    }

    /**
     * Decode and send one requested frame without starting playback. A full
     * snapshot is intentional here: while paused there are no later frames that
     * could flush updates deferred by the normal bandwidth budget.
     */
    private boolean renderPausedSeekFrame(long frameNumber) {
        long frameStartTime = System.nanoTime();
        try {
            long decodeStart = System.nanoTime();
            Frame frame = grabber.grabImage();
            long decodeEnd = System.nanoTime();
            performanceMetrics.recordFrameDecode(decodeEnd - decodeStart);
            if (frame == null || frame.image == null) {
                plugin.getLogger().warning("Could not render paused seek frame " + frameNumber);
                return false;
            }

            long conversionStart = System.nanoTime();
            BufferedImage image = converter.convert(frame);
            long conversionEnd = System.nanoTime();
            performanceMetrics.recordImageConversion(conversionEnd - conversionStart);
            if (image == null) {
                plugin.getLogger().warning("Could not convert paused seek frame " + frameNumber);
                return false;
            }

            FrameProcessor.ProcessedFrame processedFrame = frameProcessor.processFrame(
                image, screen.getPixelWidth(), screen.getPixelHeight(), performanceMetrics, videoTransform
            );

            long dispatchStart = System.nanoTime();
            packetDispatcher.dispatchImmediateFrame(screen, processedFrame.fullMapData(), getPacketRecipients());
            if (fullscreenPlayback) {
                lastFullscreenOutputSlot = Math.round(frameNumber * LIVE_TARGET_FRAME_RATE / frameRate);
            }
            performanceMetrics.recordPacketDispatch(System.nanoTime() - dispatchStart);

            framesProcessed.incrementAndGet();
            lastFrameTime = System.nanoTime();
            performanceMetrics.recordTotalFrame(lastFrameTime - frameStartTime);
            updateDebugMetrics(frameStartTime);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to render paused seek frame " + frameNumber, e);
            framesSkipped.incrementAndGet();
            return false;
        }
    }

    public void seekToTime(long milliseconds) {
        seekToTime(milliseconds, null);
    }

    public void seekToTime(long milliseconds, Consumer<Boolean> completion) {
        long targetFrame = Math.round(milliseconds / 1000.0 * frameRate);
        seek(targetFrame, milliseconds, completion);
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
        long epoch = playbackEpoch.get();
        long currentFrameNumber = processNextFrame();
        if (playbackEpoch.get() == epoch) {
            scheduleNextFrame(currentFrameNumber);
        }
    }

    private synchronized long processNextFrame() {
        if (state.get() != State.PLAYING) {
            return currentFrame.get();
        }
        long frameStartTime = System.nanoTime();
        try {
            long elapsedNanos = frameStartTime - playbackStartTime;
            long videoPositionNanos = (long)(currentFrame.get() / frameRate * 1_000_000_000.0);
            long driftNanos = elapsedNanos - videoPositionNanos;
            lastDriftNanos = driftNanos;
            boolean experimentalFullscreen = fullscreenPlayback && frameProcessor.isExperimentalFilm();
            consecutiveLateFrames = experimentalFullscreen && driftNanos > avSyncThresholdNanos
                ? consecutiveLateFrames + 1 : 0;

            if (!liveStream && driftNanos > avSyncThresholdNanos) {
                long targetFrame = (long)(elapsedNanos / 1_000_000_000.0 * frameRate);
                long total = totalFrames.get();
                if (targetFrame >= total) {
                    targetFrame = total - 1;
                }
                long currentFrameNum = currentFrame.get();
                long framesToSkip = targetFrame - currentFrameNum;
                // A full anchor can cause a single expensive cycle. Allow two
                // cycles to recover before dropping displayed fullscreen frames.
                if (framesToSkip > 0 && (!experimentalFullscreen || consecutiveLateFrames >= 3
                    || framesToSkip >= hardSeekThresholdFrames)) {
                    if (framesToSkip >= hardSeekThresholdFrames) {
                        grabber.setFrameNumber((int) targetFrame);
                        currentFrame.set(targetFrame);
                        if (fullscreenPlayback && frameProcessor.isExperimentalFilm()) frameProcessor.requestFilmAnchor();
                        framesSkipped.addAndGet(framesToSkip);
                        driftFramesDropped.addAndGet(framesToSkip);
                        logAvSync("A/V sync hard-seek: skipped " + framesToSkip + " frames " +
                            "(drift: " + (driftNanos / 1_000_000) + "ms, jumped to frame " + targetFrame + ")");
                    } else {
                        int framesToDrop = (int) Math.min(framesToSkip,
                            experimentalFullscreen ? 1 : maxDropFramesPerCycle);
                        int dropped = dropFramesSequential(framesToDrop);
                        if (dropped > 0) {
                            currentFrame.addAndGet(dropped);
                            framesSkipped.addAndGet(dropped);
                            driftFramesDropped.addAndGet(dropped);
                            logAvSync("A/V sync drop: dropped " + dropped + " frame(s) " +
                                "(drift: " + (driftNanos / 1_000_000) + "ms, target frame " + targetFrame + ")");
                        }
                        if (state.get() != State.PLAYING) {
                            return currentFrame.get();
                        }
                    }
                }
            } else if (!liveStream && driftNanos < -avSyncThresholdNanos) {
                playbackStartTime = frameStartTime - videoPositionNanos;
                lastDriftNanos = 0;
                logAvSync("A/V sync re-anchored playback clock (video ahead by " + ((-driftNanos) / 1_000_000) + "ms)");
            }

            long decodeStart = System.nanoTime();
            long frameNum = currentFrame.get();
            long fullscreenSlot = Long.MIN_VALUE;
            BufferedImage image;
            if (liveStream) {
                image = pollLiveFrame();
                long decodeEnd = System.nanoTime();
                performanceMetrics.recordFrameDecode(decodeEnd - decodeStart);
                if (image == null) {
                    framesSkipped.incrementAndGet();
                    return frameNum;
                }
                frameNum = currentFrame.getAndIncrement();
            } else {
                Frame frame = grabber.grabImage();
                long decodeEnd = System.nanoTime();
                if (frame == null || frame.image == null) {
                    onVideoComplete();
                    return currentFrame.get();
                }
                performanceMetrics.recordFrameDecode(decodeEnd - decodeStart);
                frameNum = currentFrame.getAndIncrement();

                // Map textures are presented on the client tick. Decode source
                // frames for timing, but send at most one picture per 20 Hz slot.
                fullscreenSlot = fullscreenPlayback && frameRate > LIVE_TARGET_FRAME_RATE
                    ? Math.round(frameNum * LIVE_TARGET_FRAME_RATE / frameRate) : Long.MIN_VALUE;
                if (fullscreenSlot != Long.MIN_VALUE && fullscreenSlot == lastFullscreenOutputSlot) {
                    framesSkipped.incrementAndGet();
                    return frameNum;
                }

                long conversionStart = System.nanoTime();
                image = converter.convert(frame);
                long conversionEnd = System.nanoTime();
                if (image == null) {
                    framesSkipped.incrementAndGet();
                    return frameNum;
                }
                performanceMetrics.recordImageConversion(conversionEnd - conversionStart);
                if (fullscreenSlot != Long.MIN_VALUE) lastFullscreenOutputSlot = fullscreenSlot;
            }
            FrameProcessor.ProcessedFrame processedFrame = frameProcessor.processFrame(
                image, screen.getPixelWidth(), screen.getPixelHeight(), performanceMetrics, videoTransform
            );

            if (fullscreenSlot != Long.MIN_VALUE) {
                long presentationTime = playbackStartTime
                    + fullscreenSlot * (long) (1_000_000_000L / LIVE_TARGET_FRAME_RATE);
                long waitNanos = presentationTime - System.nanoTime();
                if (waitNanos > 0) java.util.concurrent.locks.LockSupport.parkNanos(waitNanos);
            }

            long dispatchStart = System.nanoTime();
            var recipients = getPacketRecipients();
            packetDispatcher.dispatchFrame(screen, processedFrame.updates(), processedFrame.contentStats(), performanceMetrics, recipients);
            if (fullscreenPlayback && frameProcessor.isExperimentalFilm() && !recipients.isEmpty()) {
                frameProcessor.recordFilmBandwidth(packetDispatcher.getLastFrameBytesSent());
            }
            long dispatchEnd = System.nanoTime();
            performanceMetrics.recordPacketDispatch(dispatchEnd - dispatchStart);
            framesProcessed.incrementAndGet();
            lastFrameTime = System.nanoTime();
            performanceMetrics.recordTotalFrame(lastFrameTime - frameStartTime);
            updateDebugMetrics(frameStartTime);
            return frameNum;

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error processing frame", e);
            framesSkipped.incrementAndGet();
            return currentFrame.get();
        }
    }

    private int dropFramesSequential(int framesToDrop) throws Exception {
        int dropped = 0;
        long dropStartNanos = System.nanoTime();
        long dropBudgetNanos = Math.max(1L, (long) (1_000_000_000.0 / frameRate));
        for (int i = 0; i < framesToDrop; i++) {
            // Decode reference frames, but do not spend time converting discarded
            // YUV frames to BGR. Conversion is often the dominant 4K frame cost.
            Frame droppedFrame = grabber.grabFrame(false, true, false, false, false);
            if (droppedFrame == null) {
                onVideoComplete();
                return dropped;
            }
            dropped++;
            if (System.nanoTime() - dropStartNanos >= dropBudgetNanos) {
                break;
            }
        }
        return dropped;
    }

    private BufferedImage pollLiveFrame() {
        synchronized (liveFrameLock) {
            if (!liveBufferReady) {
                if (liveFrameQueue.size() >= LIVE_START_BUFFER_FRAMES) {
                    liveBufferReady = true;
                } else {
                    return null;
                }
            }
            return liveFrameQueue.poll();
        }
    }

    private void startLiveDecoder() {
        if (liveDecoderRunning) {
            return;
        }
        synchronized (liveDecoderLock) {
            if (liveDecoderThread != null && liveDecoderThread.isAlive()) {
                // Native reads may finish after close; never run two decoders at once.
                restartLiveDecoderWhenStopped = true;
                return;
            }
            restartLiveDecoderWhenStopped = false;
        }
        liveDecoderRunning = true;
        long epoch = liveDecoderEpoch.incrementAndGet();
        clearLiveFrameQueue();
        liveDecoderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCCinema-LiveDecoder");
            t.setDaemon(true);
            synchronized (liveDecoderLock) {
                liveDecoderThread = t;
            }
            return t;
        });
        liveDecoderExecutor.execute(() -> {
            try {
            Java2DFrameConverter liveConverter = new Java2DFrameConverter();
            double nextOutputFrame = 0.0;
            long sourceFrameIndex = 0;
            int consecutiveFailures = 0;
            long nextRefreshAttemptNanos = 0L;
            while (isLiveDecoderCurrent(epoch)) {
                try {
                    FFmpegFrameGrabber decoder = grabber;
                    if (decoder == null) {
                        decoder = reopenLiveGrabber(epoch);
                        if (decoder == null) break;
                    }
                    Frame frame = decoder.grabImage();
                    if (!isLiveDecoderCurrent(epoch)) break;
                    if (frame == null || frame.image == null) {
                        consecutiveFailures++;
                        if (consecutiveFailures >= 20 && System.nanoTime() >= nextRefreshAttemptNanos) {
                            if (refreshLiveStream(epoch)) {
                                consecutiveFailures = 0;
                                nextRefreshAttemptNanos = 0L;
                                nextOutputFrame = 0.0;
                                sourceFrameIndex = 0;
                            } else {
                                nextRefreshAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            }
                        } else {
                            Thread.sleep(100);
                        }
                        continue;
                    }
                    consecutiveFailures = 0;
                    double sourceFramesPerOutputFrame = Math.max(1.0, sourceFrameRate / LIVE_TARGET_FRAME_RATE);
                    boolean keepFrame = sourceFrameIndex + 0.0001 >= nextOutputFrame;
                    sourceFrameIndex++;
                    if (!keepFrame) {
                        framesSkipped.incrementAndGet();
                        continue;
                    }
                    nextOutputFrame += sourceFramesPerOutputFrame;

                    BufferedImage image = liveConverter.convert(frame);
                    if (image != null && isLiveDecoderCurrent(epoch)) {
                        BufferedImage copy = copyImage(image);
                        if (isLiveDecoderCurrent(epoch)) enqueueLiveFrame(copy);
                    }
                } catch (Exception e) {
                    if (isLiveDecoderCurrent(epoch)) {
                        consecutiveFailures++;
                        if (consecutiveFailures >= 3 && System.nanoTime() >= nextRefreshAttemptNanos && refreshLiveStream(epoch)) {
                            consecutiveFailures = 0;
                            nextRefreshAttemptNanos = 0L;
                            nextOutputFrame = 0.0;
                            sourceFrameIndex = 0;
                        } else {
                            if (consecutiveFailures >= 3 && System.nanoTime() >= nextRefreshAttemptNanos) {
                                nextRefreshAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            }
                            if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
                                plugin.getLogger().warning("Livestream decode stalled: " + e.getMessage());
                            }
                            try {
                                Thread.sleep(Math.min(2000, 200L * consecutiveFailures));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            }
            } finally {
                boolean restart;
                synchronized (liveDecoderLock) {
                    liveDecoderThread = null;
                    restart = restartLiveDecoderWhenStopped;
                    restartLiveDecoderWhenStopped = false;
                }
                if (restart) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!disposed && state.get() == State.PLAYING) startLiveDecoder();
                    });
                }
            }
        });
    }

    private boolean isLiveDecoderCurrent(long epoch) {
        return liveDecoderRunning && liveDecoderEpoch.get() == epoch;
    }

    private FFmpegFrameGrabber reopenLiveGrabber(long epoch) throws Exception {
        FFmpegFrameGrabber replacement = createLiveGrabber(liveStreamUrl, liveStreamHeaders);
        try {
            synchronized (liveDecoderLock) {
                if (!isLiveDecoderCurrent(epoch)) return null;
                pendingLiveGrabber = replacement;
            }
            replacement.start();
            configureDecodeResolution(replacement, replacement.getImageWidth(), replacement.getImageHeight(),
                screen.getPixelWidth(), screen.getPixelHeight());
            synchronized (liveDecoderLock) {
                if (!isLiveDecoderCurrent(epoch)) return null;
                grabber = replacement;
                pendingLiveGrabber = null;
            }
            return replacement;
        } finally {
            synchronized (liveDecoderLock) {
                if (pendingLiveGrabber == replacement) pendingLiveGrabber = null;
            }
            if (grabber != replacement) closeLiveGrabber(replacement);
        }
    }

    private boolean refreshLiveStream(long epoch) {
        if (!isLiveDecoderCurrent(epoch) || liveStreamRefreshHandler == null) {
            return false;
        }
        FFmpegFrameGrabber replacement = null;
        try {
            plugin.getLogger().info("Refreshing expired or stalled livestream connection...");
            LiveStreamSource source = liveStreamRefreshHandler.refresh();
            replacement = createLiveGrabber(source.streamUrl(), source.headers());
            Map<String, String> refreshedHeaders = source.headers() == null ? Map.of() : Map.copyOf(source.headers());
            synchronized (liveDecoderLock) {
                if (!isLiveDecoderCurrent(epoch)) return false;
                pendingLiveGrabber = replacement;
            }
            replacement.start();
            configureDecodeResolution(replacement, replacement.getImageWidth(), replacement.getImageHeight(),
                screen.getPixelWidth(), screen.getPixelHeight());
            double refreshedFrameRate = replacement.getFrameRate();
            FFmpegFrameGrabber previous;
            synchronized (liveDecoderLock) {
                if (!isLiveDecoderCurrent(epoch)) return false;
                previous = grabber;
                grabber = replacement;
                pendingLiveGrabber = null;
                liveStreamUrl = source.streamUrl();
                liveStreamHeaders = refreshedHeaders;
                if (refreshedFrameRate > 0 && refreshedFrameRate <= 120) {
                    sourceFrameRate = refreshedFrameRate;
                }
            }
            replacement = null;
            closeLiveGrabber(previous);
            clearLiveFrameQueue();
            plugin.getLogger().info("Livestream connection refreshed.");
            return true;
        } catch (Exception error) {
            plugin.getLogger().warning("Could not refresh livestream connection: " + error.getMessage());
            return false;
        } finally {
            if (replacement != null) {
                synchronized (liveDecoderLock) {
                    if (pendingLiveGrabber == replacement) pendingLiveGrabber = null;
                }
                closeLiveGrabber(replacement);
            }
        }
    }

    private void stopLiveDecoder() {
        liveDecoderRunning = false;
        liveDecoderEpoch.incrementAndGet();
        synchronized (liveDecoderLock) {
            restartLiveDecoderWhenStopped = false;
        }
        if (liveDecoderExecutor != null) {
            liveDecoderExecutor.shutdownNow();
            liveDecoderExecutor = null;
        }
        if (liveStream) {
            FFmpegFrameGrabber previous;
            FFmpegFrameGrabber pending;
            synchronized (liveDecoderLock) {
                previous = grabber;
                grabber = null;
                pending = pendingLiveGrabber;
                pendingLiveGrabber = null;
            }
            closeLiveGrabber(previous);
            if (pending != previous) closeLiveGrabber(pending);
        }
        clearLiveFrameQueue();
    }

    private void closeLiveGrabber(FFmpegFrameGrabber decoder) {
        if (decoder == null) return;
        try {
            decoder.close();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not close livestream decoder", e);
        }
    }

    private void enqueueLiveFrame(BufferedImage image) {
        synchronized (liveFrameLock) {
            while (liveFrameQueue.size() >= LIVE_MAX_BUFFER_FRAMES) {
                liveFrameQueue.poll();
                framesSkipped.incrementAndGet();
            }
            liveFrameQueue.offer(image);
        }
    }

    private void clearLiveFrameQueue() {
        synchronized (liveFrameLock) {
            liveFrameQueue.clear();
            liveBufferReady = false;
        }
    }

    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private void logAvSync(String message) {
        if (!avSyncDebugLogging) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastAvSyncLogNanos < 1_000_000_000L) {
            return;
        }
        lastAvSyncLogNanos = now;
        plugin.getLogger().fine(message);
    }

    private void onVideoComplete() {
        if (!state.compareAndSet(State.PLAYING, State.STOPPED)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            stop();
            if (plugin.getVideoPlayer(screen) != this) return;
            plugin.getScreenManager().fillScreenWithBlankColor(screen);
            plugin.getLogger().info("Video playback complete");
            if (onComplete != null) {
                onComplete.accept(this);
            }
        });
    }

    private void notifyStateChange() {
        if (onStateChange != null) {
            onStateChange.accept(this);
        }
    }

    public void shutdown() {
        stop();
        liveStreamRefreshHandler = null;
        if (audioManager != null) {
            audioManager.cleanup();
        }
    }

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
        return !liveStream && total > 0 ? (double) currentFrame.get() / total : 0;
    }

    public long getCurrentTimeMs() {
        return (long) (currentFrame.get() / frameRate * 1000);
    }

    public long getTotalDurationMs() {
        return videoDurationMs;
    }

    public boolean isLiveStream() {
        return liveStream;
    }

    public String getSourceName() {
        return sourceName;
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

    public VideoTransform getVideoTransform() {
        return videoTransform;
    }

    public void setVideoTransform(VideoTransform videoTransform) {
        this.videoTransform = videoTransform == null ? VideoTransform.DEFAULT : videoTransform;
    }

    public void setOnComplete(Consumer<VideoPlayer> onComplete) {
        this.onComplete = onComplete;
    }

    public void setOnStateChange(Consumer<VideoPlayer> onStateChange) {
        this.onStateChange = onStateChange;
    }

    public void setLiveStreamRefreshHandler(LiveStreamRefreshHandler liveStreamRefreshHandler) {
        this.liveStreamRefreshHandler = liveStreamRefreshHandler;
    }

    @FunctionalInterface
    public interface LiveStreamRefreshHandler {
        LiveStreamSource refresh() throws Exception;
    }

    public record LiveStreamSource(String streamUrl, Map<String, String> headers) {
        public LiveStreamSource {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
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
        double measuredFilmBytes = fullscreenPlayback && frameProcessor.isExperimentalFilm()
            ? frameProcessor.getFilmMeasuredBytesPerSecond() : 0;
        long bytesPerSecond = measuredFilmBytes > 0
            ? (long) measuredFilmBytes
            : (long) (debugCurrentFps * packetDispatcher.getLastFrameBytesSent());
        long decode = performanceMetrics.getLastFrameDecodeUs();
        long dither = performanceMetrics.getLastDitheringUs();
        long upscale = performanceMetrics.getLastUpscalingUs();
        long tiles = performanceMetrics.getLastTileExtractionUs();
        long packetDispatch = performanceMetrics.getLastPacketDispatchUs();
        long total = performanceMetrics.getLastTotalFrameUs();
        long frameBytes = packetDispatcher.getLastFrameBytesSent();
        int sentTiles = packetDispatcher.getLastFrameTileCount();
        int multiRegionTiles = packetDispatcher.getLastFrameMultiRegionTileCount();
        long boundingBytes = packetDispatcher.getLastFrameBoundingBytes();
        long fullMapBytes = packetDispatcher.getLastFrameFullMapBytes();
        long frameCapBytes = packetDispatcher.getLastFrameByteCap();
        double boxSavePercent = boundingBytes > 0 ? (1.0 - ((double) frameBytes / boundingBytes)) * 100.0 : 0.0;
        double fullSavePercent = fullMapBytes > 0 ? (1.0 - ((double) frameBytes / fullMapBytes)) * 100.0 : 0.0;
        long avgBytesPerTile = sentTiles > 0 ? frameBytes / sentTiles : 0;

        String message = String.format(
            "<gray>FPS: <white>%.1f</white> <dark_gray>|</dark_gray> " +
            "T: <white>%.1fms</white> <dark_gray>[</dark_gray>" +
            "<yellow>D:%.1f</yellow> <gold>" + (frameProcessor.isExperimentalFilm() ? "C:" : "Di:") +
            "%.1f</gold> <gold>U:%.1f</gold> <aqua>Ti:%.1f</aqua> <blue>P:%.1f</blue>" +
            "<dark_gray>]</dark_gray> " +
            "<dark_gray>|</dark_gray> BW~: <white>%s/s</white> <gray>(%s/f cap:%s)</gray> " +
            "<dark_gray>|</dark_gray> <light_purple>Tiles:<white>%d</white> MR:<white>%d</white> Avg:<white>%s</white> Save:<white>%.0f%%</white>/<green>%.0f%%</green></light_purple>" +
            " <dark_gray>|</dark_gray> %s <red>Drop:<white>%d</white></red>",
            debugCurrentFps,
            total / 1000.0,
            decode / 1000.0,
            dither / 1000.0,
            upscale / 1000.0,
            tiles / 1000.0,
            packetDispatch / 1000.0,
            formatBytes(bytesPerSecond),
            formatBytes(frameBytes),
            formatBytes(frameCapBytes),
            sentTiles,
            multiRegionTiles,
            formatBytes(avgBytesPerTile),
            boxSavePercent,
            fullSavePercent,
            formatDriftTag(lastDriftNanos),
            driftFramesDropped.get()
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

    private static String formatDriftTag(long driftNanos) {
        long driftMs = driftNanos / 1_000_000;
        String color;
        if (Math.abs(driftMs) < 50) {
            color = "green";
        } else if (Math.abs(driftMs) < 150) {
            color = "yellow";
        } else {
            color = "red";
        }
        return "<" + color + ">Drift:<white>" + driftMs + "ms</white></" + color + ">";
    }
}

