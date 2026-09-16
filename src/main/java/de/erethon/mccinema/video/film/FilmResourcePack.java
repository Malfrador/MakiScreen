package de.erethon.mccinema.video.film;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.resourcepack.ResourcePackManager.HostedResourcePack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FilmResourcePack implements Listener {
    private final MCCinema plugin;
    private volatile HostedResourcePack hosted;
    private volatile UUID packId;
    private final Map<UUID, Long> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loaded = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public FilmResourcePack(MCCinema plugin) {
        this.plugin = plugin;
    }

    public synchronized void prepare() throws IOException {
        if (hosted != null) return;
        if (plugin.getResourcePackManager() == null) throw new IOException("Resource-pack hosting is disabled");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String path : new String[]{"pack.mcmeta", "assets/minecraft/shaders/core/text.fsh",
                    "assets/minecraft/shaders/include/mcc_film.glsl"}) {
                try (var resource = plugin.getResource("film-pack/" + path)) {
                    if (resource == null) throw new IOException("Missing decoder resource: " + path);
                    ZipEntry entry = new ZipEntry(path);
                    entry.setTime(0); // Stable bytes and content-addressed host/cache key.
                    zip.putNextEntry(entry);
                    resource.transferTo(zip);
                    zip.closeEntry();
                }
            }
        }
        byte[] content = bytes.toByteArray();
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String key = "film_26_2_" + digest;
        var file = plugin.getDataFolder().toPath().resolve(key + ".zip");
        Files.write(file, content);
        HostedResourcePack result = plugin.getResourcePackManager().hostResourcePack(key, file.toFile(), () -> {}, () -> {});
        if (result == null) throw new IOException("Could not host the film decoder pack");
        packId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        hosted = result;
    }

    public void request(Player player) {
        if (hosted == null || requested.putIfAbsent(player.getUniqueId(), System.nanoTime()) != null) return;
        try {
            player.addResourcePack(packId, hosted.url(), hosted.hash(),
                "Experimental film playback needs the Minecraft 26.2 decoder pack", false);
        } catch (RuntimeException failure) {
            loaded.remove(player.getUniqueId());
            plugin.getLogger().warning("Could not send film decoder: " + failure.getMessage());
        }
    }

    public long loadedGeneration(Player player) {
        return loaded.getOrDefault(player.getUniqueId(), 0L);
    }

    public boolean isLoaded(Player player) {
        return loadedGeneration(player) != 0;
    }

    public boolean isDecoderPack(UUID id) {
        return id != null && id.equals(packId);
    }

    public void allowRetry(Player player) {
        if (!isLoaded(player)) requested.remove(player.getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!event.getID().equals(packId)) return;
        UUID id = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.put(id, generation.incrementAndGet());
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                loaded.remove(id);
                event.getPlayer().sendMessage("Experimental film video is unavailable until its decoder pack loads.");
            }
            default -> loaded.remove(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requested.remove(event.getPlayer().getUniqueId());
        loaded.remove(event.getPlayer().getUniqueId());
    }
}
