package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoPlayer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class StopCommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();


    public void onExecute(CommandSender sender, Screen screen) {
        VideoPlayer player = plugin.getVideoPlayer(screen);
        if (player == null) {
            sender.sendMessage(MM.deserialize("<red>No video player for this screen!"));
            return;
        }

        player.stop();
        plugin.getScreenManager().fillScreenWithBlankColor(screen);
        sender.sendMessage(MM.deserialize("<red>⏹ Stopped playback"));
    }

}

