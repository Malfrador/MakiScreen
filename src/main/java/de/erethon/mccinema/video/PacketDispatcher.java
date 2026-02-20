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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PacketDispatcher {

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

    // Adaptive rate limiting based on frame rate
    private double currentFrameRate = 30.0;
    private int adaptiveMaxPacketsPerFrame;

    // Scene change handling - spread large updates across multiple frames
    private List<TileUpdate> deferredUpdates = new ArrayList<>();
    private int sceneChangeFramesRemaining = 0;
    private static final int SCENE_CHANGE_SPREAD_FRAMES = 2;

    // Bundle packet option - reduces packet overhead
    private boolean useBundlePackets = true;

    public PacketDispatcher(MCCinema plugin) {
        this.plugin = plugin;
        updateAdaptiveLimit();

        // Load bandwidth optimization settings
        this.useSpatialDownsampling = plugin.getConfig().getBoolean("performance.bandwidth.spatial-downsampling", true);
        this.useEntropyFiltering = plugin.getConfig().getBoolean("performance.bandwidth.entropy-filtering", true);
        this.minUniqueColorsThreshold = plugin.getConfig().getInt("performance.bandwidth.min-unique-colors", 3);
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

    private void updateAdaptiveLimit() {
        // Calculate max packets per frame to stay under target packets/second
        this.adaptiveMaxPacketsPerFrame = Math.max(MIN_UPDATES_PER_FRAME,
            (int) (TARGET_PACKETS_PER_SECOND / currentFrameRate));
    }

    public void dispatchFrame(Screen screen, List<TileUpdate> updates, PerformanceMetrics metrics) {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            return;
        }

        frameCounter++;
        int totalTiles = screen.getTotalMaps();

        // ============ PHASE 0: Detect scene changes ============
        // Count tiles with significant changes to detect scene cuts
        int majorChangeCount = 0;

        for (TileUpdate update : updates) {
            if (update.dirtyRegion() == null) continue;
            if (update.dirtyRegion().changedPixelCount() >= SCENE_CHANGE_PIXEL_THRESHOLD) {
                majorChangeCount++;
            }
        }

        boolean isSceneChange = totalTiles > 0 &&
            (float) majorChangeCount / totalTiles >= SCENE_CHANGE_THRESHOLD;

        // When using bundle packets, no need to spread scene changes across frames
        // Bundle packets are atomic and can handle all tiles at once
        if (isSceneChange && sceneChangeFramesRemaining == 0 && !useBundlePackets) {
            sceneChangeFramesRemaining = SCENE_CHANGE_SPREAD_FRAMES;
        }

        // ============ PHASE 1: Categorize updates ============
        List<TileUpdate> criticalUpdates = new ArrayList<>();
        List<TileUpdate> highPriorityUpdates = new ArrayList<>();
        List<TileUpdate> normalUpdates = new ArrayList<>();
        List<TileUpdate> lowPriorityUpdates = new ArrayList<>();

        // Include any deferred updates from scene change handling
        List<TileUpdate> allUpdates = new ArrayList<>(updates.size() + deferredUpdates.size());
        allUpdates.addAll(updates);
        allUpdates.addAll(deferredUpdates);
        deferredUpdates.clear();

        for (TileUpdate update : allUpdates) {
            if (update.dirtyRegion() == null) {
                continue;
            }

            MapTile tile = update.tile();
            int staleness = tile.getFramesSinceLastSend();
            int changedPixels = update.dirtyRegion().changedPixelCount();
            int accumulated = tile.getAccumulatedChanges();

            if (useEntropyFiltering && staleness < 2) {
                if (!hasSignificantChanges(update.dirtyRegion().data())) {
                    // Skip this noisy update, will be picked up later if accumulates
                    lowPriorityUpdates.add(update);
                    continue;
                }
            }

            if (useSpatialDownsampling && !isSceneChange && staleness == 0 && changedPixels < MapTile.TOTAL_PIXELS / 4) {
                // Don't spatially downsample high-contrast tiles, it is very visible on sharp black/white edges
                if (!isHighContrast(update.dirtyRegion().data())) {
                    // Checkerboard pattern: (x + y + frame) % 2
                    int pattern = (tile.getTileX() + tile.getTileY() + (frameCounter % 2)) % 2;
                    if (pattern != 0) {
                        // Skip this tile this frame, will update next frame
                        markUpdateSkipped(update);
                        continue;
                    }
                }
            }

            // Critical: tiles that have been skipped too many times
            if (staleness >= CRITICAL_STALENESS_FRAMES) {
                criticalUpdates.add(update);
            }
            // High priority: large changes or significant accumulated changes
            else if (changedPixels >= SCENE_CHANGE_PIXEL_THRESHOLD || accumulated >= MapTile.TOTAL_PIXELS / 2) {
                highPriorityUpdates.add(update);
            }
            // Low priority: tiny changes with no staleness (can be deferred)
            else if (changedPixels < MIN_DIRTY_REGION_PIXELS && staleness == 0 && accumulated < MIN_DIRTY_REGION_PIXELS * 4) {
                lowPriorityUpdates.add(update);
            }
            // Normal priority: everything else
            else {
                normalUpdates.add(update);
            }
        }

        // Sort each category by priority score, then by spatial proximity (scanline order)
        // Spatial ordering ensures client receives updates in visual rendering order
        criticalUpdates.sort((a, b) -> {
            int stalenessCmp = Integer.compare(b.tile().getFramesSinceLastSend(), a.tile().getFramesSinceLastSend());
            if (stalenessCmp != 0) return stalenessCmp;
            // Secondary sort: scanline order (top to bottom, left to right)
            return compareSpatially(a.tile(), b.tile());
        });

        highPriorityUpdates.sort((a, b) -> {
            int priorityCmp = Integer.compare(calculatePriorityScore(b), calculatePriorityScore(a));
            if (priorityCmp != 0) return priorityCmp;
            return compareSpatially(a.tile(), b.tile());
        });

        normalUpdates.sort((a, b) -> {
            int priorityCmp = Integer.compare(calculatePriorityScore(b), calculatePriorityScore(a));
            if (priorityCmp != 0) return priorityCmp;
            return compareSpatially(a.tile(), b.tile());
        });

        // ============ PHASE 2: Build packet list with budget constraints ============
        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();
        List<TileUpdate> sentUpdates = new ArrayList<>();
        int totalBytes = 0;
        int skippedBytes = 0;
        int skippedPackets = 0;

        // Adjust budget for scene changes - allow more throughput to prevent checkerboarding
        int effectiveMaxBytes = isSceneChange || sceneChangeFramesRemaining > 0
            ? (int) (maxBytesPerFrame * 1.25)  // 25% boost for scene changes (was 50%)
            : maxBytesPerFrame;

        // Adjust max packets for scene changes
        // When using bundle packets, we don't need packet count limits since bundles are atomic
        // The entire bundle is one network packet, so only byte size matters
        int effectiveMaxPackets = useBundlePackets
            ? Integer.MAX_VALUE  // No limit with bundles - they're atomic and efficient
            : (isSceneChange || sceneChangeFramesRemaining > 0
                ? (int) (adaptiveMaxPacketsPerFrame * 1.3)
                : adaptiveMaxPacketsPerFrame);

        // Process critical updates first - these MUST be sent
        long packetCreationStart = metrics != null ? System.nanoTime() : 0;
        long totalPacketCreationTime = 0;

        for (TileUpdate update : criticalUpdates) {
            int updateSize = update.dirtyRegion().getDataSize();

            // Even critical updates have a hard cap at 200% to prevent explosion
            if (totalBytes + updateSize > maxBytesPerFrame * 2.0 && !packets.isEmpty()) {
                skippedBytes += updateSize;
                skippedPackets++;
                markUpdateSkipped(update);
                continue;
            }

            long creationStart = metrics != null ? System.nanoTime() : 0;
            packets.add(createPacket(update));
            if (metrics != null) {
                totalPacketCreationTime += System.nanoTime() - creationStart;
            }
            sentUpdates.add(update);
            totalBytes += updateSize;
        }

        // Process high priority updates
        for (TileUpdate update : highPriorityUpdates) {
            if (packets.size() >= effectiveMaxPackets) {
                skippedBytes += update.dirtyRegion().getDataSize();
                skippedPackets++;
                markUpdateSkipped(update);
                continue;
            }

            int updateSize = update.dirtyRegion().getDataSize();
            if (totalBytes + updateSize > effectiveMaxBytes) {
                skippedBytes += updateSize;
                skippedPackets++;
                markUpdateSkipped(update);
                continue;
            }

            long creationStart = metrics != null ? System.nanoTime() : 0;
            packets.add(createPacket(update));
            if (metrics != null) {
                totalPacketCreationTime += System.nanoTime() - creationStart;
            }
            sentUpdates.add(update);
            totalBytes += updateSize;
        }

        // Process normal updates
        for (TileUpdate update : normalUpdates) {
            if (packets.size() >= effectiveMaxPackets) {
                skippedBytes += update.dirtyRegion().getDataSize();
                skippedPackets++;
                markUpdateSkipped(update);
                continue;
            }

            int updateSize = update.dirtyRegion().getDataSize();
            if (totalBytes + updateSize > effectiveMaxBytes) {
                skippedBytes += updateSize;
                skippedPackets++;
                markUpdateSkipped(update);
                continue;
            }

            long creationStart = metrics != null ? System.nanoTime() : 0;
            packets.add(createPacket(update));
            if (metrics != null) {
                totalPacketCreationTime += System.nanoTime() - creationStart;
            }
            sentUpdates.add(update);
            totalBytes += updateSize;
        }

        // Low priority updates - only if we have budget remaining and not in scene change
        if (sceneChangeFramesRemaining == 0) {
            for (TileUpdate update : lowPriorityUpdates) {
                if (packets.size() >= effectiveMaxPackets) {
                    // Defer low-priority updates - they'll be picked up next frame
                    markUpdateSkipped(update);
                    continue;
                }

                int updateSize = update.dirtyRegion().getDataSize();
                // When using bundles, use full byte budget since bundles are atomic
                int byteBudget = useBundlePackets ? effectiveMaxBytes : (int)(effectiveMaxBytes * 0.9);
                if (totalBytes + updateSize > byteBudget) {
                    markUpdateSkipped(update);
                    continue;
                }

                long creationStart = metrics != null ? System.nanoTime() : 0;
                packets.add(createPacket(update));
                if (metrics != null) {
                    totalPacketCreationTime += System.nanoTime() - creationStart;
                }
                sentUpdates.add(update);
                totalBytes += updateSize;
            }
        } else {
            // During scene change, defer all low-priority updates
            for (TileUpdate update : lowPriorityUpdates) {
                markUpdateSkipped(update);
            }
            sceneChangeFramesRemaining--;
        }

        // Record total packet creation time
        if (metrics != null && totalPacketCreationTime > 0) {
            metrics.recordPacketCreation(totalPacketCreationTime);
        }

        // ============ PHASE 3: Update tile state ============
        for (TileUpdate update : sentUpdates) {
            update.tile().resetFramesSinceLastSend();
            update.tile().resetAccumulatedChanges();
            if (update.mapData() != null) {
                update.tile().setLastSentData(update.mapData().clone());
            }
        }

        packetsSkippedLastFrame.set(skippedPackets);
        bytesSkippedLastFrame.set(skippedBytes);

        if (packets.isEmpty()) {
            return;
        }

        // ============ PHASE 4: Send packets to players ============
        int bytesSent = totalBytes;
        int packetsSent = packets.size();

        // Packets are already in spatial order since we built them from sorted update lists
        // Bundle packets preserve this ordering for atomic delivery to the client
        long sendingStart = metrics != null ? System.nanoTime() : 0;
        for (Player player : onlinePlayers) {
            sendPacketsToPlayer(player, packets);
        }
        if (metrics != null) {
            metrics.recordPacketSending(System.nanoTime() - sendingStart);
        }

        // When using bundles, we send 1 bundle packet per player, not N packets
        int actualPacketCount = useBundlePackets ? onlinePlayers.size() : packetsSent * onlinePlayers.size();
        totalPacketsSent.addAndGet(actualPacketCount);
        totalBytesSent.addAndGet((long) bytesSent * onlinePlayers.size());

        // Update last frame metrics for debug display
        lastFramePacketCount.set(packetsSent);
        lastFrameBytesSent.set(bytesSent);
    }

    private void markUpdateSkipped(TileUpdate update) {
        update.tile().incrementFramesSinceLastSend();
        update.tile().addAccumulatedChanges(update.dirtyRegion().changedPixelCount());
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

    private ClientboundMapItemDataPacket createPacket(TileUpdate update) {
        MapTile.DirtyRegion region = update.dirtyRegion();

        return new ClientboundMapItemDataPacket(
            new MapId(update.tile().getMapId()),
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

    public int getPacketsSkippedLastFrame() {
        return packetsSkippedLastFrame.get();
    }

    public int getBytesSkippedLastFrame() {
        return bytesSkippedLastFrame.get();
    }

    public int getAdaptiveMaxPacketsPerFrame() {
        return adaptiveMaxPacketsPerFrame;
    }

    public int getLastFramePacketCount() {
        return lastFramePacketCount.get();
    }

    public long getLastFrameBytesSent() {
        return lastFrameBytesSent.get();
    }
}

