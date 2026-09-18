package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.download.YoutubeDownloadManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class MediaCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final MCCinema plugin = MCCinema.getInstance();


    public void retry(CommandSender sender) {
        YoutubeDownloadManager manager = plugin.getYoutubeDownloadManager();
        if (!manager.hasUserConsent()) {
            sender.sendMessage(MM.deserialize("<yellow>Media tools have not been accepted yet. Start a URL once to begin setup."));
            return;
        }
        YoutubeDownloadManager.RepairRequest repair = manager.requestRepairAsync();
        if (!repair.started()) {
            sender.sendMessage(MM.deserialize("<yellow>Media tool setup is already running. <gray>Check <white>/mcc media<gray> shortly."));
            return;
        }
        sender.sendMessage(MM.deserialize("<yellow>Retrying media tool setup..."));
        repair.completion().whenComplete((ignored, error) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error == null) {
                    sender.sendMessage(MM.deserialize("<green>Media tools are ready."));
                } else {
                    sender.sendMessage(MM.deserialize("<red>Media setup failed. <gray>" +
                        MM.escapeTags(manager.userMessage(error))));
                    manager.logYtDlpFailure("initialize media support", "manual retry", error);
                }
            })
        );
    }

    public void show(CommandSender sender) {
        YoutubeDownloadManager manager = plugin.getYoutubeDownloadManager();
        String state = manager.isReady() ? "<green>Ready" : manager.isInitializing() ? "<yellow>Initializing" : "<red>Unavailable";
        sender.sendMessage(MM.deserialize("<gold><bold>MCCinema media support"));
        sender.sendMessage(MM.deserialize("<gray>Status: " + state));
        sender.sendMessage(MM.deserialize("<gray>yt-dlp: <white>" + value(manager.ytDlpVersion())));
        sender.sendMessage(MM.deserialize("<gray>JavaScript: <white>" + value(manager.jsRuntimeVersion())));
        String cookieStatus = manager.cookieStatusDescription();
        sender.sendMessage(MM.deserialize("<gray>YouTube cookies: <white>" + MM.escapeTags(cookieStatus)));
        if (!manager.hasUsableCookies() && !cookieStatus.equals("Not configured")) {
            sender.sendMessage(MM.deserialize("<gray>Export fresh YouTube cookies in Netscape format to <white>youtube_cookies.txt<gray>; no reload is needed."));
        }
        if (manager.initializationFailure() != null) {
            sender.sendMessage(MM.deserialize("<gray>Last setup error: <red>" +
                MM.escapeTags(manager.initializationFailure())));
            sender.sendMessage(MM.deserialize("<gray>Retry with <white>/mcc media retry"));
        }
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "Not available" : MM.escapeTags(value);
    }
}
