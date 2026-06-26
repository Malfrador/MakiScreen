package de.erethon.mccinema.screen;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.video.PacketDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ScreenManager {

    private final MCCinema plugin;
    private final Map<UUID, Screen> screens = new ConcurrentHashMap<>();
    private final Map<String, Screen> screensByName = new ConcurrentHashMap<>();
    private final File screensFile;

    public ScreenManager(MCCinema plugin) {
        this.plugin = plugin;
        this.screensFile = new File(plugin.getDataFolder(), "screens.yml");
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

    public void deleteScreen(Screen screen) {
        unregisterScreen(screen);
        saveScreens();
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

