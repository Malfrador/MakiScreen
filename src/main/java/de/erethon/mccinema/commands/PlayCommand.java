package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.resourcepack.ResourcePackServer;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PlayCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private BukkitTask progressTask;
    private BossBar progressBar;

    public PlayCommand() {
        setCommand("play");
        setAliases("start");
        setPermission("mccinema.play");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(0);
        setMaxArgs(8);
        setHelp("/mcc play <screen> <file> [--audio [chunk_seconds|single]] [--positional] [--dither <mode>]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc play <screen> <file> [--audio [chunk_seconds|single]] [--positional] [--dither <mode>]"));
            return;
        }

        String screenName = args[1];
        String fileName = args[2];

        // Parse optional flags
        boolean audioFlag = false;
        boolean positionalAudio = false;
        int chunkDurationMs = AudioManager.DEFAULT_CHUNK_DURATION_MS;
        FrameProcessor.DitheringMode ditheringMode = FrameProcessor.DitheringMode.FLOYD_STEINBERG_REDUCED; // Default

        // Parse arguments for --audio, --positional, and --dither flags
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--audio")) {
                audioFlag = true;
                // Check for chunk duration argument
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    String chunkArg = args[i + 1].toLowerCase();
                    if (chunkArg.equals("single") || chunkArg.equals("0")) {
                        chunkDurationMs = 0;
                    } else {
                        try {
                            int seconds = Integer.parseInt(chunkArg);
                            if (seconds < 0) {
                                sender.sendMessage(MM.deserialize("<red>Chunk duration must be positive or 'single'"));
                                return;
                            }
                            chunkDurationMs = seconds * 1000;
                        } catch (NumberFormatException e) {
                            sender.sendMessage(MM.deserialize("<red>Invalid chunk duration: " + chunkArg));
                            return;
                        }
                    }
                    i++; // Skip the chunk duration argument
                }
            } else if (args[i].equalsIgnoreCase("--positional")) {
                positionalAudio = true;
            } else if (args[i].equalsIgnoreCase("--dither")) {
                if (i + 1 < args.length) {
                    String modeArg = args[i + 1].toUpperCase();
                    try {
                        ditheringMode = FrameProcessor.DitheringMode.valueOf(modeArg);
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(MM.deserialize("<red>Invalid dithering mode: " + args[i + 1]));
                        sender.sendMessage(MM.deserialize("<gray>Available modes: floyd_steinberg, floyd_steinberg_reduced, bayer_8x8, none"));
                        return;
                    }
                    i++; // Skip the mode argument
                } else {
                    sender.sendMessage(MM.deserialize("<red>Missing dithering mode after --dither"));
                    return;
                }
            }
        }

        final boolean withAudio = audioFlag;

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        Screen screen = screenOpt.get();

        File videoFile = new File(plugin.getDataFolder(), "videos/" + fileName);
        if (!videoFile.exists()) {
            videoFile = new File(plugin.getDataFolder(), fileName);
        }
        if (!videoFile.exists()) {
            sender.sendMessage(MM.deserialize("<red>Video file '" + fileName + "' not found!"));
            sender.sendMessage(MM.deserialize("<gray>Place videos in: <white>" +
                plugin.getDataFolder().getAbsolutePath() + "/videos/"));
            return;
        }

        // Stop existing player if any
        VideoPlayer existingPlayer = plugin.getVideoPlayer(screen);
        if (existingPlayer != null && existingPlayer.getState() == VideoPlayer.State.PLAYING) {
            existingPlayer.stop();
        }

        sender.sendMessage(MM.deserialize("<yellow>Loading video..."));

        File finalVideoFile = videoFile;
        int finalChunkDurationMs = chunkDurationMs;
        FrameProcessor.DitheringMode finalDitheringMode = ditheringMode;
        boolean finalPositionalAudio = positionalAudio;
        new BukkitRunnable() {
            @Override
            public void run() {
                VideoPlayer player = new VideoPlayer(plugin, screen);

                // Set dithering mode before processing any frames
                player.getFrameProcessor().setDitheringMode(finalDitheringMode);

                AudioManager audioManager = null;

                if (withAudio) {
                    String videoId = finalVideoFile.getName().replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
                    audioManager = new AudioManager(plugin, videoId, finalChunkDurationMs, screen);

                    String modeInfo = audioManager.isSingleFileMode() ? "single file" :
                                     (finalChunkDurationMs / 1000) + "s chunks";
                    String audioMode = finalPositionalAudio ? "positional (3D mono)" : "global stereo";
                    sender.sendMessage(MM.deserialize("<yellow>Extracting audio (" + modeInfo + ", " + audioMode + ")..."));


                    if (audioManager.extractAndSplitAudio(finalVideoFile)) {
                        File resourcePack = audioManager.generateResourcePack();
                        if (resourcePack != null) {
                            // Register resource pack with server
                            ResourcePackServer rpServer = plugin.getResourcePackServer();
                            if (rpServer != null) {
                                rpServer.registerResourcePack(videoId, resourcePack);

                                sender.sendMessage(MM.deserialize(
                                    "<green>✓ Audio resource pack generated and registered!" +
                                    "\n<gray>Pack URL: <white>" + rpServer.getResourcePackUrl(videoId)));
                            } else {
                                sender.sendMessage(MM.deserialize(
                                    "<yellow>⚠ Resource pack server is not enabled!" +
                                    "\n<gray>Enable it in config.yml under 'resourcepack.enabled'"));
                            }
                        }
                        player.setAudioManager(audioManager);
                    }
                }

                AudioManager finalAudioManager = audioManager;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!player.load(finalVideoFile)) {
                            sender.sendMessage(MM.deserialize("<red>Failed to load video!"));
                            return;
                        }

                        plugin.registerVideoPlayer(screen, player);

                        player.setOnComplete(p -> {
                            stopProgressBar();
                            Bukkit.broadcast(MM.deserialize("<gray>Video playback complete."));
                        });

                        player.setOnStateChange(p -> {
                            if (p.getState() == VideoPlayer.State.PLAYING) {
                                startProgressBar(p);
                            }
                        });

                        // Send resource pack to all online players if audio is enabled
                        if (withAudio && finalAudioManager != null) {
                            ResourcePackServer rpServer = plugin.getResourcePackServer();
                            if (rpServer != null && plugin.getConfig().getBoolean("resourcepack.auto-apply", true)) {
                                String videoId = finalAudioManager.getVideoId();
                                String url = rpServer.getResourcePackUrl(videoId);
                                byte[] hash = rpServer.getResourcePackHash(videoId);

                                if (hash != null) {
                                    String prompt = plugin.getConfig().getString("resourcepack.prompt",
                                        "<yellow>This video requires a resource pack for audio playback");
                                    boolean required = plugin.getConfig().getBoolean("resourcepack.required", false);

                                    Component promptComponent = MM.deserialize(prompt);

                                    // Update viewer cache before sending resource pack
                                    screen.updateViewerCache();

                                    // Collect player UUIDs to track
                                    Set<UUID> playerIds = new HashSet<>();
                                    Collection<Player> viewers = screen.getViewers();

                                    if (viewers.isEmpty()) {
                                        sender.sendMessage(MM.deserialize(
                                            "<yellow>⚠ No nearby players found to send resource pack to"));
                                        // Start playback immediately if no viewers
                                        player.play();
                                        String audioInfo = withAudio ? "\n<gray>  Audio: <green>✓ Enabled" : "";
                                        String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                                        sender.sendMessage(MM.deserialize(
                                            "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                                            "\n<gray>  On screen: <white>" + screen.getName() +
                                            "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                                            "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                                            ditherInfo +
                                            audioInfo
                                        ));
                                        return;
                                    }

                                    for (Player p : viewers) {
                                        p.setResourcePack(url, hash, promptComponent, required);
                                        playerIds.add(p.getUniqueId());
                                    }

                                    sender.sendMessage(MM.deserialize(
                                        "<green>✓ Resource pack sent to " + playerIds.size() + " nearby player(s)"));
                                    sender.sendMessage(MM.deserialize(
                                        "<yellow>⏳ Waiting for players to load resource pack..."));

                                    // Wait for resource pack to load before starting playback
                                    plugin.getResourcePackListener().trackResourcePackLoad(
                                        videoId + "_" + System.currentTimeMillis(),
                                        playerIds,
                                        (success) -> {
                                            // Start playback after resource pack is loaded
                                            new BukkitRunnable() {
                                                @Override
                                                public void run() {
                                                    player.play();

                                                    String audioInfo = withAudio ? "\n<gray>  Audio: <green>✓ Enabled" : "";
                                                    String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                                                    String loadStatus = success ? "<green>✓ Resource pack loaded" : "<yellow>⚠ Started without waiting (timeout)";
                                                    sender.sendMessage(MM.deserialize(
                                                        "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                                                        "\n<gray>  On screen: <white>" + screen.getName() +
                                                        "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                                                        "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                                                        ditherInfo +
                                                        audioInfo +
                                                        "\n<gray>  " + loadStatus
                                                    ));
                                                }
                                            }.runTask(plugin);
                                        },
                                        200L // 10 second timeout
                                    );

                                    // Don't start playback immediately
                                    return;
                                }
                            }
                        }

                        // No audio or resource pack not sent
                        player.play();

                        String audioInfo = withAudio ? "\n<gray>  Audio: <green>✓ Enabled" : "";
                        String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                        sender.sendMessage(MM.deserialize(
                            "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                            "\n<gray>  On screen: <white>" + screen.getName() +
                            "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                            "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                            ditherInfo +
                            audioInfo
                        ));
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void startProgressBar(VideoPlayer player) {
        if (progressBar != null) {
            stopProgressBar();
        }

        progressBar = BossBar.bossBar(
            Component.text("Loading..."),
            0f,
            BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS
        );

        for (Player p : player.getScreen().getViewers()) {
            p.showBossBar(progressBar);
        }

        progressTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.getState() != VideoPlayer.State.PLAYING) {
                    if (player.getState() == VideoPlayer.State.PAUSED) {
                        progressBar.color(BossBar.Color.YELLOW);
                    }
                    return;
                }

                float progress = (float) player.getProgress();
                progressBar.progress(Math.min(1f, Math.max(0f, progress)));
                progressBar.color(BossBar.Color.GREEN);

                String currentTime = VideoPlayer.formatDuration(player.getCurrentTimeMs());
                String totalTime = VideoPlayer.formatDuration(player.getTotalDurationMs());

                progressBar.name(MM.deserialize(
                    "<green>▶</green> <white>" + currentTime + " / " + totalTime + "</white>"
                ));
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void stopProgressBar() {
        if (progressTask != null) {
            progressTask.cancel();
            progressTask = null;
        }

        if (progressBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(progressBar);
            }
            progressBar = null;
        }
    }

    private String formatDitheringMode(FrameProcessor.DitheringMode mode) {
        return switch (mode) {
            case FLOYD_STEINBERG -> "Floyd-Steinberg (High Quality)";
            case FLOYD_STEINBERG_REDUCED -> "Floyd-Steinberg Reduced (Default)";
            case BAYER_8X8 -> "Bayer 8x8 (Low Noise)";
            case NONE -> "None (Fastest)";
        };
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
            File videosDir = new File(plugin.getDataFolder(), "videos");
            if (videosDir.exists()) {
                String[] files = videosDir.list((dir, name) ->
                    name.endsWith(".mp4") || name.endsWith(".mkv") ||
                    name.endsWith(".avi") || name.endsWith(".webm"));
                if (files != null) {
                    return Arrays.stream(files)
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .toList();
                }
            }
        }

        if (args.length >= 4) {
            // Check what the previous argument was
            String prevArg = args[args.length - 2];

            if (prevArg.equalsIgnoreCase("--audio")) {
                return List.of("single", "5", "10", "15", "30", "60");
            }

            if (prevArg.equalsIgnoreCase("--dither")) {
                return Arrays.stream(FrameProcessor.DitheringMode.values())
                    .map(Enum::name)
                    .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                    .toList();
            }

            // Suggest flags
            return List.of("--audio", "--dither");
        }

        return List.of();
    }
}

