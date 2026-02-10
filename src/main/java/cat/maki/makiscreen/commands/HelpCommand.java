package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;

public class HelpCommand extends ECommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public HelpCommand() {
        setCommand("help");
        setAliases("?");
        setPermission("makiscreen.help");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMaxArgs(99);
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        sender.sendMessage(MM.deserialize("""
            <gold><bold>MakiScreen Commands</bold></gold>
            
            <yellow>Screen Management:</yellow>
              <white>/maki create <name> [aspect] [width] [height]</white> <gray>- Create a new screen
              <white>/maki delete <screen></white> <gray>- Delete a screen
              <white>/maki list [screens|videos]</white> <gray>- List screens or videos
              <white>/maki info <screen></white> <gray>- Show screen details
            
            <yellow>Playback Control:</yellow>
              <white>/maki play <screen> <file> [--audio]</white> <gray>- Play a video
              <white>/maki pause <screen></white> <gray>- Pause playback
              <white>/maki resume <screen></white> <gray>- Resume playback
              <white>/maki stop <screen></white> <gray>- Stop playback
              <white>/maki seek <screen> <time></white> <gray>- Seek to time (MM:SS)
            
            <yellow>Aspect Ratios:</yellow>
              <gray>16:9, 21:9 (CinemaScope), 4:3, 1:1, 2:1, CUSTOM
            
            <dark_gray>Place video files in: plugins/MakiScreen/videos/"""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}

