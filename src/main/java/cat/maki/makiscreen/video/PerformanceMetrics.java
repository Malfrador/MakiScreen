package cat.maki.makiscreen.video;

import java.util.concurrent.atomic.AtomicLong;

public class PerformanceMetrics {

    // Frame decode timing
    private final AtomicLong frameDecodeTimeNs = new AtomicLong(0);
    private final AtomicLong frameDecodeCount = new AtomicLong(0);

    // Image conversion timing
    private final AtomicLong imageConversionTimeNs = new AtomicLong(0);
    private final AtomicLong imageConversionCount = new AtomicLong(0);

    // Dithering timing (broken down)
    private final AtomicLong ditheringTimeNs = new AtomicLong(0);
    private final AtomicLong ditheringCount = new AtomicLong(0);

    // Upscaling timing
    private final AtomicLong upscalingTimeNs = new AtomicLong(0);
    private final AtomicLong upscalingCount = new AtomicLong(0);

    // Tile extraction timing
    private final AtomicLong tileExtractionTimeNs = new AtomicLong(0);
    private final AtomicLong tileExtractionCount = new AtomicLong(0);

    // Packet dispatch timing
    private final AtomicLong packetDispatchTimeNs = new AtomicLong(0);
    private final AtomicLong packetDispatchCount = new AtomicLong(0);

    // Packet creation timing
    private final AtomicLong packetCreationTimeNs = new AtomicLong(0);
    private final AtomicLong packetCreationCount = new AtomicLong(0);

    // Packet sending timing
    private final AtomicLong packetSendingTimeNs = new AtomicLong(0);
    private final AtomicLong packetSendingCount = new AtomicLong(0);

    // Total frame processing time
    private final AtomicLong totalFrameTimeNs = new AtomicLong(0);
    private final AtomicLong totalFrameCount = new AtomicLong(0);

    // Output-based temporal stability: pixels kept unchanged due to hysteresis
    private final AtomicLong pixelsKeptByOutputStability = new AtomicLong(0);
    private final AtomicLong totalPixelsProcessed = new AtomicLong(0);

    // Motion-adaptive: tracks error reduction in high-motion areas
    private final AtomicLong motionReducedErrorPixels = new AtomicLong(0);

    // Luminance-adaptive: pixels where threshold was increased
    private final AtomicLong luminanceAdaptedPixels = new AtomicLong(0);

    // Block-based dirty detection: bytes saved by multi-region vs single bounding box
    private final AtomicLong bytesSavedByBlockDetection = new AtomicLong(0);
    private final AtomicLong totalDirtyBytes = new AtomicLong(0);

    // Dirty region stats
    private final AtomicLong tilesWithChanges = new AtomicLong(0);
    private final AtomicLong tilesSkipped = new AtomicLong(0);
    private final AtomicLong multiRegionTiles = new AtomicLong(0);

    // Last frame optimization stats
    private volatile float lastOutputStabilityPercent = 0;
    private volatile float lastMotionAdaptivePercent = 0;
    private volatile float lastLuminanceAdaptivePercent = 0;
    private volatile float lastBlockDetectionSavingsPercent = 0;
    private volatile int lastDirtyTileCount = 0;
    private volatile int lastSkippedTileCount = 0;
    private volatile int lastMultiRegionCount = 0;

    // Moving averages
    private volatile long avgFrameDecodeUs = 0;
    private volatile long avgImageConversionUs = 0;
    private volatile long avgDitheringUs = 0;
    private volatile long avgUpscalingUs = 0;
    private volatile long avgTileExtractionUs = 0;
    private volatile long avgPacketDispatchUs = 0;
    private volatile long avgPacketCreationUs = 0;
    private volatile long avgPacketSendingUs = 0;
    private volatile long avgTotalFrameUs = 0;

