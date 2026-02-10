package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.screen.Screen;
import cat.maki.makiscreen.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

public class DeleteCommand extends ECommand {

    private final MakiScreen plugin = MakiScreen.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public DeleteCommand() {
        setCommand("delete");
        setAliases("remove", "rm");
        setPermission("makiscreen.delete");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/maki delete <screen>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /maki delete <screen>"));
            return;
        }

        String screenName = args[1];

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        Screen screen = screenOpt.get();

        // Stop any playing video
        VideoPlayer player = plugin.getVideoPlayer(screen);
        if (player != null) {
            player.shutdown();
            plugin.unregisterVideoPlayer(screen);
        }

        plugin.getScreenManager().deleteScreen(screen);

        sender.sendMessage(MM.deserialize(
            "<green>Screen '<white>" + screenName + "</white>' deleted." +
            "\n<gray>Note: Item frames were not removed. Remove them manually if needed."
        ));
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

