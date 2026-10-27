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

public final class FilmFrameProcessor {
    // Keep a bounded recovery interval, but avoid refreshing a useful reference.
    private static final int MIN_ANCHOR_INTERVAL = 30;
    private static final int MAX_ANCHOR_INTERVAL = 60;
    private static final double MIN_ANCHOR_REUSE = 0.35;
    private static final int MIN_TEMPORAL_TOLERANCE = 4;
    private static final int MAX_TEMPORAL_TOLERANCE = 16;
    private final ExecutorService executor;
    private final long targetBytesPerSecond;
    private BufferedImage fitted;
    private int[] previousPixels;
    private int[] anchorPixels;
    private int[] nextPixels;
    private boolean fullscreen;
    private boolean anchorRequested;
    private int anchorBurstRemaining;
    private int framesSinceAnchor;
    private double lastMotionBlockFraction;
    private int[] previousMotionBlockCounts;
    private volatile int temporalTolerance = 8;
    private volatile double measuredBytesPerSecond;
    private long bandwidthWindowStart;
    private long bandwidthWindowBytes;

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
        previousPixels = null;
        anchorPixels = null;
        nextPixels = null;
        framesSinceAnchor = 0;
        lastMotionBlockFraction = 0;
        previousMotionBlockCounts = null;
        anchorRequested = false;
        anchorBurstRemaining = 0;
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

