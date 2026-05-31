package de.erethon.mccinema.screen;

import de.erethon.mccinema.util.ByteArrayPool;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class MapTile implements ConfigurationSerializable {

    public static final int SIZE = 128;
    public static final int TOTAL_PIXELS = SIZE * SIZE;

    // Minimum pixel change threshold - if fewer pixels changed, skip the update
    // This prevents sending updates for imperceptible changes (e.g., dithering noise)
    private static final int MIN_CHANGE_THRESHOLD = 16;

    // minimum change density (changed pixels / total pixels)
    private static final float MIN_CHANGE_DENSITY = 0.002f;

    private final int mapId;
    private final int tileX;
    private final int tileY;
    private final int tileIndex;

    private byte[] lastFrameData;
    private byte[] lastSentData; // Data that was actually sent to clients
    private DirtyRegion dirtyRegion;
    private boolean needsFullUpdate = true;
    private int unchangedFrameCount = 0;
    private int framesSinceLastSend = 0; // Tracks how many frames since this tile was sent
    private int accumulatedChanges = 0; // Tracks accumulated pixel changes while skipped

    public MapTile(int mapId, int tileX, int tileY, int tileIndex) {
        this.mapId = mapId;
        this.tileX = tileX;
        this.tileY = tileY;
        this.tileIndex = tileIndex;
        this.lastFrameData = new byte[SIZE * SIZE];
        this.lastSentData = new byte[SIZE * SIZE];
    }

    public int getMapId() {
        return mapId;
    }

    public int getTileX() {
        return tileX;
    }

    public int getTileY() {
        return tileY;
    }

    public int getTileIndex() {
        return tileIndex;
    }

    public int getPixelOffsetX() {
        return tileX * SIZE;
    }

    public int getPixelOffsetY() {
        return tileY * SIZE;
    }

    public byte[] getLastFrameData() {
        return lastFrameData;
    }

    public void setLastFrameData(byte[] data) {
        this.lastFrameData = data;
    }

    public int getFramesSinceLastSend() {
        return framesSinceLastSend;
    }

    public void incrementFramesSinceLastSend() {
        framesSinceLastSend++;
    }

    public void resetFramesSinceLastSend() {
        framesSinceLastSend = 0;
    }

    public int getAccumulatedChanges() {
        return accumulatedChanges;
    }

    public void addAccumulatedChanges(int changes) {
        accumulatedChanges += changes;
    }

    public void resetAccumulatedChanges() {
        accumulatedChanges = 0;
    }

    public byte[] getLastSentData() {
        return lastSentData;
    }

    public void setLastSentData(byte[] data) {
        this.lastSentData = data;
    }

    /**
     * Calculate dirty region compared to what was sent
     */
    public DirtyRegion calculateDirtyRegionFromSent(byte[] newData) {
        if (needsFullUpdate || lastSentData == null) {
            needsFullUpdate = false;
            return new DirtyRegion(0, 0, SIZE, SIZE, newData);
        }

        int firstDiff = Arrays.mismatch(lastSentData, newData);
        if (firstDiff < 0) {
            return null;
        }

        int minX = SIZE, minY = SIZE, maxX = -1, maxY = -1;
        int changedPixelCount = 0;

        // Pre-allocate arrays for row min/max X to avoid repeated conditionals
        int[] rowMinX = new int[SIZE];
        int[] rowMaxX = new int[SIZE];
        Arrays.fill(rowMinX, SIZE);
        Arrays.fill(rowMaxX, -1);

        // Row-based scanning with 8-byte word-aligned comparisons
        for (int y = 0; y < SIZE; y++) {
            int rowOffset = y * SIZE;
            boolean rowHasChanges = false;

            // Compare in 8-byte chunks
            int x = 0;
            int limit = SIZE - 8;

            // Process 8 bytes at a time using long comparisons
            for (; x <= limit; x += 8) {
                int idx = rowOffset + x;

                // Compare 8 bytes as a single long
                long oldLong = ((long)(lastSentData[idx] & 0xFF)) |
                              ((long)(lastSentData[idx + 1] & 0xFF) << 8) |
                              ((long)(lastSentData[idx + 2] & 0xFF) << 16) |
                              ((long)(lastSentData[idx + 3] & 0xFF) << 24) |
                              ((long)(lastSentData[idx + 4] & 0xFF) << 32) |
                              ((long)(lastSentData[idx + 5] & 0xFF) << 40) |
                              ((long)(lastSentData[idx + 6] & 0xFF) << 48) |
                              ((long)(lastSentData[idx + 7] & 0xFF) << 56);

                long newLong = ((long)(newData[idx] & 0xFF)) |
                              ((long)(newData[idx + 1] & 0xFF) << 8) |
                              ((long)(newData[idx + 2] & 0xFF) << 16) |
                              ((long)(newData[idx + 3] & 0xFF) << 24) |
                              ((long)(newData[idx + 4] & 0xFF) << 32) |
                              ((long)(newData[idx + 5] & 0xFF) << 40) |
                              ((long)(newData[idx + 6] & 0xFF) << 48) |
                              ((long)(newData[idx + 7] & 0xFF) << 56);

                if (oldLong != newLong) {
                    // At least one byte in this 8-byte chunk differs
                    // Check individual bytes to find exact changes
                    for (int i = 0; i < 8; i++) {
                        if (lastSentData[idx + i] != newData[idx + i]) {
                            changedPixelCount++;
                            rowHasChanges = true;
                            int pixelX = x + i;
                            if (pixelX < rowMinX[y]) rowMinX[y] = pixelX;
                            if (pixelX > rowMaxX[y]) rowMaxX[y] = pixelX;
                        }
                    }
                }
            }

            // Handle remaining bytes (less than 8)
            for (; x < SIZE; x++) {
                int index = rowOffset + x;
                if (lastSentData[index] != newData[index]) {
                    changedPixelCount++;
                    rowHasChanges = true;
                    if (x < rowMinX[y]) rowMinX[y] = x;
                    if (x > rowMaxX[y]) rowMaxX[y] = x;
                }
            }

            // Update global min/max based on row results
            if (rowHasChanges) {
                if (rowMinX[y] < minX) minX = rowMinX[y];
                if (rowMaxX[y] > maxX) maxX = rowMaxX[y];
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        if (maxX < 0 || changedPixelCount < MIN_CHANGE_THRESHOLD) {
            return null;
        }

        // Skip if change density is too low
        float changeDensity = (float)changedPixelCount / TOTAL_PIXELS;
        if (changeDensity < MIN_CHANGE_DENSITY) {
            return null;
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int boundingBoxSize = width * height;
        boolean largeBox = boundingBoxSize > TOTAL_PIXELS * 0.7;
        boolean highDensity = changeDensity > 0.5f;

        if (largeBox && highDensity) {
            return new DirtyRegion(0, 0, SIZE, SIZE, newData, changedPixelCount);
        }

        byte[] patchData = ByteArrayPool.getTileBuffer(boundingBoxSize);
        try {
            for (int y = 0; y < height; y++) {
                int srcOffset = (minY + y) * SIZE + minX;
                int dstOffset = y * width;
                System.arraycopy(newData, srcOffset, patchData, dstOffset, width);
            }

            // Clone the data since we're returning it
            byte[] result = patchData.clone();
            return new DirtyRegion(minX, minY, width, height, result, changedPixelCount);
        } finally {
            ByteArrayPool.releaseTileBuffer();
        }
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("mapId", mapId);
        map.put("tileX", tileX);
        map.put("tileY", tileY);
        map.put("tileIndex", tileIndex);
        return map;
    }

    public static MapTile deserialize(Map<String, Object> map) {
        int mapId = (int) map.get("mapId");
        int tileX = (int) map.get("tileX");
        int tileY = (int) map.get("tileY");
        int tileIndex = (int) map.get("tileIndex");
        return new MapTile(mapId, tileX, tileY, tileIndex);
    }

    public record DirtyRegion(int x, int y, int width, int height, byte[] data, int changedPixelCount) {

        public DirtyRegion(int x, int y, int width, int height, byte[] data) {
            this(x, y, width, height, data, width * height);
        }

        public int getDataSize() {
            return width * height;
        }

        public boolean isFullMap() {
            return x == 0 && y == 0 && width == SIZE && height == SIZE;
        }

        public float getCoveragePercent() {
            return (float) getDataSize() / TOTAL_PIXELS * 100f;
        }

    }
}

