package de.erethon.mccinema.video.film;

import de.erethon.mccinema.screen.MapTile;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.PacketDispatcher;
import de.erethon.mccinema.video.PerformanceMetrics;
import de.erethon.mccinema.video.VideoTransform;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class FilmFrameProcessor {
    private final ExecutorService executor;
    private BufferedImage fitted;

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
        if (tiles.size() < 4) {
            for (int i = 0; i < tiles.size(); i++) encodeTile(tiles.get(i), pixels, width, encoded, full, i);
        } else {
            Future<?>[] futures = new Future<?>[tiles.size()];
            for (int i = 0; i < tiles.size(); i++) {
                int index = i;
                futures[i] = executor.submit(() -> encodeTile(tiles.get(index), pixels, width, encoded, full, index));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception failure) {
                    throw new IllegalStateException("Could not encode experimental film frame", failure);
                }
            }
        }
        if (metrics != null) metrics.recordDithering(System.nanoTime() - start);
        var updates = new ArrayList<PacketDispatcher.TileUpdate>(encoded.length);
        for (PacketDispatcher.TileUpdate update : encoded) updates.add(update);
        return new FrameProcessor.ProcessedFrame(updates, full, new FrameProcessor.FrameContentStats(0, 0, 0));
    }

    private static void encodeTile(MapTile tile, int[] pixels, int width,
                                   PacketDispatcher.TileUpdate[] updates, byte[][] full, int index) {
        byte[] data = FilmCodec.encodeTile(pixels, width, tile.getPixelOffsetX(), tile.getPixelOffsetY(),
            tile.getLastFrameData());
        full[tile.getTileIndex()] = data;
        updates[index] = new PacketDispatcher.TileUpdate(tile, tile.calculateDirtyRegionFromSent(data, true), data);
        tile.setLastFrameData(data);
    }
}
