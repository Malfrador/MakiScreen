package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.download.DownloadProgressCallback;
import de.erethon.mccinema.download.DownloadTask;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import de.erethon.mccinema.download.YoutubeDownloadManager.VideoInfo;
import de.erethon.bedrock.command.ECommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;

public class DownloadCommand extends ECommand {

    private final MCCinema plugin = MCCinema.getInstance();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    public DownloadCommand() {
        setCommand("download");
        setAliases("dl");
        setPermission("mccinema.download");
        setPlayerCommand(true);
        setConsoleCommand(true);
        setMinArgs(1);
        setMaxArgs(99);
        setHelp("/mccinema download <youtube-url|video-id> [filename] [--no-convert]");
    }

    @Override
    public void onExecute(String[] args, CommandSender sender) {
        if (args.length < 2) {
            sender.sendMessage(MM.deserialize("<red>Usage: /mcc download <youtube-url|video-id> [filename] [--no-convert]"));
            sender.sendMessage(MM.deserialize("<gray>Examples:"));
            sender.sendMessage(MM.deserialize("<gray>  /mccinema download dQw4w9WgXcQ"));
            sender.sendMessage(MM.deserialize("<gray>  /mccinema download https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
            sender.sendMessage(MM.deserialize("<gray>  /mccinema download dQw4w9WgXcQ my_video"));
            sender.sendMessage(MM.deserialize("<gray>  /mccinema download dQw4w9WgXcQ my_video --no-convert"));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<yellow>Options:"));
            sender.sendMessage(MM.deserialize("<gray>  --no-convert  <white>Skip FPS conversion (keep original framerate)"));
            return;
        }

        String videoIdOrUrl = args[1];
        String customName = null;
        boolean convertFps = plugin.getConfig().getBoolean("youtube.auto-convert-fps", true);
        boolean acceptConsent = false;

        // Parse arguments
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--no-convert")) {
                convertFps = false;
            } else if (args[i].equalsIgnoreCase("--accept")) {
                acceptConsent = true;
            } else if (customName == null) {
                customName = args[i];
            }
        }

        YoutubeDownloadManager downloadManager = plugin.getYoutubeDownloadManager();

        // If user accepted consent, save it and initialize
        if (acceptConsent && !downloadManager.hasUserConsent()) {
            downloadManager.saveUserConsent(true);
            sender.sendMessage(MM.deserialize("<green>✓ Thank you! Downloading yt-dlp..."));
            sender.sendMessage(MM.deserialize("<gray>This may take a moment. Please wait..."));

            // Try to initialize now
            if (!downloadManager.ensureInitialized()) {
                sender.sendMessage(MM.deserialize("<red>Failed to start yt-dlp initialization. Please try again."));
                return;
            }

            // Wait a moment for initialization to start
            sender.sendMessage(MM.deserialize("<yellow>Initializing yt-dlp... Your download will start automatically once ready."));
        }

        // Check if yt-dlp needs to be initialized
        if (!downloadManager.ensureInitialized()) {
            // Need user consent
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            sender.sendMessage(MM.deserialize("<gold><bold>YouTube Download - First Time Setup"));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<white>To download YouTube videos, MCCinema needs to download <gold>yt-dlp</gold>,"));
            sender.sendMessage(MM.deserialize("<white>a free and open-source YouTube downloader."));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<gray>What will be downloaded:"));
            sender.sendMessage(MM.deserialize("<white>  • <gray>yt-dlp binary (<gold>~10MB</gold>)"));
            sender.sendMessage(MM.deserialize("<white>  • <gray>From: <gold>https://github.com/yt-dlp/yt-dlp"));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<white>To proceed with the download, please run:"));
            sender.sendMessage(MM.deserialize("<click:suggest_command:/mcc download --accept " + videoIdOrUrl + (customName != null ? " " + customName : "") + (convertFps ? "" : " --no-convert") + "><gold><bold>[CLICK HERE]</bold></gold></click> <gray>or type: <white>/mcc download --accept " + videoIdOrUrl));
            sender.sendMessage(MM.deserialize(""));
            sender.sendMessage(MM.deserialize("<gray>This is a one-time setup. You won't be asked again."));
            sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
            return;
        }

        if (!downloadManager.isReady()) {
            sender.sendMessage(MM.deserialize("<yellow>yt-dlp is still initializing... Please wait a moment and try again."));
            return;
        }

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

                        String errorMsg = error.getMessage();
                        if (errorMsg != null) {
                            if (errorMsg.contains("requires authentication") || errorMsg.contains("cookies are required") ||
                                errorMsg.contains("Sign in") || errorMsg.contains("login")) {
                                sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                                sender.sendMessage(MM.deserialize("<red>YouTube requires authentication!"));
                                sender.sendMessage(MM.deserialize(""));
                                sender.sendMessage(MM.deserialize("<yellow>Setup instructions:"));
                                sender.sendMessage(MM.deserialize("<white>1. <gray>Install browser extension: <white>'Get cookies.txt LOCALLY'"));
                                sender.sendMessage(MM.deserialize("<white>2. <gray>Go to <white>youtube.com <gray>and log in"));
                                sender.sendMessage(MM.deserialize("<white>3. <gray>Click the extension and export cookies"));
                                sender.sendMessage(MM.deserialize("<white>4. <gray>Save as: <white>youtube_cookies.txt"));
                                sender.sendMessage(MM.deserialize("<white>5. <gray>Place in: <white>" + plugin.getDataFolder().getAbsolutePath()));
                                sender.sendMessage(MM.deserialize("<white>6. <gray>Restart server or run: <white>/reload"));
                                sender.sendMessage(MM.deserialize(""));
                                sender.sendMessage(MM.deserialize("<gray>Chrome: <white>https://chromewebstore.google.com/detail/cclelndahbckbenkjhflpdbgdldlbecc"));
                                sender.sendMessage(MM.deserialize("<gray>Firefox: <white>https://addons.mozilla.org/en-US/firefox/addon/cookies-txt/"));
                                sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                            } else if (errorMsg.contains("Signature solving failed") || errorMsg.contains("n challenge solving failed")) {
                                sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                                sender.sendMessage(MM.deserialize("<red>YouTube signature decryption issue detected!"));
                                sender.sendMessage(MM.deserialize(""));
                                sender.sendMessage(MM.deserialize("<yellow>This has been automatically handled. If you still see this error:"));
                                sender.sendMessage(MM.deserialize("<white>1. <gray>Update yt-dlp: Delete <white>yt-dlp.exe <gray>and restart server"));
                                sender.sendMessage(MM.deserialize("<white>2. <gray>Add YouTube cookies (optional but helps):"));
                                sender.sendMessage(MM.deserialize("   <gray>- Export cookies from browser using 'Get cookies.txt LOCALLY'"));
                                sender.sendMessage(MM.deserialize("   <gray>- Save as: <white>youtube_cookies.txt"));
                                sender.sendMessage(MM.deserialize("   <gray>- Place in: <white>" + plugin.getDataFolder().getAbsolutePath()));
                                sender.sendMessage(MM.deserialize("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
                            } else if (errorMsg.contains("not ready")) {
                                sender.sendMessage(MM.deserialize("<gray>yt-dlp is still initializing. Please wait and try again."));
                            } else {
                                sender.sendMessage(MM.deserialize("<gray>Error: " + errorMsg));
                            }
                        }
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
                            sender.sendMessage(MM.deserialize("<yellow>Video: <white>" + info.title));
                            sender.sendMessage(MM.deserialize("<yellow>Author: <white>" + info.uploader));
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
}
