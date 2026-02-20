package de.erethon.mccinema;

import de.erethon.mccinema.commands.MCommandCache;
import de.erethon.mccinema.dither.DitherLookupUtil;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.resourcepack.ResourcePackManager;
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
    private ResourcePackManager resourcePackManager;
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
        // Copy any missing keys from the default config (e.g. newly added options)
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

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
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
