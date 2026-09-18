package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoTransformEditor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

public final class CropCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final MCCinema plugin = MCCinema.getInstance();


    public void start(Player player, Screen screen) {
        VideoTransformEditor editor = plugin.getVideoTransformEditor();
        VideoTransformEditor.StartResult result = editor.start(player, screen);
        switch (result) {
            case STARTED -> {
            }
            case ALREADY_EDITING -> player.sendMessage(MM.deserialize(
                "<yellow>You are already editing a crop. <gray>Use <white>/mcc crop save</white> or <white>/mcc crop cancel</white>."
            ));
            case NO_PLAYING_LOCAL_VIDEO -> player.sendMessage(MM.deserialize(
                "<red>That screen is not currently playing a local video. <gray>Livestream crops cannot be persisted."
            ));
            case SCREEN_BUSY -> player.sendMessage(MM.deserialize("<yellow>Another player is already editing that screen."));
        }
    }

    public void save(Player player) {
        if (!plugin.getVideoTransformEditor().save(player)) {
            notEditing(player);
        }
    }

    public void reset(Player player) {
        if (!plugin.getVideoTransformEditor().reset(player)) {
            notEditing(player);
        }
    }

    public void cancel(Player player) {
        if (!plugin.getVideoTransformEditor().cancel(player, true)) {
            notEditing(player);
        }
    }

    private void notEditing(Player player) {
        player.sendMessage(MM.deserialize("<yellow>You are not editing a video crop."));
    }
}
