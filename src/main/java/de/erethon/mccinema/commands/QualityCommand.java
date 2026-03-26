package de.erethon.mccinema.commands;

import de.erethon.bedrock.command.ECommand;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.PacketDispatcher;
import de.erethon.mccinema.video.VideoPlayer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class QualityCommand extends ECommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final MCCinema plugin = MCCinema.getInstance();

    public QualityCommand() {
        setCommand("quality");
        setAliases("preset", "q");
        setPermission("mccinema.debug");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(3);
        setHelp("/mcc quality <screen> [quality|balanced|performance|show]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc quality <screen> [quality|balanced|performance|show]"));
            return;
        }

        String screenName = args[1];
        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '<white>" + screenName + "</white>' not found."));
            return;
        }

        VideoPlayer videoPlayer = plugin.getVideoPlayer(screenOpt.get());
        if (videoPlayer == null) {
            sender.sendMessage(MM.deserialize("<red>No active video player for screen '<white>" + screenName + "</white>'."));
            return;
        }

        if (args.length == 2 || args[2].equalsIgnoreCase("show") || args[2].equalsIgnoreCase("status")) {
            showCurrentSettings(sender, videoPlayer, screenName);
            return;
        }

        QualityPreset preset = QualityPreset.fromInput(args[2]);
        if (preset == null) {
            sender.sendMessage(MM.deserialize("<red>Unknown preset: <white>" + args[2] + "</white>"));
            sender.sendMessage(MM.deserialize("<gray>Available: quality, balanced, performance"));
            return;
        }

        applyPreset(videoPlayer, preset);
        sender.sendMessage(MM.deserialize("<green>Applied quality preset <white>" + preset.name().toLowerCase(Locale.ROOT) + "</white> to screen <white>" + screenName + "</white>."));
        showCurrentSettings(sender, videoPlayer, screenName);
    }

    public static void applyBalancedPreset(VideoPlayer videoPlayer) {
        applyPreset(videoPlayer, QualityPreset.BALANCED);
    }

    private static void applyPreset(VideoPlayer videoPlayer, QualityPreset preset) {
        FrameProcessor processor = videoPlayer.getFrameProcessor();
        PacketDispatcher dispatcher = videoPlayer.getPacketDispatcher();

        switch (preset) {
            case QUALITY -> {
                processor.setDitheringMode(FrameProcessor.DitheringMode.ATKINSON);
                processor.setErrorDiffusionStrength(0.92f);
                processor.setErrorThreshold(2);
                processor.setUseTemporalDithering(false);
                processor.setTemporalThreshold(3);
                processor.setErrorQuantizationBits(1);
                processor.setAdaptiveTuningEnabled(false);

                dispatcher.setPatchStrategy(PacketDispatcher.PatchStrategy.BOUNDING_BOX);
                dispatcher.setFullUpdateThresholdPercent(68);
                dispatcher.setMultiRegionBlockSize(16);
                dispatcher.setMaxPatchesPerTile(8);
                dispatcher.setMinPatchArea(32);
                dispatcher.setUseEntropyFiltering(false);
                dispatcher.setUseSpatialDownsampling(false);
                dispatcher.setBandwidthTargetEnabled(false);
                dispatcher.setBandwidthTargetBytesPerSecond(1000L * 1024L * 1024L);
            }
            case BALANCED -> {
                processor.setDitheringMode(FrameProcessor.DitheringMode.FLOYD_STEINBERG);
                processor.setErrorDiffusionStrength(0.78f);
                processor.setErrorThreshold(4);
                processor.setUseTemporalDithering(false);
                processor.setTemporalThreshold(5);
                processor.setErrorQuantizationBits(2);
                processor.setAdaptiveTuningEnabled(true);

                dispatcher.setPatchStrategy(PacketDispatcher.PatchStrategy.MULTI_REGION);
                dispatcher.setFullUpdateThresholdPercent(82);
                dispatcher.setMultiRegionBlockSize(8);
                dispatcher.setMaxPatchesPerTile(24);
                dispatcher.setMinPatchArea(16);
                dispatcher.setUseEntropyFiltering(true);
                dispatcher.setMinUniqueColorsThreshold(3);
                dispatcher.setUseSpatialDownsampling(false);
                dispatcher.setBandwidthTargetEnabled(true);
                dispatcher.setBandwidthTargetBytesPerSecond(32L * 1024L * 1024L);
            }
            case PERFORMANCE -> {
                processor.setDitheringMode(FrameProcessor.DitheringMode.FLOYD_STEINBERG_REDUCED);
                processor.setErrorDiffusionStrength(0.60f);
                processor.setErrorThreshold(14);
                processor.setUseTemporalDithering(true);
                processor.setTemporalThreshold(14);
                processor.setErrorQuantizationBits(4);
                processor.setAdaptiveTuningEnabled(true);

                dispatcher.setPatchStrategy(PacketDispatcher.PatchStrategy.MULTI_REGION);
                dispatcher.setFullUpdateThresholdPercent(92);
                dispatcher.setMultiRegionBlockSize(4);
                dispatcher.setMaxPatchesPerTile(48);
                dispatcher.setMinPatchArea(8);
                dispatcher.setUseEntropyFiltering(true);
                dispatcher.setMinUniqueColorsThreshold(4);
                dispatcher.setUseSpatialDownsampling(true);
                dispatcher.setBandwidthTargetEnabled(true);
                dispatcher.setBandwidthTargetBytesPerSecond(20L * 1024L * 1024L);
            }
        }
    }

    private void showCurrentSettings(CommandSender sender, VideoPlayer videoPlayer, String screenName) {
        FrameProcessor processor = videoPlayer.getFrameProcessor();
        PacketDispatcher dispatcher = videoPlayer.getPacketDispatcher();

        sender.sendMessage(MM.deserialize("<gold>===== Video Preset Status for <white>" + screenName + "</white> ====="));
        sender.sendMessage(MM.deserialize("<gray>Dither: <white>" + processor.getDitheringMode() + "</white> | Diffusion: <white>" + String.format("%.2f", processor.getErrorDiffusionStrength()) + "</white> | ErrThr: <white>" + processor.getErrorThreshold() + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>Temporal: <white>" + (processor.isUsingTemporalDithering() ? "ON" : "OFF") + "</white> | TThr: <white>" + processor.getTemporalThreshold() + "</white> | Quant: <white>" + processor.getErrorQuantizationBits() + "</white> | Adaptive: <white>" + (processor.isAdaptiveTuningEnabled() ? "ON" : "OFF") + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>Patching: <white>" + dispatcher.getPatchStrategy() + "</white> | FullThr: <white>" + dispatcher.getFullUpdateThresholdPercent() + "%</white> | Block: <white>" + dispatcher.getMultiRegionBlockSize() + "</white> | MaxPatches: <white>" + dispatcher.getMaxPatchesPerTile() + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>MinArea: <white>" + dispatcher.getMinPatchArea() + "</white> | Entropy: <white>" + (dispatcher.isUseEntropyFiltering() ? "ON" : "OFF") + "</white> | Spatial: <white>" + (dispatcher.isUseSpatialDownsampling() ? "ON" : "OFF") + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>BW Target: <white>" + (dispatcher.isBandwidthTargetEnabled() ? formatBytes(dispatcher.getBandwidthTargetBytesPerSecond()) + "/s" : "OFF") + "</white>"));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        }
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return plugin.getScreenManager().getAllScreens().stream()
                .map(Screen::getName)
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                .toList();
        }

        if (args.length == 3) {
            return List.of("quality", "balanced", "performance", "show", "status").stream()
                .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                .toList();
        }

        return List.of();
    }

    private enum QualityPreset {
        QUALITY,
        BALANCED,
        PERFORMANCE;

        private static QualityPreset fromInput(String input) {
            if (input == null || input.isBlank()) {
                return null;
            }
            String normalized = input.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                return QualityPreset.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}

