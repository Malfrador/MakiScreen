package cat.maki.makiscreen.screen;

import cat.maki.makiscreen.MakiScreen;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ScreenManager {

    private final MakiScreen plugin;
    private final Map<UUID, Screen> screens = new ConcurrentHashMap<>();
    private final Map<String, Screen> screensByName = new ConcurrentHashMap<>();
    private final File screensFile;

    public ScreenManager(MakiScreen plugin) {
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
}

