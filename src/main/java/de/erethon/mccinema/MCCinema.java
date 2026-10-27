package de.erethon.mccinema;

import de.erethon.mccinema.commands.MCCCommandBrigadier;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.dither.DitherLookupUtil;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.resourcepack.ResourcePackManager;
import de.erethon.mccinema.resourcepack.ResourcePackManager.HostedResourcePack;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.screen.ScreenManager;
import de.erethon.mccinema.video.*;
import de.erethon.bedrock.compatibility.Internals;
import de.erethon.bedrock.plugin.EPlugin;
import de.erethon.bedrock.plugin.EPluginSettings;
import de.erethon.mccinema.video.film.FilmResourcePack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;


public final class MCCinema extends EPlugin implements Listener {

    private final Logger logger = getLogger();
    private static MCCinema instance;
    private ScreenManager screenManager;
    private ResourcePackManager resourcePackManager;
    private YoutubeDownloadManager youtubeDownloadManager;
    private ResourcePackListener resourcePackListener;
    private FilmResourcePack filmResourcePack;
    private VideoTransformManager videoTransformManager;
    private SubtitleSettingsManager subtitleSettingsManager;
    private VideoTransformEditor videoTransformEditor;
    private final Map<UUID, VideoPlayer> videoPlayers = new ConcurrentHashMap<>();

    public MCCinema() {
        instance = this;
        settings = EPluginSettings.builder()
                .internals(Internals.NEW)
                .economy(false)
                .build();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS.newHandler(event ->
            MCCCommandBrigadier.register(event.registrar())
        ));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        instance = this;

        configureFfmpegLogging();

        saveDefaultConfig();
        reloadConfig();
        boolean configDirty = false;
        for (String key : getConfig().getDefaults().getKeys(true)) {
            if (!getConfig().isSet(key) && !(getConfig().getDefaults().get(key) instanceof org.bukkit.configuration.ConfigurationSection)) {
                getConfig().set(key, getConfig().getDefaults().get(key));
                configDirty = true;
            }
        }
        if (configDirty) {
            saveConfig();
            logger.info("Config updated with new default values.");
        }
        new File(getDataFolder(), "videos").mkdirs();
        new File(getDataFolder(), "audio").mkdirs();
        new File(getDataFolder(), "resourcepack").mkdirs();

        logger.info("Initializing color lookup tables... This can take a few seconds, please wait.");
        DitherLookupUtil.init();

        screenManager = new ScreenManager(this);
        screenManager.loadScreens();
        videoTransformManager = new VideoTransformManager(this);
        subtitleSettingsManager = new SubtitleSettingsManager(this);

        youtubeDownloadManager = new YoutubeDownloadManager(this);

        if (getConfig().getBoolean("resourcepack.enabled", true)) {
            // Determine hosting mode
            String modeStr = getConfig().getString("resourcepack.mode", "MCPACKS").toUpperCase();
            ResourcePackManager.HostingMode mode;
            try {
                mode = ResourcePackManager.HostingMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid resourcepack.mode in config: " + modeStr + ", defaulting to MCPACKS");
                mode = ResourcePackManager.HostingMode.MCPACKS;
            }

            String address = getConfig().getString("resourcepack.local.address", "localhost");
            int port = getConfig().getInt("resourcepack.local.port", 8080);

            resourcePackManager = new ResourcePackManager(this, mode, address, port);

            logger.info("Resource pack configuration:");
            logger.info("  Mode: " + mode);
            if (mode == ResourcePackManager.HostingMode.LOCAL) {
                logger.info("  Local Address: " + address);
                logger.info("  Local Port: " + port);
            }
            logger.info("  Auto-apply: " + getConfig().getBoolean("resourcepack.auto-apply", true));
            logger.info("  Required: " + getConfig().getBoolean("resourcepack.required", false));
        } else {
            logger.info("Resource pack hosting is disabled in config");
        }

