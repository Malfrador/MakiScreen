package de.erethon.mccinema.resourcepack;

import de.erethon.mccinema.MCCinema;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ResourcePackManager {

    public enum HostingMode {
        MCPACKS,  // Use mcpacks.dev API (default)
        LOCAL     // Use local HTTP server
    }

    private final MCCinema plugin;
    private final HostingMode mode;

    private ResourcePackServer localServer;
    private MCPacksAPI mcpacksAPI;

    private final Map<String, HostedResourcePack> hostedPacks = new ConcurrentHashMap<>();

    private final File dataFile;
    private final YamlConfiguration data;

    public ResourcePackManager(MCCinema plugin, HostingMode mode, String localAddress, int localPort) {
        this.plugin = plugin;
        this.mode = mode;

        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);

        if (mode == HostingMode.LOCAL) {
            localServer = new ResourcePackServer(plugin, localAddress, localPort);
            localServer.start();
        } else {
            mcpacksAPI = new MCPacksAPI(plugin);
        }

        plugin.getLogger().info("Resource pack hosting mode: " + mode);
    }

    public HostedResourcePack hostResourcePack(String videoId, File packFile, Runnable onUploading, Runnable onWaiting) {
        if (!packFile.exists()) {
            plugin.getLogger().warning("Resource pack file does not exist: " + packFile.getName());
            return null;
        }

        // Check in-memory cache first
        HostedResourcePack existing = hostedPacks.get(videoId);
        if (existing != null) {
            plugin.getLogger().info("Resource pack already hosted for video: " + videoId);
            return existing;
        }

        // For mcpacks mode, check persisted data before uploading
        if (mode == HostingMode.MCPACKS) {
            HostedResourcePack persisted = loadPersistedPack(videoId);
            if (persisted != null) {
                plugin.getLogger().info("Loaded persisted mcpacks URL for video: " + videoId);
                hostedPacks.put(videoId, persisted);
                return persisted;
            }
        }

        HostedResourcePack pack;

        if (mode == HostingMode.MCPACKS) {
            pack = hostWithMCPacks(videoId, packFile, onUploading, onWaiting);
        } else {
            pack = hostWithLocalServer(videoId, packFile);
        }

        if (pack != null) {
            hostedPacks.put(videoId, pack);
            if (mode == HostingMode.MCPACKS) {
                persistPack(videoId, pack);
            }
        }

        return pack;
    }

    private HostedResourcePack hostWithMCPacks(String videoId, File packFile, Runnable onUploading, Runnable onWaiting) {
        MCPacksAPI.MCPacksResponse response = mcpacksAPI.uploadResourcePack(packFile, onUploading, onWaiting);

        if (response == null) {
            plugin.getLogger().severe("Failed to upload resource pack to mcpacks.dev");
            return null;
        }

        return new HostedResourcePack(
            videoId,
            response.downloadUrl(),
            response.getSha1Bytes(),
            HostingMode.MCPACKS
        );
    }

    private HostedResourcePack hostWithLocalServer(String videoId, File packFile) {
        localServer.registerResourcePack(videoId, packFile);

        String url = localServer.getResourcePackUrl(videoId);
        byte[] hash = localServer.getResourcePackHash(videoId);

        return new HostedResourcePack(videoId, url, hash, HostingMode.LOCAL);
    }

    private HostedResourcePack loadPersistedPack(String videoId) {
        String url = data.getString("mcpacks." + videoId + ".url");
        String sha1Hex = data.getString("mcpacks." + videoId + ".sha1");
        if (url == null || sha1Hex == null) {
            return null;
        }
        byte[] sha1Bytes = hexToBytes(sha1Hex);
        return new HostedResourcePack(videoId, url, sha1Bytes, HostingMode.MCPACKS);
    }

    private void persistPack(String videoId, HostedResourcePack pack) {
        YamlConfiguration current = YamlConfiguration.loadConfiguration(dataFile);
        current.set("mcpacks." + videoId + ".url", pack.url());
        current.set("mcpacks." + videoId + ".sha1", bytesToHex(pack.hash()));
        try {
            current.save(dataFile);
            data.set("mcpacks." + videoId + ".url", pack.url());
            data.set("mcpacks." + videoId + ".sha1", bytesToHex(pack.hash()));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to persist mcpacks data: " + e.getMessage());
        }
    }

    public HostedResourcePack getHostedPack(String videoId) {
        return hostedPacks.get(videoId);
    }

    public void shutdown() {
        if (localServer != null) {
            localServer.stop();
        }
        hostedPacks.clear();
    }

    public record HostedResourcePack(
        String videoId,
        String url,
        byte[] hash,
        HostingMode mode
    ) {}

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