    public void requestAnchor() {
        if (fullscreen) {
            anchorRequested = true;
            anchorBurstRemaining = 0;
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
        if (metrics != null) metrics.recordUpscaling(System.nanoTime() - start);
        start = System.nanoTime();
        List<MapTile> tiles = screen.getTiles();
        PacketDispatcher.TileUpdate[] encoded = new PacketDispatcher.TileUpdate[tiles.size()];
        byte[][] full = new byte[tiles.size()][];
        int[] motionBlockCounts = fullscreen ? new int[tiles.size()] : null;
        int[] lastPixels = previousPixels;
        int[] lastMotionBlockCounts = previousMotionBlockCounts;
        int[] reconstructed = null;
        if (fullscreen) {
            if (nextPixels == null || nextPixels.length != pixels.length) nextPixels = new int[pixels.length];
            reconstructed = nextPixels;
        }
        boolean sceneCut = fullscreen && lastPixels != null && isSceneCut(pixels, lastPixels, width, height);
        boolean protectAnchor = fullscreen && (lastPixels == null || anchorRequested || sceneCut);
        boolean periodicAnchor = fullscreen && framesSinceAnchor >= MIN_ANCHOR_INTERVAL
            && (framesSinceAnchor >= MAX_ANCHOR_INTERVAL
                || lastMotionBlockFraction < MIN_ANCHOR_REUSE);
        boolean anchor = !fullscreen || protectAnchor || anchorBurstRemaining > 0
            || periodicAnchor;
        int[] stableAnchor = fullscreen ? anchorPixels : lastPixels;
        int[] motion = fullscreen && !anchor ? estimateMotion(pixels, stableAnchor, width, height) : new int[]{0, 0};
        int tolerance = temporalTolerance;
        int[] frameReference = reconstructed;
        if (tiles.size() < 4) {
            for (int i = 0; i < tiles.size(); i++) encodeTile(tiles.get(i), pixels, stableAnchor, width, height,
                screen.getMapWidth(), screen.getMapHeight(), motion, anchor, tolerance,
                lastPixels, lastMotionBlockCounts, motionBlockCounts,
                frameReference, encoded, full, i);
        } else {
            Future<?>[] futures = new Future<?>[tiles.size()];
            for (int i = 0; i < tiles.size(); i++) {
                int index = i;
                futures[i] = executor.submit(() -> encodeTile(tiles.get(index), pixels, stableAnchor, width, height,
                    screen.getMapWidth(), screen.getMapHeight(), motion, anchor, tolerance,
                    lastPixels, lastMotionBlockCounts, motionBlockCounts,
                    frameReference, encoded, full, index));
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
                anchorPixels = frameReference;
                nextPixels = lastPixels;
                framesSinceAnchor = 0;
                lastMotionBlockFraction = 0;
                // Repeat recovery anchors with the *next* source picture. The old
                // hold consumed source frames without displaying them, causing a
                // visible jump whenever the anchor interval elapsed.
                if (protectAnchor) anchorBurstRemaining = 1;
                else if (anchorBurstRemaining > 0) anchorBurstRemaining--;
            } else {
                framesSinceAnchor++;
                long reusedBlocks = 0;
                for (int count : motionBlockCounts) reusedBlocks += count;
                lastMotionBlockFraction = tiles.isEmpty() ? 0
                    : (double) reusedBlocks / (tiles.size() * 1024L);
                // Interframes only reference the retained anchor. Reconstructing
                // every decoded interframe costs a full-screen shader simulation
                // on the server without improving the next prediction. Scene-cut
                // detection can compare the fitted source pictures directly.
                System.arraycopy(pixels, 0, frameReference, 0, pixels.length);
                nextPixels = lastPixels == anchorPixels ? null : lastPixels;
            }
            previousPixels = frameReference;
            previousMotionBlockCounts = motionBlockCounts;
            anchorRequested = false;
        } else {
            if (previousPixels == null || previousPixels.length != pixels.length) previousPixels = new int[pixels.length];
            System.arraycopy(pixels, 0, previousPixels, 0, pixels.length);
        }
        if (metrics != null) metrics.recordDithering(System.nanoTime() - start);
        var updates = new ArrayList<PacketDispatcher.TileUpdate>(encoded.length);
        for (PacketDispatcher.TileUpdate update : encoded) updates.add(update);
        return new FrameProcessor.ProcessedFrame(updates, full, new FrameProcessor.FrameContentStats(0, 0, 0));
    }

    private void encodeTile(MapTile tile, int[] pixels, int[] anchorPixels, int width, int height,
                            int tilesWide, int tilesHigh, int[] motion, boolean anchor,
                            int tolerance, int[] previousSourcePixels,
                            int[] previousMotionBlockCounts, int[] motionBlockCounts,
                            int[] reconstructed,
                            PacketDispatcher.TileUpdate[] updates, byte[][] full, int index) {
        byte[] previousData = tile.getLastFrameData();
        byte[] data;
        if (fullscreen) {
            boolean unchangedInterframe = !anchor && previousSourcePixels != null
                && FilmCodec.isFullscreenEncoded(previousData)
                && !FilmCodec.isFullscreenAnchor(previousData)
                && tileUnchanged(tile, pixels, previousSourcePixels, width);
            if (unchangedInterframe) {
                data = previousData;
                if (previousMotionBlockCounts != null && previousMotionBlockCounts.length > index) {
                    motionBlockCounts[index] = previousMotionBlockCounts[index];
                }
            } else {
                data = FilmCodec.encodeFullscreenTile(pixels, anchorPixels, width, height,
                    tile.getPixelOffsetX() / 128, tile.getPixelOffsetY() / 128,
                    tilesWide, tilesHigh, previousData, motion[0], motion[1], anchor, tolerance,
                    motionBlockCounts, index);
            }
        } else if (anchorPixels != null && anchorPixels.length == pixels.length
            && FilmCodec.isEncoded(previousData) && tileUnchanged(tile, pixels, anchorPixels, width)) {
            data = previousData;
        } else {
            data = FilmCodec.encodeTile(pixels, width, tile.getPixelOffsetX(), tile.getPixelOffsetY(), previousData);
        }
        if (fullscreen && anchor) {
            // Predict from the retained anchor, independent of skipped intermediate renders.
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

    private static boolean isSceneCut(int[] current, int[] previous, int width, int height) {
        if (previous == null) return false;
        int stepX = Math.max(16, width / 64), stepY = Math.max(16, height / 36);
        int samples = 0, largeChanges = 0;
        long totalDifference = 0;
        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int a = current[y * width + x], b = previous[y * width + x];
                int difference = Math.abs((a & 255) - (b & 255))
                    + Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255))
                    + Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255));
                totalDifference += difference;
                if (difference >= 180) largeChanges++;
                samples++;
            }
        }
        // A high-contrast cut can replace a large part of a shot while leaving
        // the rest (for example a letterbox or dark background) unchanged.
        // Catch that case without treating ordinary localized motion as a cut.
        return samples > 0 && largeChanges * 5 >= samples * 2
            && totalDifference > samples * 180L;
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
}
