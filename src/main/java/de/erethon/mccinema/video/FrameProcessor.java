package de.erethon.mccinema.video;

import de.erethon.mccinema.screen.MapTile;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.util.ByteArrayPool;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


import static de.erethon.mccinema.dither.DitherLookupUtil.COLOR_MAP;
import static de.erethon.mccinema.dither.DitherLookupUtil.FULL_COLOR_MAP;

public class FrameProcessor {

    public enum DitheringMode {
        FLOYD_STEINBERG,
        FLOYD_STEINBERG_REDUCED,
        BAYER_8X8,
        NONE
    }

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

    private final Screen screen;
    private final int frameWidth;
    private final int frameHeight;
    private final ExecutorService executor;
    private byte[] ditheredFrameData;
    private int[][] ditherBuffer;
    private byte[] previousDitheredFrame;
    private int[] previousSourceHash;
    private boolean useTemporalDithering = true;
    private int temporalThreshold = 4;
    private int errorQuantizationBits = 2;
    private DitheringMode ditheringMode = DitheringMode.FLOYD_STEINBERG_REDUCED;
    private float errorDiffusionStrength = 0.8f;
    private int errorThreshold = 4;

    public FrameProcessor(Screen screen, Plugin plugin) {
        this.screen = screen;
        this.frameWidth = screen.getPixelWidth();
        this.frameHeight = screen.getPixelHeight();
        this.ditheredFrameData = new byte[frameWidth * frameHeight];
        this.previousDitheredFrame = new byte[frameWidth * frameHeight];
        this.previousSourceHash = new int[frameWidth * frameHeight];
        this.ditherBuffer = new int[2][frameWidth * 3];
        loadDitheringConfig(plugin.getConfig());
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "MCCinema-FrameProcessor");
            t.setDaemon(true);
            return t;
        });
    }

    private void loadDitheringConfig(FileConfiguration config) {
        String modeStr = config.getString("dithering.mode", "FLOYD_STEINBERG_REDUCED");
        try {
            this.ditheringMode = DitheringMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {

            this.ditheringMode = DitheringMode.FLOYD_STEINBERG_REDUCED;
        }
        this.errorDiffusionStrength = (float) Math.max(0.0, Math.min(1.0,
            config.getDouble("dithering.error-diffusion-strength", 0.8)));
        this.errorThreshold = Math.max(0, Math.min(255,
            config.getInt("dithering.error-threshold", 4)));
        this.useTemporalDithering = config.getBoolean("dithering.temporal.enabled", true);
        this.temporalThreshold = Math.max(0, Math.min(255,
            config.getInt("dithering.temporal.threshold", 4)));
        this.errorQuantizationBits = Math.max(0, Math.min(7,
            config.getInt("dithering.temporal.error-quantization-bits", 2)));
    }

    public void setDitheringMode(DitheringMode mode) {
        this.ditheringMode = mode;
    }

    public DitheringMode getDitheringMode() {
        return ditheringMode;
    }

    public double getErrorDiffusionStrength() {
        return errorDiffusionStrength;
    }

    public void setErrorDiffusionStrength(float strength) {
        this.errorDiffusionStrength = Math.max(0.0f, Math.min(1.0f, strength));
    }

    public void setErrorThreshold(int threshold) {
        this.errorThreshold = Math.max(0, Math.min(255, threshold));
    }

    public int getErrorThreshold() {
        return errorThreshold;
    }

    public void setUseTemporalDithering(boolean useTemporalDithering) {
        this.useTemporalDithering = useTemporalDithering;
    }

    public boolean isUsingTemporalDithering() {
        return useTemporalDithering;
    }

    public void setTemporalThreshold(int temporalThreshold) {
        this.temporalThreshold = Math.max(0, Math.min(255, temporalThreshold));
    }

    public int getTemporalThreshold() {
        return temporalThreshold;
    }
    
    public ProcessedFrame processFrame(BufferedImage sourceImage, int targetWidth, int targetHeight, PerformanceMetrics metrics) {
        long aspectStart = metrics != null ? System.nanoTime() : 0;
        BufferedImage correctedImage = applyAspectRatioCorrection(sourceImage, targetWidth, targetHeight);
        if (metrics != null && correctedImage != sourceImage) {
            metrics.recordImageConversion(System.nanoTime() - aspectStart);
        }
        int sourceWidth = correctedImage.getWidth();
        int sourceHeight = correctedImage.getHeight();
        byte[] sourceFrameData = extractFrameData(correctedImage);
        byte[] savedDitheredData = ditheredFrameData;
        byte[] savedPreviousData = previousDitheredFrame;
        int[] savedPreviousHash = previousSourceHash;
        int[][] savedDitherBuffer = ditherBuffer;
        ditheredFrameData = new byte[sourceWidth * sourceHeight];

        if (previousSourceHash.length != sourceWidth * sourceHeight) {
            previousSourceHash = new int[sourceWidth * sourceHeight];
        }

        previousDitheredFrame = new byte[sourceWidth * sourceHeight];

        ditherBuffer = new int[2][sourceWidth * 3];

        long ditherStart = metrics != null ? System.nanoTime() : 0;
        ditherFrameAtResolution(sourceFrameData, sourceWidth, sourceHeight);
        if (metrics != null) {
            metrics.recordDithering(System.nanoTime() - ditherStart);
        }
        byte[] tempSourceDithered = new byte[sourceWidth * sourceHeight];
        System.arraycopy(ditheredFrameData, 0, tempSourceDithered, 0, tempSourceDithered.length);
        int[] tempSourceHash = new int[sourceWidth * sourceHeight];
        System.arraycopy(previousSourceHash, 0, tempSourceHash, 0, tempSourceHash.length);

        long upscaleStart = metrics != null ? System.nanoTime() : 0;
        byte[] upscaledDithered = upscalePaletteIndices(ditheredFrameData, sourceWidth, sourceHeight, targetWidth, targetHeight);
        if (metrics != null) {
            metrics.recordUpscaling(System.nanoTime() - upscaleStart);
        }

        ditheredFrameData = savedDitheredData;
        System.arraycopy(upscaledDithered, 0, ditheredFrameData, 0, upscaledDithered.length);
        previousDitheredFrame = savedPreviousData;
        if (previousDitheredFrame.length == tempSourceDithered.length) {
            System.arraycopy(tempSourceDithered, 0, previousDitheredFrame, 0, tempSourceDithered.length);
        }
        previousSourceHash = savedPreviousHash;
        if (previousSourceHash.length == tempSourceHash.length) {
            System.arraycopy(tempSourceHash, 0, previousSourceHash, 0, tempSourceHash.length);
        }

        ditherBuffer = savedDitherBuffer;

        long tileExtractionStart = metrics != null ? System.nanoTime() : 0;

        List<MapTile> tiles = screen.getTiles();
        int totalTiles = tiles.size();

        List<PacketDispatcher.TileUpdate> updates = new ArrayList<>(totalTiles);
        byte[][] fullMapData = new byte[totalTiles][];

        if (totalTiles >= 64) {
            @SuppressWarnings("unchecked")
            Future<TileExtractionResult>[] futures = new Future[totalTiles];
            for (int i = 0; i < tiles.size(); i++) {
                final MapTile tile = tiles.get(i);
                futures[i] = executor.submit(() -> {
                    byte[] mapData = extractMapData(tile);
                    MapTile.DirtyRegion dirtyRegion = tile.calculateDirtyRegionFromSent(mapData);
                    return new TileExtractionResult(tile, mapData, dirtyRegion);
                });
            }
            for (Future<TileExtractionResult> future : futures) {
                try {
                    TileExtractionResult result = future.get();
                    fullMapData[result.tile.getTileIndex()] = result.mapData;
                    updates.add(new PacketDispatcher.TileUpdate(result.tile, result.dirtyRegion, result.mapData));
                    result.tile.setLastFrameData(result.mapData.clone());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            for (MapTile tile : tiles) {
                byte[] mapData = extractMapData(tile);
                fullMapData[tile.getTileIndex()] = mapData;

                MapTile.DirtyRegion dirtyRegion = tile.calculateDirtyRegionFromSent(mapData);
                updates.add(new PacketDispatcher.TileUpdate(tile, dirtyRegion, mapData));
                tile.setLastFrameData(mapData.clone());
            }
        }

        if (metrics != null) {
            metrics.recordTileExtraction(System.nanoTime() - tileExtractionStart);
        }

        return new ProcessedFrame(updates, fullMapData);
    }

    private record TileExtractionResult(MapTile tile, byte[] mapData, MapTile.DirtyRegion dirtyRegion) {
    }

    private BufferedImage applyAspectRatioCorrection(BufferedImage source, int targetWidth, int targetHeight) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        double sourceAspect = (double) sourceWidth / sourceHeight;
        double targetAspect = (double) targetWidth / targetHeight;

        if (Math.abs(sourceAspect - targetAspect) < 0.01) {
            return source;
        }
        int correctedWidth, correctedHeight;
        int offsetX, offsetY;

        if (sourceAspect > targetAspect) {
            correctedWidth = sourceWidth;
            correctedHeight = (int) (sourceWidth / targetAspect);
            offsetX = 0;
            offsetY = (correctedHeight - sourceHeight) / 2;
        } else {
            correctedHeight = sourceHeight;
            correctedWidth = (int) (sourceHeight * targetAspect);
            offsetX = (correctedWidth - sourceWidth) / 2;
            offsetY = 0;
        }

        BufferedImage corrected = new BufferedImage(correctedWidth, correctedHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = corrected.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, correctedWidth, correctedHeight);
        g.drawImage(source, offsetX, offsetY, null);
        g.dispose();

        return corrected;
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
        byte[] mapData = ByteArrayPool.getTileBuffer(MapTile.SIZE * MapTile.SIZE);

        int startX = tile.getPixelOffsetX();
        int startY = tile.getPixelOffsetY();

        int baseOffset = startY * frameWidth + startX;

        for (int y = 0; y < MapTile.SIZE; y++) {
            int srcOffset = baseOffset + (y * frameWidth);
            int dstOffset = y * MapTile.SIZE;
            System.arraycopy(ditheredFrameData, srcOffset, mapData, dstOffset, MapTile.SIZE);
        }

        byte[] result = mapData.clone();
        ByteArrayPool.releaseTileBuffer();

        return result;
    }
    private void ditherFrameAtResolution(byte[] frameData, int width, int height) {

        switch (ditheringMode) {
            case FLOYD_STEINBERG:
                ditherFrameFloydSteinberg(frameData, width, height, 1.0f);
                break;
            case FLOYD_STEINBERG_REDUCED:
                ditherFrameFloydSteinberg(frameData, width, height, errorDiffusionStrength);
                break;
            case BAYER_8X8:
                ditherFrameBayer(frameData, width, height);
                break;
            case NONE:
                ditherFrameNone(frameData, width, height);
                break;
        }
    }

    private byte[] upscalePaletteIndices(byte[] sourceIndices, int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        byte[] result = new byte[targetWidth * targetHeight];
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            System.arraycopy(sourceIndices, 0, result, 0, sourceIndices.length);
            return result;
        }

        int xRatioFixed = (sourceWidth << 16) / targetWidth;
        int yRatioFixed = (sourceHeight << 16) / targetHeight;

        int[] srcXLookup = new int[targetWidth];
        for (int x = 0; x < targetWidth; x++) {
            srcXLookup[x] = (x * xRatioFixed) >>> 16;
        }

        boolean isIntegerScale = (targetWidth % sourceWidth == 0) && (targetHeight % sourceHeight == 0);
        int scaleX = isIntegerScale ? targetWidth / sourceWidth : 0;
        int scaleY = isIntegerScale ? targetHeight / sourceHeight : 0;

        if (targetWidth * targetHeight > 1_000_000) {
            upscalePaletteIndicesParallel(sourceIndices, result, sourceWidth, sourceHeight,
                                         targetWidth, targetHeight, srcXLookup, yRatioFixed,
                                         isIntegerScale, scaleX, scaleY);
        } else {
            upscalePaletteIndicesSerial(sourceIndices, result, sourceWidth, sourceHeight,
                                       targetWidth, targetHeight, srcXLookup, yRatioFixed,
                                       isIntegerScale, scaleX, scaleY);
        }

        return result;
    }

    private void upscalePaletteIndicesSerial(byte[] sourceIndices, byte[] result,
                                             int sourceWidth, int sourceHeight,
                                             int targetWidth, int targetHeight,
                                             int[] srcXLookup, int yRatioFixed,
                                             boolean isIntegerScale, int scaleX, int scaleY) {
        if (isIntegerScale && scaleX == scaleY && scaleX <= 8) {
            int scale = scaleX;
            for (int srcY = 0; srcY < sourceHeight; srcY++) {
                int srcYOffset = srcY * sourceWidth;
                int dstYBase = srcY * scale * targetWidth;
                for (int srcX = 0; srcX < sourceWidth; srcX++) {
                    byte pixel = sourceIndices[srcYOffset + srcX];
                    int dstXBase = srcX * scale;
                    for (int dy = 0; dy < scale; dy++) {
                        int dstOffset = dstYBase + dy * targetWidth + dstXBase;
                        for (int dx = 0; dx < scale; dx++) {
                            result[dstOffset + dx] = pixel;
                        }
                    }
                }
            }
        } else {
            int lastSrcY = -1;
            int firstTargetRowWithThisSrcY = -1;
            for (int y = 0; y < targetHeight; y++) {
                int srcY = (y * yRatioFixed) >>> 16;
                int dstYOffset = y * targetWidth;
                if (srcY == lastSrcY) {
                    int srcOffset = firstTargetRowWithThisSrcY * targetWidth;
                    System.arraycopy(result, srcOffset, result, dstYOffset, targetWidth);
                } else {
                    int srcYOffset = srcY * sourceWidth;
                    for (int x = 0; x < targetWidth; x++) {
                        result[dstYOffset + x] = sourceIndices[srcYOffset + srcXLookup[x]];
                    }
                    lastSrcY = srcY;
                    firstTargetRowWithThisSrcY = y;
                }
            }
        }
    }

    
    private void upscalePaletteIndicesParallel(byte[] sourceIndices, byte[] result,
                                               int sourceWidth, int sourceHeight,
                                               int targetWidth, int targetHeight,
                                               int[] srcXLookup, int yRatioFixed,
                                               boolean isIntegerScale, int scale, int scaleY) {
        int availableThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        int chunkHeight = Math.max(64, targetHeight / availableThreads);
        int numChunks = (targetHeight + chunkHeight - 1) / chunkHeight;
        List<Future<?>> futures = new ArrayList<>(numChunks);
        for (int chunk = 0; chunk < numChunks; chunk++) {
            final int startY = chunk * chunkHeight;
            final int endY = Math.min(startY + chunkHeight, targetHeight);
            futures.add(executor.submit(() -> {
                if (isIntegerScale && scale == scaleY && scale <= 8) {
                    int srcStartY = startY / scale;
                    int srcEndY = (endY + scale - 1) / scale;
                    for (int srcY = srcStartY; srcY < srcEndY && srcY < sourceHeight; srcY++) {
                        int srcYOffset = srcY * sourceWidth;
                        int dstYBase = srcY * scale * targetWidth;
                        if (dstYBase >= endY) break;
                        if (dstYBase + scale < startY) continue;
                        for (int srcX = 0; srcX < sourceWidth; srcX++) {
                            byte pixel = sourceIndices[srcYOffset + srcX];
                            int dstXBase = srcX * scale;

                            for (int dy = 0; dy < scale; dy++) {
                                int dstY = dstYBase + dy * targetWidth;
                                if (dstY >= startY && dstY < endY) {
                                    int dstOffset = dstY + dstXBase;
                                    for (int dx = 0; dx < scale; dx++) {
                                        result[dstOffset + dx] = pixel;
                                    }
                                }
                            }
                        }
                    }
                } else {

                    int lastSrcY = -1;
                    int firstTargetRowWithThisSrcY = -1;
                    for (int y = startY; y < endY; y++) {
                        int srcY = (y * yRatioFixed) >>> 16;
                        int dstYOffset = y * targetWidth;
                        if (srcY == lastSrcY && firstTargetRowWithThisSrcY >= 0 && firstTargetRowWithThisSrcY >= startY) {

                            int srcOffset = firstTargetRowWithThisSrcY * targetWidth;
                            System.arraycopy(result, srcOffset, result, dstYOffset, targetWidth);
                        } else {
                            int srcYOffset = srcY * sourceWidth;
                            for (int x = 0; x < targetWidth; x++) {
                                result[dstYOffset + x] = sourceIndices[srcYOffset + srcXLookup[x]];
                            }
                            lastSrcY = srcY;
                            firstTargetRowWithThisSrcY = y;
                        }
                    }
                }
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (Exception e) {
                System.err.println("Error in parallel upscaling chunk " + i + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static int clamp(int value) {
        return (value & ~255) == 0 ? value : (value < 0 ? 0 : 255);
    }

    private void ditherFrameNone(byte[] frameData, int width, int height) {
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

    private void ditherFrameBayer(byte[] frameData, int width, int height) {
        for (int y = 0; y < height; y++) {
            int yIndex = y * width;
            int bayerY = y & 7;

            for (int x = 0; x < width; x++) {
                int pos = (yIndex + x) * 3;
                int blue = frameData[pos] & 0xff;
                int green = frameData[pos + 1] & 0xff;
                int red = frameData[pos + 2] & 0xff;
                int bayerX = x & 7;
                int threshold = (BAYER_MATRIX_8x8[bayerY][bayerX] * 4) - 128;

                red = clamp(red + threshold);
                green = clamp(green + threshold);
                blue = clamp(blue + threshold);

                int closest = getBestFullColor(red, green, blue);
                ditheredFrameData[yIndex + x] = getColor(closest);
            }
        }
    }

    private void ditherFrameFloydSteinberg(byte[] frameData, int width, int height, float errorStrength) {
        int widthMinus = width - 1;
        int heightMinus = height - 1;
        int errorStrengthFixed = (int)(errorStrength * 256.0f);

        java.util.Arrays.fill(ditherBuffer[0], 0);
        java.util.Arrays.fill(ditherBuffer[1], 0);

        ditherSerial(frameData, width, height, widthMinus, heightMinus, errorStrengthFixed);
    }

    
    private void ditherSerial(byte[] frameData, int width, int height, int widthMinus, int heightMinus, int errorStrengthFixed) {
        int[] currentRow = ditherBuffer[0];
        int[] nextRow = ditherBuffer[1];

        for (int y = 0; y < height; y++) {
            boolean hasNextY = y < heightMinus;
            int yIndex = y * width;
            int[] temp = currentRow;
            currentRow = nextRow;
            nextRow = temp;
            java.util.Arrays.fill(nextRow, 0);

            if ((y & 0x1) == 0) {
                ditherRowForward(frameData, currentRow, nextRow, width, widthMinus, hasNextY, yIndex, errorStrengthFixed);
            } else {
                ditherRowBackward(frameData, currentRow, nextRow, width, widthMinus, hasNextY, yIndex, errorStrengthFixed);
            }
        }
    }

    private void ditherRowForward(byte[] frameData, int[] currentRow, int[] nextRow, int width, int widthMinus, boolean hasNextY, int yIndex, int errorStrengthFixed) {
        int bufferIndex = 0;
        int pos = yIndex * 3;
        int pixelIdx = yIndex;
        boolean doTemporal = useTemporalDithering;
        byte[] prevFrame = previousDitheredFrame;
        int[] prevHash = previousSourceHash;
        int prevFrameLength = prevFrame.length;
        int prevHashLength = prevHash.length;
        int errorMask = errorQuantizationBits > 0 ? -(1 << errorQuantizationBits) : 0;

        for (int x = 0; x < width; x++, bufferIndex += 3, pos += 3, pixelIdx++) {
            int blue = clamp((frameData[pos] & 0xff) + currentRow[bufferIndex + 2]);
            int green = clamp((frameData[pos + 1] & 0xff) + currentRow[bufferIndex + 1]);
            int red = clamp((frameData[pos + 2] & 0xff) + currentRow[bufferIndex]);

            if (doTemporal && pixelIdx < prevFrameLength && pixelIdx < prevHashLength) {
                int hashBucket = Math.max(1, temporalThreshold * 2);
                int currentHash = (((red / hashBucket) << 16) |
                                  ((green / hashBucket) << 8) |
                                  (blue / hashBucket)) + 1;

                if (currentHash == prevHash[pixelIdx]) {
                    ditheredFrameData[pixelIdx] = prevFrame[pixelIdx];
                    continue;
                }
                int prevHashValue = prevHash[pixelIdx];
                if (prevHashValue > 0) {
                    int prevR = ((prevHashValue - 1) >> 16) * hashBucket;
                    int prevG = (((prevHashValue - 1) >> 8) & 0xFF) * hashBucket;
                    int prevB = ((prevHashValue - 1) & 0xFF) * hashBucket;
                    if (Math.abs(red - prevR) <= temporalThreshold &&
                        Math.abs(green - prevG) <= temporalThreshold &&
                        Math.abs(blue - prevB) <= temporalThreshold) {
                        ditheredFrameData[pixelIdx] = prevFrame[pixelIdx];
                        continue;
                    }
                }
                prevHash[pixelIdx] = currentHash;
            }


            int closest = getBestFullColor(red, green, blue);
            int closestR = (closest >> 16) & 0xFF;
            int closestG = (closest >> 8) & 0xFF;
            int closestB = closest & 0xFF;
            int deltaR = red - closestR;
            int deltaG = green - closestG;
            int deltaB = blue - closestB;
            int totalError = Math.abs(deltaR) + Math.abs(deltaG) + Math.abs(deltaB);
            if (totalError > errorThreshold) {
                if (errorMask != 0) {
                    deltaR &= errorMask;
                    deltaG &= errorMask;
                    deltaB &= errorMask;
                }
                deltaR = (deltaR * errorStrengthFixed) >> 8;
                deltaG = (deltaG * errorStrengthFixed) >> 8;
                deltaB = (deltaB * errorStrengthFixed) >> 8;
                if (x < widthMinus) {
                    currentRow[bufferIndex + 3] += (deltaR * 7) >> 4;
                    currentRow[bufferIndex + 4] += (deltaG * 7) >> 4;
                    currentRow[bufferIndex + 5] += (deltaB * 7) >> 4;
                }
                if (hasNextY) {
                    if (x > 0) {
                        nextRow[bufferIndex - 3] += (deltaR * 3) >> 4;
                        nextRow[bufferIndex - 2] += (deltaG * 3) >> 4;
                        nextRow[bufferIndex - 1] += (deltaB * 3) >> 4;
                    }
                    nextRow[bufferIndex] += (deltaR * 5) >> 4;
                    nextRow[bufferIndex + 1] += (deltaG * 5) >> 4;
                    nextRow[bufferIndex + 2] += (deltaB * 5) >> 4;
                    if (x < widthMinus) {
                        nextRow[bufferIndex + 3] += deltaR >> 4;
                        nextRow[bufferIndex + 4] += deltaG >> 4;
                        nextRow[bufferIndex + 5] += deltaB >> 4;
                    }
                }
            }
            ditheredFrameData[pixelIdx] = getColor(closest);
        }
    }

    private void ditherRowBackward(byte[] frameData, int[] currentRow, int[] nextRow, int width, int widthMinus, boolean hasNextY, int yIndex, int errorStrengthFixed) {
        boolean doTemporal = useTemporalDithering;
        byte[] prevFrame = previousDitheredFrame;
        int[] prevHash = previousSourceHash;
        int prevFrameLength = prevFrame.length;
        int prevHashLength = prevHash.length;
        int errorMask = errorQuantizationBits > 0 ? -(1 << errorQuantizationBits) : 0;

        for (int x = widthMinus; x >= 0; x--) {
            int bufferIndex = x * 3;
            int pos = (yIndex + x) * 3;
            int pixelIdx = yIndex + x;
            int blue = clamp((frameData[pos] & 0xff) + currentRow[bufferIndex + 2]);
            int green = clamp((frameData[pos + 1] & 0xff) + currentRow[bufferIndex + 1]);
            int red = clamp((frameData[pos + 2] & 0xff) + currentRow[bufferIndex]);

            if (doTemporal && pixelIdx < prevFrameLength && pixelIdx < prevHashLength) {
                int hashBucket = Math.max(1, temporalThreshold * 2);
                int currentHash = (((red / hashBucket) << 16) |
                                  ((green / hashBucket) << 8) |
                                  (blue / hashBucket)) + 1;

                if (currentHash == prevHash[pixelIdx]) {
                    ditheredFrameData[pixelIdx] = prevFrame[pixelIdx];
                    continue;
                }

                int prevHashValue = prevHash[pixelIdx];
                if (prevHashValue > 0) {
                    int prevR = ((prevHashValue - 1) >> 16) * hashBucket;
                    int prevG = (((prevHashValue - 1) >> 8) & 0xFF) * hashBucket;
                    int prevB = ((prevHashValue - 1) & 0xFF) * hashBucket;

                    if (Math.abs(red - prevR) <= temporalThreshold &&
                        Math.abs(green - prevG) <= temporalThreshold &&
                        Math.abs(blue - prevB) <= temporalThreshold) {
                        ditheredFrameData[pixelIdx] = prevFrame[pixelIdx];
                        continue;
                    }
                }
                prevHash[pixelIdx] = currentHash;
            }
            int closest = getBestFullColor(red, green, blue);
            int closestR = (closest >> 16) & 0xFF;
            int closestG = (closest >> 8) & 0xFF;
            int closestB = closest & 0xFF;
            int deltaR = red - closestR;
            int deltaG = green - closestG;
            int deltaB = blue - closestB;
            int totalError = Math.abs(deltaR) + Math.abs(deltaG) + Math.abs(deltaB);
            if (totalError > errorThreshold) {
                if (errorMask != 0) {
                    deltaR &= errorMask;
                    deltaG &= errorMask;
                    deltaB &= errorMask;
                }
                deltaR = (deltaR * errorStrengthFixed) >> 8;
                deltaG = (deltaG * errorStrengthFixed) >> 8;
                deltaB = (deltaB * errorStrengthFixed) >> 8;
                if (x > 0) {
                    int nextIdx = bufferIndex - 3;
                    currentRow[nextIdx] += (deltaR * 7) >> 4;
                    currentRow[nextIdx + 1] += (deltaG * 7) >> 4;
                    currentRow[nextIdx + 2] += (deltaB * 7) >> 4;
                }
                if (hasNextY) {
                    if (x < widthMinus) {
                        int brIdx = bufferIndex + 3;
                        nextRow[brIdx] += (deltaR * 3) >> 4;
                        nextRow[brIdx + 1] += (deltaG * 3) >> 4;
                        nextRow[brIdx + 2] += (deltaB * 3) >> 4;
                    }
                    nextRow[bufferIndex] += (deltaR * 5) >> 4;
                    nextRow[bufferIndex + 1] += (deltaG * 5) >> 4;
                    nextRow[bufferIndex + 2] += (deltaB * 5) >> 4;
                    if (x > 0) {
                        int blIdx = bufferIndex - 3;
                        nextRow[blIdx] += deltaR >> 4;
                        nextRow[blIdx + 1] += deltaG >> 4;
                        nextRow[blIdx + 2] += deltaB >> 4;
                    }
                }
            }
            ditheredFrameData[pixelIdx] = getColor(closest);
        }
    }


    private static byte getColor(int rgb) {
        return COLOR_MAP[((rgb >> 16) & 0xFF) >> 1 << 14 |
                         ((rgb >> 8) & 0xFF) >> 1 << 7 |
                         (rgb & 0xFF) >> 1];
    }

    
    private static int getBestFullColor(int red, int green, int blue) {
        int lookupIdx = (red >> 1) << 14 | (green >> 1) << 7 | (blue >> 1);
        return FULL_COLOR_MAP[lookupIdx];
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

