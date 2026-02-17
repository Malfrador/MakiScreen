package de.erethon.mccinema;

import de.erethon.mccinema.commands.MCommandCache;
import de.erethon.mccinema.dither.DitherLookupUtil;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.resourcepack.ResourcePackServer;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.screen.ScreenManager;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.compatibility.Internals;
import de.erethon.bedrock.plugin.EPlugin;
import de.erethon.bedrock.plugin.EPluginSettings;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;


public final class MCCinema extends EPlugin implements Listener {

    private final Logger logger = getLogger();
    private static MCCinema instance;
    private MCommandCache commands;

    private ScreenManager screenManager;
    private ResourcePackServer resourcePackServer;
    private YoutubeDownloadManager youtubeDownloadManager;
    private ResourcePackListener resourcePackListener;
    private final Map<UUID, VideoPlayer> videoPlayers = new ConcurrentHashMap<>();

    public MCCinema() {
        settings = EPluginSettings.builder()
                .internals(Internals.NEW)
                .economy(false)
                .build();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        instance = this;

        //FFmpegLogCallback.set(); Very spammy, only enable for debugging

        saveDefaultConfig();
        reloadConfig();
        new File(getDataFolder(), "videos").mkdirs();
        new File(getDataFolder(), "audio").mkdirs();
        new File(getDataFolder(), "resourcepack").mkdirs();

        logger.info("Initializing color lookup tables... This can take a few seconds, please wait.");
        DitherLookupUtil.init();

        screenManager = new ScreenManager(this);
        screenManager.loadScreens();

        youtubeDownloadManager = new YoutubeDownloadManager(this);

        if (getConfig().getBoolean("resourcepack.enabled", true)) {
            String address = getConfig().getString("resourcepack.address", "localhost");
            int port = getConfig().getInt("resourcepack.port", 8080);

            resourcePackServer = new ResourcePackServer(this, address, port);
            resourcePackServer.start();

            logger.info("Resource pack server configuration:");
            logger.info("  Address: " + address);
            logger.info("  Port: " + port);
            logger.info("  Auto-apply: " + getConfig().getBoolean("resourcepack.auto-apply", true));
            logger.info("  Required: " + getConfig().getBoolean("resourcepack.required", false));
        } else {
            logger.info("Resource pack server is disabled in config");
        }

        commands = new MCommandCache(this);
        commands.register(this);
        setCommandCache(commands);
        getServer().getPluginManager().registerEvents(this, this);
        resourcePackListener = new ResourcePackListener(this);
        getServer().getPluginManager().registerEvents(resourcePackListener, this);
        logger.info("MCCinema enabled!");
        logger.info("  Screens loaded: " + screenManager.getAllScreens().size());
    }

    @Override
    public void onDisable() {
        for (VideoPlayer player : videoPlayers.values()) {
            player.shutdown();
        }
        videoPlayers.clear();
        if (resourcePackServer != null) {
            resourcePackServer.stop();
        }
        if (screenManager != null) {
            screenManager.saveScreens();
        }
        logger.info("MCCinema disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Send last frame to joining players
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = event.getPlayer();
                for (VideoPlayer vp : videoPlayers.values()) {
                    if (vp.getState() == VideoPlayer.State.PLAYING ||
                        vp.getState() == VideoPlayer.State.PAUSED) {
                        vp.getPacketDispatcher().sendLastFrameToPlayer(player, vp.getScreen());
                    }
                }
            }
        }.runTaskLater(this, 20L);
    }

    public static MCCinema getInstance() {
        return instance;
    }

    public ScreenManager getScreenManager() {
        return screenManager;
    }

    public ResourcePackServer getResourcePackServer() {
        return resourcePackServer;
    }

    public YoutubeDownloadManager getYoutubeDownloadManager() {
        return youtubeDownloadManager;
    }

    public void registerVideoPlayer(Screen screen, VideoPlayer player) {
        videoPlayers.put(screen.getId(), player);
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
}
