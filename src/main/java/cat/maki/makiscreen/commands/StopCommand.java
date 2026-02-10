package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.screen.Screen;
import cat.maki.makiscreen.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

public class StopCommand extends ECommand {

    private final MakiScreen plugin = MakiScreen.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public StopCommand() {
        setCommand("stop");
        setPermission("makiscreen.control");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/maki stop <screen>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /maki stop <screen>"));
            return;
        }

        String screenName = args[1];

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        VideoPlayer player = plugin.getVideoPlayer(screenOpt.get());
        if (player == null) {
            sender.sendMessage(MM.deserialize("<red>No video player for this screen!"));
            return;
        }

        player.stop();
        sender.sendMessage(MM.deserialize("<red>⏹ Stopped playback"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return plugin.getScreenManager().getAllScreens().stream()
                    .map(Screen::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}

