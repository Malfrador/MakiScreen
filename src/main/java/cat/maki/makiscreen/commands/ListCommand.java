package cat.maki.makiscreen.commands;

import cat.maki.makiscreen.MakiScreen;
import cat.maki.makiscreen.screen.Screen;
import cat.maki.makiscreen.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.Collection;
import java.util.List;

public class ListCommand extends ECommand {

    private final MakiScreen plugin = MakiScreen.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public ListCommand() {
        setCommand("list");
        setAliases("ls", "screens");
        setPermission("makiscreen.list");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/maki list [screens|videos]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        String type = args.length > 1 ? args[1].toLowerCase() : "screens";

        if (type.equals("videos") || type.equals("files")) {
            listVideos(sender);
        } else {
            listScreens(sender);
        }
    }

    private void listScreens(CommandSender sender) {
        Collection<Screen> screens = plugin.getScreenManager().getAllScreens();

        if (screens.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>No screens created yet."));
            sender.sendMessage(MM.deserialize("<gray>Use <white>/maki create <name></white> to create one."));
            return;
        }

        sender.sendMessage(MM.deserialize("<gold><bold>Screens</bold> <gray>(" + screens.size() + ")"));

        for (Screen screen : screens) {
            VideoPlayer player = plugin.getVideoPlayer(screen);
            String status = getStatusIcon(player);

            String videoInfo = "";
            if (player != null && player.getVideoFile() != null) {
                videoInfo = " <dark_gray>- " + player.getVideoFile().getName();
            }

            sender.sendMessage(MM.deserialize(
                "  " + status + " <white>" + screen.getName() + "</white> " +
                "<gray>(" + screen.getMapWidth() + "x" + screen.getMapHeight() + " maps, " +
                screen.getPixelWidth() + "x" + screen.getPixelHeight() + "px)" +
                videoInfo
            ));
        }
    }

    private void listVideos(CommandSender sender) {
        File videosDir = new File(plugin.getDataFolder(), "videos");
        if (!videosDir.exists()) {
            videosDir.mkdirs();
        }

        File[] files = videosDir.listFiles((dir, name) ->
            name.endsWith(".mp4") || name.endsWith(".mkv") ||
            name.endsWith(".avi") || name.endsWith(".webm") ||
            name.endsWith(".mov"));

        if (files == null || files.length == 0) {
            sender.sendMessage(MM.deserialize("<gray>No video files found."));
            sender.sendMessage(MM.deserialize("<gray>Place videos in: <white>" + videosDir.getAbsolutePath()));
            return;
        }

        sender.sendMessage(MM.deserialize("<gold><bold>Videos</bold> <gray>(" + files.length + ")"));

        for (File file : files) {
            String size = formatFileSize(file.length());
            sender.sendMessage(MM.deserialize(
                "  <white>" + file.getName() + "</white> <gray>(" + size + ")"
            ));
        }
    }

    private String getStatusIcon(VideoPlayer player) {
        if (player == null) {
            return "<dark_gray>○";
        }

        return switch (player.getState()) {
            case PLAYING -> "<green>▶";
            case PAUSED -> "<yellow>⏸";
            case LOADING -> "<aqua>⟳";
            default -> "<dark_gray>○";
        };
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return List.of("screens", "videos").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}

