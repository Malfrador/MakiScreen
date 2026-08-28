package de.erethon.mccinema.commands;

import de.erethon.bedrock.command.ECommand;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoTransformEditor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

public final class CropCommand extends ECommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final MCCinema plugin = MCCinema.getInstance();

    public CropCommand() {
        setCommand("crop");
        setAliases("zoom", "viewport");
        setPermission("mccinema.crop");
        setPlayerCommand(true);
        setConsoleCommand(false);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/mcc crop <screen|save|reset|cancel>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        Player player = (Player) sender;
        VideoTransformEditor editor = plugin.getVideoTransformEditor();
        if (args.length < 2) {
            player.sendMessage(MM.deserialize("<red>Usage: /mcc crop <screen|save|reset|cancel>"));
            return;
        }

        String argument = args[1];
        if (argument.equalsIgnoreCase("save")) {
            if (!editor.save(player)) {
                notEditing(player);
            }
            return;
        }
        if (argument.equalsIgnoreCase("reset")) {
            if (!editor.reset(player)) {
                notEditing(player);
            }
            return;
        }
        if (argument.equalsIgnoreCase("cancel")) {
            if (!editor.cancel(player, true)) {
                notEditing(player);
            }
            return;
        }
        Optional<Screen> screen = plugin.getScreenManager().getScreen(argument);
        if (screen.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Screen '<white>" + MM.escapeTags(argument) + "</white>' not found."));
            return;
        }

        VideoTransformEditor.StartResult result = editor.start(player, screen.get());
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

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return List.of("save", "reset", "cancel").stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
        }
        return List.of();
    }

    private void notEditing(Player player) {
        player.sendMessage(MM.deserialize("<yellow>You are not editing a video crop."));
    }
}
