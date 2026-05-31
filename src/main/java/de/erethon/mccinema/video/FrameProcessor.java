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
        /** Atkinson dithering: distributes only 6/8 of the error to 6 neighbours (including 2 rows below).
         *  The discarded 2/8 naturally suppresses noise in flat areas, giving a cleaner look than FS. */
        ATKINSON,
        /** Stucki dithering: distributes 100% of error to 12 neighbours across the next 2 rows.
         *  The wider spread reduces directional streaking and produces smoother gradients than FS. */
        STUCKI,
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
    private byte[] previousDitheredFrame;
    private int[] previousSourceHash;

    // Pre-allocated error buffers for band-parallel dithering: [numBands*2][width*3]
    private int[][] bandDitherBuffers;
    private int bandBufferWidth = -1;

    // Source-resolution buffers for when source != target
    private byte[] sourceDitheredFrameData;
    private byte[] sourcePreviousDitheredFrame;
    private int[] sourcePreviousHash;
    private int lastSourceWidth = -1;
    private int lastSourceHeight = -1;

    private boolean useTemporalDithering = true;
    private int temporalThreshold = 4;
    private int errorQuantizationBits = 2;
    private DitheringMode ditheringMode = DitheringMode.FLOYD_STEINBERG_REDUCED;
    private float errorDiffusionStrength = 0.8f;
    private int errorThreshold = 4;

    // Bandwidth-related tuning
    private boolean adaptiveTuningEnabled = true;
    private double adaptiveHighMotionThreshold = 0.12;
    private double adaptiveLowMotionThreshold = 0.05;
    private double adaptiveFlatAreaThreshold = 0.70;
    private int adaptiveTemporalBoostMotion = 6;
    private int adaptiveTemporalBoostFlat = 10;
    private int adaptiveQuantBoostMotion = 1;
    private int adaptiveQuantBoostFlat = 2;
    private int adaptiveErrorThresholdBoostMotion = 4;
    private int adaptiveErrorThresholdBoostFlat = 8;
    private float adaptiveDiffusionScaleMotion = 0.80f;
    private float adaptiveDiffusionScaleFlat = 0.65f;
    private byte[] previousRawFrameData;
    private int previousRawWidth = -1;
    private int previousRawHeight = -1;
    private volatile FrameContentStats lastFrameContentStats = new FrameContentStats(0.0, 0.0, 0.0);
    private volatile AdaptiveDitherProfile lastAdaptiveProfile = new AdaptiveDitherProfile(4, 2, 4, 0.8f, "BASE");

    public FrameProcessor(Screen screen, Plugin plugin) {
        this.screen = screen;
        this.frameWidth = screen.getPixelWidth();
        this.frameHeight = screen.getPixelHeight();
        this.ditheredFrameData = new byte[frameWidth * frameHeight];
        this.previousDitheredFrame = new byte[frameWidth * frameHeight];
        this.previousSourceHash = new int[frameWidth * frameHeight];
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

        this.adaptiveTuningEnabled = config.getBoolean("dithering.adaptive.enabled", true);
        this.adaptiveHighMotionThreshold = clampDouble(config.getDouble("dithering.adaptive.high-motion-threshold", 0.12), 0.0, 1.0);
        this.adaptiveLowMotionThreshold = clampDouble(config.getDouble("dithering.adaptive.low-motion-threshold", 0.05), 0.0, 1.0);
        this.adaptiveFlatAreaThreshold = clampDouble(config.getDouble("dithering.adaptive.flat-area-threshold", 0.70), 0.0, 1.0);
        this.adaptiveTemporalBoostMotion = clampInt(config.getInt("dithering.adaptive.motion.temporal-threshold-boost", 6), 0, 64);
        this.adaptiveTemporalBoostFlat = clampInt(config.getInt("dithering.adaptive.flat.temporal-threshold-boost", 10), 0, 64);
        this.adaptiveQuantBoostMotion = clampInt(config.getInt("dithering.adaptive.motion.error-quantization-boost", 1), 0, 4);
        this.adaptiveQuantBoostFlat = clampInt(config.getInt("dithering.adaptive.flat.error-quantization-boost", 2), 0, 4);
        this.adaptiveErrorThresholdBoostMotion = clampInt(config.getInt("dithering.adaptive.motion.error-threshold-boost", 4), 0, 64);
        this.adaptiveErrorThresholdBoostFlat = clampInt(config.getInt("dithering.adaptive.flat.error-threshold-boost", 8), 0, 64);
        this.adaptiveDiffusionScaleMotion = (float) clampDouble(config.getDouble("dithering.adaptive.motion.diffusion-scale", 0.80), 0.0, 1.0);
        this.adaptiveDiffusionScaleFlat = (float) clampDouble(config.getDouble("dithering.adaptive.flat.diffusion-scale", 0.65), 0.0, 1.0);
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

    public void setErrorQuantizationBits(int errorQuantizationBits) {
        this.errorQuantizationBits = clampInt(errorQuantizationBits, 0, 7);
    }

    public int getErrorQuantizationBits() {
        return errorQuantizationBits;
    }

    public void setAdaptiveTuningEnabled(boolean adaptiveTuningEnabled) {
        this.adaptiveTuningEnabled = adaptiveTuningEnabled;
    }

    public boolean isAdaptiveTuningEnabled() {
        return adaptiveTuningEnabled;
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
        FrameContentStats contentStats = analyzeFrameContent(sourceFrameData, sourceWidth, sourceHeight);
        AdaptiveDitherProfile adaptiveProfile = buildAdaptiveProfile(contentStats);
        lastFrameContentStats = contentStats;
        lastAdaptiveProfile = adaptiveProfile;

        boolean needsUpscale = sourceWidth != targetWidth || sourceHeight != targetHeight;

        if (needsUpscale) {
            ensureSourceBuffers(sourceWidth, sourceHeight);

            // Swap to source-resolution buffers for dithering
            byte[] savedDitheredData = ditheredFrameData;
            byte[] savedPreviousData = previousDitheredFrame;
            int[] savedPreviousHash = previousSourceHash;

            ditheredFrameData = sourceDitheredFrameData;
            previousDitheredFrame = sourcePreviousDitheredFrame;
            previousSourceHash = sourcePreviousHash;

            long ditherStart = metrics != null ? System.nanoTime() : 0;
            ditherFrameAtResolution(sourceFrameData, sourceWidth, sourceHeight, adaptiveProfile);
            if (metrics != null) {
                metrics.recordDithering(System.nanoTime() - ditherStart);
            }

            // Persist source-resolution state for next frame's temporal dithering
            // Copy current dithered result into the previous-frame buffer for next frame
            System.arraycopy(ditheredFrameData, 0, sourcePreviousDitheredFrame, 0, ditheredFrameData.length);
            sourceDitheredFrameData = ditheredFrameData;
            sourcePreviousHash = previousSourceHash;

            // Upscale dithered result to target resolution
            long upscaleStart = metrics != null ? System.nanoTime() : 0;
            byte[] upscaled = upscalePaletteIndices(ditheredFrameData, sourceWidth, sourceHeight, targetWidth, targetHeight);
            if (metrics != null) {
                metrics.recordUpscaling(System.nanoTime() - upscaleStart);
            }

            // Restore target-resolution buffers and copy upscaled result
            ditheredFrameData = savedDitheredData;
            System.arraycopy(upscaled, 0, ditheredFrameData, 0, upscaled.length);
            previousDitheredFrame = savedPreviousData;
            previousSourceHash = savedPreviousHash;
        } else {
            long ditherStart = metrics != null ? System.nanoTime() : 0;
            ditherFrameAtResolution(sourceFrameData, sourceWidth, sourceHeight, adaptiveProfile);
            if (metrics != null) {
                metrics.recordDithering(System.nanoTime() - ditherStart);
            }
            // Persist current dithered result for next frame's temporal dithering
            System.arraycopy(ditheredFrameData, 0, previousDitheredFrame, 0, ditheredFrameData.length);
        }

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

        return new ProcessedFrame(updates, fullMapData, contentStats);
    }

    private record TileExtractionResult(MapTile tile, byte[] mapData, MapTile.DirtyRegion dirtyRegion) {
    }

    /**
     * Ensure source-resolution buffers are allocated and correctly sized.
     * Only re-allocates when the source resolution changes.
     */
    private void ensureSourceBuffers(int sourceWidth, int sourceHeight) {
        if (sourceWidth == lastSourceWidth && sourceHeight == lastSourceHeight) {
            return;
        }
        int sourcePixels = sourceWidth * sourceHeight;
        sourceDitheredFrameData = new byte[sourcePixels];
        sourcePreviousDitheredFrame = new byte[sourcePixels];
        sourcePreviousHash = new int[sourcePixels];
        lastSourceWidth = sourceWidth;
        lastSourceHeight = sourceHeight;
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
    private void ditherFrameAtResolution(byte[] frameData, int width, int height, AdaptiveDitherProfile adaptiveProfile) {
        int baseTemporalThreshold = temporalThreshold;
        int baseQuantizationBits = errorQuantizationBits;
        int baseErrorThreshold = errorThreshold;
        float baseDiffusionStrength = errorDiffusionStrength;

        try {
            if (adaptiveProfile != null) {
                temporalThreshold = clampInt(adaptiveProfile.temporalThreshold(), 0, 255);
                errorQuantizationBits = clampInt(adaptiveProfile.errorQuantizationBits(), 0, 7);
                errorThreshold = clampInt(adaptiveProfile.errorThreshold(), 0, 255);
                errorDiffusionStrength = (float) clampDouble(adaptiveProfile.errorDiffusionStrength(), 0.0, 1.0);
            }

            switch (ditheringMode) {
                case FLOYD_STEINBERG:
                    ditherFrameFloydSteinberg(frameData, width, height, 1.0f);
                    break;
                case FLOYD_STEINBERG_REDUCED:
                    ditherFrameFloydSteinberg(frameData, width, height, errorDiffusionStrength);
                    break;
                case ATKINSON:
                    ditherFrameAtkinson(frameData, width, height, errorDiffusionStrength);
                    break;
                case STUCKI:
                    ditherFrameStucki(frameData, width, height, errorDiffusionStrength);
                    break;
                case BAYER_8X8:
                    ditherFrameBayer(frameData, width, height);
                    break;
                case NONE:
                    ditherFrameNone(frameData, width, height);
                    break;
            }
        } finally {
            temporalThreshold = baseTemporalThreshold;
            errorQuantizationBits = baseQuantizationBits;
            errorThreshold = baseErrorThreshold;
            errorDiffusionStrength = baseDiffusionStrength;
        }
    }

    private FrameContentStats analyzeFrameContent(byte[] sourceFrameData, int width, int height) {
        if (sourceFrameData == null || sourceFrameData.length == 0) {
            return new FrameContentStats(0.0, 0.0, 0.0);
        }

        if (previousRawFrameData == null || previousRawFrameData.length != sourceFrameData.length ||
            width != previousRawWidth || height != previousRawHeight) {
            previousRawFrameData = sourceFrameData.clone();
            previousRawWidth = width;
            previousRawHeight = height;
            return new FrameContentStats(0.0, 0.0, 0.0);
        }

        int stride = 4;
        long motionAccum = 0;
        int flatSamples = 0;
        int lowSaturationSamples = 0;
        int samples = 0;

        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                int pixelIndex = y * width + x;
                int dataIndex = pixelIndex * 3;

                int blue = sourceFrameData[dataIndex] & 0xFF;
                int green = sourceFrameData[dataIndex + 1] & 0xFF;
                int red = sourceFrameData[dataIndex + 2] & 0xFF;

                int prevBlue = previousRawFrameData[dataIndex] & 0xFF;
                int prevGreen = previousRawFrameData[dataIndex + 1] & 0xFF;
                int prevRed = previousRawFrameData[dataIndex + 2] & 0xFF;

                int luma = ((red * 77) + (green * 150) + (blue * 29)) >> 8;
                int prevLuma = ((prevRed * 77) + (prevGreen * 150) + (prevBlue * 29)) >> 8;
                motionAccum += Math.abs(luma - prevLuma);

                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                if (max - min <= 20) {
                    lowSaturationSamples++;
                }

                int rightLuma = luma;
                if (x + 1 < width) {
                    int rightIdx = (pixelIndex + 1) * 3;
                    int rb = sourceFrameData[rightIdx] & 0xFF;
                    int rg = sourceFrameData[rightIdx + 1] & 0xFF;
                    int rr = sourceFrameData[rightIdx + 2] & 0xFF;
                    rightLuma = ((rr * 77) + (rg * 150) + (rb * 29)) >> 8;
                }

                int downLuma = luma;
                if (y + 1 < height) {
                    int downPixel = ((y + 1) * width + x) * 3;
                    int db = sourceFrameData[downPixel] & 0xFF;
                    int dg = sourceFrameData[downPixel + 1] & 0xFF;
                    int dr = sourceFrameData[downPixel + 2] & 0xFF;
                    downLuma = ((dr * 77) + (dg * 150) + (db * 29)) >> 8;
                }

                if (Math.abs(luma - rightLuma) <= 10 && Math.abs(luma - downLuma) <= 10) {
                    flatSamples++;
                }

                samples++;
            }
        }

        System.arraycopy(sourceFrameData, 0, previousRawFrameData, 0, sourceFrameData.length);

        if (samples == 0) {
            return new FrameContentStats(0.0, 0.0, 0.0);
        }

        double motionScore = clampDouble((double) motionAccum / (samples * 255.0), 0.0, 1.0);
        double flatScore = clampDouble((double) flatSamples / samples, 0.0, 1.0);
        double lowSaturationScore = clampDouble((double) lowSaturationSamples / samples, 0.0, 1.0);
        return new FrameContentStats(motionScore, flatScore, lowSaturationScore);
    }

    private AdaptiveDitherProfile buildAdaptiveProfile(FrameContentStats contentStats) {
        int tunedTemporalThreshold = temporalThreshold;
        int tunedQuantizationBits = errorQuantizationBits;
        int tunedErrorThreshold = errorThreshold;
        float tunedDiffusionStrength = errorDiffusionStrength;
        String mode = "BASE";

        if (adaptiveTuningEnabled && contentStats != null) {
            if (contentStats.motionScore() >= adaptiveHighMotionThreshold) {
                tunedTemporalThreshold += adaptiveTemporalBoostMotion;
                tunedQuantizationBits += adaptiveQuantBoostMotion;
                tunedErrorThreshold += adaptiveErrorThresholdBoostMotion;
                tunedDiffusionStrength *= adaptiveDiffusionScaleMotion;
                mode = "MOTION";
            } else if (contentStats.motionScore() <= adaptiveLowMotionThreshold &&
                (contentStats.flatScore() >= adaptiveFlatAreaThreshold || contentStats.lowSaturationScore() >= adaptiveFlatAreaThreshold)) {
                tunedTemporalThreshold += adaptiveTemporalBoostFlat;
                tunedQuantizationBits += adaptiveQuantBoostFlat;
                tunedErrorThreshold += adaptiveErrorThresholdBoostFlat;
                tunedDiffusionStrength *= adaptiveDiffusionScaleFlat;
                mode = "FLAT";
            }
        }

        return new AdaptiveDitherProfile(
            clampInt(tunedTemporalThreshold, 0, 255),
            clampInt(tunedQuantizationBits, 0, 7),
            clampInt(tunedErrorThreshold, 0, 255),
            (float) clampDouble(tunedDiffusionStrength, 0.0, 1.0),
            mode
        );
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public FrameContentStats getLastFrameContentStats() {
        return lastFrameContentStats;
    }

    public AdaptiveDitherProfile getLastAdaptiveProfile() {
        return lastAdaptiveProfile;
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

    private void ensureBandBuffers(int numBands, int width) {
        // 3 rows per band: current + next + next-next (needed for Atkinson/Stucki 2-row lookahead)
        int needed = numBands * 3;
        if (bandDitherBuffers == null || bandDitherBuffers.length < needed || bandBufferWidth != width) {
            bandDitherBuffers = new int[needed][];
            for (int i = 0; i < needed; i++) {
                bandDitherBuffers[i] = new int[width * 3];
            }
            bandBufferWidth = width;
        }
    }

    /**
     * Band-parallel Floyd-Steinberg dithering. Splits the image into horizontal bands,
     * each processed by a separate thread. Error propagation is contained within each band
     * (no cross-band propagation), which produces a negligible visual seam at band boundaries
     * that is imperceptible in video.
     */
    private void ditherFrameFloydSteinberg(byte[] frameData, int width, int height, float errorStrength) {
        int widthMinus = width - 1;
        int errorStrengthFixed = (int) (errorStrength * 256.0f);

        // at least 32 rows per band to minimize seam artifacts
        int maxBands = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int numBands = Math.max(1, Math.min(maxBands, height / 32));

        ensureBandBuffers(numBands, width);

        boolean doTemporal = useTemporalDithering;
        int hashBucket = doTemporal ? Math.max(1, temporalThreshold * 2) : 1;
        int localTemporalThreshold = temporalThreshold;
        int errorMask = errorQuantizationBits > 0 ? -(1 << errorQuantizationBits) : 0;
        int localErrorThreshold = errorThreshold;
        byte[] prevFrame = previousDitheredFrame;
        int[] prevHash = previousSourceHash;
        int prevFrameLength = prevFrame.length;
        int prevHashLength = prevHash.length;
        byte[] output = ditheredFrameData;

        if (numBands <= 1) {
            int[] buf0 = bandDitherBuffers[0];
            int[] buf1 = bandDitherBuffers[1];
            java.util.Arrays.fill(buf0, 0);
            java.util.Arrays.fill(buf1, 0);
            ditherBand(frameData, width, widthMinus, 0, height, errorStrengthFixed,
                       buf0, buf1, doTemporal, hashBucket, localTemporalThreshold,
                       errorMask, localErrorThreshold,
                       prevFrame, prevHash, prevFrameLength, prevHashLength, output);
        } else {
            int bandHeight = height / numBands;
            Future<?>[] futures = new Future<?>[numBands];

            for (int band = 0; band < numBands; band++) {
                int startY = band * bandHeight;
                int endY = (band == numBands - 1) ? height : startY + bandHeight;
                int[] buf0 = bandDitherBuffers[band * 3];
                int[] buf1 = bandDitherBuffers[band * 3 + 1];

                futures[band] = executor.submit(() -> {
                    java.util.Arrays.fill(buf0, 0);
                    java.util.Arrays.fill(buf1, 0);
                    ditherBand(frameData, width, widthMinus, startY, endY, errorStrengthFixed,
                               buf0, buf1, doTemporal, hashBucket, localTemporalThreshold,
                               errorMask, localErrorThreshold,
                               prevFrame, prevHash, prevFrameLength, prevHashLength, output);
                });
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void ditherFrameAtkinson(byte[] frameData, int width, int height, float errorStrength) {
        // Slightly dampen Atkinson diffusion to better match video stability
        int errorStrengthFixed = (int) (Math.max(0.0f, Math.min(1.0f, errorStrength * 0.75f)) * 256.0f);
        int maxBands = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int numBands = Math.max(1, Math.min(maxBands, height / 32));

        ensureBandBuffers(numBands, width);

        boolean doTemporal = useTemporalDithering;
        int hashBucket = doTemporal ? Math.max(1, temporalThreshold * 2) : 1;
        int localTemporalThreshold = Math.min(255, temporalThreshold + 2);
        int errorMask = errorQuantizationBits > 0 ? -(1 << errorQuantizationBits) : 0;
        int localErrorThreshold = errorThreshold;
        byte[] prevFrame = previousDitheredFrame;
        int[] prevHash = previousSourceHash;
        int prevFrameLength = prevFrame.length;
        int prevHashLength = prevHash.length;
        byte[] output = ditheredFrameData;

        if (numBands <= 1) {
            int[] currentRow = bandDitherBuffers[0];
            int[] nextRow = bandDitherBuffers[1];
            int[] nextNextRow = bandDitherBuffers[2];
            java.util.Arrays.fill(currentRow, 0);
            java.util.Arrays.fill(nextRow, 0);
            java.util.Arrays.fill(nextNextRow, 0);
            ditherBandAtkinson(frameData, width, 0, height, errorStrengthFixed,
                currentRow, nextRow, nextNextRow,
                doTemporal, hashBucket, localTemporalThreshold,
                errorMask, localErrorThreshold,
                prevFrame, prevHash, prevFrameLength, prevHashLength, output);
            return;
        }

        int bandHeight = height / numBands;
        Future<?>[] futures = new Future<?>[numBands];
        for (int band = 0; band < numBands; band++) {
            int startY = band * bandHeight;
            int endY = (band == numBands - 1) ? height : startY + bandHeight;
            int[] currentRow = bandDitherBuffers[band * 3];
            int[] nextRow = bandDitherBuffers[band * 3 + 1];
            int[] nextNextRow = bandDitherBuffers[band * 3 + 2];

            futures[band] = executor.submit(() -> {
                java.util.Arrays.fill(currentRow, 0);
                java.util.Arrays.fill(nextRow, 0);
                java.util.Arrays.fill(nextNextRow, 0);
                ditherBandAtkinson(frameData, width, startY, endY, errorStrengthFixed,
                    currentRow, nextRow, nextNextRow,
                    doTemporal, hashBucket, localTemporalThreshold,
                    errorMask, localErrorThreshold,
                    prevFrame, prevHash, prevFrameLength, prevHashLength, output);
            });
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void ditherFrameStucki(byte[] frameData, int width, int height, float errorStrength) {
        int errorStrengthFixed = (int) (Math.max(0.0f, Math.min(1.0f, errorStrength * 0.85f)) * 256.0f);
        int maxBands = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int numBands = Math.max(1, Math.min(maxBands, height / 32));

        ensureBandBuffers(numBands, width);

        boolean doTemporal = useTemporalDithering;
        int hashBucket = doTemporal ? Math.max(1, temporalThreshold * 2) : 1;
        int localTemporalThreshold = Math.min(255, temporalThreshold + 1);
        int errorMask = errorQuantizationBits > 0 ? -(1 << errorQuantizationBits) : 0;
        int localErrorThreshold = errorThreshold;
        byte[] prevFrame = previousDitheredFrame;
        int[] prevHash = previousSourceHash;
        int prevFrameLength = prevFrame.length;
        int prevHashLength = prevHash.length;
        byte[] output = ditheredFrameData;

        if (numBands <= 1) {
            int[] currentRow = bandDitherBuffers[0];
            int[] nextRow = bandDitherBuffers[1];
            int[] nextNextRow = bandDitherBuffers[2];
            java.util.Arrays.fill(currentRow, 0);
            java.util.Arrays.fill(nextRow, 0);
            java.util.Arrays.fill(nextNextRow, 0);
            ditherBandStucki(frameData, width, 0, height, errorStrengthFixed,
                currentRow, nextRow, nextNextRow,
                doTemporal, hashBucket, localTemporalThreshold,
                errorMask, localErrorThreshold,
                prevFrame, prevHash, prevFrameLength, prevHashLength, output);
            return;
        }

        int bandHeight = height / numBands;
        Future<?>[] futures = new Future<?>[numBands];
        for (int band = 0; band < numBands; band++) {
            int startY = band * bandHeight;
            int endY = (band == numBands - 1) ? height : startY + bandHeight;
            int[] currentRow = bandDitherBuffers[band * 3];
            int[] nextRow = bandDitherBuffers[band * 3 + 1];
            int[] nextNextRow = bandDitherBuffers[band * 3 + 2];

            futures[band] = executor.submit(() -> {
                java.util.Arrays.fill(currentRow, 0);
                java.util.Arrays.fill(nextRow, 0);
                java.util.Arrays.fill(nextNextRow, 0);
                ditherBandStucki(frameData, width, startY, endY, errorStrengthFixed,
                    currentRow, nextRow, nextNextRow,
                    doTemporal, hashBucket, localTemporalThreshold,
                    errorMask, localErrorThreshold,
                    prevFrame, prevHash, prevFrameLength, prevHashLength, output);
            });
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void ditherBandAtkinson(byte[] frameData, int width,
                                           int startY, int endY, int errorStrengthFixed,
                                           int[] currentRow, int[] nextRow, int[] nextNextRow,
                                           boolean doTemporal, int hashBucket, int temporalThreshold,
                                           int errorMask, int errorThreshold,
                                           byte[] prevFrame, int[] prevHash,
                                           int prevFrameLength, int prevHashLength,
                                           byte[] output) {
        int widthMinus1 = width - 1;
        int widthMinus2 = width - 2;

        for (int y = startY; y < endY; y++) {
            boolean hasNextY = (y + 1) < endY;
            boolean hasNextNextY = (y + 2) < endY;
            int yIndex = y * width;
            int bufferIndex = 0;
            int pos = yIndex * 3;
            int pixelIdx = yIndex;

            for (int x = 0; x < width; x++, bufferIndex += 3, pos += 3, pixelIdx++) {
                int rawBlue = frameData[pos] & 0xff;
                int rawGreen = frameData[pos + 1] & 0xff;
                int rawRed = frameData[pos + 2] & 0xff;
                int blue = clamp((frameData[pos] & 0xff) + currentRow[bufferIndex + 2]);
                int green = clamp((frameData[pos + 1] & 0xff) + currentRow[bufferIndex + 1]);
                int red = clamp((frameData[pos + 2] & 0xff) + currentRow[bufferIndex]);

                if (tryTemporalReuse(doTemporal, pixelIdx, rawRed, rawGreen, rawBlue,
                    hashBucket, temporalThreshold,
                    prevFrame, prevHash, prevFrameLength, prevHashLength, output)) {
                    continue;
                }

                int lookupIdx = (red >> 1) << 14 | (green >> 1) << 7 | (blue >> 1);
                int closest = FULL_COLOR_MAP[lookupIdx];
                int deltaR = red - ((closest >> 16) & 0xFF);
                int deltaG = green - ((closest >> 8) & 0xFF);
                int deltaB = blue - (closest & 0xFF);
                int totalError = Math.abs(deltaR) + Math.abs(deltaG) + Math.abs(deltaB);
                if (totalError > errorThreshold) {
                    if (errorMask != 0) {
                        deltaR = deltaR >= 0 ? (deltaR & errorMask) : -((-deltaR) & errorMask);
                        deltaG = deltaG >= 0 ? (deltaG & errorMask) : -((-deltaG) & errorMask);
                        deltaB = deltaB >= 0 ? (deltaB & errorMask) : -((-deltaB) & errorMask);
                    }
                    deltaR = (deltaR * errorStrengthFixed) >> 8;
                    deltaG = (deltaG * errorStrengthFixed) >> 8;
                    deltaB = (deltaB * errorStrengthFixed) >> 8;

                    int shareR = deltaR >> 3;
                    int shareG = deltaG >> 3;
                    int shareB = deltaB >> 3;

                    if (x < widthMinus1) {
                        int idx = bufferIndex + 3;
                        currentRow[idx] += shareR;
                        currentRow[idx + 1] += shareG;
                        currentRow[idx + 2] += shareB;
                    }
                    if (x < widthMinus2) {
                        int idx = bufferIndex + 6;
                        currentRow[idx] += shareR;
                        currentRow[idx + 1] += shareG;
                        currentRow[idx + 2] += shareB;
                    }
                    if (hasNextY) {
                        if (x > 0) {
                            int idx = bufferIndex - 3;
                            nextRow[idx] += shareR;
                            nextRow[idx + 1] += shareG;
                            nextRow[idx + 2] += shareB;
                        }
                        nextRow[bufferIndex] += shareR;
                        nextRow[bufferIndex + 1] += shareG;
                        nextRow[bufferIndex + 2] += shareB;
                        if (x < widthMinus1) {
                            int idx = bufferIndex + 3;
                            nextRow[idx] += shareR;
                            nextRow[idx + 1] += shareG;
                            nextRow[idx + 2] += shareB;
                        }
                    }
                    if (hasNextNextY) {
                        nextNextRow[bufferIndex] += shareR;
                        nextNextRow[bufferIndex + 1] += shareG;
                        nextNextRow[bufferIndex + 2] += shareB;
                    }
                }

                output[pixelIdx] = COLOR_MAP[lookupIdx];
            }

            int[] temp = currentRow;
            currentRow = nextRow;
            nextRow = nextNextRow;
            nextNextRow = temp;
            java.util.Arrays.fill(nextNextRow, 0);
        }
    }

    private static void ditherBandStucki(byte[] frameData, int width,
                                         int startY, int endY, int errorStrengthFixed,
                                         int[] currentRow, int[] nextRow, int[] nextNextRow,
                                         boolean doTemporal, int hashBucket, int temporalThreshold,
                                         int errorMask, int errorThreshold,
                                         byte[] prevFrame, int[] prevHash,
                                         int prevFrameLength, int prevHashLength,
                                         byte[] output) {
        int widthMinus1 = width - 1;
        int widthMinus2 = width - 2;

        for (int y = startY; y < endY; y++) {
            boolean hasNextY = (y + 1) < endY;
            boolean hasNextNextY = (y + 2) < endY;
            int yIndex = y * width;
            int bufferIndex = 0;
            int pos = yIndex * 3;
            int pixelIdx = yIndex;

            for (int x = 0; x < width; x++, bufferIndex += 3, pos += 3, pixelIdx++) {
                int rawBlue = frameData[pos] & 0xff;
                int rawGreen = frameData[pos + 1] & 0xff;
                int rawRed = frameData[pos + 2] & 0xff;
                int blue = clamp((frameData[pos] & 0xff) + currentRow[bufferIndex + 2]);
                int green = clamp((frameData[pos + 1] & 0xff) + currentRow[bufferIndex + 1]);
                int red = clamp((frameData[pos + 2] & 0xff) + currentRow[bufferIndex]);

                if (tryTemporalReuse(doTemporal, pixelIdx, rawRed, rawGreen, rawBlue,
                    hashBucket, temporalThreshold,
                    prevFrame, prevHash, prevFrameLength, prevHashLength, output)) {
                    continue;
                }

                int lookupIdx = (red >> 1) << 14 | (green >> 1) << 7 | (blue >> 1);
                int closest = FULL_COLOR_MAP[lookupIdx];
                int deltaR = red - ((closest >> 16) & 0xFF);
                int deltaG = green - ((closest >> 8) & 0xFF);
                int deltaB = blue - (closest & 0xFF);
                int totalError = Math.abs(deltaR) + Math.abs(deltaG) + Math.abs(deltaB);
                if (totalError > errorThreshold) {
                    if (errorMask != 0) {
                        deltaR = deltaR >= 0 ? (deltaR & errorMask) : -((-deltaR) & errorMask);
                        deltaG = deltaG >= 0 ? (deltaG & errorMask) : -((-deltaG) & errorMask);
                        deltaB = deltaB >= 0 ? (deltaB & errorMask) : -((-deltaB) & errorMask);
                    }
                    deltaR = (deltaR * errorStrengthFixed) >> 8;
                    deltaG = (deltaG * errorStrengthFixed) >> 8;
                    deltaB = (deltaB * errorStrengthFixed) >> 8;

                    if (x < widthMinus1) {
                        int idx = bufferIndex + 3;
                        currentRow[idx] += (deltaR * 8) / 42;
                        currentRow[idx + 1] += (deltaG * 8) / 42;
                        currentRow[idx + 2] += (deltaB * 8) / 42;
                    }
                    if (x < widthMinus2) {
                        int idx = bufferIndex + 6;
                        currentRow[idx] += (deltaR * 4) / 42;
                        currentRow[idx + 1] += (deltaG * 4) / 42;
                        currentRow[idx + 2] += (deltaB * 4) / 42;
                    }

                    if (hasNextY) {
                        if (x > 1) {
                            int idx = bufferIndex - 6;
                            nextRow[idx] += (deltaR * 2) / 42;
                            nextRow[idx + 1] += (deltaG * 2) / 42;
                            nextRow[idx + 2] += (deltaB * 2) / 42;
                        }
                        if (x > 0) {
                            int idx = bufferIndex - 3;
                            nextRow[idx] += (deltaR * 4) / 42;
                            nextRow[idx + 1] += (deltaG * 4) / 42;
                            nextRow[idx + 2] += (deltaB * 4) / 42;
                        }
                        nextRow[bufferIndex] += (deltaR * 8) / 42;
                        nextRow[bufferIndex + 1] += (deltaG * 8) / 42;
                        nextRow[bufferIndex + 2] += (deltaB * 8) / 42;
                        if (x < widthMinus1) {
                            int idx = bufferIndex + 3;
                            nextRow[idx] += (deltaR * 4) / 42;
                            nextRow[idx + 1] += (deltaG * 4) / 42;
                            nextRow[idx + 2] += (deltaB * 4) / 42;
                        }
                        if (x < widthMinus2) {
                            int idx = bufferIndex + 6;
                            nextRow[idx] += (deltaR * 2) / 42;
                            nextRow[idx + 1] += (deltaG * 2) / 42;
                            nextRow[idx + 2] += (deltaB * 2) / 42;
                        }
                    }

                    if (hasNextNextY) {
                        if (x > 1) {
                            int idx = bufferIndex - 6;
                            nextNextRow[idx] += deltaR / 42;
                            nextNextRow[idx + 1] += deltaG / 42;
                            nextNextRow[idx + 2] += deltaB / 42;
                        }
                        if (x > 0) {
                            int idx = bufferIndex - 3;
                            nextNextRow[idx] += (deltaR * 2) / 42;
                            nextNextRow[idx + 1] += (deltaG * 2) / 42;
                            nextNextRow[idx + 2] += (deltaB * 2) / 42;
                        }
                        nextNextRow[bufferIndex] += (deltaR * 4) / 42;
                        nextNextRow[bufferIndex + 1] += (deltaG * 4) / 42;
                        nextNextRow[bufferIndex + 2] += (deltaB * 4) / 42;
                        if (x < widthMinus1) {
                            int idx = bufferIndex + 3;
                            nextNextRow[idx] += (deltaR * 2) / 42;
                            nextNextRow[idx + 1] += (deltaG * 2) / 42;
                            nextNextRow[idx + 2] += (deltaB * 2) / 42;
                        }
                        if (x < widthMinus2) {
                            int idx = bufferIndex + 6;
                            nextNextRow[idx] += deltaR / 42;
                            nextNextRow[idx + 1] += deltaG / 42;
                            nextNextRow[idx + 2] += deltaB / 42;
                        }
                    }
                }

                output[pixelIdx] = COLOR_MAP[lookupIdx];
            }

            int[] temp = currentRow;
            currentRow = nextRow;
            nextRow = nextNextRow;
            nextNextRow = temp;
            java.util.Arrays.fill(nextNextRow, 0);
        }
    }

    private static void ditherBand(byte[] frameData, int width, int widthMinus,
                                   int startY, int endY, int errorStrengthFixed,
                                   int[] currentRow, int[] nextRow,
                                   boolean doTemporal, int hashBucket, int temporalThreshold,
                                   int errorMask, int errorThreshold,
                                   byte[] prevFrame, int[] prevHash,
                                   int prevFrameLength, int prevHashLength,
                                   byte[] output) {
        for (int y = startY; y < endY; y++) {
            boolean hasNextY = (y + 1) < endY; // Only propagate error within this band
            int yIndex = y * width;

            // Swap error buffers
            int[] temp = currentRow;
            currentRow = nextRow;
            nextRow = temp;
            java.util.Arrays.fill(nextRow, 0);

            if ((y & 0x1) == 0) {
                ditherRowForwardOpt(frameData, currentRow, nextRow, width, widthMinus,
                                    hasNextY, yIndex, errorStrengthFixed,
                                    doTemporal, hashBucket, temporalThreshold,
                                    errorMask, errorThreshold,
                                    prevFrame, prevHash, prevFrameLength, prevHashLength, output);
            } else {
                ditherRowBackwardOpt(frameData, currentRow, nextRow, widthMinus,
                                     hasNextY, yIndex, errorStrengthFixed,
                                     doTemporal, hashBucket, temporalThreshold,
                                     errorMask, errorThreshold,
                                     prevFrame, prevHash, prevFrameLength, prevHashLength, output);
            }
        }
    }

    private static void ditherRowForwardOpt(byte[] frameData, int[] currentRow, int[] nextRow,
                                            int width, int widthMinus, boolean hasNextY, int yIndex,
                                            int errorStrengthFixed,
                                            boolean doTemporal, int hashBucket, int temporalThreshold,
                                            int errorMask, int errorThreshold,
                                            byte[] prevFrame, int[] prevHash,
                                            int prevFrameLength, int prevHashLength,
                                            byte[] output) {
        int bufferIndex = 0;
        int pos = yIndex * 3;
        int pixelIdx = yIndex;

        for (int x = 0; x < width; x++, bufferIndex += 3, pos += 3, pixelIdx++) {
            int rawBlue = frameData[pos] & 0xff;
            int rawGreen = frameData[pos + 1] & 0xff;
            int rawRed = frameData[pos + 2] & 0xff;
            int blue = clamp((frameData[pos] & 0xff) + currentRow[bufferIndex + 2]);
            int green = clamp((frameData[pos + 1] & 0xff) + currentRow[bufferIndex + 1]);
            int red = clamp((frameData[pos + 2] & 0xff) + currentRow[bufferIndex]);

            if (tryTemporalReuse(doTemporal, pixelIdx, rawRed, rawGreen, rawBlue,
                hashBucket, temporalThreshold,
                prevFrame, prevHash, prevFrameLength, prevHashLength, output)) {
                continue;
            }

            int lookupIdx = (red >> 1) << 14 | (green >> 1) << 7 | (blue >> 1);
            int closest = FULL_COLOR_MAP[lookupIdx];
            int closestR = (closest >> 16) & 0xFF;
            int closestG = (closest >> 8) & 0xFF;
            int closestB = closest & 0xFF;
            int deltaR = red - closestR;
            int deltaG = green - closestG;
            int deltaB = blue - closestB;
            int totalError = Math.abs(deltaR) + Math.abs(deltaG) + Math.abs(deltaB);
            if (totalError > errorThreshold) {
                if (errorMask != 0) {
                    deltaR = deltaR >= 0 ? (deltaR & errorMask) : -((-deltaR) & errorMask);
                    deltaG = deltaG >= 0 ? (deltaG & errorMask) : -((-deltaG) & errorMask);
                    deltaB = deltaB >= 0 ? (deltaB & errorMask) : -((-deltaB) & errorMask);
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
            output[pixelIdx] = COLOR_MAP[lookupIdx];
        }
    }

    private static void ditherRowBackwardOpt(byte[] frameData, int[] currentRow, int[] nextRow,
                                             int widthMinus, boolean hasNextY, int yIndex,
                                             int errorStrengthFixed,
                                             boolean doTemporal, int hashBucket, int temporalThreshold,
                                             int errorMask, int errorThreshold,
                                             byte[] prevFrame, int[] prevHash,
                                             int prevFrameLength, int prevHashLength,
                                             byte[] output) {
        int bufferIndex = widthMinus * 3;
        int pos = (yIndex + widthMinus) * 3;
        int pixelIdx = yIndex + widthMinus;

        for (int x = widthMinus; x >= 0; x--, bufferIndex -= 3, pos -= 3, pixelIdx--) {
            int rawBlue = frameData[pos] & 0xff;
            int rawGreen = frameData[pos + 1] & 0xff;
            int rawRed = frameData[pos + 2] & 0xff;
            int blue = clamp((frameData[pos] & 0xff) + currentRow[bufferIndex + 2]);
            int green = clamp((frameData[pos + 1] & 0xff) + currentRow[bufferIndex + 1]);
            int red = clamp((frameData[pos + 2] & 0xff) + currentRow[bufferIndex]);

            if (tryTemporalReuse(doTemporal, pixelIdx, rawRed, rawGreen, rawBlue,
                hashBucket, temporalThreshold,
                prevFrame, prevHash, prevFrameLength, prevHashLength, output)) {
                continue;
            }

            int lookupIdx = (red >> 1) << 14 | (green >> 1) << 7 | (blue >> 1);
            int closest = FULL_COLOR_MAP[lookupIdx];
            int closestR = (closest >> 16) & 0xFF;
            int closestG = (closest >> 8) & 0xFF;
            int closestB = closest & 0xFF;
            int deltaR = red - closestR;
            int deltaG = green - closestG;
            int deltaB = blue - closestB;
            int totalError = Math.abs(deltaR) + Math.abs(deltaG) + Math.abs(deltaB);
            if (totalError > errorThreshold) {
                if (errorMask != 0) {
                    deltaR = deltaR >= 0 ? (deltaR & errorMask) : -((-deltaR) & errorMask);
                    deltaG = deltaG >= 0 ? (deltaG & errorMask) : -((-deltaG) & errorMask);
                    deltaB = deltaB >= 0 ? (deltaB & errorMask) : -((-deltaB) & errorMask);
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
            output[pixelIdx] = COLOR_MAP[lookupIdx];
        }
    }


    private static byte getColor(int rgb) {
        return COLOR_MAP[((rgb >> 16) & 0xFF) >> 1 << 14 |
                         ((rgb >> 8) & 0xFF) >> 1 << 7 |
                         (rgb & 0xFF) >> 1];
    }

    private static boolean tryTemporalReuse(boolean doTemporal, int pixelIdx,
                                            int red, int green, int blue,
                                            int hashBucket, int temporalThreshold,
                                            byte[] prevFrame, int[] prevHash,
                                            int prevFrameLength, int prevHashLength,
                                            byte[] output) {
        if (!doTemporal || pixelIdx >= prevFrameLength || pixelIdx >= prevHashLength) {
            return false;
        }

        int currentHash = (((red / hashBucket) << 16) |
            ((green / hashBucket) << 8) |
            (blue / hashBucket)) + 1;
        int prevHashValue = prevHash[pixelIdx];

        if (currentHash == prevHashValue) {
            output[pixelIdx] = prevFrame[pixelIdx];
            return true;
        }

        if (prevHashValue > 0) {
            int prevR = ((prevHashValue - 1) >> 16) * hashBucket;
            int prevG = (((prevHashValue - 1) >> 8) & 0xFF) * hashBucket;
            int prevB = ((prevHashValue - 1) & 0xFF) * hashBucket;
            if (Math.abs(red - prevR) <= temporalThreshold &&
                Math.abs(green - prevG) <= temporalThreshold &&
                Math.abs(blue - prevB) <= temporalThreshold) {
                // Track drift while still reusing the previous palette index
                prevHash[pixelIdx] = currentHash;
                output[pixelIdx] = prevFrame[pixelIdx];
                return true;
            }
        }

        prevHash[pixelIdx] = currentHash;
        return false;
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
        byte[][] fullMapData,
        FrameContentStats contentStats
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

    public record FrameContentStats(double motionScore, double flatScore, double lowSaturationScore) {
    }

    public record AdaptiveDitherProfile(
        int temporalThreshold,
        int errorQuantizationBits,
        int errorThreshold,
        float errorDiffusionStrength,
        String mode
    ) {
    }
}

