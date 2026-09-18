package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoPlayer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class SeekCommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public void onExecute(CommandSender sender, Screen screen, long requestedTimeMs) {
        VideoPlayer player = plugin.getVideoPlayer(screen);
        if (player == null) {
            sender.sendMessage(MM.deserialize("<red>No video player for this screen!"));
            return;
        }

        if (player.isLiveStream()) {
            sender.sendMessage(MM.deserialize("<red>Cannot seek a livestream."));
            return;
        }

        long timeMs = Math.min(requestedTimeMs, player.getTotalDurationMs());

        player.seekToTime(timeMs);
        sender.sendMessage(MM.deserialize(
            "<yellow>⏩ Seeked to " + VideoPlayer.formatDuration(timeMs)
        ));
    }

}

