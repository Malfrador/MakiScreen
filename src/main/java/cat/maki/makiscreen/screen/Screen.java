package cat.maki.makiscreen.screen;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Screen implements ConfigurationSerializable {

    public static final int MAP_SIZE = 128;

    private final UUID id;
    private String name;
    private final int mapWidth;
    private final int mapHeight;
    private final AspectRatio aspectRatio;
    private final List<MapTile> tiles;
    private Location origin;
    private BlockFace facing;

    public Screen(String name, int mapWidth, int mapHeight, AspectRatio aspectRatio) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.aspectRatio = aspectRatio;
        this.tiles = new ArrayList<>(mapWidth * mapHeight);
    }

    private Screen(UUID id, String name, int mapWidth, int mapHeight, AspectRatio aspectRatio,
                   List<MapTile> tiles, Location origin, BlockFace facing) {
        this.id = id;
        this.name = name;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.aspectRatio = aspectRatio;
        this.tiles = tiles;
        this.origin = origin;
        this.facing = facing;
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

    public Location getOrigin() {
        return origin;
    }

    public void setOrigin(Location origin) {
        this.origin = origin;
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

        if (origin != null && origin.getWorld() != null) {
            map.put("world", origin.getWorld().getName());
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
        if (map.containsKey("originX")) {
            int x = (int) map.get("originX");
            int y = (int) map.get("originY");
            int z = (int) map.get("originZ");
            origin = new Location(world, x, y, z);
        }

        BlockFace facing = null;
        if (map.containsKey("facing")) {
            facing = BlockFace.valueOf((String) map.get("facing"));
        }

        return new Screen(id, name, mapWidth, mapHeight, aspectRatio, tiles, origin, facing);
    }
}

