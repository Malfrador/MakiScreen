package de.erethon.mccinema.video;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.MapTile;
import de.erethon.mccinema.screen.Screen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PacketDispatcher {
    private boolean experimentalFilm;
    private boolean fullscreenFilm;
    private boolean fullscreenMaps;
    private final java.util.Map<java.util.UUID, Long> filmViewerGenerations = new java.util.concurrent.ConcurrentHashMap<>();

    public void setExperimentalFilm(boolean enabled) {
        experimentalFilm = enabled;
    }

    public void setFullscreenFilm(boolean enabled) {
        fullscreenFilm = enabled;
    }

    public void setFullscreenMaps(boolean enabled) {
        fullscreenMaps = enabled;
    }

    public enum PatchStrategy {
        BOUNDING_BOX,
        MULTI_REGION,
        FULL_MAP
    }

    // Maximum bytes to send per frame
    private static final int DEFAULT_MAX_BYTES_PER_FRAME = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_PACKETS_PER_BUNDLE = BundlerInfo.BUNDLE_SIZE_LIMIT;
    // Map id, patch dimensions, byte-array length and packet framing. This is
    // an estimate because the final framing depends on the connection codec.
    private static final int MAP_PACKET_OVERHEAD_BYTES = 16;

    // Target packets per second
    private static final int TARGET_PACKETS_PER_SECOND = 1800;

    // Staleness
    private static final int STALENESS_FACTOR = 500; // Each frame of staleness adds this much priority
    private static final int MAX_STALENESS_FRAMES = 5; // Cap - after 5 frames, tile gets max priority boost (2500)
    private static final int CRITICAL_STALENESS_FRAMES = 3; // After this many frames, tile is CRITICAL

    // Minimum guaranteed updates per frame to prevent total starvation of low-priority tiles (
    private static final int MIN_UPDATES_PER_FRAME = 4;

    // Scene change detection - when this % of tiles have significant changes, it's a scene cut
    private static final float SCENE_CHANGE_THRESHOLD = 0.6f; // 60% of tiles with major changes
    private static final int SCENE_CHANGE_PIXEL_THRESHOLD = MapTile.TOTAL_PIXELS / 2; // 50% of tile changed = major change

    // Minimum dirty region size to bother sending
    private static final int MIN_DIRTY_REGION_PIXELS = 32;

    // Aggressive bandwidth reduction: skip tiles with low-entropy changes (dither noise)
    private boolean useEntropyFiltering = true;
    private int minUniqueColorsThreshold = 3; // Skip tiles with <3 changed colors (likely noise)

    // in low-motion scenes, update tiles in a checkerboard pattern
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

    // Bundle packet option to not spam the client with thousands of packets-per-second
    private boolean useBundlePackets = true;

    // Patch generation settings
    private PatchStrategy patchStrategy = PatchStrategy.BOUNDING_BOX;
    private int fullUpdateThresholdPercent = 75;
    private int multiRegionBlockSize = 8;
    private int maxPatchesPerTile = 24;
    private int minPatchArea = 16;
    private int patchPacketOverheadBytes = 0;
    private final int maxMapPacketsPerFrame;
    private final boolean interFrameCompression;

    // Bandwidth target controls
    private boolean bandwidthTargetEnabled = true;
    private long bandwidthTargetBytesPerSecond = 20L * 1024L * 1024L;
    private double adaptiveMotionThreshold = 0.12;
    private double adaptiveFlatThreshold = 0.70;

    public PacketDispatcher(MCCinema plugin) {
        this.plugin = plugin;
        int configuredMapPackets = plugin.getConfig().contains("performance.max-map-packets-per-frame", true)
            ? plugin.getConfig().getInt("performance.max-map-packets-per-frame")
            : plugin.getConfig().getInt("performance.max-packets-per-tick", 150);
        this.maxMapPacketsPerFrame = clamp(configuredMapPackets, 1, MAX_PACKETS_PER_BUNDLE);
        this.interFrameCompression = plugin.getConfig().getBoolean("performance.inter-frame-compression", true);
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

    public void setFrameRate(double frameRate) {
        this.currentFrameRate = Math.max(1.0, frameRate);
        updateAdaptiveLimit();
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
        dispatchFrame(screen, updates, contentStats, metrics, screen.getViewers());
    }

    public void dispatchFrame(Screen screen, List<TileUpdate> updates, FrameProcessor.FrameContentStats contentStats,
                              PerformanceMetrics metrics, Collection<? extends Player> recipients) {
        if (recipients.isEmpty()) {
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
        if (isSceneChange && sceneChangeFramesRemaining == 0 && !useBundlePackets && !fullscreenFilm) {
            sceneChangeFramesRemaining = SCENE_CHANGE_SPREAD_FRAMES;
        }

        List<PreparedUpdate> criticalUpdates = new ArrayList<>();
        List<PreparedUpdate> highPriorityUpdates = new ArrayList<>();
        List<PreparedUpdate> normalUpdates = new ArrayList<>();
        List<PreparedUpdate> lowPriorityUpdates = new ArrayList<>();

        List<TileUpdate> allUpdates = new ArrayList<>(updates.size() + deferredUpdates.size());
        if (!experimentalFilm && !interFrameCompression) {
            for (TileUpdate update : updates) {
                byte[] data = update.mapData();
                if (data != null && data.length == MapTile.TOTAL_PIXELS) {
                    allUpdates.add(new TileUpdate(update.tile(),
                        new MapTile.DirtyRegion(0, 0, MapTile.SIZE, MapTile.SIZE, data), data));
                }
            }
        } else {
            allUpdates.addAll(updates);
        }
        allUpdates.addAll(deferredUpdates);
        deferredUpdates.clear();
        long dirtyTileCount = allUpdates.stream().filter(update -> update.dirtyRegion() != null).count();
        int maxRegionsPerTile = Math.max(1,
            (fullscreenFilm ? MAX_PACKETS_PER_BUNDLE : maxMapPacketsPerFrame)
                / Math.max(1, (int) Math.min(Integer.MAX_VALUE, dirtyTileCount)));

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

            PreparedUpdate prepared = prepareUpdate(update, effectiveFullUpdateThreshold,
                effectiveMinPatchArea, maxRegionsPerTile);
            if (prepared == null || prepared.regions().isEmpty()) {
                continue;
            }

            MapTile tile = update.tile();
            int staleness = tile.getFramesSinceLastSend();
            int changedPixels = update.dirtyRegion().changedPixelCount();
            int accumulated = tile.getAccumulatedChanges();

            if (!experimentalFilm && useEntropyFiltering && staleness < 2 && !hasSignificantChanges(update.dirtyRegion().data())) {
                lowPriorityUpdates.add(prepared);
                continue;
            }

            if (!experimentalFilm && useSpatialDownsampling && !isSceneChange && staleness == 0 && changedPixels < MapTile.TOTAL_PIXELS / 4) {
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
        if (fullscreenFilm) effectiveMaxBytes = Integer.MAX_VALUE;
        if (bandwidthTargetEnabled && !fullscreenFilm) {
            int targetPerFrame = Math.max(MIN_DIRTY_REGION_PIXELS,
                (int) (bandwidthTargetBytesPerSecond / Math.max(1.0, currentFrameRate)));
            effectiveMaxBytes = Math.min(effectiveMaxBytes, targetPerFrame);
        }
        lastFrameByteCap.set(effectiveMaxBytes);
        int effectiveMaxPackets = fullscreenFilm ? MAX_PACKETS_PER_BUNDLE : Math.min(maxMapPacketsPerFrame, useBundlePackets
            ? MAX_PACKETS_PER_BUNDLE
            : (isSceneChange || sceneChangeFramesRemaining > 0
                ? (int) (adaptiveMaxPacketsPerFrame * 1.3)
                : adaptiveMaxPacketsPerFrame));

        long totalPacketCreationTime = 0;

        for (PreparedUpdate prepared : criticalUpdates) {
            int updateSize = prepared.estimatedSize();
            int packetCount = prepared.regions().size();

            if ((totalBytes + updateSize > effectiveMaxBytes && !packets.isEmpty()) ||
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
            sentBoundingBytes += prepared.boundingSize() + MAP_PACKET_OVERHEAD_BYTES;
            sentFullMapBytes += MapTile.TOTAL_PIXELS + MAP_PACKET_OVERHEAD_BYTES;
        }

        for (PreparedUpdate prepared : highPriorityUpdates) {
            int updateSize = prepared.estimatedSize();
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
            sentBoundingBytes += prepared.boundingSize() + MAP_PACKET_OVERHEAD_BYTES;
            sentFullMapBytes += MapTile.TOTAL_PIXELS + MAP_PACKET_OVERHEAD_BYTES;
        }

        for (PreparedUpdate prepared : normalUpdates) {
            int updateSize = prepared.estimatedSize();
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
            sentBoundingBytes += prepared.boundingSize() + MAP_PACKET_OVERHEAD_BYTES;
            sentFullMapBytes += MapTile.TOTAL_PIXELS + MAP_PACKET_OVERHEAD_BYTES;
        }

        if (sceneChangeFramesRemaining == 0) {
            for (PreparedUpdate prepared : lowPriorityUpdates) {
                int updateSize = prepared.estimatedSize();
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
                sentBoundingBytes += prepared.boundingSize() + MAP_PACKET_OVERHEAD_BYTES;
                sentFullMapBytes += MapTile.TOTAL_PIXELS + MAP_PACKET_OVERHEAD_BYTES;
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
                if (experimentalFilm) {
                    // Film planning covers every changed byte. Encoded arrays are immutable
                    // after publication, so the complete snapshot is also the exact sent state.
                    update.tile().setLastSentData(update.mapData());
                } else {
                    applySentRegions(update.tile(), update.mapData(), sentUpdate.regions());
                }
            }
        }

        packetsSkippedLastFrame.set(skippedPackets);
        bytesSkippedLastFrame.set(skippedBytes);
        lastFrameTileCount.set(sentTiles);
        lastFrameMultiRegionTileCount.set(sentMultiRegionTiles);
        lastFrameBoundingBytes.set(sentBoundingBytes);
        lastFrameFullMapBytes.set(sentFullMapBytes);

        long sendingStart = metrics != null ? System.nanoTime() : 0;
        Set<UUID> baselineViewers = new HashSet<>();
        long baselineBytes = 0;
        int baselinePacketCount = 0;
        if (experimentalFilm || fullscreenMaps) {
            List<ClientboundMapItemDataPacket> baseline = null;
            for (Player viewer : recipients) {
                long generation = plugin.getFilmResourcePack().loadedGeneration(viewer);
                if (generation != 0 && !java.util.Objects.equals(filmViewerGenerations.get(viewer.getUniqueId()), generation)) {
                    // Synchronize with the shared SENT baseline, not the newest frame,
                    // which may include tiles deferred by the bandwidth budget.
                    if (baseline == null) {
                        baseline = new ArrayList<>();
                        for (MapTile tile : screen.getTiles()) {
                            baseline.add(createFullMapPacket(tile.getMapId(), tile.getLastSentData()));
                        }
                    }
                    sendPacketsToPlayer(viewer, baseline);
                    filmViewerGenerations.put(viewer.getUniqueId(), generation);
                    baselineViewers.add(viewer.getUniqueId());
                    baselineBytes += (long) baseline.size() * (MapTile.TOTAL_PIXELS + MAP_PACKET_OVERHEAD_BYTES);
                    baselinePacketCount += transportPacketCount(baseline.size());
                }
            }
        }

        int deltaRecipientCount = 0;
        for (Player player : recipients) {
            if (baselineViewers.contains(player.getUniqueId())) continue;
            sendPacketsToPlayer(player, packets);
            deltaRecipientCount++;
        }
        if (metrics != null) {
            metrics.recordPacketSending(System.nanoTime() - sendingStart);
        }

        int deltaPacketCount = transportPacketCount(packets.size()) * deltaRecipientCount;
        long estimatedBytesSent = baselineBytes + (long) totalBytes * deltaRecipientCount;
        totalPacketsSent.addAndGet(baselinePacketCount + deltaPacketCount);
        totalBytesSent.addAndGet(estimatedBytesSent);

        lastFramePacketCount.set(packets.size() + (baselineViewers.isEmpty() ? 0 : screen.getTotalMaps()));
        lastFrameBytesSent.set(Math.round((double) estimatedBytesSent / recipients.size()));
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

    private PreparedUpdate prepareUpdate(TileUpdate update, int effectiveFullUpdateThreshold,
                                         int effectiveMinPatchArea, int bundleRegionLimit) {
        MapTile.DirtyRegion dirtyRegion = update.dirtyRegion();
        if (dirtyRegion == null) {
            return null;
        }

        byte[] mapData = update.mapData();
        // Film bytes describe matching 4x4 image blocks. Preserve that locality
        // with exact block patches rather than collapsing all changes into one box.
        if (experimentalFilm) {
            if (fullscreenFilm && de.erethon.mccinema.video.film.FilmCodec.isFullscreenAnchor(mapData)) {
                // Send an anchor in one patch per map. Planning many tiny patches
                // makes the keyframe a packet/CPU spike and can defer other tiles.
                return new PreparedUpdate(update, List.of(dirtyRegion),
                    dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
            }
            List<MapTile.DirtyRegion> regions = de.erethon.mccinema.video.film.FilmPatchPlanner.plan(
                mapData, update.tile().getLastSentData(), dirtyRegion,
                bundleRegionLimit, Math.max(MAP_PACKET_OVERHEAD_BYTES, patchPacketOverheadBytes));
            int bytes = 0;
            for (MapTile.DirtyRegion region : regions) bytes += region.getDataSize();
            return new PreparedUpdate(update, regions, bytes, dirtyRegion.getDataSize());
        }
        if (mapData == null || mapData.length != MapTile.TOTAL_PIXELS) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        if (!interFrameCompression) {
            MapTile.DirtyRegion fullRegion = new MapTile.DirtyRegion(0, 0, MapTile.SIZE, MapTile.SIZE, mapData);
            return new PreparedUpdate(update, List.of(fullRegion), MapTile.TOTAL_PIXELS, dirtyRegion.getDataSize());
        }

        if (patchStrategy == PatchStrategy.FULL_MAP || dirtyRegion.getCoveragePercent() >= effectiveFullUpdateThreshold) {
            MapTile.DirtyRegion fullRegion = new MapTile.DirtyRegion(0, 0, MapTile.SIZE, MapTile.SIZE, mapData, dirtyRegion.changedPixelCount());
            return new PreparedUpdate(update, List.of(fullRegion), MapTile.TOTAL_PIXELS, dirtyRegion.getDataSize());
        }

        if (patchStrategy != PatchStrategy.MULTI_REGION || dirtyRegion.isFullMap()) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        List<MapTile.DirtyRegion> splitRegions = buildMultiRegions(update, multiRegionBlockSize,
            effectiveMinPatchArea, Math.min(bundleRegionLimit, maxPatchesPerTile));
        if (splitRegions.isEmpty()) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        int splitPayload = 0;
        for (MapTile.DirtyRegion region : splitRegions) {
            splitPayload += region.getDataSize();
        }

        int effectivePatchOverhead = Math.max(MAP_PACKET_OVERHEAD_BYTES, patchPacketOverheadBytes);
        int splitWithOverhead = splitPayload + splitRegions.size() * effectivePatchOverhead;
        int boxWithOverhead = dirtyRegion.getDataSize() + effectivePatchOverhead;
        if (splitWithOverhead >= boxWithOverhead) {
            return new PreparedUpdate(update, List.of(dirtyRegion), dirtyRegion.getDataSize(), dirtyRegion.getDataSize());
        }

        return new PreparedUpdate(update, splitRegions, splitPayload, dirtyRegion.getDataSize());
    }

    private void applySentRegions(MapTile tile, byte[] mapData, List<MapTile.DirtyRegion> regions) {
        byte[] baseline = tile.getLastSentData();
        if (baseline == null || baseline.length != MapTile.TOTAL_PIXELS) {
            baseline = new byte[MapTile.TOTAL_PIXELS];
        } else {
            baseline = baseline.clone();
        }
        for (MapTile.DirtyRegion region : regions) {
            for (int row = 0; row < region.height(); row++) {
                int source = (region.y() + row) * MapTile.SIZE + region.x();
                System.arraycopy(mapData, source, baseline, source, region.width());
            }
        }
        tile.setLastSentData(baseline);
    }

    private List<MapTile.DirtyRegion> buildMultiRegions(TileUpdate update, int blockSize,
                                                        int effectiveMinPatchArea, int patchLimit) {
        byte[] mapData = update.mapData();
        byte[] lastSentData = update.tile().getLastSentData();
        if (mapData == null || lastSentData == null || lastSentData.length != MapTile.TOTAL_PIXELS) {
            return List.of();
        }

        blockSize = Math.max(1, blockSize);
        int blockColumns = (MapTile.SIZE + blockSize - 1) / blockSize;
        int blockRows = (MapTile.SIZE + blockSize - 1) / blockSize;
        int blockCount = blockColumns * blockRows;

        boolean[] dirtyBlocks = new boolean[blockCount];
        int[] changedPerBlock = new int[blockCount];
        MapTile.DirtyRegion bounding = update.dirtyRegion();
        int startY = Math.max(0, bounding.y());
        int endY = Math.min(MapTile.SIZE, bounding.y() + bounding.height());
        int startX = Math.max(0, bounding.x());
        int endX = Math.min(MapTile.SIZE, bounding.x() + bounding.width());
        for (int y = startY; y < endY; y++) {
            int rowOffset = y * MapTile.SIZE;
            int blockY = y / blockSize;
            for (int x = startX; x < endX; x++) {
                int index = rowOffset + x;
                if (mapData[index] != lastSentData[index]) {
                    int blockX = x / blockSize;
                    int blockIndex = blockY * blockColumns + blockX;
                    dirtyBlocks[blockIndex] = true;
                    changedPerBlock[blockIndex]++;
                }
            }
        }

        List<PatchBounds> rectangles = new ArrayList<>();
        java.util.Map<Long, PatchBounds> active = new java.util.HashMap<>();
        int firstBlockY = startY / blockSize;
        int lastBlockY = (endY + blockSize - 1) / blockSize;
        int firstBlockX = startX / blockSize;
        int lastBlockX = (endX + blockSize - 1) / blockSize;
        for (int blockY = firstBlockY; blockY < lastBlockY; blockY++) {
            java.util.Map<Long, PatchBounds> next = new java.util.HashMap<>();
            int blockX = firstBlockX;
            while (blockX < lastBlockX) {
                int index = blockY * blockColumns + blockX;
                if (!dirtyBlocks[index]) {
                    blockX++;
                    continue;
                }
                int runStartX = blockX;
                int changed = 0;
                while (blockX < lastBlockX && dirtyBlocks[blockY * blockColumns + blockX]) {
                    changed += changedPerBlock[blockY * blockColumns + blockX];
                    blockX++;
                }
                int runEndX = blockX;
                long key = ((long) runStartX << 32) | (runEndX & 0xffffffffL);
                PatchBounds rectangle = active.get(key);
                if (rectangle == null) {
                    rectangle = new PatchBounds(runStartX, blockY, runEndX - runStartX, changed);
                    rectangles.add(rectangle);
                } else {
                    rectangle.heightBlocks++;
                    rectangle.changedPixels += changed;
                }
                next.put(key, rectangle);
            }
            active = next;
        }

        List<MapTile.DirtyRegion> regions = new ArrayList<>(rectangles.size());
        for (PatchBounds rectangle : rectangles) {
            int x = rectangle.xBlock * blockSize;
            int y = rectangle.yBlock * blockSize;
            int width = Math.min(MapTile.SIZE, (rectangle.xBlock + rectangle.widthBlocks) * blockSize) - x;
            int height = Math.min(MapTile.SIZE, (rectangle.yBlock + rectangle.heightBlocks) * blockSize) - y;
            if (width * height < effectiveMinPatchArea) continue;
            byte[] patchData = copyPatchData(mapData, x, y, width, height);
            regions.add(new MapTile.DirtyRegion(x, y, width, height, patchData,
                Math.max(1, rectangle.changedPixels)));
        }

        if (regions.size() > patchLimit) {
            return List.of();
        }

        regions.sort((a, b) -> Integer.compare(b.getDataSize(), a.getDataSize()));
        return regions;
    }

    private static final class PatchBounds {
        private final int xBlock;
        private final int yBlock;
        private final int widthBlocks;
        private int heightBlocks = 1;
        private int changedPixels;

        private PatchBounds(int xBlock, int yBlock, int widthBlocks, int changedPixels) {
            this.xBlock = xBlock;
            this.yBlock = yBlock;
            this.widthBlocks = widthBlocks;
            this.changedPixels = changedPixels;
        }
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
     * This ensures tiles are sent in the order the client renders them
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
        dispatchFullFrame(screen, mapData, screen.getViewers());
    }

    public void dispatchFullFrame(Screen screen, byte[][] mapData, Collection<? extends Player> recipients) {
        if (recipients.isEmpty()) {
            return;
        }

        List<ClientboundMapItemDataPacket> packets = new ArrayList<>(screen.getTotalMaps());
        long estimatedBytesPerViewer = 0;

        for (int i = 0; i < screen.getTiles().size(); i++) {
            MapTile tile = screen.getTile(i);
            byte[] data = mapData[i];
            if (data == null) continue;

            ClientboundMapItemDataPacket packet = createFullMapPacket(tile.getMapId(), data);
            packets.add(packet);
            estimatedBytesPerViewer += data.length + MAP_PACKET_OVERHEAD_BYTES;
        }

        for (Player player : recipients) {
            sendPacketsToPlayer(player, packets);
        }
        totalPacketsSent.addAndGet((long) transportPacketCount(packets.size()) * recipients.size());
        totalBytesSent.addAndGet(estimatedBytesPerViewer * recipients.size());
    }

    /**
     * Send a complete still frame and make it the baseline for future delta
     * updates. This is used for paused seeks, where deferred updates would never
     * otherwise be flushed until playback resumes.
     */
    public void dispatchImmediateFrame(Screen screen, byte[][] mapData, Collection<? extends Player> recipients) {
        deferredUpdates.clear();
        sceneChangeFramesRemaining = 0;
        dispatchFullFrame(screen, mapData, recipients);

        for (MapTile tile : screen.getTiles()) {
            int tileIndex = tile.getTileIndex();
            if (tileIndex < 0 || tileIndex >= mapData.length || mapData[tileIndex] == null) {
                continue;
            }
            tile.setLastSentData(mapData[tileIndex].clone());
            tile.resetFramesSinceLastSend();
            tile.resetAccumulatedChanges();
        }
    }

    /** Send the remaining exact changes when playback pauses and no later frame can catch them up. */
    public void flushPendingFrame(Screen screen, Collection<? extends Player> recipients) {
        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();
        long estimatedBytes = 0;
        for (MapTile tile : screen.getTiles()) {
            byte[] data = tile.getLastFrameData();
            if (data == null || data.length != MapTile.TOTAL_PIXELS) continue;
            MapTile.DirtyRegion region = tile.calculateDirtyRegionFromSent(data, true);
            if (region == null) continue;
            packets.add(createPacket(tile, region));
            estimatedBytes += region.getDataSize() + MAP_PACKET_OVERHEAD_BYTES;
            tile.setLastSentData(data);
            tile.resetFramesSinceLastSend();
            tile.resetAccumulatedChanges();
        }
        for (Player viewer : recipients) sendPacketsToPlayer(viewer, packets);
        totalPacketsSent.addAndGet((long) transportPacketCount(packets.size()) * recipients.size());
        totalBytesSent.addAndGet(estimatedBytes * recipients.size());
    }

    public void sendLastFrameToPlayer(Player player, Screen screen) {
        List<ClientboundMapItemDataPacket> packets = new ArrayList<>();

        for (MapTile tile : screen.getTiles()) {
            byte[] data = tile.getLastFrameData();
            if (de.erethon.mccinema.video.film.FilmCodec.isEncoded(data)
                || de.erethon.mccinema.video.film.FilmCodec.isFullscreenEncoded(data)) {
                VideoPlayer video = plugin.getVideoPlayer(screen);
                if (!plugin.getFilmResourcePack().isLoaded(player)
                    || video != null && !video.canSendTo(player)) continue;
                data = tile.getLastSentData();
            }
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
            totalPacketsSent.addAndGet(transportPacketCount(packets.size()));
            totalBytesSent.addAndGet((long) packets.size() *
                (MapTile.TOTAL_PIXELS + MAP_PACKET_OVERHEAD_BYTES));
        }
    }

    private void sendPacketsToPlayer(Player player, List<ClientboundMapItemDataPacket> packets) {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;

        if (useBundlePackets && packets.size() > 1) {
            for (int start = 0; start < packets.size(); start += MAX_PACKETS_PER_BUNDLE) {
                int end = Math.min(packets.size(), start + MAX_PACKETS_PER_BUNDLE);
                if (end == start + 1) {
                    connection.send(packets.get(start));
                    continue;
                }
                List<Packet<? super ClientGamePacketListener>> packetList = new ArrayList<>(packets.subList(start, end));
                connection.send(new ClientboundBundlePacket(packetList));
            }
        } else {
            for (ClientboundMapItemDataPacket packet : packets) {
                connection.send(packet);
            }
        }
    }

    private int transportPacketCount(int mapPackets) {
        return useBundlePackets && mapPackets > 1
            ? (mapPackets + MAX_PACKETS_PER_BUNDLE - 1) / MAX_PACKETS_PER_BUNDLE
            : mapPackets;
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

    public record TileUpdate(MapTile tile, MapTile.DirtyRegion dirtyRegion, byte[] mapData) {
    }

    private record PreparedUpdate(TileUpdate update, List<MapTile.DirtyRegion> regions, int totalDataSize, int boundingSize) {
        int estimatedSize() {
            return totalDataSize + regions.size() * MAP_PACKET_OVERHEAD_BYTES;
        }
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

    public long getLastFrameBytesSent() {
        return lastFrameBytesSent.get();
    }
}

