package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.download.DownloadProgressCallback;
import de.erethon.mccinema.download.DownloadTask;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.download.YoutubeDownloadManager.VideoInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;

public class DownloadCommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public void onExecute(DownloadRequest request, CommandSender sender) {
        YoutubeDownloadManager downloadManager = plugin.getYoutubeDownloadManager();

        if (request.acceptTools()) {
            downloadManager.saveUserConsent(true);
        }

        if (!downloadManager.hasUserConsent()) {
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            sender.sendMessage(MM.deserialize("<gold><bold>One-time media setup"));
            sender.sendMessage(MM.deserialize("<gray>MCCinema will download verified, self-contained yt-dlp and Deno tools from their official GitHub releases."));
            String command = "/mcc download --accept-tools " + request.source() +
                (request.customName() != null ? " " + request.customName() : "") +
                (request.convertFps() ? "" : " --no-convert");
            sender.sendMessage(Component.text("[INSTALL AND CONTINUE]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command)));
            sender.sendMessage(MM.deserialize("<gray>Console: <white>" + escape(command)));
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            return;
        }

        final String requestedUrl = request.source();
        final String requestedName = request.customName();
        final boolean requestedConversion = request.convertFps();
        if (downloadManager.isReady()) {
            startDownload(sender, downloadManager, requestedUrl, requestedName, requestedConversion);
            return;
        }

        sender.sendMessage(MM.deserialize("<yellow>Preparing media support... Your download will start automatically."));
        downloadManager.initializeAsync().whenComplete((ignored, error) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    sender.sendMessage(MM.deserialize("<red>Media setup failed. <gray>" +
                        escape(downloadManager.userMessage(error))));
                    downloadManager.logYtDlpFailure("initialize media support", requestedUrl, error);
                    return;
                }
                startDownload(sender, downloadManager, requestedUrl, requestedName, requestedConversion);
            })
        );
    }

    private void startDownload(CommandSender sender, YoutubeDownloadManager downloadManager, String videoIdOrUrl,
                               String customName, boolean convertFps) {
        sender.sendMessage(MM.deserialize("<yellow>Fetching video information..."));

        // Start the download
        DownloadTask task = downloadManager.downloadVideo(videoIdOrUrl, customName, convertFps, new DownloadProgressCallback() {
            private int lastReportedProgress = 0;

            @Override
            public void onProgress(int progress) {
                // Report every 10%
                if (progress >= lastReportedProgress + 10) {
                    lastReportedProgress = progress;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(MM.deserialize(
                                "<yellow>Downloading: <white>" + progress + "%"
                            ));
                        }
                    }.runTask(plugin);
                }
            }

            @Override
            public void onConversionStart(String message) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(MM.deserialize(
                            "<yellow>" + message
                        ));
                    }
                }.runTask(plugin);
            }

            @Override
            public void onComplete(File file) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(MM.deserialize(
                            "<green>✓ Download complete!"
                        ));
                        sender.sendMessage(MM.deserialize(
                            "<gray>File: <white>" + file.getName()
                        ));
                        sender.sendMessage(MM.deserialize(
                            "<gray>You can now play it with: <white>/mcc play <screen> " + file.getName()
                        ));
                    }
                }.runTask(plugin);
            }

            @Override
            public void onError(Throwable error) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(MM.deserialize("<red>✗ Download failed!"));
                        sender.sendMessage(MM.deserialize("<gray>" + escape(downloadManager.userMessage(error))));
                    }
                }.runTask(plugin);
            }
        });

        // Show video info once fetched
        new BukkitRunnable() {
            @Override
            public void run() {
                if (task.getVideoInfo() != null) {
                    VideoInfo info = task.getVideoInfo();
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(MM.deserialize("<gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                            sender.sendMessage(MM.deserialize("<yellow>Video: <white>" + escape(info.title)));
                            sender.sendMessage(MM.deserialize("<yellow>Author: <white>" + escape(info.uploader)));
                            sender.sendMessage(MM.deserialize("<yellow>Duration: <white>" + formatDuration(info.duration)));
                            sender.sendMessage(MM.deserialize("<gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                            sender.sendMessage(MM.deserialize("<yellow>Starting download..."));
                        }
                    }.runTask(plugin);
                    cancel();
                } else if (task.getError() != null) {
                    cancel();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 10L);
    }

    private String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }

    private static String escape(String value) {
        return MM.escapeTags(value == null ? "Unknown media error" : value);
    }
}