    // Last frame metrics
    private volatile long lastFrameDecodeUs = 0;
    private volatile long lastImageConversionUs = 0;
    private volatile long lastDitheringUs = 0;
    private volatile long lastUpscalingUs = 0;
    private volatile long lastTileExtractionUs = 0;
    private volatile long lastPacketDispatchUs = 0;
    private volatile long lastPacketCreationUs = 0;
    private volatile long lastPacketSendingUs = 0;
    private volatile long lastTotalFrameUs = 0;

    public void recordFrameDecode(long durationNs) {
        frameDecodeTimeNs.addAndGet(durationNs);
        frameDecodeCount.incrementAndGet();
        lastFrameDecodeUs = durationNs / 1000;
        updateAverage();
    }

    public void recordImageConversion(long durationNs) {
        imageConversionTimeNs.addAndGet(durationNs);
        imageConversionCount.incrementAndGet();
        lastImageConversionUs = durationNs / 1000;
        updateAverage();
    }

    public void recordDithering(long durationNs) {
        ditheringTimeNs.addAndGet(durationNs);
        ditheringCount.incrementAndGet();
        lastDitheringUs = durationNs / 1000;
        updateAverage();
    }

    public void recordUpscaling(long durationNs) {
        upscalingTimeNs.addAndGet(durationNs);
        upscalingCount.incrementAndGet();
        lastUpscalingUs = durationNs / 1000;
        updateAverage();
    }

    public void recordTileExtraction(long durationNs) {
        tileExtractionTimeNs.addAndGet(durationNs);
        tileExtractionCount.incrementAndGet();
        lastTileExtractionUs = durationNs / 1000;
        updateAverage();
    }

    public void recordPacketDispatch(long durationNs) {
        packetDispatchTimeNs.addAndGet(durationNs);
        packetDispatchCount.incrementAndGet();
        lastPacketDispatchUs = durationNs / 1000;
        updateAverage();
    }

    public void recordPacketCreation(long durationNs) {
        packetCreationTimeNs.addAndGet(durationNs);
        packetCreationCount.incrementAndGet();
        lastPacketCreationUs = durationNs / 1000;
        updateAverage();
    }

    public void recordPacketSending(long durationNs) {
        packetSendingTimeNs.addAndGet(durationNs);
        packetSendingCount.incrementAndGet();
        lastPacketSendingUs = durationNs / 1000;
        updateAverage();
    }

    public void recordTotalFrame(long durationNs) {
        totalFrameTimeNs.addAndGet(durationNs);
        totalFrameCount.incrementAndGet();
        lastTotalFrameUs = durationNs / 1000;
        updateAverage();
    }


    public void recordOutputStability(long keptPixels, long totalPixels) {
        pixelsKeptByOutputStability.addAndGet(keptPixels);
        totalPixelsProcessed.addAndGet(totalPixels);
        if (totalPixels > 0) {
            lastOutputStabilityPercent = (float) keptPixels / totalPixels * 100f;
        }
    }

    public void recordMotionAdaptive(long reducedPixels, long totalPixels) {
        motionReducedErrorPixels.addAndGet(reducedPixels);
        if (totalPixels > 0) {
            lastMotionAdaptivePercent = (float) reducedPixels / totalPixels * 100f;
        }
    }

    public void recordLuminanceAdaptive(long adaptedPixels, long totalPixels) {
        luminanceAdaptedPixels.addAndGet(adaptedPixels);
        if (totalPixels > 0) {
            lastLuminanceAdaptivePercent = (float) adaptedPixels / totalPixels * 100f;
        }
    }

    public void recordBlockDetectionSavings(long savedBytes, long totalBytes) {
        bytesSavedByBlockDetection.addAndGet(savedBytes);
        totalDirtyBytes.addAndGet(totalBytes);
        if (totalBytes > 0) {
            lastBlockDetectionSavingsPercent = (float) savedBytes / totalBytes * 100f;
        }
    }

