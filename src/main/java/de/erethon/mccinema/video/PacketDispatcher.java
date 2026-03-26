package de.erethon.mccinema.video;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.MapTile;
import de.erethon.mccinema.screen.Screen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PacketDispatcher {

    public enum PatchStrategy {
        BOUNDING_BOX,
        MULTI_REGION,
        FULL_MAP
    }

    // Maximum bytes to send per frame (helps with very large screens)
    // Increased to 10MB to support full-screen updates on 40x16 screens (640 tiles)
    // Each tile is 128x128 = 16KB, so 640 tiles = ~10MB for full update
    private static final int DEFAULT_MAX_BYTES_PER_FRAME = 10 * 1024 * 1024; // 10 MB

    // Target packets per second - used for adaptive limiting on large screens (when NOT using bundles)
    private static final int TARGET_PACKETS_PER_SECOND = 1800;

    // Staleness tuning - higher values make skipped tiles catch up faster
    private static final int STALENESS_FACTOR = 500; // Each frame of staleness adds this much priority
    private static final int MAX_STALENESS_FRAMES = 5; // Cap - after 5 frames, tile gets max priority boost (2500)
    private static final int CRITICAL_STALENESS_FRAMES = 3; // After this many frames, tile is CRITICAL

    // Minimum guaranteed updates per frame to prevent total starvation
    private static final int MIN_UPDATES_PER_FRAME = 4;

    // Scene change detection - when this % of tiles have significant changes, it's a scene cut
    private static final float SCENE_CHANGE_THRESHOLD = 0.6f; // 60% of tiles with major changes
    private static final int SCENE_CHANGE_PIXEL_THRESHOLD = MapTile.TOTAL_PIXELS / 2; // 50% of tile changed = major change

    // Minimum dirty region size to bother sending (skip tiny updates unless accumulated)
    private static final int MIN_DIRTY_REGION_PIXELS = 32;

    // Aggressive bandwidth reduction: skip tiles with low-entropy changes (dither noise)
    private boolean useEntropyFiltering = true;
    private int minUniqueColorsThreshold = 3; // Skip tiles with <3 changed colors (likely noise)

    // Spatial downsampling: in low-motion scenes, update tiles in a checkerboard pattern
    private boolean useSpatialDownsampling = true;
    private int frameCounter = 0;

    private final MCCinema plugin;
    private int maxBytesPerFrame = DEFAULT_MAX_BYTES_PER_FRAME;

    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicInteger packetsSkippedLastFrame = new AtomicInteger(0);
    private final AtomicInteger bytesSkippedLastFrame = new AtomicInteger(0);

    // Last frame metrics for debug display
    private final AtomicInteger lastFramePacketCount = new AtomicInteger(0);
    private final AtomicLong lastFrameBytesSent = new AtomicLong(0);
    private final AtomicInteger lastFrameTileCount = new AtomicInteger(0);
    private final AtomicInteger lastFrameMultiRegionTileCount = new AtomicInteger(0);
    private final AtomicLong lastFrameBoundingBytes = new AtomicLong(0);
    private final AtomicLong lastFrameFullMapBytes = new AtomicLong(0);
    private final AtomicLong lastFrameByteCap = new AtomicLong(DEFAULT_MAX_BYTES_PER_FRAME);

    // Adaptive rate limiting based on frame rate
    private double currentFrameRate = 30.0;
    private int adaptiveMaxPacketsPerFrame;

    // Scene change handling - spread large updates across multiple frames
    private List<TileUpdate> deferredUpdates = new ArrayList<>();
    private int sceneChangeFramesRemaining = 0;
    private static final int SCENE_CHANGE_SPREAD_FRAMES = 2;

    // Bundle packet option - reduces packet overhead
    private boolean useBundlePackets = true;

    // Patch generation settings
    private PatchStrategy patchStrategy = PatchStrategy.BOUNDING_BOX;
    private int fullUpdateThresholdPercent = 75;
    private int multiRegionBlockSize = 8;
    private int maxPatchesPerTile = 24;
    private int minPatchArea = 16;
    private int patchPacketOverheadBytes = 0;

    // Bandwidth target controls
    private boolean bandwidthTargetEnabled = true;
    private long bandwidthTargetBytesPerSecond = 20L * 1024L * 1024L;
    private double adaptiveMotionThreshold = 0.12;
    private double adaptiveFlatThreshold = 0.70;

    public PacketDispatcher(MCCinema plugin) {
        this.plugin = plugin;
        updateAdaptiveLimit();

        // Load bandwidth optimization settings
        this.useSpatialDownsampling = plugin.getConfig().getBoolean("performance.bandwidth.spatial-downsampling", true);
        this.useEntropyFiltering = plugin.getConfig().getBoolean("performance.bandwidth.entropy-filtering", true);
        this.minUniqueColorsThreshold = plugin.getConfig().getInt("performance.bandwidth.min-unique-colors", 3);

        this.fullUpdateThresholdPercent = clamp(plugin.getConfig().getInt("performance.full-update-threshold", 75), 1, 100);
        this.patchStrategy = parsePatchStrategy(plugin.getConfig().getString("performance.bandwidth.patching.strategy", "BOUNDING_BOX"));
        this.multiRegionBlockSize = clamp(plugin.getConfig().getInt("performance.bandwidth.patching.multi-region-block-size", 8), 4, 32);
        this.maxPatchesPerTile = clamp(plugin.getConfig().getInt("performance.bandwidth.patching.max-patches-per-tile", 24), 1, 64);
        this.minPatchArea = clamp(plugin.getConfig().getInt("performance.bandwidth.patching.min-patch-area", 16), 1, MapTile.TOTAL_PIXELS);
        this.patchPacketOverheadBytes = clamp(plugin.getConfig().getInt("performance.bandwidth.patching.packet-overhead-bytes", 0), 0, 128);
        this.bandwidthTargetEnabled = plugin.getConfig().getBoolean("performance.bandwidth.target.enabled", false);
        this.bandwidthTargetBytesPerSecond = Math.max(1024L, plugin.getConfig().getLong("performance.bandwidth.target.bytes-per-second", 20L * 1024L * 1024L));
        this.adaptiveMotionThreshold = clampDouble(plugin.getConfig().getDouble("performance.bandwidth.adaptive.high-motion-threshold", 0.12), 0.0, 1.0);
        this.adaptiveFlatThreshold = clampDouble(plugin.getConfig().getDouble("performance.bandwidth.adaptive.flat-threshold", 0.70), 0.0, 1.0);
    }

    public void setMaxBytesPerFrame(int maxBytesPerFrame) {
        this.maxBytesPerFrame = maxBytesPerFrame;
    }

    public void setFrameRate(double frameRate) {
        this.currentFrameRate = Math.max(1.0, frameRate);
        updateAdaptiveLimit();
    }

    public void setUseBundlePackets(boolean useBundlePackets) {
        this.useBundlePackets = useBundlePackets;
    }

    public boolean isUsingBundlePackets() {
        return useBundlePackets;
    }

    public void setUseEntropyFiltering(boolean useEntropyFiltering) {
        this.useEntropyFiltering = useEntropyFiltering;
    }

    public boolean isUseEntropyFiltering() {
        return useEntropyFiltering;
    }

    public void setMinUniqueColorsThreshold(int minUniqueColorsThreshold) {
        this.minUniqueColorsThreshold = clamp(minUniqueColorsThreshold, 1, 32);
    }

    public int getMinUniqueColorsThreshold() {
        return minUniqueColorsThreshold;
    }

    public void setUseSpatialDownsampling(boolean useSpatialDownsampling) {
        this.useSpatialDownsampling = useSpatialDownsampling;
    }

    public boolean isUseSpatialDownsampling() {
        return useSpatialDownsampling;
    }

    public void setBandwidthTargetEnabled(boolean bandwidthTargetEnabled) {
        this.bandwidthTargetEnabled = bandwidthTargetEnabled;
    }

    public boolean isBandwidthTargetEnabled() {
        return bandwidthTargetEnabled;
    }

    public void setBandwidthTargetBytesPerSecond(long bandwidthTargetBytesPerSecond) {
        this.bandwidthTargetBytesPerSecond = Math.max(1024L, bandwidthTargetBytesPerSecond);
    }

    public long getBandwidthTargetBytesPerSecond() {
        return bandwidthTargetBytesPerSecond;
    }

    private void updateAdaptiveLimit() {
        // Calculate max packets per frame to stay under target packets/second
        this.adaptiveMaxPacketsPerFrame = Math.max(MIN_UPDATES_PER_FRAME,
            (int) (TARGET_PACKETS_PER_SECOND / currentFrameRate));
    }

    public void dispatchFrame(Screen screen, List<TileUpdate> updates, FrameProcessor.FrameContentStats contentStats, PerformanceMetrics metrics) {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            return;
        }

        frameCounter++;
        int totalTiles = screen.getTotalMaps();

        int majorChangeCount = 0;
        for (TileUpdate update : updates) {
            if (update.dirtyRegion() != null && update.dirtyRegion().changedPixelCount() >= SCENE_CHANGE_PIXEL_THRESHOLD) {
                majorChangeCount++;
            }
        }

        boolean isSceneChange = totalTiles > 0 && (float) majorChangeCount / totalTiles >= SCENE_CHANGE_THRESHOLD;
        if (isSceneChange && sceneChangeFramesRemaining == 0 && !useBundlePackets) {
            sceneChangeFramesRemaining = SCENE_CHANGE_SPREAD_FRAMES;
        }

        List<PreparedUpdate> criticalUpdates = new ArrayList<>();
        List<PreparedUpdate> highPriorityUpdates = new ArrayList<>();
        List<PreparedUpdate> normalUpdates = new ArrayList<>();
        List<PreparedUpdate> lowPriorityUpdates = new ArrayList<>();

        List<TileUpdate> allUpdates = new ArrayList<>(updates.size() + deferredUpdates.size());
        allUpdates.addAll(updates);
        allUpdates.addAll(deferredUpdates);
        deferredUpdates.clear();

        boolean highMotionFrame = contentStats != null && contentStats.motionScore() >= adaptiveMotionThreshold;
        boolean flatFrame = contentStats != null && contentStats.motionScore() < adaptiveMotionThreshold &&
            (contentStats.flatScore() >= adaptiveFlatThreshold || contentStats.lowSaturationScore() >= adaptiveFlatThreshold);
        int effectiveFullUpdateThreshold = flatFrame ? Math.min(98, fullUpdateThresholdPercent + 10)
            : (highMotionFrame ? Math.min(95, fullUpdateThresholdPercent + 5) : fullUpdateThresholdPercent);
        int effectiveMinPatchArea = flatFrame ? Math.max(4, minPatchArea / 2) : minPatchArea;

        for (TileUpdate update : allUpdates) {
            if (update.dirtyRegion() == null) {
                continue;
            }

            PreparedUpdate prepared = prepareUpdate(update, effectiveFullUpdateThreshold, effectiveMinPatchArea);
            if (prepared == null || prepared.regions().isEmpty()) {
                continue;
            }

            MapTile tile = update.tile();
            int staleness = tile.getFramesSinceLastSend();
            int changedPixels = update.dirtyRegion().changedPixelCount();
            int accumulated = tile.getAccumulatedChanges();

            if (useEntropyFiltering && staleness < 2 && !hasSignificantChanges(update.dirtyRegion().data())) {
                lowPriorityUpdates.add(prepared);
                continue;
            }

            if (useSpatialDownsampling && !isSceneChange && staleness == 0 && changedPixels < MapTile.TOTAL_PIXELS / 4) {
                if (highMotionFrame || !isHighContrast(update.dirtyRegion().data())) {
                    int pattern = (tile.getTileX() + tile.getTileY() + (frameCounter % 2)) % 2;
                    if (pattern != 0) {
                        markUpdateSkipped(update);
                        continue;
                    }
                }
            }

            if (staleness >= CRITICAL_STALENESS_FRAMES) {
                criticalUpdates.add(prepared);
            } else if (changedPixels >= SCENE_CHANGE_PIXEL_THRESHOLD || accumulated >= MapTile.TOTAL_PIXELS / 2) {
                highPriorityUpdates.add(prepared);
            } else if (changedPixels < MIN_DIRTY_REGION_PIXELS && staleness == 0 && accumulated < MIN_DIRTY_REGION_PIXELS * 4) {
                lowPriorityUpdates.add(prepared);
            } else {
                normalUpdates.add(prepared);
            }
        }

        criticalUpdates.sort((a, b) -> {
            int stalenessCmp = Integer.compare(b.update().tile().getFramesSinceLastSend(), a.update().tile().getFramesSinceLastSend());
            if (stalenessCmp != 0) return stalenessCmp;
            return compareSpatially(a.update().tile(), b.update().tile());
        });

        highPriorityUpdates.sort((a, b) -> {
            int priorityCmp = Integer.compare(calculatePriorityScore(b.update()), calculatePriorityScore(a.update()));
            if (priorityCmp != 0) return priorityCmp;
            return compareSpatially(a.update().tile(), b.update().tile());
        });

        normalUpdates.sort((a, b) -> {
            int priorityCmp = Integer.compare(calculatePriorityScore(b.update()), calculatePriorityScore(a.update()));
            if (priorityCmp != 0) return priorityCmp;
            return compareSpatially(a.update().tile(), b.update().tile());
        });

        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();
        List<PreparedUpdate> sentUpdates = new ArrayList<>();
        int totalBytes = 0;
        int skippedBytes = 0;
        int skippedPackets = 0;
        int sentTiles = 0;
        int sentMultiRegionTiles = 0;
        long sentBoundingBytes = 0;
        long sentFullMapBytes = 0;

        int effectiveMaxBytes = isSceneChange || sceneChangeFramesRemaining > 0
            ? (int) (maxBytesPerFrame * 1.25)
            : maxBytesPerFrame;
        if (bandwidthTargetEnabled) {
            int targetPerFrame = Math.max(MIN_DIRTY_REGION_PIXELS,
                (int) (bandwidthTargetBytesPerSecond / Math.max(1.0, currentFrameRate)));
            effectiveMaxBytes = Math.min(effectiveMaxBytes, targetPerFrame);
        }
        lastFrameByteCap.set(effectiveMaxBytes);
        int effectiveMaxPackets = useBundlePackets
            ? Integer.MAX_VALUE
            : (isSceneChange || sceneChangeFramesRemaining > 0
                ? (int) (adaptiveMaxPacketsPerFrame * 1.3)
                : adaptiveMaxPacketsPerFrame);

        long totalPacketCreationTime = 0;

        for (PreparedUpdate prepared : criticalUpdates) {
            int updateSize = prepared.totalDataSize();
            int packetCount = prepared.regions().size();

            if ((totalBytes + updateSize > maxBytesPerFrame * 2.0 && !packets.isEmpty()) ||
                packets.size() + packetCount > effectiveMaxPackets) {
                skippedBytes += updateSize;
                skippedPackets += packetCount;
                markUpdateSkipped(prepared.update());
                continue;
            }

            long creationStart = metrics != null ? System.nanoTime() : 0;
            addPacketsForUpdate(packets, prepared);
            if (metrics != null) {
                totalPacketCreationTime += System.nanoTime() - creationStart;
            }
            sentUpdates.add(prepared);
            totalBytes += updateSize;
            sentTiles++;
            if (prepared.regions().size() > 1) {
                sentMultiRegionTiles++;
            }
            sentBoundingBytes += prepared.boundingSize();
            sentFullMapBytes += MapTile.TOTAL_PIXELS;
        }

        for (PreparedUpdate prepared : highPriorityUpdates) {
            int updateSize = prepared.totalDataSize();
            int packetCount = prepared.regions().size();

            if (packets.size() + packetCount > effectiveMaxPackets || totalBytes + updateSize > effectiveMaxBytes) {
                skippedBytes += updateSize;
                skippedPackets += packetCount;
                markUpdateSkipped(prepared.update());
                continue;
            }

            long creationStart = metrics != null ? System.nanoTime() : 0;
            addPacketsForUpdate(packets, prepared);
            if (metrics != null) {
                totalPacketCreationTime += System.nanoTime() - creationStart;
            }
            sentUpdates.add(prepared);
            totalBytes += updateSize;
            sentTiles++;
            if (prepared.regions().size() > 1) {
                sentMultiRegionTiles++;
            }
            sentBoundingBytes += prepared.boundingSize();
            sentFullMapBytes += MapTile.TOTAL_PIXELS;
        }

        for (PreparedUpdate prepared : normalUpdates) {
            int updateSize = prepared.totalDataSize();
            int packetCount = prepared.regions().size();

            if (packets.size() + packetCount > effectiveMaxPackets || totalBytes + updateSize > effectiveMaxBytes) {
                skippedBytes += updateSize;
                skippedPackets += packetCount;
                markUpdateSkipped(prepared.update());
                continue;
            }

            long creationStart = metrics != null ? System.nanoTime() : 0;
            addPacketsForUpdate(packets, prepared);
            if (metrics != null) {
                totalPacketCreationTime += System.nanoTime() - creationStart;
            }
            sentUpdates.add(prepared);
            totalBytes += updateSize;
            sentTiles++;
            if (prepared.regions().size() > 1) {
                sentMultiRegionTiles++;
            }
            sentBoundingBytes += prepared.boundingSize();
            sentFullMapBytes += MapTile.TOTAL_PIXELS;
        }

        if (sceneChangeFramesRemaining == 0) {
            for (PreparedUpdate prepared : lowPriorityUpdates) {
                int updateSize = prepared.totalDataSize();
                int packetCount = prepared.regions().size();
                int byteBudget = useBundlePackets ? effectiveMaxBytes : (int) (effectiveMaxBytes * 0.9);

                if (packets.size() + packetCount > effectiveMaxPackets || totalBytes + updateSize > byteBudget) {
                    markUpdateSkipped(prepared.update());
                    continue;
                }

                long creationStart = metrics != null ? System.nanoTime() : 0;
                addPacketsForUpdate(packets, prepared);
                if (metrics != null) {
                    totalPacketCreationTime += System.nanoTime() - creationStart;
                }
                sentUpdates.add(prepared);
                totalBytes += updateSize;
                sentTiles++;
                if (prepared.regions().size() > 1) {
                    sentMultiRegionTiles++;
                }
                sentBoundingBytes += prepared.boundingSize();
                sentFullMapBytes += MapTile.TOTAL_PIXELS;
            }
        } else {
            for (PreparedUpdate prepared : lowPriorityUpdates) {
                markUpdateSkipped(prepared.update());
            }
            sceneChangeFramesRemaining--;
        }

        if (metrics != null && totalPacketCreationTime > 0) {
            metrics.recordPacketCreation(totalPacketCreationTime);
        }

        for (PreparedUpdate sentUpdate : sentUpdates) {
            TileUpdate update = sentUpdate.update();
            update.tile().resetFramesSinceLastSend();
            update.tile().resetAccumulatedChanges();
            if (update.mapData() != null) {
                update.tile().setLastSentData(update.mapData().clone());
            }
        }

        packetsSkippedLastFrame.set(skippedPackets);
        bytesSkippedLastFrame.set(skippedBytes);
        lastFrameTileCount.set(sentTiles);
        lastFrameMultiRegionTileCount.set(sentMultiRegionTiles);
        lastFrameBoundingBytes.set(sentBoundingBytes);
        lastFrameFullMapBytes.set(sentFullMapBytes);

        if (packets.isEmpty()) {
            lastFramePacketCount.set(0);
            lastFrameBytesSent.set(0);
            return;
        }

        int bytesSent = totalBytes;
        int packetsSent = packets.size();

        long sendingStart = metrics != null ? System.nanoTime() : 0;
        for (Player player : onlinePlayers) {
            sendPacketsToPlayer(player, packets);
        }
        if (metrics != null) {
            metrics.recordPacketSending(System.nanoTime() - sendingStart);
        }

        int actualPacketCount = useBundlePackets ? onlinePlayers.size() : packetsSent * onlinePlayers.size();
        totalPacketsSent.addAndGet(actualPacketCount);
        totalBytesSent.addAndGet((long) bytesSent * onlinePlayers.size());

        lastFramePacketCount.set(packetsSent);
        lastFrameBytesSent.set(bytesSent);
    }

    private void markUpdateSkipped(TileUpdate update) {
        update.tile().incrementFramesSinceLastSend();
        update.tile().addAccumulatedChanges(update.dirtyRegion().changedPixelCount());
    }

    private void addPacketsForUpdate(List<ClientboundMapItemDataPacket> packets, PreparedUpdate prepared) {
        for (MapTile.DirtyRegion region : prepared.regions()) {
            packets.add(createPacket(prepared.update().tile(), region));
        }
    }

    private PreparedUpdate prepareUpdate(TileUpdate update, int effectiveFullUpdateThreshold, int effectiveMinPatchArea) {
        MapTile.DirtyRegion dirtyRegion = update.dirtyRegion();
        if (dirtyRegion == null) {
            return null;
        }

        byte[] mapData = update.mapData();
        if (mapData == null || mapData.length != MapTile.TOTAL_PIXELS) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        if (patchStrategy == PatchStrategy.FULL_MAP || dirtyRegion.getCoveragePercent() >= effectiveFullUpdateThreshold) {
            MapTile.DirtyRegion fullRegion = new MapTile.DirtyRegion(0, 0, MapTile.SIZE, MapTile.SIZE, mapData, dirtyRegion.changedPixelCount());
            return new PreparedUpdate(update, List.of(fullRegion), MapTile.TOTAL_PIXELS, dirtyRegion.getDataSize());
        }

        if (patchStrategy != PatchStrategy.MULTI_REGION || dirtyRegion.isFullMap()) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        List<MapTile.DirtyRegion> splitRegions = buildMultiRegions(update, effectiveMinPatchArea);
        if (splitRegions.isEmpty()) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        int splitPayload = 0;
        for (MapTile.DirtyRegion region : splitRegions) {
            splitPayload += region.getDataSize();
        }

        int effectivePatchOverhead = useBundlePackets ? 0 : patchPacketOverheadBytes;
        int splitWithOverhead = splitPayload + splitRegions.size() * effectivePatchOverhead;
        int boxWithOverhead = dirtyRegion.getDataSize() + effectivePatchOverhead;
        if (splitWithOverhead >= boxWithOverhead) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        return new PreparedUpdate(update, splitRegions, splitPayload, dirtyRegion.getDataSize());
    }

    private List<MapTile.DirtyRegion> buildMultiRegions(TileUpdate update, int effectiveMinPatchArea) {
        byte[] mapData = update.mapData();
        byte[] lastSentData = update.tile().getLastSentData();
        if (mapData == null || lastSentData == null || lastSentData.length != MapTile.TOTAL_PIXELS) {
            return List.of();
        }

        int blockSize = Math.max(1, multiRegionBlockSize);
        int blockColumns = (MapTile.SIZE + blockSize - 1) / blockSize;
        int blockRows = (MapTile.SIZE + blockSize - 1) / blockSize;
        int blockCount = blockColumns * blockRows;

        boolean[] dirtyBlocks = new boolean[blockCount];
        int[] changedPerBlock = new int[blockCount];
        for (int y = 0; y < MapTile.SIZE; y++) {
            int rowOffset = y * MapTile.SIZE;
            int blockY = y / blockSize;
            for (int x = 0; x < MapTile.SIZE; x++) {
                int index = rowOffset + x;
                if (mapData[index] != lastSentData[index]) {
                    int blockX = x / blockSize;
                    int blockIndex = blockY * blockColumns + blockX;
                    dirtyBlocks[blockIndex] = true;
                    changedPerBlock[blockIndex]++;
                }
            }
        }

        List<MapTile.DirtyRegion> regions = new ArrayList<>();
        boolean[] visited = new boolean[blockCount];
        int[] queue = new int[blockCount];

        for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
            if (!dirtyBlocks[blockIndex] || visited[blockIndex]) {
                continue;
            }

            int queueStart = 0;
            int queueEnd = 0;
            queue[queueEnd++] = blockIndex;
            visited[blockIndex] = true;

            int minBlockX = Integer.MAX_VALUE;
            int minBlockY = Integer.MAX_VALUE;
            int maxBlockX = Integer.MIN_VALUE;
            int maxBlockY = Integer.MIN_VALUE;
            int changedPixels = 0;

            while (queueStart < queueEnd) {
                int current = queue[queueStart++];
                int currentY = current / blockColumns;
                int currentX = current % blockColumns;

                minBlockX = Math.min(minBlockX, currentX);
                maxBlockX = Math.max(maxBlockX, currentX);
                minBlockY = Math.min(minBlockY, currentY);
                maxBlockY = Math.max(maxBlockY, currentY);
                changedPixels += changedPerBlock[current];

                if (currentX > 0) {
                    int left = current - 1;
                    if (dirtyBlocks[left] && !visited[left]) {
                        visited[left] = true;
                        queue[queueEnd++] = left;
                    }
                }
                if (currentX + 1 < blockColumns) {
                    int right = current + 1;
                    if (dirtyBlocks[right] && !visited[right]) {
                        visited[right] = true;
                        queue[queueEnd++] = right;
                    }
                }
                if (currentY > 0) {
                    int up = current - blockColumns;
                    if (dirtyBlocks[up] && !visited[up]) {
                        visited[up] = true;
                        queue[queueEnd++] = up;
                    }
                }
                if (currentY + 1 < blockRows) {
                    int down = current + blockColumns;
                    if (dirtyBlocks[down] && !visited[down]) {
                        visited[down] = true;
                        queue[queueEnd++] = down;
                    }
                }
            }

            int x = minBlockX * blockSize;
            int y = minBlockY * blockSize;
            int width = Math.min(MapTile.SIZE, (maxBlockX + 1) * blockSize) - x;
            int height = Math.min(MapTile.SIZE, (maxBlockY + 1) * blockSize) - y;
            int area = width * height;
            if (area < effectiveMinPatchArea) {
                continue;
            }

            byte[] patchData = copyPatchData(mapData, x, y, width, height);
            regions.add(new MapTile.DirtyRegion(x, y, width, height, patchData, Math.max(1, changedPixels)));
        }

        if (regions.size() > maxPatchesPerTile) {
            return List.of();
        }

        regions.sort((a, b) -> Integer.compare(b.getDataSize(), a.getDataSize()));
        return regions;
    }

    private byte[] copyPatchData(byte[] fullMapData, int x, int y, int width, int height) {
        byte[] patchData = new byte[width * height];
        for (int row = 0; row < height; row++) {
            int sourceOffset = (y + row) * MapTile.SIZE + x;
            int targetOffset = row * width;
            System.arraycopy(fullMapData, sourceOffset, patchData, targetOffset, width);
        }
        return patchData;
    }

    /**
     * Detect if a dirty region has significant changes or just dither noise.
     * Low-entropy changes (few unique colors) are likely just dithering artifacts.
     */
    private boolean hasSignificantChanges(byte[] data) {
        if (data.length < MIN_DIRTY_REGION_PIXELS) {
            return false; // Too small to matter
        }

        // Count unique color values in the changed region
        boolean[] seen = new boolean[256];
        int uniqueColors = 0;

        // Sample up to 128 pixels for performance
        int step = Math.max(1, data.length / 128);
        for (int i = 0; i < data.length; i += step) {
            int colorIndex = data[i] & 0xFF;
            if (!seen[colorIndex]) {
                seen[colorIndex] = true;
                uniqueColors++;
                if (uniqueColors >= minUniqueColorsThreshold) {
                    return true; // Enough variety, significant change
                }
            }
        }

        // If very few unique colors, it's likely just noise
        return uniqueColors >= minUniqueColorsThreshold;
    }

    private boolean isHighContrast(byte[] data) {
        if (data == null || data.length < 64) {
            return false;
        }

        // Count color occurrences using a sampled histogram
        int[] histogram = new int[256];
        int step = Math.max(1, data.length / 128);
        int samples = 0;
        for (int i = 0; i < data.length; i += step) {
            histogram[data[i] & 0xFF]++;
            samples++;
        }

        // Find the top 2 most common colors
        int max1 = 0, max2 = 0;
        for (int count : histogram) {
            if (count > max1) {
                max2 = max1;
                max1 = count;
            } else if (count > max2) {
                max2 = count;
            }
        }

        // If the top 2 colors cover >80% of samples, it's high contrast
        return (max1 + max2) > samples * 0.8;
    }

    /**
     * Compare tiles spatially in scanline order (top to bottom, left to right).
     * This ensures tiles are sent in the order the client renders them, reducing checkerboarding.
     */
    private int compareSpatially(MapTile a, MapTile b) {
        int yCmp = Integer.compare(a.getTileY(), b.getTileY());
        if (yCmp != 0) return yCmp;
        return Integer.compare(a.getTileX(), b.getTileX());
    }

    private int calculatePriorityScore(TileUpdate update) {
        MapTile tile = update.tile();
        MapTile.DirtyRegion region = update.dirtyRegion();

        int changeScore = region.changedPixelCount();
        int stalenessScore = Math.min(tile.getFramesSinceLastSend(), MAX_STALENESS_FRAMES) * STALENESS_FACTOR;
        int accumulatedScore = Math.min(tile.getAccumulatedChanges(), MapTile.TOTAL_PIXELS); // Cap at full tile

        return changeScore + stalenessScore + accumulatedScore;
    }

    public void dispatchFullFrame(Screen screen, byte[][] mapData) {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            return;
        }

        List<ClientboundMapItemDataPacket> packets = new ArrayList<>(screen.getTotalMaps());

        for (int i = 0; i < screen.getTiles().size(); i++) {
            MapTile tile = screen.getTile(i);
            byte[] data = mapData[i];
            if (data == null) continue;

            ClientboundMapItemDataPacket packet = createFullMapPacket(tile.getMapId(), data);
            packets.add(packet);
        }

        for (Player player : onlinePlayers) {
            sendPacketsToPlayer(player, packets);
        }
    }

    public void sendLastFrameToPlayer(Player player, Screen screen) {
        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();

        for (MapTile tile : screen.getTiles()) {
            byte[] data = tile.getLastFrameData();
            if (data != null && data.length > 0) {
                boolean hasData = false;
                for (byte b : data) {
                    if (b != 0) {
                        hasData = true;
                        break;
                    }
                }
                if (hasData) {
                    packets.add(createFullMapPacket(tile.getMapId(), data));
                }
            }
        }

        if (!packets.isEmpty()) {
            sendPacketsToPlayer(player, packets);
        }
    }

    private void sendPacketsToPlayer(Player player, List<ClientboundMapItemDataPacket> packets) {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;

        if (useBundlePackets && packets.size() > 1) {
            // Bundle all map packets into a single bundle packet
            // This reduces packet count from N to 1, significantly reducing overhead
            List<Packet<? super ClientGamePacketListener>> packetList = new ArrayList<>(packets);
            ClientboundBundlePacket bundlePacket = new ClientboundBundlePacket(packetList);
            connection.send(bundlePacket);
        } else {
            // Send individual packets (fallback or single packet case)
            for (ClientboundMapItemDataPacket packet : packets) {
                connection.send(packet);
            }
        }
    }

    private ClientboundMapItemDataPacket createPacket(MapTile tile, MapTile.DirtyRegion region) {
        return new ClientboundMapItemDataPacket(
            new MapId(tile.getMapId()),
            (byte) 0,
            false,
            null,
            new MapItemSavedData.MapPatch(
                region.x(),
                region.y(),
                region.width(),
                region.height(),
                region.data()
            )
        );
    }

    private ClientboundMapItemDataPacket createFullMapPacket(int mapId, byte[] data) {
        return new ClientboundMapItemDataPacket(
            new MapId(mapId),
            (byte) 0,
            false,
            null,
            new MapItemSavedData.MapPatch(0, 0, 128, 128, data)
        );
    }

    public long getTotalPacketsSent() {
        return totalPacketsSent.get();
    }

    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }

    public PatchStrategy getPatchStrategy() {
        return patchStrategy;
    }

    public boolean setPatchStrategy(String value) {
        PatchStrategy parsed = parsePatchStrategyOrNull(value);
        if (parsed == null) {
            return false;
        }
        this.patchStrategy = parsed;
        return true;
    }

    public void setPatchStrategy(PatchStrategy patchStrategy) {
        this.patchStrategy = patchStrategy;
    }

    public int getFullUpdateThresholdPercent() {
        return fullUpdateThresholdPercent;
    }

    public void setFullUpdateThresholdPercent(int fullUpdateThresholdPercent) {
        this.fullUpdateThresholdPercent = clamp(fullUpdateThresholdPercent, 1, 100);
    }

    public int getMultiRegionBlockSize() {
        return multiRegionBlockSize;
    }

    public void setMultiRegionBlockSize(int multiRegionBlockSize) {
        this.multiRegionBlockSize = clamp(multiRegionBlockSize, 4, 32);
    }

    public int getMaxPatchesPerTile() {
        return maxPatchesPerTile;
    }

    public void setMaxPatchesPerTile(int maxPatchesPerTile) {
        this.maxPatchesPerTile = clamp(maxPatchesPerTile, 1, 64);
    }

    public int getMinPatchArea() {
        return minPatchArea;
    }

    public void setMinPatchArea(int minPatchArea) {
        this.minPatchArea = clamp(minPatchArea, 1, MapTile.TOTAL_PIXELS);
    }

    private PatchStrategy parsePatchStrategy(String value) {
        PatchStrategy parsed = parsePatchStrategyOrNull(value);
        return parsed != null ? parsed : PatchStrategy.BOUNDING_BOX;
    }

    private PatchStrategy parsePatchStrategyOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("MULTIPATCH".equals(normalized) || "MULTI".equals(normalized) || "DIFF".equals(normalized)) {
            return PatchStrategy.MULTI_REGION;
        }

        try {
            return PatchStrategy.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public void resetStats() {
        totalPacketsSent.set(0);
        totalBytesSent.set(0);
    }

    public record TileUpdate(MapTile tile, MapTile.DirtyRegion dirtyRegion, byte[] mapData) {
        // Backwards compatible constructor
        public TileUpdate(MapTile tile, MapTile.DirtyRegion dirtyRegion) {
            this(tile, dirtyRegion, null);
        }
    }

    private record PreparedUpdate(TileUpdate update, List<MapTile.DirtyRegion> regions, int totalDataSize, int boundingSize) {
    }

    public int getPacketsSkippedLastFrame() {
        return packetsSkippedLastFrame.get();
    }

    public int getBytesSkippedLastFrame() {
        return bytesSkippedLastFrame.get();
    }

    public int getAdaptiveMaxPacketsPerFrame() {
        return adaptiveMaxPacketsPerFrame;
    }

    public long getLastFrameByteCap() {
        return lastFrameByteCap.get();
    }

    public int getLastFrameTileCount() {
        return lastFrameTileCount.get();
    }

    public int getLastFrameMultiRegionTileCount() {
        return lastFrameMultiRegionTileCount.get();
    }

    public long getLastFrameBoundingBytes() {
        return lastFrameBoundingBytes.get();
    }

    public long getLastFrameFullMapBytes() {
        return lastFrameFullMapBytes.get();
    }

    public int getLastFramePacketCount() {
        return lastFramePacketCount.get();
    }

    public long getLastFrameBytesSent() {
        return lastFrameBytesSent.get();
    }
}

