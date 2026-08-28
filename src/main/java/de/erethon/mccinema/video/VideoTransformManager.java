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

public final class VideoTransformManager {

    private final MCCinema plugin;
    private final File dataFile;
    private final Map<String, VideoTransform> transforms = new HashMap<>();
    private final Map<String, String> sources = new HashMap<>();

    public VideoTransformManager(MCCinema plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "video-transforms.yml");
        load();
    }

    public synchronized VideoTransform get(File videoFile) {
        String id = key(videoFile);
        sources.putIfAbsent(id, normalizedIdentity(videoFile));
        return transforms.getOrDefault(id, VideoTransform.DEFAULT);
    }

    public synchronized boolean save(File videoFile, VideoTransform transform) {
        String id = key(videoFile);
        transforms.put(id, transform);
        sources.put(id, normalizedIdentity(videoFile));
        return saveAll();
    }

    private void load() {
        if (!dataFile.isFile()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection videos = config.getConfigurationSection("videos");
        if (videos == null) {
            return;
        }
        for (String id : videos.getKeys(false)) {
            ConfigurationSection entry = videos.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            transforms.put(id, new VideoTransform(
                entry.getDouble("zoom", 1.0),
                entry.getDouble("focus-x", 0.5),
                entry.getDouble("focus-y", 0.5)
            ));
            sources.put(id, entry.getString("source", id));
        }
    }

    private boolean saveAll() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, VideoTransform> entry : transforms.entrySet()) {
            String path = "videos." + entry.getKey();
            VideoTransform transform = entry.getValue();
            config.set(path + ".source", sources.get(entry.getKey()));
            config.set(path + ".zoom", transform.zoom());
            config.set(path + ".focus-x", transform.focusX());
            config.set(path + ".focus-y", transform.focusY());
        }
        try {
            config.save(dataFile);
            return true;
        } catch (IOException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not save video crop settings", error);
            return false;
        }
    }

    private String key(File videoFile) {
        String identity = normalizedIdentity(videoFile);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Could not identify video file", error);
        }
    }

    private String normalizedIdentity(File videoFile) {
        try {
            Path videos = new File(plugin.getDataFolder(), "videos").getCanonicalFile().toPath();
            Path source = videoFile.getCanonicalFile().toPath();
            String identity = source.startsWith(videos) ? videos.relativize(source).toString() : source.toString();
            identity = identity.replace('\\', '/');
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                identity = identity.toLowerCase(Locale.ROOT);
            }
            return identity;
        } catch (IOException error) {
            return videoFile.getAbsolutePath().replace('\\', '/');
        }
    }
}
