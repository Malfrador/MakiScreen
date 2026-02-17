package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public class DebugCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public DebugCommand() {
        setCommand("debug");
        setAliases("metrics", "perf");
        setPermission("mccinema.debug");
        setPlayerCommand(true);
        setConsoleCommand(false);
        setMinArgs(0);
        setMaxArgs(4);
        setHelp("/mcc debug <screen> [setting] [value]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>This command can only be used by players!"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc debug <screen> [setting] [value]"));
            sender.sendMessage(MM.deserialize("<gray>Settings: show, output-stability, motion-adaptive, luminance-adaptive, stability-bias"));
            return;
        }

        String screenName = args[1];

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        Screen screen = screenOpt.get();
        VideoPlayer videoPlayer = plugin.getVideoPlayer(screen);

        if (videoPlayer == null) {
            sender.sendMessage(MM.deserialize("<red>No video player active for screen '" + screenName + "'!"));
            return;
        }

        // If no setting specified, toggle debug display
        if (args.length == 2) {
            toggleDebugDisplay(player, videoPlayer, screenName);
            return;
        }

        // Handle settings
        String setting = args[2].toLowerCase();
        FrameProcessor processor = videoPlayer.getFrameProcessor();

        switch (setting) {
            case "show", "status" -> showOptimizationStatus(sender, processor, screenName);
            case "temporal", "temp" -> {
                if (args.length == 3) {
                    boolean newValue = !processor.isUsingTemporalDithering();
                    processor.setUseTemporalDithering(newValue);
                    sender.sendMessage(MM.deserialize("<green>Temporal dithering: " + formatBoolean(newValue)));
                } else {
                    boolean value = parseBoolean(args[3]);
                    processor.setUseTemporalDithering(value);
                    sender.sendMessage(MM.deserialize("<green>Temporal dithering set to: " + formatBoolean(value)));
                }
            }
            case "error-threshold", "threshold", "et" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<yellow>Current error threshold: <white>" + processor.getErrorThreshold()));
                    sender.sendMessage(MM.deserialize("<gray>Usage: /mcc debug " + screenName + " error-threshold <0-255>"));
                } else {
                    try {
                        int value = Integer.parseInt(args[3]);
                        processor.setErrorThreshold(value);
                        sender.sendMessage(MM.deserialize("<green>Error threshold set to: <white>" + processor.getErrorThreshold()));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(MM.deserialize("<red>Invalid number: " + args[3]));
                    }
                }
            }
            case "temporal-threshold", "tt" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<yellow>Current temporal threshold: <white>" + processor.getTemporalThreshold()));
                    sender.sendMessage(MM.deserialize("<gray>Usage: /mcc debug " + screenName + " temporal-threshold <0-255>"));
                } else {
                    try {
                        int value = Integer.parseInt(args[3]);
                        processor.setTemporalThreshold(value);
                        sender.sendMessage(MM.deserialize("<green>Temporal threshold set to: <white>" + processor.getTemporalThreshold()));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(MM.deserialize("<red>Invalid number: " + args[3]));
                    }
                }
            }
            case "error-diffusion", "diffusion", "ed" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<yellow>Current error diffusion strength: <white>" + String.format("%.2f", processor.getErrorDiffusionStrength())));
                    sender.sendMessage(MM.deserialize("<gray>Usage: /mcc debug " + screenName + " error-diffusion <0.0-1.0>"));
                } else {
                    try {
                        float value = Float.parseFloat(args[3]);
                        processor.setErrorDiffusionStrength(value);
                        sender.sendMessage(MM.deserialize("<green>Error diffusion strength set to: <white>" + String.format("%.2f", processor.getErrorDiffusionStrength())));
                    } catch (NumberFormatException e) {
                        sender.sendMessage(MM.deserialize("<red>Invalid number: " + args[3]));
                    }
                }
            }
            case "mode", "dither-mode", "dm" -> {
                if (args.length < 4) {
                    sender.sendMessage(MM.deserialize("<yellow>Current dithering mode: <white>" + processor.getDitheringMode()));
                    sender.sendMessage(MM.deserialize("<gray>Available: FLOYD_STEINBERG, FLOYD_STEINBERG_REDUCED, BAYER_8X8, NONE"));
                } else {
                    try {
                        FrameProcessor.DitheringMode mode = FrameProcessor.DitheringMode.valueOf(args[3].toUpperCase());
                        processor.setDitheringMode(mode);
                        sender.sendMessage(MM.deserialize("<green>Dithering mode set to: <white>" + mode));
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(MM.deserialize("<red>Invalid mode. Available: FLOYD_STEINBERG, FLOYD_STEINBERG_REDUCED, BAYER_8X8, NONE"));
                    }
                }
            }
            default -> {
                sender.sendMessage(MM.deserialize("<red>Unknown setting: " + setting));
                sender.sendMessage(MM.deserialize("<gray>Available settings:"));
                sender.sendMessage(MM.deserialize("<gray>  show, output-stability, motion-adaptive, luminance-adaptive, temporal"));
                sender.sendMessage(MM.deserialize("<gray>  stability-bias, error-threshold, temporal-threshold, error-diffusion, mode"));
            }
        }
    }

    private void toggleDebugDisplay(Player player, VideoPlayer videoPlayer, String screenName) {
        UUID playerId = player.getUniqueId();
        boolean currentlyEnabled = videoPlayer.isDebugEnabled(playerId);

        if (currentlyEnabled) {
            videoPlayer.disableDebug(playerId);
            player.sendMessage(MM.deserialize("<green>Debug metrics disabled for screen <white>" + screenName + "</white>"));
        } else {
            videoPlayer.enableDebug(playerId);
            player.sendMessage(MM.deserialize("<green>Debug metrics enabled for screen <white>" + screenName + "</white>"));
            player.sendMessage(MM.deserialize("<gray>Metrics will be displayed in your action bar"));
            player.sendMessage(MM.deserialize("<gray>Use <white>/mcc debug " + screenName + " show</white> to see optimization settings"));
        }
    }

    private void showOptimizationStatus(CommandSender sender, FrameProcessor processor, String screenName) {
        sender.sendMessage(MM.deserialize("<gold>===== Optimization Settings for <white>" + screenName + "</white> ====="));
        sender.sendMessage(MM.deserialize("<gray>Dithering Mode: <white>" + processor.getDitheringMode()));
        sender.sendMessage(MM.deserialize("<gray>Error Diffusion Strength: <white>" + String.format("%.2f", processor.getErrorDiffusionStrength())));
        sender.sendMessage(MM.deserialize("<gray>Error Threshold: <white>" + processor.getErrorThreshold()));
        sender.sendMessage(MM.deserialize(""));
        sender.sendMessage(MM.deserialize("<yellow>Temporal Optimizations:"));
        sender.sendMessage(MM.deserialize("<gray>  Temporal Dithering: " + formatBoolean(processor.isUsingTemporalDithering())));
        sender.sendMessage(MM.deserialize("<gray>  Temporal Threshold: <white>" + processor.getTemporalThreshold()));
        sender.sendMessage(MM.deserialize(""));
        sender.sendMessage(MM.deserialize("<aqua>Tip: Toggle with /mcc debug " + screenName + " <setting>"));
    }

    private String formatBoolean(boolean value) {
        return value ? "<green>ON</green>" : "<red>OFF</red>";
    }

    private boolean parseBoolean(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") ||
               value.equalsIgnoreCase("yes") || value.equals("1");
    }
}

