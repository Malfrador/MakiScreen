package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

public class InfoCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public InfoCommand() {
        setCommand("info");
        setAliases("status", "stats");
        setPermission("mccinema.info");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(1);
        setHelp("/mcc info <screen>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc info <screen>"));
            return;
        }

        String screenName = args[1];

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        Screen screen = screenOpt.get();
        VideoPlayer player = plugin.getVideoPlayer(screen);

        StringBuilder info = new StringBuilder();
        info.append("<gold><bold>Screen Info: </bold><white>").append(screen.getName()).append("</white>\n");
        info.append("<gray>  ID: <white>").append(screen.getId()).append("</white>\n");
        info.append("<gray>  Size: <white>").append(screen.getMapWidth()).append("x").append(screen.getMapHeight())
            .append("</white> maps (").append(screen.getTotalMaps()).append(" total)\n");
        info.append("<gray>  Resolution: <white>").append(screen.getPixelWidth()).append("x")
            .append(screen.getPixelHeight()).append("</white> pixels\n");
        info.append("<gray>  Aspect Ratio: <white>").append(screen.getAspectRatio().getDisplayName()).append("</white>\n");

        if (screen.getOrigin() != null) {
            info.append("<gray>  Location: <white>").append(formatLocation(screen)).append("</white>\n");
        }

        if (player != null) {
            info.append("\n<gold><bold>Playback Status</bold>\n");
            info.append("<gray>  State: <white>").append(getStateDisplay(player.getState())).append("</white>\n");

            if (player.getVideoFile() != null) {
                info.append("<gray>  Video: <white>").append(player.getVideoFile().getName()).append("</white>\n");
            }

            if (player.getState() == VideoPlayer.State.PLAYING || player.getState() == VideoPlayer.State.PAUSED) {
                String currentTime = VideoPlayer.formatDuration(player.getCurrentTimeMs());
                String totalTime = VideoPlayer.formatDuration(player.getTotalDurationMs());
                double progress = player.getProgress() * 100;

                info.append("<gray>  Progress: <white>").append(currentTime).append(" / ").append(totalTime)
                    .append("</white> (").append(String.format("%.1f", progress)).append("%)\n");
                info.append("<gray>  Frame: <white>").append(player.getCurrentFrame()).append(" / ")
                    .append(player.getTotalFrames()).append("</white>\n");
                info.append("<gray>  Frame Rate: <white>").append(String.format("%.1f", player.getFrameRate()))
                    .append("</white> fps\n");
            }

            // Performance stats
            info.append("\n<gold><bold>Performance</bold>\n");
            info.append("<gray>  Frames Processed: <white>").append(player.getFramesProcessed()).append("</white>\n");
            info.append("<gray>  Frames Skipped: <white>").append(player.getFramesSkipped()).append("</white>\n");
            info.append("<gray>  Packets Sent: <white>").append(formatNumber(player.getPacketDispatcher().getTotalPacketsSent()))
                .append("</white>\n");
            info.append("<gray>  Data Sent: <white>").append(formatBytes(player.getPacketDispatcher().getTotalBytesSent()))
                .append("</white>");
        } else {
            info.append("\n<gray>No video loaded");
        }

        sender.sendMessage(MM.deserialize(info.toString()));
    }

    private String formatLocation(Screen screen) {
        if (screen.getOrigin() == null) return "Unknown";
        return String.format("%d, %d, %d (%s)",
            screen.getOrigin().getBlockX(),
            screen.getOrigin().getBlockY(),
            screen.getOrigin().getBlockZ(),
            screen.getFacing() != null ? screen.getFacing().name() : "?"
        );
    }

    private String getStateDisplay(VideoPlayer.State state) {
        return switch (state) {
            case IDLE -> "<gray>Idle";
            case LOADING -> "<aqua>Loading";
            case PLAYING -> "<green>Playing";
            case PAUSED -> "<yellow>Paused";
            case STOPPED -> "<red>Stopped";
        };
    }

    private String formatNumber(long number) {
        if (number < 1000) return String.valueOf(number);
        if (number < 1000000) return String.format("%.1fK", number / 1000.0);
        return String.format("%.1fM", number / 1000000.0);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
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

