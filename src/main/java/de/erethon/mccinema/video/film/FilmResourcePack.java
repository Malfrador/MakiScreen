package de.erethon.mccinema.video.film;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.resourcepack.ResourcePackManager.HostedResourcePack;
import de.erethon.mccinema.screen.Screen;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class FilmResourcePack implements Listener {
    private record PackKey(ClientVersion version, int width, int height, boolean experimental) {
        boolean fullscreen() { return width > 0; }
    }

    private enum ClientVersion {
        V26_2("26.2", 776), V26_3("26.3", 777);

        private final String name;
        private final int protocol;

        ClientVersion(String name, int protocol) {
            this.name = name;
            this.protocol = protocol;
        }

        static ClientVersion forProtocol(int protocol) {
            for (ClientVersion version : values()) {
                if (version.protocol == protocol) return version;
            }
            return null;
        }
    }

    private record DecoderPack(UUID id, HostedResourcePack hosted) {}

    private final MCCinema plugin;
    private volatile Map<PackKey, DecoderPack> packs = Map.of();
    private final Map<UUID, PackKey> requested = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loaded = new ConcurrentHashMap<>();
    private final Map<UUID, PackKey> loadedPack = new ConcurrentHashMap<>();
    private final Set<UUID> unsupported = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private boolean warnedViaApi;

    public FilmResourcePack(MCCinema plugin) {
        this.plugin = plugin;
    }

    public synchronized void prepare() throws IOException {
        if (plugin.getResourcePackManager() == null) throw new IOException("Resource-pack hosting is disabled");
        Map<PackKey, DecoderPack> prepared = new java.util.HashMap<>(packs);
        for (ClientVersion version : ClientVersion.values()) {
            PackKey key = new PackKey(version, 0, 0, true);
            if (!prepared.containsKey(key)) prepared.put(key, prepare(key));
        }
        packs = Map.copyOf(prepared);
    }

    public synchronized void prepareFullscreen(Screen screen, boolean experimental) throws IOException {
        if (plugin.getResourcePackManager() == null) throw new IOException("Resource-pack hosting is disabled");
        PackKey key = new PackKey(ClientVersion.V26_3, screen.getPixelWidth(), screen.getPixelHeight(), experimental);
        if (packs.containsKey(key)) return;
        Map<PackKey, DecoderPack> updated = new java.util.HashMap<>(packs);
        updated.put(key, prepare(key));
        packs = Map.copyOf(updated);
    }

    private DecoderPack prepare(PackKey packKey) throws IOException {
        ClientVersion version = packKey.version();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            java.util.List<String> paths = new java.util.ArrayList<>(java.util.List.of(
                "pack.mcmeta", "assets/minecraft/shaders/core/text.fsh"));
            if (packKey.experimental()) paths.add("assets/minecraft/shaders/include/mcc_film.glsl");
            if (packKey.fullscreen()) paths.addAll(java.util.List.of(
                "assets/minecraft/shaders/core/text.vsh",
                "assets/minecraft/shaders/include/mcc_film_vertex.glsl",
                "assets/mcc/post_effect/fullscreen.json",
                "assets/mcc/shaders/post/copy_current.fsh",
                "assets/mcc/shaders/post/fullscreen_present.fsh"));
            if (packKey.fullscreen() && packKey.experimental()) paths.addAll(java.util.List.of(
                "assets/mcc/shaders/post/fullscreen_decode.fsh",
                "assets/mcc/shaders/post/anchor_update.fsh"));
            for (String path : paths) {
                String source = packKey.fullscreen() && !packKey.experimental() && path.endsWith("text.fsh")
                    ? "film-pack/26.3-fullscreen/assets/minecraft/shaders/core/text-map.fsh"
                    : packKey.fullscreen() && !packKey.experimental() && path.endsWith("fullscreen.json")
                    ? "film-pack/26.3-fullscreen/assets/mcc/post_effect/fullscreen-map.json"
                    : path.endsWith("mcc_film.glsl")
                    ? "film-pack/" + path
                    : packKey.fullscreen() && !path.equals("pack.mcmeta") && !path.endsWith("text.fsh")
                        ? "film-pack/26.3-fullscreen/" + path
                        : "film-pack/" + version.name + "/" + path;
                try (var resource = plugin.getResource(source)) {
                    if (resource == null) throw new IOException("Missing decoder resource: " + path);
                    ZipEntry entry = new ZipEntry(path);
                    entry.setTime(0); // Stable bytes and content-addressed host/cache key.
                    zip.putNextEntry(entry);
                    if (path.endsWith("fullscreen.json")) {
                        String shader = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("@FRAME_WIDTH@", Integer.toString(packKey.width()))
                            .replace("@FRAME_HEIGHT@", Integer.toString(packKey.height()));
                        zip.write(shader.getBytes(StandardCharsets.UTF_8));
                    } else {
                        resource.transferTo(zip);
                    }
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
        String key = (packKey.experimental() ? "film_" : "maps_") + version.name.replace('.', '_')
            + (packKey.fullscreen() ? "_fullscreen_" + packKey.width() + "x" + packKey.height() : "")
            + "_" + digest;
        var file = plugin.getDataFolder().toPath().resolve(key + ".zip");
        Files.write(file, content);
        HostedResourcePack result = plugin.getResourcePackManager().hostResourcePack(key, file.toFile(), () -> {}, () -> {});
        if (result == null) throw new IOException("Could not host the " + version.name + " film decoder pack");
        return new DecoderPack(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), result);
    }

    public void request(Player player) {
        request(player, false, null, true);
    }

    public void request(Player player, boolean fullscreen, Screen screen, boolean experimental) {
        UUID playerId = player.getUniqueId();
        if (packs.isEmpty() || unsupported.contains(playerId)) return;
        int protocol = clientProtocol(player);
        if (protocol <= 0) return; // The connection may not have its protocol attached yet.
        ClientVersion version = ClientVersion.forProtocol(protocol);
        if (version == null) {
            if (unsupported.add(playerId)) {
                player.sendMessage("Decoder playback supports Minecraft 26.2 and 26.3 clients (protocol "
                    + protocol + " detected).");
            }
            return;
        }
        if (fullscreen && version != ClientVersion.V26_3) {
            if (unsupported.add(playerId)) player.sendMessage("Fullscreen playback requires a Minecraft 26.3 client.");
            return;
        }
        PackKey packKey = fullscreen
            ? new PackKey(version, screen.getPixelWidth(), screen.getPixelHeight(), experimental)
            : new PackKey(version, 0, 0, true);
        DecoderPack pack = packs.get(packKey);
        if (pack == null || packKey.equals(requested.get(playerId))) return;
        PackKey old = requested.put(playerId, packKey);
        if (old != null) {
            DecoderPack oldPack = packs.get(old);
            if (oldPack != null) player.removeResourcePack(oldPack.id());
        }
        loaded.remove(playerId);
        loadedPack.remove(playerId);
        try {
            player.addResourcePack(pack.id(), pack.hosted().url(), pack.hosted().hash(),
                (fullscreen ? "Fullscreen playback" : "Experimental film")
                    + " needs the Minecraft " + version.name + " resource pack", false);
        } catch (RuntimeException failure) {
            requested.remove(playerId, packKey);
            loaded.remove(playerId);
            plugin.getLogger().warning("Could not send film decoder: " + failure.getMessage());
        }
    }

    private int clientProtocol(Player player) {
        var viaPlugin = plugin.getServer().getPluginManager().getPlugin("ViaVersion");
        if (viaPlugin != null && viaPlugin.isEnabled()) {
            try {
                int protocol = ViaVersionClientProtocol.get(player);
                if (protocol > 0) return protocol;
            } catch (LinkageError | RuntimeException failure) {
                if (!warnedViaApi) {
                    warnedViaApi = true;
                    plugin.getLogger().warning("Could not query ViaVersion client protocol; using Paper: "
                        + failure.getMessage());
                }
            }
        }
        return player.getProtocolVersion();
    }

    public long loadedGeneration(Player player) {
        return loaded.getOrDefault(player.getUniqueId(), 0L);
    }

    public boolean isLoaded(Player player) {
        return loadedGeneration(player) != 0;
    }

    public boolean isUnsupported(Player player) {
        return unsupported.contains(player.getUniqueId());
    }

    public boolean isLoaded(Player player, boolean fullscreen, Screen screen, boolean experimental) {
        PackKey key = loadedPack.get(player.getUniqueId());
        return key != null && key.fullscreen() == fullscreen
            && (!fullscreen || key.experimental() == experimental)
            && (!fullscreen || key.width() == screen.getPixelWidth() && key.height() == screen.getPixelHeight())
            && isLoaded(player);
    }

    public boolean isDecoderPack(UUID id) {
        return id != null && packs.values().stream().anyMatch(pack -> id.equals(pack.id()));
    }

    public void allowRetry(Player player) {
        if (!isLoaded(player)) requested.remove(player.getUniqueId());
        unsupported.remove(player.getUniqueId());
    }

    public void releaseFullscreen(Player player) {
        UUID id = player.getUniqueId();
        PackKey key = requested.get(id);
        if (key == null || !key.fullscreen() || !requested.remove(id, key)) return;
        loaded.remove(id);
        loadedPack.remove(id);
        DecoderPack pack = packs.get(key);
        if (pack != null) player.removeResourcePack(pack.id());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        PackKey packKey = null;
        for (var entry : packs.entrySet()) {
            if (entry.getValue().id().equals(event.getID())) {
                packKey = entry.getKey();
                break;
            }
        }
        if (packKey == null) return;
        UUID id = event.getPlayer().getUniqueId();
        if (!packKey.equals(requested.get(id))) return;
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                loadedPack.put(id, packKey);
                loaded.put(id, generation.incrementAndGet());
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                loaded.remove(id);
                loadedPack.remove(id);
                event.getPlayer().sendMessage("Video playback is unavailable until its decoder pack loads.");
            }
            default -> {
                loaded.remove(id);
                loadedPack.remove(id);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requested.remove(event.getPlayer().getUniqueId());
        loaded.remove(event.getPlayer().getUniqueId());
        loadedPack.remove(event.getPlayer().getUniqueId());
        unsupported.remove(event.getPlayer().getUniqueId());
    }
}
