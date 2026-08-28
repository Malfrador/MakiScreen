package de.erethon.mccinema.video;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Input;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class VideoTransformEditor implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final double ZOOM_FACTOR = 1.04;
    private static final double PRECISE_ZOOM_FACTOR = 1.01;
    private static final double PAN_STEP = 0.025;
    private static final double PRECISE_PAN_STEP = 0.0033;

    private final MCCinema plugin;
    private final NamespacedKey movementLockKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final BukkitTask updateTask;
    private long tick;

    public VideoTransformEditor(MCCinema plugin) {
        this.plugin = plugin;
        this.movementLockKey = new NamespacedKey(plugin, "crop_movement_lock");
        this.updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateSessions, 1L, 1L);
    }

    public StartResult start(Player player, Screen screen) {
        if (sessions.containsKey(player.getUniqueId())) {
            return StartResult.ALREADY_EDITING;
        }
        VideoPlayer videoPlayer = plugin.getVideoPlayer(screen);
        if (videoPlayer == null || videoPlayer.getVideoFile() == null ||
            videoPlayer.getState() != VideoPlayer.State.PLAYING) {
            return StartResult.NO_PLAYING_LOCAL_VIDEO;
        }
        for (Session existing : sessions.values()) {
            if (existing.videoPlayer == videoPlayer) {
                return StartResult.SCREEN_BUSY;
            }
        }

        File videoFile = videoPlayer.getVideoFile();
        VideoTransform original = videoPlayer.getVideoTransform();
        Session session = new Session(player, screen, videoPlayer, videoFile, original);
        lockMovement(player);
        sessions.put(player.getUniqueId(), session);
        showInstructions(player, screen, videoFile);
        showStatus(session);
        return StartResult.STARTED;
    }

    public boolean save(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        if (!plugin.getVideoTransformManager().save(session.videoFile, session.working)) {
            player.sendMessage(MM.deserialize("<red>Could not save the crop settings. <gray>See the server log for details."));
            return true;
        }
        sessions.remove(player.getUniqueId());
        unlockMovement(player);
        session.videoPlayer.setVideoTransform(session.working);
        clearActionBar(player);
        player.sendMessage(MM.deserialize("<green>Saved crop for <white>" + escape(session.videoFile.getName()) + "</white>."));
        return true;
    }

    public boolean cancel(Player player, boolean notify) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return false;
        }
        unlockMovement(player);
        session.videoPlayer.setVideoTransform(session.original);
        clearActionBar(player);
        if (notify) {
            player.sendMessage(MM.deserialize("<yellow>Crop changes cancelled."));
        }
        return true;
    }

    public boolean reset(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        apply(session, VideoTransform.DEFAULT);
        player.sendMessage(MM.deserialize("<yellow>Crop preview reset. <gray>Use <white>/mcc crop save</white> to keep it."));
        return true;
    }

    public boolean isEditing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void shutdown() {
        updateTask.cancel();
        for (Session session : sessions.values()) {
            unlockMovement(session.player);
            session.videoPlayer.setVideoTransform(session.original);
            clearActionBar(session.player);
        }
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeldItemChanged(PlayerItemHeldEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        int direction = circularSlotDelta(event.getPreviousSlot(), event.getNewSlot());
        if (direction != 0) {
            double zoomFactor = event.getPlayer().isSneaking() ? PRECISE_ZOOM_FACTOR : ZOOM_FACTOR;
            apply(session, session.working.scaleZoom(direction < 0 ? zoomFactor : 1.0 / zoomFactor));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            reset(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }

    private void updateSessions() {
        tick++;
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (!session.player.isOnline() || plugin.getVideoPlayer(session.screen) != session.videoPlayer) {
                iterator.remove();
                unlockMovement(session.player);
                session.videoPlayer.setVideoTransform(session.original);
                clearActionBar(session.player);
                continue;
            }
            Input input = session.player.getCurrentInput();
            int movement = movementMask(input);
            if (movement != 0) {
                pan(session, movement, input.isSneak());
            }
            if (tick % 10 == 0) {
                showStatus(session);
            }
        }
    }

    private void pan(Session session, int movement, boolean precise) {
        double step = precise ? PRECISE_PAN_STEP : PAN_STEP;
        double x = 0.0;
        double y = 0.0;
        if ((movement & 1) != 0) y += step;
        if ((movement & 2) != 0) y -= step;
        if ((movement & 4) != 0) x += step;
        if ((movement & 8) != 0) x -= step;
        apply(session, session.working.panBy(x, y));
    }

    private void apply(Session session, VideoTransform transform) {
        session.working = transform;
        session.videoPlayer.setVideoTransform(transform);
        showStatus(session);
    }

    private void showInstructions(Player player, Screen screen, File videoFile) {
        player.sendMessage(MM.deserialize(
            "<gold><bold>Crop editor</bold></gold> <gray>— <white>" + escape(videoFile.getName()) + "</white> on <white>" +
                escape(screen.getName()) + "</white>\n" +
            "<gray>Mouse wheel: <white>zoom</white> · W/A/S/D: <white>pan</white> · Sneak: <white>precision</white> · F: <white>reset</white>\n" +
            "<green><click:run_command:'/mcc crop save'>[Save]</click></green> " +
            "<yellow><click:run_command:'/mcc crop reset'>[Reset]</click></yellow> " +
            "<red><click:run_command:'/mcc crop cancel'>[Cancel]</click></red>"
        ));
    }

    private void showStatus(Session session) {
        VideoTransform transform = session.working;
        session.player.sendActionBar(MM.deserialize(String.format(
            "<gold>Crop</gold> <white>%.0f%%</white> <gray>· Focus X <white>%.0f%%</white> Y <white>%.0f%%</white>",
            transform.zoom() * 100.0, transform.focusX() * 100.0, transform.focusY() * 100.0
        )));
    }

    private void clearActionBar(Player player) {
        if (player.isOnline()) {
            player.sendActionBar(Component.empty());
        }
    }

    private int movementMask(Input input) {
        int mask = 0;
        if (input.isForward()) mask |= 1;
        if (input.isBackward()) mask |= 2;
        if (input.isLeft()) mask |= 4;
        if (input.isRight()) mask |= 8;
        return mask;
    }

    private int circularSlotDelta(int previous, int next) {
        int delta = next - previous;
        if (delta > 4) delta -= 9;
        if (delta < -4) delta += 9;
        return Integer.compare(delta, 0);
    }

    private void lockMovement(Player player) {
        applyMovementLock(player, Attribute.MOVEMENT_SPEED);
        applyMovementLock(player, Attribute.FLYING_SPEED);
        applyMovementLock(player, Attribute.JUMP_STRENGTH);
    }

    private void applyMovementLock(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(movementLockKey);
        instance.addTransientModifier(new AttributeModifier(
            movementLockKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1
        ));
    }

    private void unlockMovement(Player player) {
        removeMovementLock(player, Attribute.MOVEMENT_SPEED);
        removeMovementLock(player, Attribute.FLYING_SPEED);
        removeMovementLock(player, Attribute.JUMP_STRENGTH);
    }

    private void removeMovementLock(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(movementLockKey);
        }
    }

    private String escape(String value) {
        return MM.escapeTags(value == null ? "unknown" : value);
    }

    public enum StartResult {
        STARTED,
        ALREADY_EDITING,
        NO_PLAYING_LOCAL_VIDEO,
        SCREEN_BUSY
    }

    private static final class Session {
        private final Player player;
        private final Screen screen;
        private final VideoPlayer videoPlayer;
        private final File videoFile;
        private final VideoTransform original;
        private VideoTransform working;

        private Session(Player player, Screen screen, VideoPlayer videoPlayer, File videoFile, VideoTransform original) {
            this.player = player;
            this.screen = screen;
            this.videoPlayer = videoPlayer;
            this.videoFile = videoFile;
            this.original = original;
            this.working = original;
        }
    }
}