        getServer().getPluginManager().registerEvents(this, this);
        resourcePackListener = new ResourcePackListener(this);
        getServer().getPluginManager().registerEvents(resourcePackListener, this);
        filmResourcePack = new de.erethon.mccinema.video.film.FilmResourcePack(this);
        getServer().getPluginManager().registerEvents(filmResourcePack, this);
        for (Player player : getServer().getOnlinePlayers()) {
            recoverFullscreenCamera(player);
        }
        videoTransformEditor = new VideoTransformEditor(this);
        getServer().getPluginManager().registerEvents(videoTransformEditor, this);
        logger.info("MCCinema enabled!");
        logger.info("  Screens loaded: " + screenManager.getAllScreens().size());
    }

    private void configureFfmpegLogging() {
        try {
            FFmpegLogCallback.setLevel(avutil.AV_LOG_WARNING);
            avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        } catch (Throwable t) {
            logger.warning("Could not configure FFmpeg log level: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (videoTransformEditor != null) {
            videoTransformEditor.shutdown();
        }
        for (VideoPlayer player : videoPlayers.values()) {
            player.shutdown();
        }
        videoPlayers.clear();
        if (resourcePackManager != null) {
            resourcePackManager.shutdown();
        }
        if (screenManager != null) {
            screenManager.saveScreens();
        }
        logger.info("MCCinema disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        recoverFullscreenCamera(event.getPlayer());
        resendAudioPacksAfterJoin(event.getPlayer());
        // Send last frame to joining players
        resendScreenFramesAfterJoin(event.getPlayer(), 20L);
        resendScreenFramesAfterJoin(event.getPlayer(), 60L);
        resendScreenFramesAfterJoin(event.getPlayer(), 120L);
    }

    private void recoverFullscreenCamera(Player player) {
        // Restore after the join callback, when the persisted player state is in place.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                de.erethon.mccinema.video.film.FilmSpectatorCamera.restoreMarkedViewer(this, player);
            }
        }, 1L);
    }

    private void resendAudioPacksAfterJoin(Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || resourcePackManager == null
                    || !getConfig().getBoolean("resourcepack.auto-apply", true)) return;

                Set<String> sentVideoIds = new HashSet<>();
                boolean required = getConfig().getBoolean("resourcepack.required", false);
                for (Screen screen : screenManager.getAllScreens()) {
                    VideoPlayer videoPlayer = getVideoPlayer(screen);
                    if (videoPlayer == null || (videoPlayer.getState() != VideoPlayer.State.PLAYING
                        && videoPlayer.getState() != VideoPlayer.State.PAUSED)) continue;

                    AudioManager audio = videoPlayer.getAudioManager();
                    if (audio == null) continue;
                    screen.updateViewerCache();
                    if (!videoPlayer.getPlaybackViewers().contains(player)) continue;

                    String videoId = audio.getVideoId();
                    if (sentVideoIds.contains(videoId)) continue;
                    HostedResourcePack pack = resourcePackManager.getHostedPack(videoId);
                    if (pack == null) continue;

                    player.addResourcePack(UUID.randomUUID(), pack.url(), pack.hash(),
                        "This video requires a resource pack for audio playback", required);
                    sentVideoIds.add(videoId);
                }
            }
        }.runTaskLater(this, 20L);
    }

    private void resendScreenFramesAfterJoin(Player player, long delayTicks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }

                PacketDispatcher dispatcher = new PacketDispatcher(MCCinema.this);
                for (Screen screen : screenManager.getAllScreens()) {
                    VideoPlayer videoPlayer = getVideoPlayer(screen);
                    if (videoPlayer != null && !videoPlayer.canSendTo(player)) {
                        continue;
                    }
                    dispatcher.sendLastFrameToPlayer(player, screen);
                }
            }
        }.runTaskLater(this, delayTicks);
    }

    public static MCCinema getInstance() {
        return instance;
    }

    public ScreenManager getScreenManager() {
        return screenManager;
    }

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
    }

    public YoutubeDownloadManager getYoutubeDownloadManager() {
        return youtubeDownloadManager;
    }

    public VideoTransformManager getVideoTransformManager() {
        return videoTransformManager;
    }

    public SubtitleSettingsManager getSubtitleSettingsManager() {
        return subtitleSettingsManager;
    }

    public VideoTransformEditor getVideoTransformEditor() {
        return videoTransformEditor;
    }

    public void registerVideoPlayer(Screen screen, VideoPlayer player) {
        VideoPlayer previous = videoPlayers.put(screen.getId(), player);
        if (previous != null && previous != player) {
            previous.shutdown();
        }
    }

    public void unregisterVideoPlayer(Screen screen) {
        VideoPlayer player = videoPlayers.remove(screen.getId());
        if (player != null) {
            player.shutdown();
        }
    }

    public VideoPlayer getVideoPlayer(Screen screen) {
        return videoPlayers.get(screen.getId());
    }

    public ResourcePackListener getResourcePackListener() {
        return resourcePackListener;
    }

    public FilmResourcePack getFilmResourcePack() {
        return filmResourcePack;
    }
}
