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
    private final ExecutorService executor;
    private BufferedImage fitted;
    private int[] previousPixels;

    public FilmFrameProcessor(ExecutorService executor) {
        this.executor = executor;
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
        int[] lastPixels = previousPixels;
        if (tiles.size() < 4) {
            for (int i = 0; i < tiles.size(); i++) encodeTile(tiles.get(i), pixels, lastPixels, width, encoded, full, i);
        } else {
            Future<?>[] futures = new Future<?>[tiles.size()];
            for (int i = 0; i < tiles.size(); i++) {
                int index = i;
                futures[i] = executor.submit(() -> encodeTile(tiles.get(index), pixels, lastPixels, width, encoded, full, index));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception failure) {
                    throw new IllegalStateException("Could not encode experimental film frame", failure);
                }
            }
        }
        if (previousPixels == null || previousPixels.length != pixels.length) previousPixels = new int[pixels.length];
        System.arraycopy(pixels, 0, previousPixels, 0, pixels.length);
        if (metrics != null) metrics.recordDithering(System.nanoTime() - start);
        var updates = new ArrayList<PacketDispatcher.TileUpdate>(encoded.length);
        for (PacketDispatcher.TileUpdate update : encoded) updates.add(update);
        return new FrameProcessor.ProcessedFrame(updates, full, new FrameProcessor.FrameContentStats(0, 0, 0));
    }

    private static void encodeTile(MapTile tile, int[] pixels, int[] previousPixels, int width,
                                   PacketDispatcher.TileUpdate[] updates, byte[][] full, int index) {
        byte[] previousData = tile.getLastFrameData();
        byte[] data = previousPixels != null && previousPixels.length == pixels.length
            && FilmCodec.isEncoded(previousData) && tileUnchanged(tile, pixels, previousPixels, width)
            ? previousData
            : FilmCodec.encodeTile(pixels, width, tile.getPixelOffsetX(), tile.getPixelOffsetY(), previousData);
        full[tile.getTileIndex()] = data;
        updates[index] = new PacketDispatcher.TileUpdate(tile, tile.calculateDirtyRegionFromSent(data, true), data);
        tile.setLastFrameData(data);
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
