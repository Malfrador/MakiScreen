package cat.maki.makiscreen;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks resource pack loading status for players.
 * Used to delay video playback until all players have loaded the audio resource pack.
 */
public class ResourcePackListener implements Listener {

    private final MakiScreen plugin;

    // Maps a tracking ID to a set of players waiting for resource pack load
    private final Map<String, ResourcePackLoadTracker> trackers = new ConcurrentHashMap<>();

    public ResourcePackListener(MakiScreen plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();

        // Notify all active trackers
        for (ResourcePackLoadTracker tracker : trackers.values()) {
            tracker.onPlayerStatus(player.getUniqueId(), status);
        }
    }

    /**
     * Creates a tracker that waits for all specified players to load a resource pack.
     *
     * @param trackerId Unique identifier for this tracker
     * @param players Players to wait for
     * @param onComplete Callback when all players have loaded or declined
     * @param timeoutTicks Maximum time to wait (in ticks) before giving up
     */
    public void trackResourcePackLoad(String trackerId, Set<UUID> players,
                                      Consumer<Boolean> onComplete, long timeoutTicks) {
        ResourcePackLoadTracker tracker = new ResourcePackLoadTracker(
            trackerId, players, onComplete, timeoutTicks, plugin
        );
        trackers.put(trackerId, tracker);
        tracker.start();
    }

    /**
     * Cancels and removes a tracker.
     */
    public void cancelTracker(String trackerId) {
        ResourcePackLoadTracker tracker = trackers.remove(trackerId);
        if (tracker != null) {
            tracker.cancel();
        }
    }

    /**
     * Internal class that tracks resource pack loading for a set of players.
     */
    private class ResourcePackLoadTracker {
        private final String trackerId;
        private final Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
        private final Consumer<Boolean> onComplete;
        private final long timeoutTicks;
        private final MakiScreen plugin;
        private boolean completed = false;
        private int taskId = -1;

        public ResourcePackLoadTracker(String trackerId, Set<UUID> players,
                                      Consumer<Boolean> onComplete, long timeoutTicks,
                                      MakiScreen plugin) {
            this.trackerId = trackerId;
            this.waitingPlayers.addAll(players);
            this.onComplete = onComplete;
            this.timeoutTicks = timeoutTicks;
            this.plugin = plugin;
        }

        public void start() {
            // Start timeout task
            taskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                complete(false);
            }, timeoutTicks);
        }

        public void cancel() {
            if (taskId != -1) {
                plugin.getServer().getScheduler().cancelTask(taskId);
                taskId = -1;
            }
        }

        public void onPlayerStatus(UUID playerId, PlayerResourcePackStatusEvent.Status status) {
            if (completed || !waitingPlayers.contains(playerId)) {
                return;
            }

            switch (status) {
                case SUCCESSFULLY_LOADED:
                case DECLINED:
                case FAILED_DOWNLOAD:
                case INVALID_URL:
                case FAILED_RELOAD:
                case DISCARDED:
                    // Remove player from waiting list
                    waitingPlayers.remove(playerId);

                    // Check if all players are done
                    if (waitingPlayers.isEmpty()) {
                        complete(true);
                    }
                    break;

                case ACCEPTED:
                    // Still loading, do nothing
                    break;
            }
        }

        private void complete(boolean success) {
            if (completed) {
                return;
            }
            completed = true;
            cancel();
            trackers.remove(trackerId);

            if (onComplete != null) {
                onComplete.accept(success);
            }
        }
    }
}

