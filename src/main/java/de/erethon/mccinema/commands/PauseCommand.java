package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoPlayer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class PauseCommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();


    public void onExecute(CommandSender sender, Screen screen) {
        VideoPlayer player = plugin.getVideoPlayer(screen);
        if (player == null || player.getState() != VideoPlayer.State.PLAYING) {
            sender.sendMessage(MM.deserialize("<red>No video is playing on this screen!"));
            return;
        }

        player.pause();
        sender.sendMessage(MM.deserialize(
            "<yellow>⏸ Paused at " + VideoPlayer.formatDuration(player.getCurrentTimeMs())
        ));
    }

}

