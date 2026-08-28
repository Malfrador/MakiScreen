package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.download.LivestreamResolver;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.resourcepack.ResourcePackManager;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.VideoPlayer;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Locale;

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
        setMaxArgs(99);
        setHelp("/mcc play <screen> <file-or-url> [--audio [chunk_seconds|single]] [--dither <mode>] [players...]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc play <screen> <file-or-url> [--audio [chunk_seconds|single]] [--dither <mode>] [players...]"));
            return;
        }

        String screenName = args[1];
        String fileName = args[2];

        // Parse optional flags
        boolean audioFlag = false;
        boolean acceptTools = false;
        int chunkDurationMs = AudioManager.DEFAULT_CHUNK_DURATION_MS;
        FrameProcessor.DitheringMode ditheringMode = FrameProcessor.DitheringMode.FLOYD_STEINBERG_REDUCED; // Default
        List<String> targetPlayerNames = new ArrayList<>();

        // Parse arguments for --audio, --dither, and optional target players
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--audio")) {
                audioFlag = true;
                // Check for chunk duration argument
                if (i + 1 < args.length && isAudioChunkArgument(args[i + 1])) {
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
            } else if (args[i].equalsIgnoreCase("--accept-tools")) {
                acceptTools = true;
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
            } else if (args[i].startsWith("--")) {
                sender.sendMessage(MM.deserialize("<red>Unknown option: " + args[i]));
                return;
            } else {
                targetPlayerNames.add(args[i]);
            }
        }

        final boolean withAudio = audioFlag;
        Set<UUID> targetPlayerIds = resolveTargetPlayers(targetPlayerNames, sender);
        if (targetPlayerIds == null) {
            return;
        }

        Optional<Screen> screenOpt = plugin.getScreenManager().getScreen(screenName);
        if (screenOpt.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Screen '" + screenName + "' not found!"));
            return;
        }

        Screen screen = screenOpt.get();

        if (isUrl(fileName)) {
            String continuationCommand = "/mcc " + String.join(" ", args) +
                (acceptTools ? "" : " --accept-tools");
            playLivestream(sender, screen, fileName, withAudio, ditheringMode, targetPlayerIds,
                acceptTools, continuationCommand);
            return;
        }

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
        Set<UUID> finalTargetPlayerIds = targetPlayerIds;
        new BukkitRunnable() {
            @Override
            public void run() {
                VideoPlayer player = new VideoPlayer(plugin, screen);
                player.setTargetPlayerIds(finalTargetPlayerIds);

                QualityCommand.applyScreenPreset(player, screen);

                // Set dithering mode before processing any frames
                player.getFrameProcessor().setDitheringMode(finalDitheringMode);

                AudioManager audioManager = null;

                if (withAudio) {
                    String videoId = finalVideoFile.getName().replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
                    audioManager = new AudioManager(plugin, videoId, finalChunkDurationMs, false, screen);

                    String modeInfo = audioManager.isSingleFileMode() ? "single file" :
                                     (finalChunkDurationMs / 1000) + "s chunks";
                    sender.sendMessage(MM.deserialize("<yellow>Extracting audio (" + modeInfo + ", global stereo)..."));


                    if (audioManager.extractAndSplitAudio(finalVideoFile, createAudioProgressListener(sender))) {
                        File resourcePack = audioManager.generateResourcePack();
                        if (resourcePack != null) {
                            // Host resource pack with configured hosting mode
                            ResourcePackManager rpManager = plugin.getResourcePackManager();
                            if (rpManager != null) {
                                AtomicBoolean uploadStarted = new AtomicBoolean(false);
                                ResourcePackManager.HostedResourcePack hostedPack =
                                    rpManager.hostResourcePack(videoId, resourcePack,
                                        () -> { uploadStarted.set(true); sender.sendMessage(MM.deserialize("<yellow>Uploading resource pack to mcpacks.dev...")); },
                                        () -> sender.sendMessage(MM.deserialize("<yellow>Waiting for mcpacks.dev...")));

                                if (hostedPack != null) {
                                    if (!uploadStarted.get()) {
                                        sender.sendMessage(MM.deserialize("<green>✓ Using cached mcpacks.dev upload"));
                                    }
                                    sender.sendMessage(MM.deserialize(
                                        "<green>✓ Audio resource pack ready!" +
                                        "\n<gray>Mode: <white>" + hostedPack.mode() +
                                        "\n<gray>URL: ").append(
                                        Component.text(hostedPack.url())
                                            .color(NamedTextColor.WHITE)
                                            .decorate(TextDecoration.UNDERLINED)
                                            .clickEvent(ClickEvent.openUrl(hostedPack.url()))
                                            .hoverEvent(HoverEvent.showText(
                                                Component.text("Click to open URL")))
                                    ));
                                } else {
                                    sender.sendMessage(MM.deserialize(
                                        "<yellow>⚠ Failed to host resource pack!"));
                                }
                            } else {
                                sender.sendMessage(MM.deserialize(
                                    "<yellow>⚠ Resource pack hosting is not enabled!" +
                                    "\n<gray>Enable it in config.yml under 'resourcepack.enabled'"));
                            }
                        }
                        player.setAudioManager(audioManager);
                    } else {
                        sender.sendMessage(MM.deserialize(
                            "<yellow>⚠ Audio conversion failed; starting video without audio."));
                        audioManager = null;
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
                            } else if (p.getState() == VideoPlayer.State.STOPPED || p.getState() == VideoPlayer.State.IDLE) {
                                stopProgressBar();
                            }
                        });

                        // Send resource pack to all viewers
                        if (withAudio && finalAudioManager != null) {
                            ResourcePackManager rpManager = plugin.getResourcePackManager();
                            if (rpManager != null && plugin.getConfig().getBoolean("resourcepack.auto-apply", true)) {
                                String videoId = finalAudioManager.getVideoId();
                                ResourcePackManager.HostedResourcePack hostedPack = rpManager.getHostedPack(videoId);

                                if (hostedPack != null) {
                                    String url = hostedPack.url();
                                    byte[] hash = hostedPack.hash();

                                    boolean required = plugin.getConfig().getBoolean("resourcepack.required", false);

                                    // Update viewer cache before sending resource pack
                                    screen.updateViewerCache();

                                    // Collect player UUIDs to track
                                    Set<UUID> playerIds = new HashSet<>();
                                    Collection<Player> viewers = getPlaybackViewers(player);

                                    if (viewers.isEmpty()) {
                                        sender.sendMessage(MM.deserialize(
                                            player.hasTargetPlayerLimit()
                                                ? "<yellow>⚠ No selected players are online to send resource pack to"
                                                : "<yellow>⚠ No nearby players found to send resource pack to"));
                                        // Start playback immediately if no viewers
                                        player.play();
                                        String audioInfo = finalAudioManager != null ? "\n<gray>  Audio: <green>✓ Enabled" : "";
                                        String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                                        String viewerInfo = formatViewerInfo(player);
                                        sender.sendMessage(MM.deserialize(
                                            "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                                            "\n<gray>  On screen: <white>" + screen.getName() +
                                            "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                                            "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                                            ditherInfo +
                                            viewerInfo +
                                            audioInfo
                                        ));
                                        return;
                                    }

                                    for (Player p : viewers) {
                                        p.addResourcePack(UUID.randomUUID(),url, hash, "This video requires a resource pack for audio playback", required);
                                        playerIds.add(p.getUniqueId());
                                    }

                                    String recipientLabel = player.hasTargetPlayerLimit() ? " selected player(s)" : " nearby player(s)";
                                    sender.sendMessage(MM.deserialize(
                                        "<green>✓ Resource pack sent to " + playerIds.size() + recipientLabel));
                                    sender.sendMessage(MM.deserialize(
                                        "<yellow>⏳ Waiting for players to load resource pack..."));

                                    // Wait for resource pack to load before starting playback
                                    plugin.getResourcePackListener().trackResourcePackLoad(
                                        videoId + "_" + System.currentTimeMillis(),
                                        playerIds,
                                        (success) -> {
                                            new BukkitRunnable() {
                                                @Override
                                                public void run() {
                                                    player.play();

                                                    String audioInfo = finalAudioManager != null ? "\n<gray>  Audio: <green>✓ Enabled" : "";
                                                    String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                                                    String viewerInfo = formatViewerInfo(player);
                                                    String loadStatus = success ? "<green>✓ Resource pack loaded" : "<yellow>⚠ Started without waiting (timeout)";
                                                    sender.sendMessage(MM.deserialize(
                                                        "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                                                        "\n<gray>  On screen: <white>" + screen.getName() +
                                                        "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                                                        "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                                                        ditherInfo +
                                                        viewerInfo +
                                                        audioInfo +
                                                        "\n<gray>  " + loadStatus
                                                    ));
                                                }
                                            }.runTask(plugin);
                                        },
                                        200L // 10 second timeout
                                    );
                                    return;
                                }
                            }
                        }

                        // No audio or resource pack not sent
                        player.play();

                        String audioInfo = finalAudioManager != null ? "\n<gray>  Audio: <green>✓ Enabled" : "";
                        String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(finalDitheringMode);
                        String viewerInfo = formatViewerInfo(player);
                        sender.sendMessage(MM.deserialize(
                            "<green>▶ Now playing: <white>" + finalVideoFile.getName() +
                            "\n<gray>  On screen: <white>" + screen.getName() +
                            "\n<gray>  Duration: <white>" + VideoPlayer.formatDuration(player.getTotalDurationMs()) +
                            "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
                            ditherInfo +
                            viewerInfo +
                            audioInfo
                        ));
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private AudioManager.AudioProgressListener createAudioProgressListener(CommandSender sender) {
        AtomicReference<String> lastStage = new AtomicReference<>("");
        AtomicInteger lastBucket = new AtomicInteger(-1);
        AtomicLong lastPlayerUpdateNanos = new AtomicLong();

        return (stage, percent, detail) -> {
            boolean playerSender = sender instanceof Player;
            int bucketSize = playerSender ? 1 : 10;
            int bucket = percent / bucketSize;
            boolean stageChanged = !stage.equals(lastStage.getAndSet(stage));
            long now = System.nanoTime();
            boolean heartbeatDue = playerSender
                && now - lastPlayerUpdateNanos.get() >= TimeUnit.SECONDS.toNanos(1);
            if (!stageChanged && percent < 100 && bucket == lastBucket.get() && !heartbeatDue) {
                return;
            }
            lastBucket.set(bucket);
            if (playerSender) {
                lastPlayerUpdateNanos.set(now);
            }

            new BukkitRunnable() {
                @Override
                public void run() {
                    Component message = MM.deserialize(
                        "<yellow>Audio: <white>" + stage + " <gold>" + percent + "%</gold>" +
                        (detail == null || detail.isBlank() ? "" : " <gray>(" + detail + ")")
                    );
                    if (sender instanceof Player player) {
                        player.sendActionBar(message);
                    } else {
                        sender.sendMessage(message);
                    }
                }
            }.runTask(plugin);
        };
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

        for (Player p : getPlaybackViewers(player)) {
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

                float progress = player.isLiveStream() ? 1f : (float) player.getProgress();
                progressBar.progress(Math.min(1f, Math.max(0f, progress)));
                progressBar.color(BossBar.Color.GREEN);

                String currentTime = VideoPlayer.formatDuration(player.getCurrentTimeMs());
                String totalTime = player.isLiveStream() ? "LIVE" : VideoPlayer.formatDuration(player.getTotalDurationMs());

                if (player.isLiveStream()) {
                    progressBar.name(MM.deserialize("<red>LIVE</red> <white>" + currentTime + "</white>"));
                } else {
                    progressBar.name(MM.deserialize(
                        "<green>▶</green> <white>" + currentTime + " / " + totalTime + "</white>"
                    ));
                }
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
            case ATKINSON -> "Atkinson (Cleaner, Less Noise)";
            case STUCKI -> "Stucki (Smoother Gradients)";
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
            String prevArg = args[args.length - 2];
            String currentArg = args[args.length - 1];

            if (prevArg.equalsIgnoreCase("--audio")) {
                return filterCompletions(currentArg, combinedCompletions(
                    List.of("single", "5", "10", "15", "30", "60"),
                    onlinePlayerNames()
                ));
            }

            if (prevArg.equalsIgnoreCase("--dither")) {
                return Arrays.stream(FrameProcessor.DitheringMode.values())
                    .map(Enum::name)
                    .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                    .toList();
            }

            return filterCompletions(currentArg, combinedCompletions(
                List.of("--audio", "--dither"),
                onlinePlayerNames()
            ));
        }

        return List.of();
    }

    private void playLivestream(CommandSender sender, Screen screen, String sourceUrl, boolean requestedAudio,
                                FrameProcessor.DitheringMode ditheringMode, Set<UUID> targetPlayerIds,
                                boolean acceptTools, String continuationCommand) {
        YoutubeDownloadManager downloadManager = plugin.getYoutubeDownloadManager();

        if (acceptTools) {
            downloadManager.saveUserConsent(true);
        }

        if (!downloadManager.hasUserConsent()) {
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            sender.sendMessage(MM.deserialize("<gold><bold>One-time media setup"));
            sender.sendMessage(MM.deserialize("<gray>MCCinema will download verified, self-contained yt-dlp and Deno tools from their official GitHub releases."));
            sender.sendMessage(Component.text("[INSTALL AND PLAY]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(continuationCommand)));
            sender.sendMessage(MM.deserialize("<gray>Console: <white>" + escape(continuationCommand)));
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            return;
        }

        if (downloadManager.isReady()) {
            resolveAndLoadLivestream(sender, screen, sourceUrl, requestedAudio, ditheringMode, targetPlayerIds);
            return;
        }

        sender.sendMessage(MM.deserialize("<yellow>Preparing media support... Playback will continue automatically."));
        downloadManager.initializeAsync().whenComplete((ignored, error) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    sender.sendMessage(MM.deserialize("<red>Media setup failed. <gray>" +
                        escape(downloadManager.userMessage(error))));
                    downloadManager.logYtDlpFailure("initialize media support", sourceUrl, error);
                    return;
                }
                resolveAndLoadLivestream(sender, screen, sourceUrl, requestedAudio, ditheringMode, targetPlayerIds);
            })
        );
    }

    private void resolveAndLoadLivestream(CommandSender sender, Screen screen, String sourceUrl,
                                          boolean requestedAudio, FrameProcessor.DitheringMode ditheringMode,
                                          Set<UUID> targetPlayerIds) {
        if (requestedAudio) {
            sender.sendMessage(MM.deserialize("<yellow>Live audio is not supported yet; playing the video stream only."));
        }
        sender.sendMessage(MM.deserialize("<yellow>Connecting to livestream..."));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            VideoPlayer player = null;
            try {
                LivestreamResolver resolver = new LivestreamResolver(plugin, plugin.getYoutubeDownloadManager());
                LivestreamResolver.Livestream livestream = resolver.resolve(sourceUrl);

                player = new VideoPlayer(plugin, screen);
                player.setTargetPlayerIds(targetPlayerIds);
                QualityCommand.applyScreenPreset(player, screen);
                player.getFrameProcessor().setDitheringMode(ditheringMode);
                player.setLiveStreamRefreshHandler(() -> {
                    LivestreamResolver.Livestream refreshed = resolver.resolve(sourceUrl);
                    return new VideoPlayer.LiveStreamSource(refreshed.streamUrl(), refreshed.headers());
                });
                if (!player.loadLiveStream(livestream.streamUrl(), livestream.displayName(), livestream.headers())) {
                    player.shutdown();
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MM.deserialize("<red>Could not connect to the livestream. <gray>See the server log for details."))
                    );
                    return;
                }

                VideoPlayer loadedPlayer = player;
                plugin.getServer().getScheduler().runTask(plugin, () -> activateLivestream(
                    sender, screen, loadedPlayer, livestream, ditheringMode
                ));
            } catch (Exception error) {
                if (player != null) {
                    player.shutdown();
                }
                YoutubeDownloadManager manager = plugin.getYoutubeDownloadManager();
                manager.logYtDlpFailure("resolve livestream", sourceUrl, error);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(MM.deserialize("<red>Could not play livestream. <gray>" +
                        escape(manager.userMessage(error))));
                });
            }
        });
    }

    private void activateLivestream(CommandSender sender, Screen screen, VideoPlayer player,
                                    LivestreamResolver.Livestream livestream,
                                    FrameProcessor.DitheringMode ditheringMode) {
        VideoPlayer existingPlayer = plugin.getVideoPlayer(screen);
        if (existingPlayer != null && existingPlayer != player) {
            existingPlayer.shutdown();
        }
        plugin.registerVideoPlayer(screen, player);

        player.setOnComplete(p -> {
            stopProgressBar();
            Bukkit.broadcast(MM.deserialize("<gray>Livestream playback ended."));
        });
        player.setOnStateChange(p -> {
            if (p.getState() == VideoPlayer.State.PLAYING) {
                startProgressBar(p);
            } else if (p.getState() == VideoPlayer.State.STOPPED || p.getState() == VideoPlayer.State.IDLE) {
                stopProgressBar();
            }
        });

        player.play();
        String ditherInfo = "\n<gray>  Dithering: <white>" + formatDitheringMode(ditheringMode);
        String viewerInfo = formatViewerInfo(player);
        sender.sendMessage(MM.deserialize(
            "<green>▶ Now playing live: <white>" + escape(livestream.displayName()) +
            "\n<gray>  On screen: <white>" + escape(screen.getName()) +
            "\n<gray>  Duration: <red>LIVE" +
            "\n<gray>  Frame rate: <white>" + String.format("%.1f", player.getFrameRate()) + " fps" +
            ditherInfo + viewerInfo +
            "\n<gray>  Audio: <yellow>Not supported for livestreams"
        ));
    }

    private boolean isUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String escape(String value) {
        return MM.escapeTags(value == null ? "Unknown media error" : value);
    }

    private boolean isAudioChunkArgument(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("single") || lower.equals("0")) {
            return true;
        }
        try {
            Integer.parseInt(lower);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Set<UUID> resolveTargetPlayers(List<String> names, CommandSender sender) {
        if (names.isEmpty()) {
            return Set.of();
        }

        Set<UUID> playerIds = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null || !player.isOnline()) {
                missing.add(name);
                continue;
            }
            playerIds.add(player.getUniqueId());
        }

        if (!missing.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Unknown or offline player(s): " + String.join(", ", missing)));
            return null;
        }

        return playerIds;
    }

    private Collection<Player> getPlaybackViewers(VideoPlayer player) {
        return player.hasTargetPlayerLimit() ? player.getPlaybackViewers() : player.getScreen().getViewers();
    }

    private String formatViewerInfo(VideoPlayer player) {
        if (!player.hasTargetPlayerLimit()) {
            return "";
        }
        return "\n<gray>  Viewers: <white>" + player.getPlaybackViewers().size() + " selected";
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private List<String> combinedCompletions(List<String> first, List<String> second) {
        List<String> completions = new ArrayList<>(first.size() + second.size());
        completions.addAll(first);
        completions.addAll(second);
        return completions;
    }

    private List<String> filterCompletions(String currentArg, List<String> completions) {
        String lower = currentArg.toLowerCase(Locale.ROOT);
        return completions.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
            .toList();
    }
}

