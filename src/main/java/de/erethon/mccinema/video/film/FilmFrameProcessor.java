package de.erethon.mccinema.video.film;

import de.erethon.mccinema.screen.MapTile;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.PacketDispatcher;
import de.erethon.mccinema.video.PerformanceMetrics;
import de.erethon.mccinema.video.VideoTransform;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FilmFrameProcessor {
    // Bound visible prediction errors after a client render skips an interframe.
    // At 20 Hz this is at most two seconds, with only one extra full refresh
    // per 40 frames instead of making every scene cut a keyframe burst.
    private static final int MAX_ANCHOR_INTERVAL = 40;
    private static final int MIN_TEMPORAL_TOLERANCE = 4;
    // Higher values leave a visible 4x4 halo at moving high-contrast edges.
    private static final int MAX_TEMPORAL_TOLERANCE = 8;
    private final ExecutorService executor;
    private final long targetBytesPerSecond;
    private BufferedImage fitted;
    private int[] previousPixels;
    private int[] olderPixels;
    private int[] nextPixels;
    private boolean fullscreen;
    private final AtomicBoolean anchorRequested = new AtomicBoolean();
    private int framesSinceAnchor;
    private int lastFrameId;
    private volatile int temporalTolerance = 8;
    private volatile int flatRange = 8;
    private volatile int flatColorStep = 4;
    private volatile double measuredBytesPerSecond;
    private long bandwidthWindowStart;
    private long bandwidthWindowBytes;

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
        previousPixels = null;
        olderPixels = null;
        nextPixels = null;
        framesSinceAnchor = 0;
        lastFrameId = 0;
        anchorRequested.set(false);
        temporalTolerance = 8;
        measuredBytesPerSecond = 0;
        bandwidthWindowStart = 0;
        bandwidthWindowBytes = 0;
    }

    public FilmFrameProcessor(ExecutorService executor, long targetBytesPerSecond) {
        this.executor = executor;
        this.targetBytesPerSecond = Math.max(1024L * 1024L, targetBytesPerSecond);
    }

    /** Feedback changes codec reuse precision; it never defers or drops a map update. */
    public void recordBandwidth(long bytesPerViewer) {
        if (!fullscreen || bytesPerViewer <= 0) return;
        long now = System.nanoTime();
        if (bandwidthWindowStart == 0) bandwidthWindowStart = now;
        bandwidthWindowBytes += bytesPerViewer;
        long elapsed = now - bandwidthWindowStart;
        if (elapsed < 1_000_000_000L) return;
        double bytesPerSecond = bandwidthWindowBytes * 1_000_000_000.0 / elapsed;
        measuredBytesPerSecond = bytesPerSecond;
        if (bytesPerSecond > targetBytesPerSecond * 1.10) {
            temporalTolerance = Math.min(MAX_TEMPORAL_TOLERANCE, temporalTolerance + 2);
        } else if (bytesPerSecond < targetBytesPerSecond * 0.75) {
            temporalTolerance = Math.max(MIN_TEMPORAL_TOLERANCE, temporalTolerance - 1);
        }
        bandwidthWindowStart = now;
        bandwidthWindowBytes = 0;
    }

    public int getTemporalTolerance() {
        return fullscreen ? temporalTolerance : 0;
    }

    public void setFlatFilter(int channelRange, int colorStep) {
        flatRange = Math.max(0, Math.min(16, channelRange));
        flatColorStep = Math.max(1, Math.min(8, colorStep));
    }

    public int getFlatRange() {
        return flatRange;
    }

    public int getFlatColorStep() {
        return flatColorStep;
    }

    public void requestAnchor() {
        if (fullscreen) {
            anchorRequested.set(true);
        }
    }

    public double getMeasuredBytesPerSecond() {
        return measuredBytesPerSecond;
    }

    public FrameProcessor.ProcessedFrame process(BufferedImage source, Screen screen,
                                                 VideoTransform transform, PerformanceMetrics metrics) {
        long start = System.nanoTime();
        int width = screen.getPixelWidth(), height = screen.getPixelHeight();
        if (fitted == null || fitted.getWidth() != width || fitted.getHeight() != height) {
            fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }
        int[] pixels = FilmCodec.fitInto(source, fitted, transform.zoom(), transform.focusX(), transform.focusY());
        if (fullscreen) {
            clearMetadataPixels(pixels, width);
            FilmFlatPrefilter.apply(pixels, width, height, flatRange, flatColorStep);
        }
        if (metrics != null) metrics.recordUpscaling(System.nanoTime() - start);
        start = System.nanoTime();
        List<MapTile> tiles = screen.getTiles();
        PacketDispatcher.TileUpdate[] encoded = new PacketDispatcher.TileUpdate[tiles.size()];
        byte[][] full = new byte[tiles.size()][];
        int[] motionBlockCounts = fullscreen ? new int[tiles.size()] : null;
        int[] lastPixels = previousPixels;
        int[] skippedFramePixels = olderPixels;
        int[] reconstructed = null;
        if (fullscreen) {
            if (nextPixels == null || nextPixels.length != pixels.length) nextPixels = new int[pixels.length];
            reconstructed = nextPixels;
        }
        boolean requestedAnchor = anchorRequested.getAndSet(false);
        // A hard cut naturally falls back to spatial blocks in an interframe.
        // Forcing a keyframe here sends full patches for every map at once.
        boolean protectAnchor = fullscreen && (lastPixels == null || requestedAnchor);
        boolean periodicAnchor = fullscreen && framesSinceAnchor >= MAX_ANCHOR_INTERVAL;
        boolean anchor = !fullscreen || protectAnchor || periodicAnchor;
        int[] motion = fullscreen && !anchor ? estimateMotion(pixels, lastPixels, width, height) : new int[]{0, 0};
        int tolerance = temporalTolerance;
        int[] frameReference = reconstructed;
        int frameId = fullscreen ? (lastFrameId % FilmCodec.FRAME_ID_MASK) + 1 : 0;
        int referenceId = anchor ? 0 : lastFrameId;
        if (tiles.size() < 4) {
            for (int i = 0; i < tiles.size(); i++) encodeTile(tiles.get(i), pixels, lastPixels,
                skippedFramePixels, width, height,
                screen.getMapWidth(), screen.getMapHeight(), motion, anchor, tolerance,
                frameId, referenceId, motionBlockCounts, frameReference, encoded, full, i);
        } else {
            Future<?>[] futures = new Future<?>[tiles.size()];
            for (int i = 0; i < tiles.size(); i++) {
                int index = i;
                futures[i] = executor.submit(() -> encodeTile(tiles.get(index), pixels, lastPixels,
                    skippedFramePixels, width, height,
                    screen.getMapWidth(), screen.getMapHeight(), motion, anchor, tolerance,
                    frameId, referenceId, motionBlockCounts, frameReference, encoded, full, index));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception failure) {
                    throw new IllegalStateException("Could not encode experimental film frame", failure);
                }
            }
        }
        if (fullscreen) {
            if (anchor) {
                framesSinceAnchor = 0;
            } else {
                framesSinceAnchor++;
            }
            olderPixels = lastPixels;
            previousPixels = frameReference;
            nextPixels = skippedFramePixels;
            lastFrameId = frameId;
        } else {
            if (previousPixels == null || previousPixels.length != pixels.length) previousPixels = new int[pixels.length];
            System.arraycopy(pixels, 0, previousPixels, 0, pixels.length);
        }
        if (metrics != null) metrics.recordDithering(System.nanoTime() - start);
        var updates = new ArrayList<PacketDispatcher.TileUpdate>(encoded.length);
        for (PacketDispatcher.TileUpdate update : encoded) updates.add(update);
        return new FrameProcessor.ProcessedFrame(updates, full, new FrameProcessor.FrameContentStats(0, 0, 0));
    }

    private void encodeTile(MapTile tile, int[] pixels, int[] anchorPixels,
                            int[] skippedFramePixels, int width, int height,
                            int tilesWide, int tilesHigh, int[] motion, boolean anchor,
                            int tolerance, int frameId, int referenceId,
                            int[] motionBlockCounts,
                            int[] reconstructed,
                            PacketDispatcher.TileUpdate[] updates, byte[][] full, int index) {
        byte[] previousData = tile.getLastFrameData();
        byte[] data;
        if (fullscreen) {
            data = FilmCodec.encodeFullscreenTile(pixels, anchorPixels, skippedFramePixels,
                width, height,
                tile.getPixelOffsetX() / 128, tile.getPixelOffsetY() / 128,
                tilesWide, tilesHigh, previousData, motion[0], motion[1], anchor, tolerance,
                frameId, referenceId, motionBlockCounts, index);
        } else if (anchorPixels != null && anchorPixels.length == pixels.length
            && FilmCodec.isEncoded(previousData) && tileUnchanged(tile, pixels, anchorPixels, width)) {
            data = previousData;
        } else {
            data = FilmCodec.encodeTile(pixels, width, tile.getPixelOffsetX(), tile.getPixelOffsetY(), previousData);
        }
        if (fullscreen) {
            FilmCodec.reconstructFullscreenTile(data, anchorPixels, reconstructed, width, height,
                tile.getPixelOffsetX(), tile.getPixelOffsetY());
        }
        full[tile.getTileIndex()] = data;
        updates[index] = new PacketDispatcher.TileUpdate(tile, tile.calculateDirtyRegionFromSent(data, true), data);
        tile.setLastFrameData(data);
    }

    private static int[] estimateMotion(int[] current, int[] previous, int width, int height) {
        int stepX = Math.max(8, width / 24), stepY = Math.max(8, height / 14);
        long best = Long.MAX_VALUE;
        long still = sampledMotionError(current, previous, width, height, stepX, stepY, 0, 0);
        int samplesX = Math.max(0, (width - 64 + stepX - 1) / stepX);
        int samplesY = Math.max(0, (height - 64 + stepY - 1) / stepY);
        // Small per-channel changes are handled by stationary residuals. Avoid
        // searching 1,089 translations for essentially still pictures.
        if (still <= (long) samplesX * samplesY * 24) return new int[]{0, 0};
        int bestX = 0, bestY = 0;
        for (int dy = -32; dy <= 32; dy += 2) {
            for (int dx = -32; dx <= 32; dx += 2) {
                long error = sampledMotionError(current, previous, width, height, stepX, stepY, dx, dy);
                if (error < best) {
                    best = error;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        int coarseX = bestX, coarseY = bestY;
        for (int dy = Math.max(-32, coarseY - 1); dy <= Math.min(32, coarseY + 1); dy++) {
            for (int dx = Math.max(-32, coarseX - 1); dx <= Math.min(32, coarseX + 1); dx++) {
                long error = sampledMotionError(current, previous, width, height, stepX, stepY, dx, dy);
                if (error < best) {
                    best = error;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        // Grain and resampling make exact temporal matches rare. A useful translation
        // still has to improve the sampled error by at least 25% over no motion.
        return best * 4 < still * 3 ? new int[]{bestX, bestY} : new int[]{0, 0};
    }

    private static long sampledMotionError(int[] current, int[] reference, int width, int height,
                                           int stepX, int stepY, int dx, int dy) {
        long error = 0;
        for (int y = 32; y < height - 32; y += stepY) {
            for (int x = 32; x < width - 32; x += stepX) {
                int a = current[y * width + x];
                int b = reference[(y + dy) * width + x + dx];
                error += Math.abs((a & 255) - (b & 255));
                error += Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255));
                error += Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255));
            }
        }
        return error;
    }

    private static boolean tileUnchanged(MapTile tile, int[] pixels, int[] previousPixels, int width) {
        int base = tile.getPixelOffsetY() * width + tile.getPixelOffsetX();
        for (int row = 0; row < MapTile.SIZE; row++) {
            int offset = base + row * width;
            if (Arrays.mismatch(pixels, offset, offset + MapTile.SIZE,
                previousPixels, offset, offset + MapTile.SIZE) >= 0) return false;
        }
        return true;
    }

    private static void clearMetadataPixels(int[] pixels, int width) {
        for (int y = 0; y < 16; y++) {
            int row = y * width;
            for (int x = 0; x < 112; x += 48) {
                Arrays.fill(pixels, row + x, row + x + 16, 0);
            }
        }
    }
}
