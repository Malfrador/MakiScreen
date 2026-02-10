package cat.maki.makiscreen.screen;

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
    private static final int MIN_CHANGE_THRESHOLD = 8;

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

    public DirtyRegion getDirtyRegion() {
        return dirtyRegion;
    }

    public void setDirtyRegion(DirtyRegion dirtyRegion) {
        this.dirtyRegion = dirtyRegion;
    }

    public boolean needsFullUpdate() {
        return needsFullUpdate;
    }

    public void setNeedsFullUpdate(boolean needsFullUpdate) {
        this.needsFullUpdate = needsFullUpdate;
    }

    public void clearDirtyRegion() {
        this.dirtyRegion = null;
    }

    public DirtyRegion calculateDirtyRegion(byte[] newData) {
        if (needsFullUpdate || lastFrameData == null) {
            needsFullUpdate = false;
            unchangedFrameCount = 0;
            return new DirtyRegion(0, 0, SIZE, SIZE, newData);
        }

        // Quick check: if arrays are identical, no changes needed
        if (Arrays.equals(lastFrameData, newData)) {
            unchangedFrameCount++;
            return null;
        }

        int minX = SIZE, minY = SIZE, maxX = -1, maxY = -1;
        int changedPixelCount = 0;

        // Row-based scanning with pixel counting
        for (int y = 0; y < SIZE; y++) {
            int rowOffset = y * SIZE;

            for (int x = 0; x < SIZE; x++) {
                int index = rowOffset + x;
                if (lastFrameData[index] != newData[index]) {
                    changedPixelCount++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0) {
            unchangedFrameCount++;
            return null; // No changes
        }

        // Skip updates with very few changed pixels (imperceptible noise)
        if (changedPixelCount < MIN_CHANGE_THRESHOLD) {
            // Still track as "unchanged" for optimization purposes
            unchangedFrameCount++;
            return null;
        }

        unchangedFrameCount = 0;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        byte[] patchData = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int srcOffset = (minY + y) * SIZE + minX;
            int dstOffset = y * width;
            System.arraycopy(newData, srcOffset, patchData, dstOffset, width);
        }

        return new DirtyRegion(minX, minY, width, height, patchData, changedPixelCount);
    }

    public int getUnchangedFrameCount() {
        return unchangedFrameCount;
    }

    public void resetUnchangedFrameCount() {
        unchangedFrameCount = 0;
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
     * Calculate dirty region compared to what was actually SENT to clients,
     * not just what was processed. This ensures skipped frames accumulate properly.
     */
    public DirtyRegion calculateDirtyRegionFromSent(byte[] newData) {
        if (needsFullUpdate || lastSentData == null) {
            needsFullUpdate = false;
            return new DirtyRegion(0, 0, SIZE, SIZE, newData);
        }

        // Compare against last SENT data, not last processed
        if (Arrays.equals(lastSentData, newData)) {
            return null;
        }

        int minX = SIZE, minY = SIZE, maxX = -1, maxY = -1;
        int changedPixelCount = 0;

        for (int y = 0; y < SIZE; y++) {
            int rowOffset = y * SIZE;
            for (int x = 0; x < SIZE; x++) {
                int index = rowOffset + x;
                if (lastSentData[index] != newData[index]) {
                    changedPixelCount++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0 || changedPixelCount < MIN_CHANGE_THRESHOLD) {
            return null;
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int boundingBoxSize = width * height;

        if (boundingBoxSize > TOTAL_PIXELS * 0.7) {
            return new DirtyRegion(0, 0, SIZE, SIZE, newData, changedPixelCount);
        }

        byte[] patchData = new byte[boundingBoxSize];
        for (int y = 0; y < height; y++) {
            int srcOffset = (minY + y) * SIZE + minX;
            int dstOffset = y * width;
            System.arraycopy(newData, srcOffset, patchData, dstOffset, width);
        }

        return new DirtyRegion(minX, minY, width, height, patchData, changedPixelCount);
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

        // Constructor for full map updates
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

        /**
         * Returns the density of changes within the dirty region.
         * Higher density means more actual pixel changes within the bounding box.
         */
        public float getChangeDensity() {
            return (float) changedPixelCount / getDataSize();
        }
    }
}

