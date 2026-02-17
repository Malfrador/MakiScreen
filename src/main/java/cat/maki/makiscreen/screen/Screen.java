package cat.maki.makiscreen.screen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class Screen implements ConfigurationSerializable {

    public static final int MAP_SIZE = 128;
    public static final double VIEWER_DISTANCE = 32.0;
    private static final long VIEWER_CACHE_EXPIRE_MS = 500;

    private final UUID id;
    private String name;
    private final int mapWidth;
    private final int mapHeight;
    private final AspectRatio aspectRatio;
    private final List<MapTile> tiles;
    private Location origin;
    private BlockFace facing;
    private String worldName;

    // Cached viewers list
    private volatile List<Player> cachedViewers = Collections.emptyList();
    private volatile long lastViewerCacheUpdate = 0;
    private volatile int viewerUpdateTaskId = -1;

    public Screen(String name, int mapWidth, int mapHeight, AspectRatio aspectRatio) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.aspectRatio = aspectRatio;
        this.tiles = new ArrayList<>(mapWidth * mapHeight);
    }

    private Screen(UUID id, String name, int mapWidth, int mapHeight, AspectRatio aspectRatio,
                   List<MapTile> tiles, Location origin, BlockFace facing, String worldName) {
        this.id = id;
        this.name = name;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.aspectRatio = aspectRatio;
        this.tiles = tiles;
        this.origin = origin;
        this.facing = facing;
        this.worldName = worldName;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMapWidth() {
        return mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public int getTotalMaps() {
        return mapWidth * mapHeight;
    }

    public int getPixelWidth() {
        return mapWidth * MAP_SIZE;
    }

    public int getPixelHeight() {
        return mapHeight * MAP_SIZE;
    }

    public AspectRatio getAspectRatio() {
        return aspectRatio;
    }

    public List<MapTile> getTiles() {
        return tiles;
    }

    public MapTile getTile(int index) {
        return tiles.get(index);
    }

    public MapTile getTile(int x, int y) {
        return tiles.get(y * mapWidth + x);
    }

    public void addTile(MapTile tile) {
        tiles.add(tile);
    }

    /**
     * Fills all tiles of the screen with a specific color.
     * @param colorByte The Minecraft map color byte to fill with (e.g., (byte) 34 for white)
     */
    public void fillWithColor(byte colorByte) {
        byte[] fillData = new byte[MAP_SIZE * MAP_SIZE];
        java.util.Arrays.fill(fillData, colorByte);

        for (MapTile tile : tiles) {
            tile.setLastFrameData(fillData.clone());
            tile.setLastSentData(fillData.clone());
        }
    }

    public Location getOrigin() {
        if (origin != null && origin.getWorld() == null && worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                origin.setWorld(world);
            }
        }
        return origin;
    }

    public void setOrigin(Location origin) {
        this.origin = origin;
        if (origin != null && origin.getWorld() != null) {
            this.worldName = origin.getWorld().getName();
        }
    }

    public BlockFace getFacing() {
        return facing;
    }

    public void setFacing(BlockFace facing) {
        this.facing = facing;
    }

    public World getWorld() {
        return origin != null ? origin.getWorld() : null;
    }

    public @NotNull Collection<Player> getViewers() {
        return cachedViewers;
    }

    public void updateViewerCache() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("updateViewerCache must be called from the main thread!");
        }

        Location loc = getOrigin();
        if (loc == null || loc.getWorld() == null) {
            cachedViewers = Collections.emptyList();
            return;
        }
        Collection<Player> nearby = loc.getNearbyPlayers(VIEWER_DISTANCE);
        cachedViewers = new CopyOnWriteArrayList<>(nearby);
        lastViewerCacheUpdate = System.currentTimeMillis();
    }

    public void startViewerCacheUpdater(Plugin plugin, long updateIntervalTicks) {
        stopViewerCacheUpdater();
        viewerUpdateTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::updateViewerCache, 0L, updateIntervalTicks).getTaskId();
    }

    public void stopViewerCacheUpdater() {
        if (viewerUpdateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(viewerUpdateTaskId);
            viewerUpdateTaskId = -1;
        }
    }

    public boolean hasValidOrigin() {
        Location loc = getOrigin();
        return loc != null && loc.getWorld() != null;
    }

    public Location getCenterLocation() {
        if (origin == null) {
            return null;
        }

        double offsetX = 0;
        double offsetZ = 0;

        if (facing != null) {
            switch (facing) {
                case NORTH:
                    offsetX = mapWidth / 2.0;
                    break;
                case SOUTH:
                    offsetX = -mapWidth / 2.0;
                    break;
                case EAST:
                    offsetZ = mapWidth / 2.0;
                    break;
                case WEST:
                    offsetZ = -mapWidth / 2.0;
                    break;
            }
        }

        double centerY = origin.getY() + (mapHeight / 2.0);

        return new Location(origin.getWorld(),
                          origin.getX() + offsetX,
                          centerY,
                          origin.getZ() + offsetZ);
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id.toString());
        map.put("name", name);
        map.put("mapWidth", mapWidth);
        map.put("mapHeight", mapHeight);
        map.put("aspectRatio", aspectRatio.name());

        List<Map<String, Object>> tileData = new ArrayList<>();
        for (MapTile tile : tiles) {
            tileData.add(tile.serialize());
        }
        map.put("tiles", tileData);

        if (origin != null) {
            String savedWorldName = origin.getWorld() != null ? origin.getWorld().getName() : worldName;
            if (savedWorldName != null) {
                map.put("world", savedWorldName);
            }
            map.put("originX", origin.getBlockX());
            map.put("originY", origin.getBlockY());
            map.put("originZ", origin.getBlockZ());
        }

        if (facing != null) {
            map.put("facing", facing.name());
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    public static Screen deserialize(Map<String, Object> map, World world) {
        UUID id = UUID.fromString((String) map.get("id"));
        String name = (String) map.get("name");
        int mapWidth = (int) map.get("mapWidth");
        int mapHeight = (int) map.get("mapHeight");
        AspectRatio aspectRatio = AspectRatio.fromString((String) map.get("aspectRatio"));

        List<MapTile> tiles = new ArrayList<>();
        List<Map<String, Object>> tileData = (List<Map<String, Object>>) map.get("tiles");
        if (tileData != null) {
            for (Map<String, Object> td : tileData) {
                tiles.add(MapTile.deserialize(td));
            }
        }

        Location origin = null;
        String worldName = (String) map.get("world");
        if (map.containsKey("originX")) {
            int x = (int) map.get("originX");
            int y = (int) map.get("originY");
            int z = (int) map.get("originZ");
            // Create location with potentially null world - will be resolved lazily
            origin = new Location(world, x, y, z);
        }

        BlockFace facing = null;
        if (map.containsKey("facing")) {
            facing = BlockFace.valueOf((String) map.get("facing"));
        }

        return new Screen(id, name, mapWidth, mapHeight, aspectRatio, tiles, origin, facing, worldName);
    }
}