    public void recordDirtyTileStats(int changedTiles, int skippedTiles, int multiRegion) {
        tilesWithChanges.addAndGet(changedTiles);
        tilesSkipped.addAndGet(skippedTiles);
        multiRegionTiles.addAndGet(multiRegion);
        lastDirtyTileCount = changedTiles;
        lastSkippedTileCount = skippedTiles;
        lastMultiRegionCount = multiRegion;
    }

    private void updateAverage() {
        long decodeCount = frameDecodeCount.get();
        if (decodeCount > 0) {
            avgFrameDecodeUs = frameDecodeTimeNs.get() / decodeCount / 1000;
        }

        long convCount = imageConversionCount.get();
        if (convCount > 0) {
            avgImageConversionUs = imageConversionTimeNs.get() / convCount / 1000;
        }

        long ditherCount = ditheringCount.get();
        if (ditherCount > 0) {
            avgDitheringUs = ditheringTimeNs.get() / ditherCount / 1000;
        }

        long upscaleCount = upscalingCount.get();
        if (upscaleCount > 0) {
            avgUpscalingUs = upscalingTimeNs.get() / upscaleCount / 1000;
        }

        long tileCount = tileExtractionCount.get();
        if (tileCount > 0) {
            avgTileExtractionUs = tileExtractionTimeNs.get() / tileCount / 1000;
        }

        long dispatchCount = packetDispatchCount.get();
        if (dispatchCount > 0) {
            avgPacketDispatchUs = packetDispatchTimeNs.get() / dispatchCount / 1000;
        }

        long creationCount = packetCreationCount.get();
        if (creationCount > 0) {
            avgPacketCreationUs = packetCreationTimeNs.get() / creationCount / 1000;
        }

        long sendCount = packetSendingCount.get();
        if (sendCount > 0) {
            avgPacketSendingUs = packetSendingTimeNs.get() / sendCount / 1000;
        }

        long totalCount = totalFrameCount.get();
        if (totalCount > 0) {
            avgTotalFrameUs = totalFrameTimeNs.get() / totalCount / 1000;
        }
    }

    public void reset() {
        frameDecodeTimeNs.set(0);
        frameDecodeCount.set(0);
        imageConversionTimeNs.set(0);
        imageConversionCount.set(0);
        ditheringTimeNs.set(0);
        ditheringCount.set(0);
        upscalingTimeNs.set(0);
        upscalingCount.set(0);
        tileExtractionTimeNs.set(0);
        tileExtractionCount.set(0);
        packetDispatchTimeNs.set(0);
        packetDispatchCount.set(0);
        packetCreationTimeNs.set(0);
        packetCreationCount.set(0);
        packetSendingTimeNs.set(0);
        packetSendingCount.set(0);
        totalFrameTimeNs.set(0);
        totalFrameCount.set(0);

        avgFrameDecodeUs = 0;
        avgImageConversionUs = 0;
        avgDitheringUs = 0;
        avgUpscalingUs = 0;
        avgTileExtractionUs = 0;
        avgPacketDispatchUs = 0;
        avgPacketCreationUs = 0;
        avgPacketSendingUs = 0;
        avgTotalFrameUs = 0;

        lastFrameDecodeUs = 0;
        lastImageConversionUs = 0;
        lastDitheringUs = 0;
        lastUpscalingUs = 0;
        lastTileExtractionUs = 0;
        lastPacketDispatchUs = 0;
        lastPacketCreationUs = 0;
        lastPacketSendingUs = 0;
        lastTotalFrameUs = 0;

        // Reset optimization metrics
        pixelsKeptByOutputStability.set(0);
        totalPixelsProcessed.set(0);
        motionReducedErrorPixels.set(0);
        luminanceAdaptedPixels.set(0);
        bytesSavedByBlockDetection.set(0);
        totalDirtyBytes.set(0);
        tilesWithChanges.set(0);
        tilesSkipped.set(0);
        multiRegionTiles.set(0);

        lastOutputStabilityPercent = 0;
        lastMotionAdaptivePercent = 0;
        lastLuminanceAdaptivePercent = 0;
        lastBlockDetectionSavingsPercent = 0;
        lastDirtyTileCount = 0;
        lastSkippedTileCount = 0;
        lastMultiRegionCount = 0;
    }

