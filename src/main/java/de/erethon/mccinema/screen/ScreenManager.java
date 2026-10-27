package de.erethon.mccinema.screen;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.video.PacketDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ScreenManager {

    private final MCCinema plugin;
    private final Map<UUID, Screen> screens = new ConcurrentHashMap<>();
    private final Map<String, Screen> screensByName = new ConcurrentHashMap<>();
    private final File screensFile;
    private final NamespacedKey screenIdKey;
    private final NamespacedKey tileIndexKey;
    private final NamespacedKey mapIdKey;

    public ScreenManager(MCCinema plugin) {
        this.plugin = plugin;
        this.screensFile = new File(plugin.getDataFolder(), "screens.yml");
        this.screenIdKey = new NamespacedKey(plugin, "screen_id");
        this.tileIndexKey = new NamespacedKey(plugin, "screen_tile_index");
        this.mapIdKey = new NamespacedKey(plugin, "screen_map_id");
    }

    public void loadScreens() {
        screens.clear();
        screensByName.clear();

        if (!screensFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(screensFile);
        ConfigurationSection screensSection = config.getConfigurationSection("screens");

        if (screensSection == null) {
            return;
        }

        for (String key : screensSection.getKeys(false)) {
            try {
                ConfigurationSection screenData = screensSection.getConfigurationSection(key);
                if (screenData == null) continue;

                String worldName = screenData.getString("world");
                World world = worldName != null ? Bukkit.getWorld(worldName) : null;

                Map<String, Object> data = screenData.getValues(true);
                Screen screen = Screen.deserialize(data, world);

                registerScreen(screen);
                plugin.getLogger().info("Loaded screen: " + screen.getName() + " (" + screen.getId() + ")");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load screen: " + key, e);
            }
        }
    }

    public void saveScreens() {
        YamlConfiguration config = new YamlConfiguration();

        for (Screen screen : screens.values()) {
            String path = "screens." + screen.getId().toString();
            Map<String, Object> data = screen.serialize();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                config.set(path + "." + entry.getKey(), entry.getValue());
            }
        }

        try {
            config.save(screensFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save screens", e);
        }
    }

    public void registerScreen(Screen screen) {
        screens.put(screen.getId(), screen);
        screensByName.put(screen.getName().toLowerCase(), screen);
    }

    public void unregisterScreen(Screen screen) {
        screens.remove(screen.getId());
        screensByName.remove(screen.getName().toLowerCase());
    }

    public Optional<Screen> getScreen(UUID id) {
        return Optional.ofNullable(screens.get(id));
    }

    public Optional<Screen> getScreen(String name) {
        return Optional.ofNullable(screensByName.get(name.toLowerCase()));
    }

    public Collection<Screen> getAllScreens() {
        return screens.values();
    }

    public boolean screenExists(String name) {
        return screensByName.containsKey(name.toLowerCase());
    }

    public Screen createScreen(String name, int mapWidth, int mapHeight, AspectRatio aspectRatio) {
        if (screenExists(name)) {
            throw new IllegalArgumentException("Screen with name '" + name + "' already exists");
        }

        Screen screen = new Screen(name, mapWidth, mapHeight, aspectRatio);
        registerScreen(screen);
        return screen;
    }

    public DeletionResult deleteScreen(Screen screen) {
        Location origin = screen.getOrigin();
        if (origin == null || origin.getWorld() == null || screen.getFacing() == null) {
            return new DeletionResult(false, 0, 0, 0);
        }

        int detachedRenderers = detachMapRenderers(screen);
        RemovalCounts removed = removeScreenFrames(screen, origin.getWorld());
        screen.stopViewerCacheUpdater();
        unregisterScreen(screen);
        saveScreens();
        return new DeletionResult(true, removed.frames, removed.maps, detachedRenderers);
    }

    public void tagScreenMap(ItemStack mapItem, Screen screen, MapTile tile) {
        ItemMeta meta = mapItem.getItemMeta();
        tag(meta.getPersistentDataContainer(), screen, tile);
        mapItem.setItemMeta(meta);
    }

    public void tagScreenFrame(ItemFrame frame, Screen screen, MapTile tile) {
        tag(frame.getPersistentDataContainer(), screen, tile);
    }

    private void tag(PersistentDataContainer data, Screen screen, MapTile tile) {
        data.set(screenIdKey, PersistentDataType.STRING, screen.getId().toString());
        data.set(tileIndexKey, PersistentDataType.INTEGER, tile.getTileIndex());
        data.set(mapIdKey, PersistentDataType.INTEGER, tile.getMapId());
    }

    private int detachMapRenderers(Screen screen) {
        int detached = 0;
        for (MapTile tile : screen.getTiles()) {
            MapView mapView = Bukkit.getMap(tile.getMapId());
            if (mapView == null) {
                continue;
            }
            for (MapRenderer renderer : List.copyOf(mapView.getRenderers())) {
                if (renderer instanceof ScreenMapRenderer) {
                    mapView.removeRenderer(renderer);
                    detached++;
                }
            }
        }
        return detached;
    }

    public void attachScreenRenderers(Screen screen) {
        for (MapTile tile : screen.getTiles()) {
            MapView mapView = Bukkit.getMap(tile.getMapId());
            if (mapView == null) continue;
            // Renderers are not persisted with saved screen/map IDs across restarts.
            for (MapRenderer renderer : List.copyOf(mapView.getRenderers())) {
                mapView.removeRenderer(renderer);
            }
            mapView.addRenderer(new ScreenMapRenderer(tile, screen));
        }
    }

    private RemovalCounts removeScreenFrames(Screen screen, World world) {
        Set<UUID> removedFrames = new HashSet<>();
        int mapsRemoved = 0;

        for (ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
            if (belongsToScreen(frame.getPersistentDataContainer(), screen)) {
                mapsRemoved += removeFrame(frame, removedFrames);
            }
        }

        BlockFace rightFace = getRightFace(screen.getFacing());
        Map<Chunk, Set<Integer>> expectedMapsByChunk = new HashMap<>();
        int tileIndex = 0;
        for (int y = screen.getMapHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < screen.getMapWidth(); x++) {
                MapTile tile = screen.getTile(tileIndex++);
                Location frameLocation = screen.getOrigin().getBlock()
                    .getRelative(rightFace, x)
                    .getRelative(BlockFace.UP, y)
                    .getRelative(screen.getFacing().getOppositeFace())
                    .getLocation();
                Chunk chunk = frameLocation.getChunk();
                expectedMapsByChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(tile.getMapId());
            }
        }

        for (Map.Entry<Chunk, Set<Integer>> entry : expectedMapsByChunk.entrySet()) {
            Chunk chunk = entry.getKey();
            chunk.load();
            for (Entity entity : chunk.getEntities()) {
                if (!(entity instanceof ItemFrame frame)) {
                    continue;
                }
                Integer mapId = getMapId(frame);
                if (belongsToScreen(frame.getPersistentDataContainer(), screen) ||
                    mapId != null && entry.getValue().contains(mapId)) {
                    mapsRemoved += removeFrame(frame, removedFrames);
                }
            }
        }
        return new RemovalCounts(removedFrames.size(), mapsRemoved);
    }

    private boolean belongsToScreen(PersistentDataContainer data, Screen screen) {
        return screen.getId().toString().equals(data.get(screenIdKey, PersistentDataType.STRING));
    }

    private Integer getMapId(ItemFrame frame) {
        ItemStack item = frame.getItem();
        if (item.getType() != Material.FILLED_MAP || !(item.getItemMeta() instanceof MapMeta meta)) {
            return null;
        }
        Integer taggedMapId = meta.getPersistentDataContainer().get(mapIdKey, PersistentDataType.INTEGER);
        if (taggedMapId != null) {
            return taggedMapId;
        }
        MapView mapView = meta.getMapView();
        return mapView == null ? null : mapView.getId();
    }

    private int removeFrame(ItemFrame frame, Set<UUID> removedFrames) {
        if (!removedFrames.add(frame.getUniqueId())) {
            return 0;
        }
        boolean removedMap = frame.getItem().getType() == Material.FILLED_MAP;
        frame.setItem(new ItemStack(Material.AIR), false);
        frame.remove();
        return removedMap ? 1 : 0;
    }

    private BlockFace getRightFace(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    public record DeletionResult(boolean deleted, int itemFramesRemoved, int mapItemsRemoved,
                                 int mapRenderersDetached) {
    }

    private record RemovalCounts(int frames, int maps) {
    }

    public byte getBlankColorByte() {
        String configured = plugin.getConfig().getString("display.blank-color", "WHITE");
        Optional<Byte> parsed = MapColorUtil.parseMapColor(configured);
        if (parsed.isPresent()) {
            return parsed.get();
        }

        plugin.getLogger().warning("Invalid display.blank-color '" + configured + "', using WHITE.");
        return MapColorUtil.DEFAULT_BLANK_COLOR;
    }

    public void fillScreenWithBlankColor(Screen screen) {
        fillScreenWithColor(screen, getBlankColorByte());
    }

    public void fillScreenWithPlaybackBackground(Screen screen, Collection<? extends Player> recipients) {
        fillScreenWithColor(screen, MapColorUtil.visibleBlack(), recipients, false);
    }

    /**
     * Fills a screen with a specific color and sends the update to nearby viewers.
     * @param screen The screen to fill
     * @param colorByte The Minecraft map color byte (e.g., (byte) 34 for white)
     */
    public void fillScreenWithColor(Screen screen, byte colorByte) {
        fillScreenWithColor(screen, colorByte, null, true);
    }

    private void fillScreenWithColor(Screen screen, byte colorByte, Collection<? extends Player> recipients, boolean scheduleResends) {
        if (!Bukkit.isPrimaryThread()) {
            Collection<? extends Player> recipientSnapshot = recipients == null ? null : List.copyOf(recipients);
            Bukkit.getScheduler().runTask(plugin, () -> fillScreenWithColor(screen, colorByte, recipientSnapshot, scheduleResends));
            return;
        }

        screen.fillWithColor(colorByte);
        byte[][] mapData = createFillFrame(screen, colorByte);
        writeColorToServerMapData(screen, colorByte);
        if (screen.hasValidOrigin()) {
            screen.updateViewerCache();
        }
        PacketDispatcher dispatcher =
            new PacketDispatcher(plugin);
        if (recipients == null) {
            dispatcher.dispatchFullFrame(screen, mapData);
        } else {
            dispatcher.dispatchFullFrame(screen, mapData, recipients);
        }
        if (scheduleResends) screen.clearViewerCacheIfStopped();
        if (scheduleResends) {
            scheduleFullFrameDispatch(screen, 5L);
            scheduleFullFrameDispatch(screen, 20L);
            scheduleFullFrameDispatch(screen, 60L);
        }
    }

    private byte[][] createFillFrame(Screen screen, byte colorByte) {
        byte[][] mapData = new byte[screen.getTotalMaps()][];
        byte[] fillData = new byte[Screen.MAP_SIZE * Screen.MAP_SIZE];
        Arrays.fill(fillData, colorByte);

        for (int i = 0; i < screen.getTotalMaps(); i++) {
            mapData[i] = fillData;
        }
        return mapData;
    }

    private void scheduleFullFrameDispatch(Screen screen, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (screen.hasValidOrigin()) {
                screen.updateViewerCache();
            }
            PacketDispatcher dispatcher = new PacketDispatcher(plugin);
            for (org.bukkit.entity.Player player : screen.getViewers()) {
                dispatcher.sendLastFrameToPlayer(player, screen);
            }
            screen.clearViewerCacheIfStopped();
        }, delayTicks);
    }

    private void writeColorToServerMapData(Screen screen, byte colorByte) {
        World world = screen.getOrigin() != null ? screen.getOrigin().getWorld() : null;
        if (!(world instanceof CraftWorld craftWorld)) {
            return;
        }

        for (MapTile tile : screen.getTiles()) {
            MapItemSavedData mapData = craftWorld.getHandle().getMapData(new MapId(tile.getMapId()));
            if (mapData == null) {
                continue;
            }

            for (int y = 0; y < Screen.MAP_SIZE; y++) {
                for (int x = 0; x < Screen.MAP_SIZE; x++) {
                    mapData.setColor(x, y, colorByte);
                }
            }
        }
    }
}

