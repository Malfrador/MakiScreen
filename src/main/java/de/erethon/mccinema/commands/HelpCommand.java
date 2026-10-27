package de.erethon.mccinema.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class HelpCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();


    public void onExecute(CommandSender sender) {
        sender.sendMessage(MM.deserialize("""
            <gold><bold>MCCinema Commands</bold></gold>
            
            <yellow>Screen Management:</yellow>
              <white>/mcc create <name> [aspect] [width] [height]</white> <gray>- Create a new screen
              <white>/mcc delete <screen></white> <gray>- Delete a screen
              <white>/mcc list [screens|videos]</white> <gray>- List screens or videos
              <white>/mcc info <screen></white> <gray>- Show screen details
            
            <yellow>Playback Control:</yellow>
              <white>/mcc play <screen> <file-or-url> [--audio]</white> <gray>- Play a video or livestream
              <white>/mcc play <screen> <file> --experimental [--audio]</white> <gray>- Experimental film colour (26.2 decoder pack)
              <white>/mcc play bigscreen https://www.youtube.com/watch?v=...</white> <gray>- Play YouTube live
              <white>/mcc play bigscreen https://www.twitch.tv/channel</white> <gray>- Play Twitch live
              <white>/mcc pause <screen></white> <gray>- Pause playback
              <white>/mcc resume <screen></white> <gray>- Resume playback
              <white>/mcc stop <screen></white> <gray>- Stop playback
              <white>/mcc seek <screen> <time></white> <gray>- Seek to time (MM:SS)
              <white>/mcc crop <screen></white> <gray>- Interactively zoom and pan a local video
              <white>/mcc crop <save|reset|cancel></white> <gray>- Finish or reset crop editing
              <white>/mcc quality <screen> [show|status|quality|high|balanced|performance]</white> <gray>- View or set quality
              <white>/mcc reload</white> <gray>- Reload the config

            <yellow>Online Media:</yellow>
              <white>/mcc download <url> [filename]</white> <gray>- Download a video
              <white>/mcc media [retry]</white> <gray>- Show or repair media support
            
            <yellow>Aspect Ratios:</yellow>
              <gray>16:9, 21:9 (CinemaScope), 4:3, 1:1, 2:1, CUSTOM
            
            <dark_gray>Place video files in: plugins/mccScreen/videos/"""));
    }

}

