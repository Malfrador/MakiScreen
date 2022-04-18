package cat.maki.makiscreen;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public class ConfigFile extends BukkitRunnable {
    private static int mapSize;
    private static int mapWidth;
    private static int VCWidth;
    private static int VCHeight;
    private FileConfiguration config;
    public Plugin plugin;

    //create config file if it doesn't exist
    public ConfigFile(@NotNull Plugin plugin) {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveDefaultConfig();
        }
        config = plugin.getConfig();
    }

    @Override
    public void run() {
        this.config = plugin.getConfig();
        if (config.getInt("size") != 0) {
            ConfigSize(config.getInt("size"));
        } else {
            config.addDefault("size", 1);
        }
    }


    private void ConfigSize(int size) {
        switch (size) {
            case 1 -> {
                mapSize = 2;
                mapWidth = 2;
                VCWidth = 128 * 2;
                VCHeight = 128;
            }
            case 2 -> {
                mapSize = 8;
                mapWidth = 4;
                VCWidth = 128 * 4;
                VCHeight = 128 * 2;
            }
            case 3 -> {
                mapSize = 32;
                mapWidth = 8;
                VCWidth = 128 * 8;
                VCHeight = 128 * 4;
            }
        }

    }

    public static int getMapSize() {
        return mapSize;
    }

    public static int getMapWidth() {
        return mapWidth;
    }

    public static int getVCWidth() {
        return VCWidth;
    }

    public static int getVCHeight() {
        return VCHeight;
    }


}
