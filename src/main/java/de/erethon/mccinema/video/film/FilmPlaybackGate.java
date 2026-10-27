package de.erethon.mccinema.video.film;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.video.VideoPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class FilmPlaybackGate {
    private static final int PACK_LOAD_TIMEOUT_SECONDS = 180;
    private final MCCinema plugin;
    private final VideoPlayer video;
    private final CommandSender sender;
    private final boolean fullscreen;
    private FilmSpectatorCamera camera;
    private volatile BukkitTask monitor;
    private volatile boolean startupReleased;
    private long deadline;
    private final java.util.Map<java.util.UUID, Long> pausedViewers = new java.util.concurrent.ConcurrentHashMap<>();

    public FilmPlaybackGate(MCCinema plugin, VideoPlayer video, CommandSender sender, boolean fullscreen) {
        this.plugin = plugin;
        this.video = video;
        this.sender = sender;
        this.fullscreen = fullscreen;
    }

    public boolean beforePlay() {
        if (startupReleased) return true;
        if (monitor != null) return false;
        video.getScreen().updateViewerCache();
        for (Player viewer : video.getPlaybackViewers()) plugin.getFilmResourcePack().allowRetry(viewer);
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(PACK_LOAD_TIMEOUT_SECONDS);
        sender.sendMessage((fullscreen ? "Fullscreen playback" : "Experimental film")
            + ": waiting for the client-specific resource pack.");
        monitor = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, 10L);
        return false;
    }

    private void tick() {
        if (plugin.getVideoPlayer(video.getScreen()) != video) {
            cancel();
            return;
        }
        video.getScreen().updateViewerCache();
        var viewers = video.getPlaybackViewers();
        int ready = 0;
        int pending = 0;
        for (Player viewer : viewers) {
            boolean experimental = video.getFrameProcessor().isExperimentalFilm();
            plugin.getFilmResourcePack().request(viewer, fullscreen, video.getScreen(), experimental);
            boolean loaded = plugin.getFilmResourcePack().isLoaded(viewer, fullscreen, video.getScreen(), experimental);
            if (loaded) {
                ready++;
                if (fullscreen) {
                    try {
                        if (camera == null) camera = new FilmSpectatorCamera(plugin, video.getScreen(),
                            net.kyori.adventure.key.Key.key("mcc", "fullscreen"));
                        camera.add(viewer);
                    } catch (RuntimeException failure) {
                        sender.sendMessage("Could not start the fullscreen camera: " + failure.getMessage());
                        video.stop();
                        return;
                    }
                }
            } else if (!plugin.getFilmResourcePack().isUnsupported(viewer)) {
                pending++;
            }
            if (video.getState() == VideoPlayer.State.PAUSED) {
                long generation = plugin.getFilmResourcePack().loadedGeneration(viewer);
                if (generation != 0 && !java.util.Objects.equals(pausedViewers.put(viewer.getUniqueId(), generation), generation)) {
                    video.getPacketDispatcher().sendLastFrameToPlayer(viewer, video.getScreen());
                }
            }
        }
        if (fullscreen && ready > 0 && !video.hasTargetPlayerLimit()) {
            video.setTargetPlayerIds(viewers.stream().map(Player::getUniqueId).toList());
        }
        if (startupReleased) return;
        boolean timedOut = System.nanoTime() >= deadline;
        if (ready > 0 && (pending == 0 || timedOut)) {
            startupReleased = true;
            sender.sendMessage((fullscreen ? "Fullscreen playback" : "Experimental film playback")
                + " starting for " + ready + " viewer(s) with the resource pack loaded.");
            video.play();
        } else if (timedOut) {
            sender.sendMessage((fullscreen ? "Fullscreen playback" : "Experimental playback")
                + " cancelled: no viewer loaded the resource pack within "
                + PACK_LOAD_TIMEOUT_SECONDS + " seconds.");
            video.stop();
        }
    }

    public void cancel() {
        if (monitor != null) monitor.cancel();
        monitor = null;
        startupReleased = false;
        pausedViewers.clear();
        if (camera != null) camera.close();
        camera = null;
    }
}
