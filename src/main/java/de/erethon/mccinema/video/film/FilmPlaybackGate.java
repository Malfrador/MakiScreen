package de.erethon.mccinema.video.film;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.video.VideoPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class FilmPlaybackGate {
    private final MCCinema plugin;
    private final VideoPlayer video;
    private final CommandSender sender;
    private volatile BukkitTask monitor;
    private volatile boolean startupReleased;
    private long deadline;
    private final java.util.Map<java.util.UUID, Long> pausedViewers = new java.util.concurrent.ConcurrentHashMap<>();

    public FilmPlaybackGate(MCCinema plugin, VideoPlayer video, CommandSender sender) {
        this.plugin = plugin;
        this.video = video;
        this.sender = sender;
    }

    public boolean beforePlay() {
        if (startupReleased) return true;
        if (monitor != null) return false;
        video.getScreen().updateViewerCache();
        for (Player viewer : video.getPlaybackViewers()) plugin.getFilmResourcePack().allowRetry(viewer);
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        sender.sendMessage("Experimental film mode: waiting for the Minecraft 26.2 decoder pack.");
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
        for (Player viewer : viewers) {
            plugin.getFilmResourcePack().request(viewer);
            if (plugin.getFilmResourcePack().isLoaded(viewer)) ready++;
            if (video.getState() == VideoPlayer.State.PAUSED) {
                long generation = plugin.getFilmResourcePack().loadedGeneration(viewer);
                if (generation != 0 && !java.util.Objects.equals(pausedViewers.put(viewer.getUniqueId(), generation), generation)) {
                    video.getPacketDispatcher().sendLastFrameToPlayer(viewer, video.getScreen());
                }
            }
        }
        if (startupReleased) return;
        boolean timedOut = System.nanoTime() >= deadline;
        if (ready > 0 && (ready == viewers.size() || timedOut)) {
            startupReleased = true;
            sender.sendMessage("Experimental film playback starting for " + ready + " viewer(s) with the decoder loaded.");
            video.play();
        } else if (timedOut) {
            sender.sendMessage("Experimental playback cancelled: no viewer loaded the decoder pack within 60 seconds.");
            video.stop();
        }
    }

    public void cancel() {
        if (monitor != null) monitor.cancel();
        monitor = null;
        startupReleased = false;
        pausedViewers.clear();
    }
}
