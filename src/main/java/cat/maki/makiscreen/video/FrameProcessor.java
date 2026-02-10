package cat.maki.makiscreen.video;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.screen.MapTile;
import cat.maki.makiscreen.screen.Screen;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static cat.maki.makiscreen.dither.DitherLookupUtil.COLOR_MAP;
import static cat.maki.makiscreen.dither.DitherLookupUtil.FULL_COLOR_MAP;

public class FrameProcessor {

    public enum DitheringMode {
        FLOYD_STEINBERG,        // Classic, high quality but noisy
        FLOYD_STEINBERG_REDUCED, // 75% error diffusion - less noise
        BAYER_8X8,              // Ordered dithering - very compression-friendly
        NONE                     // No dithering (for testing)
    }

    // 8x8 Bayer matrix for ordered dithering - produces stable, compressible patterns
    private static final int[][] BAYER_MATRIX_8x8 = {
        { 0, 32,  8, 40,  2, 34, 10, 42},
        {48, 16, 56, 24, 50, 18, 58, 26},
        {12, 44,  4, 36, 14, 46,  6, 38},
        {60, 28, 52, 20, 62, 30, 54, 22},
        { 3, 35, 11, 43,  1, 33,  9, 41},
        {51, 19, 59, 27, 49, 17, 57, 25},
        {15, 47,  7, 39, 13, 45,  5, 37},
        {63, 31, 55, 23, 61, 29, 53, 21}
    };
    private static final int BAYER_SCALE = 64;

    private final Screen screen;
    private final int frameWidth;
    private final int frameHeight;
    private final ExecutorService executor;

    private byte[] ditheredFrameData;
    private int[][] ditherBuffer;

    private DitheringMode ditheringMode = DitheringMode.FLOYD_STEINBERG_REDUCED;
    private float errorDiffusionStrength = 0.75f; // Reduced from 1.0 to lower noise

