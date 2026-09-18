package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.screen.ScreenManager;
import de.erethon.mccinema.video.VideoPlayer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class DeleteCommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();


    public void onExecute(CommandSender sender, Screen screen) {
        String screenName = screen.getName();

        // Stop any playing video
        VideoPlayer player = plugin.getVideoPlayer(screen);
        if (player != null) {
            player.shutdown();
            plugin.unregisterVideoPlayer(screen);
        }

        ScreenManager.DeletionResult result = plugin.getScreenManager().deleteScreen(screen);
        if (!result.deleted()) {
            sender.sendMessage(MM.deserialize(
                "<red>Could not delete screen '<white>" + screenName + "</white>'. " +
                "<gray>Its world or placement data is currently unavailable."
            ));
            return;
        }

        sender.sendMessage(MM.deserialize(
            "<green>Screen '<white>" + screenName + "</white>' deleted." +
            "\n<gray>Removed <white>" + result.itemFramesRemoved() + "</white> item frames and <white>" +
            result.mapItemsRemoved() + "</white> screen maps."
        ));
    }

}

