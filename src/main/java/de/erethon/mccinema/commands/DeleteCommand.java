package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

public class DeleteCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public DeleteCommand() {
        setCommand("delete");
        setAliases("remove", "rm");
        setPermission("mccinema.delete");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/mcc delete <screen>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc delete <screen>"));
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