    public FrameProcessor(Screen screen) {
        this.screen = screen;
        this.frameWidth = screen.getPixelWidth();
        this.frameHeight = screen.getPixelHeight();
        this.ditheredFrameData = new byte[frameWidth * frameHeight];
        this.ditherBuffer = new int[2][frameWidth * 3];

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "MakiScreen-FrameProcessor");
            t.setDaemon(true);
            return t;
        });
    }

    public void setDitheringMode(DitheringMode mode) {
        this.ditheringMode = mode;
    }

    public DitheringMode getDitheringMode() {
        return ditheringMode;
    }

    public void setErrorDiffusionStrength(float strength) {
        this.errorDiffusionStrength = Math.max(0.0f, Math.min(1.0f, strength));
    }

    public ProcessedFrame processFrame(BufferedImage image) {
        byte[] frameData = extractFrameData(image);
        ditherFrame(frameData);

        List<PacketDispatcher.TileUpdate> updates = new ArrayList<>(screen.getTotalMaps());
        byte[][] fullMapData = new byte[screen.getTotalMaps()][];

        for (MapTile tile : screen.getTiles()) {
            byte[] mapData = extractMapData(tile);
            fullMapData[tile.getTileIndex()] = mapData;

            // Compare against last SENT data, not last processed
            // This ensures skipped tiles accumulate their changes properly
            MapTile.DirtyRegion dirtyRegion = tile.calculateDirtyRegionFromSent(mapData);

            // Always add the tile to updates with the full mapData
            // PacketDispatcher will handle prioritization and tracking
            updates.add(new PacketDispatcher.TileUpdate(tile, dirtyRegion, mapData));

            // Store the current frame data for reference
            tile.setLastFrameData(mapData.clone());
        }

        return new ProcessedFrame(updates, fullMapData);
    }

    public Future<ProcessedFrame> processFrameAsync(BufferedImage image) {
        return executor.submit(() -> processFrame(image));
    }

    private byte[] extractFrameData(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        }

        BufferedImage converted = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_3BYTE_BGR);
        converted.getGraphics().drawImage(image, 0, 0, frameWidth, frameHeight, null);
        return ((DataBufferByte) converted.getRaster().getDataBuffer()).getData();
    }

    private byte[] extractMapData(MapTile tile) {
        byte[] mapData = new byte[MapTile.SIZE * MapTile.SIZE];

        int startX = tile.getPixelOffsetX();
        int startY = tile.getPixelOffsetY();

        int offset = 0;
        for (int y = startY; y < startY + MapTile.SIZE; y++) {
            int yIndex = y * frameWidth;
            for (int x = startX; x < startX + MapTile.SIZE; x++) {
                mapData[offset++] = ditheredFrameData[yIndex + x];
            }
        }

        return mapData;
    }

    private void ditherFrame(byte[] frameData) {
        switch (ditheringMode) {
            case FLOYD_STEINBERG:
                ditherFrameFloydSteinberg(frameData, 1.0f);
                break;
            case FLOYD_STEINBERG_REDUCED:
                ditherFrameFloydSteinberg(frameData, errorDiffusionStrength);
                break;
            case BAYER_8X8:
                ditherFrameBayer(frameData);
                break;
            case NONE:
                ditherFrameNone(frameData);
                break;
        }
    }

    /**
     * No dithering - just nearest color matching.
     * Produces blocky results but very compressible.
     */
    private void ditherFrameNone(byte[] frameData) {
        int width = this.frameWidth;
        int height = this.frameHeight;

        for (int y = 0; y < height; y++) {
            int yIndex = y * width;
            for (int x = 0; x < width; x++) {
                int pos = (yIndex + x) * 3;
                int blue = frameData[pos] & 0xff;
                int green = frameData[pos + 1] & 0xff;
                int red = frameData[pos + 2] & 0xff;

                int closest = getBestFullColor(red, green, blue);
                ditheredFrameData[yIndex + x] = getColor(closest);
            }
        }
    }

    /**
     * Bayer ordered dithering - produces stable, compressible patterns.
     * Best for video/streaming where temporal stability matters.
     */
    private void ditherFrameBayer(byte[] frameData) {
        int width = this.frameWidth;
        int height = this.frameHeight;

        for (int y = 0; y < height; y++) {
            int yIndex = y * width;
            int bayerY = y & 7; // y % 8

            for (int x = 0; x < width; x++) {
                int pos = (yIndex + x) * 3;
                int blue = frameData[pos] & 0xff;
                int green = frameData[pos + 1] & 0xff;
                int red = frameData[pos + 2] & 0xff;

                // Apply Bayer threshold
                int bayerX = x & 7; // x % 8
                int threshold = (BAYER_MATRIX_8x8[bayerY][bayerX] * 4) - 128; // Scale to -128 to +128

                // Add threshold to each channel
                red = clamp(red + threshold);
                green = clamp(green + threshold);
                blue = clamp(blue + threshold);

                int closest = getBestFullColor(red, green, blue);
                ditheredFrameData[yIndex + x] = getColor(closest);
            }
        }
    }

    /**
     * Floyd-Steinberg error diffusion dithering with configurable strength.
     * Lower strength reduces noise and improves compression at the cost of slight quality loss.
     */
    private void ditherFrameFloydSteinberg(byte[] frameData, float errorStrength) {
        int width = this.frameWidth;
        int height = this.frameHeight;
        int widthMinus = width - 1;
        int heightMinus = height - 1;

        // Clear buffers before starting
        java.util.Arrays.fill(ditherBuffer[0], 0);
        java.util.Arrays.fill(ditherBuffer[1], 0);

        // Serpentine Floyd-Steinberg dithering with correct coefficients:
        // Current pixel error distributed as:
        //          X    7/16
        //   3/16  5/16  1/16
        for (int y = 0; y < height; y++) {
            boolean hasNextY = y < heightMinus;
            int yIndex = y * width;

            // Swap buffers: current row's accumulated error becomes active,
            // and we clear the next row's buffer for new accumulation
            int[] temp = ditherBuffer[0];
            ditherBuffer[0] = ditherBuffer[1];
            ditherBuffer[1] = temp;
            java.util.Arrays.fill(ditherBuffer[1], 0);

            if ((y & 0x1) == 0) { // Forward pass (left to right)
                ditherRowForward(frameData, width, widthMinus, hasNextY, yIndex, errorStrength);
            } else { // Backward pass (right to left)
                ditherRowBackward(frameData, width, widthMinus, hasNextY, yIndex, errorStrength);
            }
        }
    }

    private void ditherRowForward(byte[] frameData, int width, int widthMinus, boolean hasNextY, int yIndex, float errorStrength) {
        int[] currentRow = ditherBuffer[0];
        int[] nextRow = ditherBuffer[1];

        for (int x = 0; x < width; ++x) {
            int bufferIndex = x * 3;
            int pos = (yIndex + x) * 3;

            int blue = (frameData[pos] & 0xff);
            int green = (frameData[pos + 1] & 0xff);
            int red = (frameData[pos + 2] & 0xff);

            // Add accumulated error from previous pixels
            red = clamp(red + currentRow[bufferIndex]);
            green = clamp(green + currentRow[bufferIndex + 1]);
            blue = clamp(blue + currentRow[bufferIndex + 2]);

            int closest = getBestFullColor(red, green, blue);

            // Calculate error and apply strength reduction
            int deltaR = (int)((red - ((closest >> 16) & 0xFF)) * errorStrength);
            int deltaG = (int)((green - ((closest >> 8) & 0xFF)) * errorStrength);
            int deltaB = (int)((blue - (closest & 0xFF)) * errorStrength);

            // Floyd-Steinberg error diffusion with correct coefficients
            // Distribute error: 7/16 right, 3/16 bottom-left, 5/16 bottom, 1/16 bottom-right
            if (x < widthMinus) {
                // 7/16 to the right
                int nextIdx = bufferIndex + 3;
                currentRow[nextIdx] += (deltaR * 7) >> 4;
                currentRow[nextIdx + 1] += (deltaG * 7) >> 4;
                currentRow[nextIdx + 2] += (deltaB * 7) >> 4;
            }

            if (hasNextY) {
                if (x > 0) {
                    // 3/16 to bottom-left
                    int blIdx = bufferIndex - 3;
                    nextRow[blIdx] += (deltaR * 3) >> 4;
                    nextRow[blIdx + 1] += (deltaG * 3) >> 4;
                    nextRow[blIdx + 2] += (deltaB * 3) >> 4;
                }
                // 5/16 to bottom
                nextRow[bufferIndex] += (deltaR * 5) >> 4;
                nextRow[bufferIndex + 1] += (deltaG * 5) >> 4;
                nextRow[bufferIndex + 2] += (deltaB * 5) >> 4;

                if (x < widthMinus) {
                    // 1/16 to bottom-right
                    int brIdx = bufferIndex + 3;
                    nextRow[brIdx] += deltaR >> 4;
                    nextRow[brIdx + 1] += deltaG >> 4;
                    nextRow[brIdx + 2] += deltaB >> 4;
                }
            }

            ditheredFrameData[yIndex + x] = getColor(closest);
        }
    }

    private void ditherRowBackward(byte[] frameData, int width, int widthMinus, boolean hasNextY, int yIndex, float errorStrength) {
        int[] currentRow = ditherBuffer[0];
        int[] nextRow = ditherBuffer[1];

        for (int x = widthMinus; x >= 0; --x) {
            int bufferIndex = x * 3;
            int pos = (yIndex + x) * 3;

            int blue = (frameData[pos] & 0xff);
            int green = (frameData[pos + 1] & 0xff);
            int red = (frameData[pos + 2] & 0xff);

            // Add accumulated error from previous pixels
            red = clamp(red + currentRow[bufferIndex]);
            green = clamp(green + currentRow[bufferIndex + 1]);
            blue = clamp(blue + currentRow[bufferIndex + 2]);

            int closest = getBestFullColor(red, green, blue);

            // Calculate error and apply strength reduction
            int deltaR = (int)((red - ((closest >> 16) & 0xFF)) * errorStrength);
            int deltaG = (int)((green - ((closest >> 8) & 0xFF)) * errorStrength);
            int deltaB = (int)((blue - (closest & 0xFF)) * errorStrength);

            // Floyd-Steinberg error diffusion (mirrored for backward pass)
            // Distribute error: 7/16 left, 3/16 bottom-right, 5/16 bottom, 1/16 bottom-left
            if (x > 0) {
                // 7/16 to the left
                int prevIdx = bufferIndex - 3;
                currentRow[prevIdx] += (deltaR * 7) >> 4;
                currentRow[prevIdx + 1] += (deltaG * 7) >> 4;
                currentRow[prevIdx + 2] += (deltaB * 7) >> 4;
            }

            if (hasNextY) {
                if (x < widthMinus) {
                    // 3/16 to bottom-right
                    int brIdx = bufferIndex + 3;
                    nextRow[brIdx] += (deltaR * 3) >> 4;
                    nextRow[brIdx + 1] += (deltaG * 3) >> 4;
                    nextRow[brIdx + 2] += (deltaB * 3) >> 4;
                }
                // 5/16 to bottom
                nextRow[bufferIndex] += (deltaR * 5) >> 4;
                nextRow[bufferIndex + 1] += (deltaG * 5) >> 4;
                nextRow[bufferIndex + 2] += (deltaB * 5) >> 4;

                if (x > 0) {
                    // 1/16 to bottom-left
                    int blIdx = bufferIndex - 3;
                    nextRow[blIdx] += deltaR >> 4;
                    nextRow[blIdx + 1] += deltaG >> 4;
                    nextRow[blIdx + 2] += deltaB >> 4;
                }
            }

            ditheredFrameData[yIndex + x] = getColor(closest);
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static byte getColor(int rgb) {
        return COLOR_MAP[((rgb >> 16) & 0xFF) >> 1 << 14 |
                         ((rgb >> 8) & 0xFF) >> 1 << 7 |
                         (rgb & 0xFF) >> 1];
    }

    private static int getBestFullColor(int red, int green, int blue) {
        return FULL_COLOR_MAP[red >> 1 << 14 | green >> 1 << 7 | blue >> 1];
    }

    public void shutdown() {
        executor.shutdown();
    }

    public record ProcessedFrame(
        List<PacketDispatcher.TileUpdate> updates,
        byte[][] fullMapData
    ) {
        public int getChangedTileCount() {
            return updates.size();
        }

        public long getTotalChangedBytes() {
            return updates.stream()
                .filter(u -> u.dirtyRegion() != null)
                .mapToLong(u -> u.dirtyRegion().getDataSize())
                .sum();
        }
    }
}

