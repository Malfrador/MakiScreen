package de.erethon.mccinema.video;

import de.erethon.mccinema.MCCinema;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** Persistent per-video subtitle defaults, keyed like saved crop settings. */
public final class SubtitleSettingsManager {
    public record Setting(boolean enabled, String track) {
        public static final Setting DEFAULT = new Setting(false, null);
    }

    private final MCCinema plugin;
    private final File dataFile;
    private final Map<String, Setting> settings = new HashMap<>();
    private final Map<String, String> sources = new HashMap<>();

    public SubtitleSettingsManager(MCCinema plugin) {
        this.plugin = plugin;
        dataFile = new File(plugin.getDataFolder(), "video-subtitles.yml");
        load();
    }

    public synchronized Setting get(File video, Setting defaultSetting) {
        return settings.getOrDefault(key(video), defaultSetting);
    }

    public synchronized boolean save(File video, Setting setting) {
        String id = key(video);
        settings.put(id, setting);
        sources.put(id, identity(video));
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Setting> entry : settings.entrySet()) {
            String prefix = "videos." + entry.getKey();
            config.set(prefix + ".source", sources.get(entry.getKey()));
            config.set(prefix + ".enabled", entry.getValue().enabled());
            config.set(prefix + ".track", entry.getValue().track());
        }
        try {
            config.save(dataFile);
            return true;
        } catch (IOException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not save subtitle settings", error);
            return false;
        }
    }

    private void load() {
        if (!dataFile.isFile()) return;
        ConfigurationSection videos = YamlConfiguration.loadConfiguration(dataFile)
            .getConfigurationSection("videos");
        if (videos == null) return;
        for (String id : videos.getKeys(false)) {
            ConfigurationSection entry = videos.getConfigurationSection(id);
            if (entry == null) continue;
            settings.put(id, new Setting(entry.getBoolean("enabled", false), entry.getString("track")));
            sources.put(id, entry.getString("source", id));
        }
    }

    private String key(File video) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity(video).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Could not identify video file", error);
        }
    }

    private String identity(File video) {
        try {
            Path videos = new File(plugin.getDataFolder(), "videos").getCanonicalFile().toPath();
            Path source = video.getCanonicalFile().toPath();
            String value = source.startsWith(videos) ? videos.relativize(source).toString() : source.toString();
            value = value.replace('\\', '/');
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? value.toLowerCase(Locale.ROOT) : value;
        } catch (IOException error) {
            return video.getAbsolutePath().replace('\\', '/');
        }
    }
}