    public long getAvgFrameDecodeUs() { return avgFrameDecodeUs; }
    public long getAvgImageConversionUs() { return avgImageConversionUs; }
    public long getAvgDitheringUs() { return avgDitheringUs; }
    public long getAvgUpscalingUs() { return avgUpscalingUs; }
    public long getAvgTileExtractionUs() { return avgTileExtractionUs; }
    public long getAvgPacketDispatchUs() { return avgPacketDispatchUs; }
    public long getAvgPacketCreationUs() { return avgPacketCreationUs; }
    public long getAvgPacketSendingUs() { return avgPacketSendingUs; }
    public long getAvgTotalFrameUs() { return avgTotalFrameUs; }

    public long getLastFrameDecodeUs() { return lastFrameDecodeUs; }
    public long getLastImageConversionUs() { return lastImageConversionUs; }
    public long getLastDitheringUs() { return lastDitheringUs; }
    public long getLastUpscalingUs() { return lastUpscalingUs; }
    public long getLastTileExtractionUs() { return lastTileExtractionUs; }
    public long getLastPacketDispatchUs() { return lastPacketDispatchUs; }
    public long getLastPacketCreationUs() { return lastPacketCreationUs; }
    public long getLastPacketSendingUs() { return lastPacketSendingUs; }
    public long getLastTotalFrameUs() { return lastTotalFrameUs; }

    public long getFrameDecodeCount() { return frameDecodeCount.get(); }
    public long getImageConversionCount() { return imageConversionCount.get(); }
    public long getDitheringCount() { return ditheringCount.get(); }
    public long getUpscalingCount() { return upscalingCount.get(); }
    public long getTileExtractionCount() { return tileExtractionCount.get(); }
    public long getPacketDispatchCount() { return packetDispatchCount.get(); }
    public long getPacketCreationCount() { return packetCreationCount.get(); }
    public long getPacketSendingCount() { return packetSendingCount.get(); }
    public long getTotalFrameCount() { return totalFrameCount.get(); }

    public float getLastOutputStabilityPercent() { return lastOutputStabilityPercent; }
    public float getLastMotionAdaptivePercent() { return lastMotionAdaptivePercent; }
    public float getLastLuminanceAdaptivePercent() { return lastLuminanceAdaptivePercent; }
    public float getLastBlockDetectionSavingsPercent() { return lastBlockDetectionSavingsPercent; }
    public int getLastDirtyTileCount() { return lastDirtyTileCount; }
    public int getLastSkippedTileCount() { return lastSkippedTileCount; }
    public int getLastMultiRegionCount() { return lastMultiRegionCount; }

    public long getPixelsKeptByOutputStability() { return pixelsKeptByOutputStability.get(); }
    public long getTotalPixelsProcessed() { return totalPixelsProcessed.get(); }
    public long getMotionReducedErrorPixels() { return motionReducedErrorPixels.get(); }
    public long getLuminanceAdaptedPixels() { return luminanceAdaptedPixels.get(); }
    public long getBytesSavedByBlockDetection() { return bytesSavedByBlockDetection.get(); }
    public long getTotalDirtyBytes() { return totalDirtyBytes.get(); }
    public long getTilesWithChanges() { return tilesWithChanges.get(); }
    public long getTilesSkipped() { return tilesSkipped.get(); }
    public long getMultiRegionTiles() { return multiRegionTiles.get(); }

    public float getOverallOutputStabilityPercent() {
        long total = totalPixelsProcessed.get();
        return total > 0 ? (float) pixelsKeptByOutputStability.get() / total * 100f : 0;
    }

    public float getOverallBlockDetectionSavingsPercent() {
        long total = totalDirtyBytes.get();
        return total > 0 ? (float) bytesSavedByBlockDetection.get() / total * 100f : 0;
    }

}

