package cat.maki.makiscreen.video;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.screen.MapTile;
import cat.maki.makiscreen.screen.Screen;
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
    private static final int DEFAULT_MAX_BYTES_PER_FRAME = 2 * 1024 * 1024; // 2 MB

    // Target packets per second - used for adaptive limiting on large screens
    private static final int TARGET_PACKETS_PER_SECOND = 1800;

    // Staleness tuning - higher values make skipped tiles catch up faster
    private static final int STALENESS_FACTOR = 500; // Each frame of staleness adds this much priority
    private static final int MAX_STALENESS_FRAMES = 5; // Cap - after 5 frames, tile gets max priority boost (2500)
    private static final int CRITICAL_STALENESS_FRAMES = 3; // After this many frames, tile is CRITICAL

    // Minimum guaranteed updates per frame to prevent total starvation
    private static final int MIN_UPDATES_PER_FRAME = 4;

    // Scene change detection - when this % of tiles have significant changes, it's a scene cut
    private static final float SCENE_CHANGE_THRESHOLD = 0.5f; // 50% of tiles with major changes
    private static final int SCENE_CHANGE_PIXEL_THRESHOLD = MapTile.TOTAL_PIXELS / 3; // 33% of tile changed = major change

    // Minimum dirty region size to bother sending (skip tiny updates unless accumulated)
    private static final int MIN_DIRTY_REGION_PIXELS = 32;

    private final MakiScreen plugin;
    private int maxBytesPerFrame = DEFAULT_MAX_BYTES_PER_FRAME;

    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicInteger packetsSkippedLastFrame = new AtomicInteger(0);
    private final AtomicInteger bytesSkippedLastFrame = new AtomicInteger(0);

    // Adaptive rate limiting based on frame rate
    private double currentFrameRate = 30.0;
    private int adaptiveMaxPacketsPerFrame;

    // Scene change handling - spread large updates across multiple frames
    private List<TileUpdate> deferredUpdates = new ArrayList<>();
    private int sceneChangeFramesRemaining = 0;
    private static final int SCENE_CHANGE_SPREAD_FRAMES = 2;

    // Bundle packet option - reduces packet overhead
    private boolean useBundlePackets = true;

    public PacketDispatcher(MakiScreen plugin) {
        this.plugin = plugin;
        updateAdaptiveLimit();
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

    public void dispatchFrame(Screen screen, List<TileUpdate> updates) {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            return;
        }

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

        if (isSceneChange && sceneChangeFramesRemaining == 0) {
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

        // Sort each category by priority score
        criticalUpdates.sort((a, b) -> Integer.compare(
            b.tile().getFramesSinceLastSend(), a.tile().getFramesSinceLastSend()));

        highPriorityUpdates.sort((a, b) -> Integer.compare(
            calculatePriorityScore(b), calculatePriorityScore(a)));

        normalUpdates.sort((a, b) -> Integer.compare(
            calculatePriorityScore(b), calculatePriorityScore(a)));

        // ============ PHASE 2: Build packet list with budget constraints ============
        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();
        List<TileUpdate> sentUpdates = new ArrayList<>();
        int totalBytes = 0;
        int skippedBytes = 0;
        int skippedPackets = 0;

        // Adjust budget for scene changes - allow more throughput to prevent checkerboarding
        int effectiveMaxBytes = isSceneChange || sceneChangeFramesRemaining > 0
            ? (int) (maxBytesPerFrame * 1.5)
            : maxBytesPerFrame;

        // Adjust max packets for scene changes
        int effectiveMaxPackets = isSceneChange || sceneChangeFramesRemaining > 0
            ? (int) (adaptiveMaxPacketsPerFrame * 1.3)
            : adaptiveMaxPacketsPerFrame;

        // Process critical updates first - these MUST be sent
        for (TileUpdate update : criticalUpdates) {
            int updateSize = update.dirtyRegion().getDataSize();

            // Even critical updates have a hard cap at 200% to prevent explosion
            if (totalBytes + updateSize > maxBytesPerFrame * 2.0 && !packets.isEmpty()) {
                skippedBytes += updateSize;
                skippedPackets++;
                markUpdateSkipped(update);
                continue;
            }

            packets.add(createPacket(update));
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

            packets.add(createPacket(update));
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

            packets.add(createPacket(update));
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
                if (totalBytes + updateSize > effectiveMaxBytes * 0.9) { // Keep some headroom
                    markUpdateSkipped(update);
                    continue;
                }

                packets.add(createPacket(update));
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

        for (Player player : onlinePlayers) {
            sendPacketsToPlayer(player, packets);
        }

        // When using bundles, we send 1 bundle packet per player, not N packets
        int actualPacketCount = useBundlePackets ? onlinePlayers.size() : packetsSent * onlinePlayers.size();
        totalPacketsSent.addAndGet(actualPacketCount);
        totalBytesSent.addAndGet((long) bytesSent * onlinePlayers.size());
    }

    private void markUpdateSkipped(TileUpdate update) {
        update.tile().incrementFramesSinceLastSend();
        update.tile().addAccumulatedChanges(update.dirtyRegion().changedPixelCount());
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
}

