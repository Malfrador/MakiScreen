package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SeekCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern TIME_PATTERN = Pattern.compile("(?:(?:(\\d+):)?(\\d+):)?(\\d+)");

    public SeekCommand() {
        setCommand("seek");
        setAliases("goto", "jump");
        setPermission("mccinema.control");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(2);
        setHelp("/mcc seek <screen> <time>");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc seek <screen> <time>"));
            return;
        }

        String screenName = args[1];
        String timeStr = args[2];

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

        long timeMs = parseTime(timeStr);
        if (timeMs < 0) {
            sender.sendMessage(MM.deserialize("<red>Invalid time format! Use: [HH:]MM:SS or seconds"));
            return;
        }

        if (timeMs > player.getTotalDurationMs()) {
            timeMs = player.getTotalDurationMs();
        }

        player.seekToTime(timeMs);
        sender.sendMessage(MM.deserialize(
            "<yellow>⏩ Seeked to " + VideoPlayer.formatDuration(timeMs)
        ));
    }

    private long parseTime(String timeStr) {
        // Try parsing as just seconds
        try {
            return Long.parseLong(timeStr) * 1000;
        } catch (NumberFormatException ignored) {}

        // Try parsing as MM:SS or HH:MM:SS
        Matcher matcher = TIME_PATTERN.matcher(timeStr);
        if (matcher.matches()) {
            long hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
            long minutes = matcher.group(2) != null ? Long.parseLong(matcher.group(2)) : 0;
            long seconds = Long.parseLong(matcher.group(3));

            return (hours * 3600 + minutes * 60 + seconds) * 1000;
        }

        return -1;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return plugin.getScreenManager().getAllScreens().stream()
                .map(Screen::getName)
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }

        if (args.length == 3) {
            return List.of("0:00", "1:00", "5:00", "10:00");
        }

        return List.of();
    }
}

