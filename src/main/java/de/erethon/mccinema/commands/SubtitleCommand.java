package de.erethon.mccinema.commands;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import de.erethon.mccinema.video.SubtitleCatalog;
import de.erethon.mccinema.video.SubtitleSettingsManager;
import de.erethon.mccinema.video.VideoPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Locale;

/** Viewer action-bar choices and saved administrator defaults. */
public final class SubtitleCommand {
    private final MCCinema plugin = MCCinema.getInstance();

    public void show(Player viewer, Screen screen) {
        VideoPlayer video = playable(screen, viewer);
        if (video == null) return;
        SubtitleCatalog catalog = video.getSubtitleCatalog();
        String selected = video.getPersonalSubtitleTrack(viewer.getUniqueId());
        SubtitleSettingsManager.Setting global = video.getSubtitleSetting();
        viewer.sendMessage(Component.text("Subtitle tracks: " + String.join(", ", catalog.names()), NamedTextColor.GOLD));
        viewer.sendMessage(Component.text(global.enabled()
            ? "Screen subtitles: " + (global.track() == null ? catalog.firstName() : global.track()) +
                " (admin controlled)"
            : "Your action-bar subtitles: " + (selected == null ? "off" : selected), NamedTextColor.GRAY));
        viewer.sendMessage(Component.text("Use /mcc subtitle " + screen.getName() +
            " on|off or select <track>.", NamedTextColor.GRAY));
    }

    public void personal(Player viewer, Screen screen, String action, String track) {
        VideoPlayer video = playable(screen, viewer);
        if (video == null) return;
        if (video.getSubtitleSetting().enabled()) {
            viewer.sendMessage(Component.text("Subtitles are displayed on this screen by an admin setting.",
                NamedTextColor.YELLOW));
            return;
        }
        String selected;
        if (action.equals("off")) selected = null;
        else if (action.equals("on")) selected = video.getSubtitleCatalog().firstName();
        else selected = track.strip().toLowerCase(Locale.ROOT);
        if (selected != null && video.getSubtitleCatalog().get(selected) == null) {
            viewer.sendMessage(Component.text("Unknown subtitle track. Available: " +
                String.join(", ", video.getSubtitleCatalog().names()), NamedTextColor.RED));
            return;
        }
        video.setPersonalSubtitleTrack(viewer.getUniqueId(), selected);
        viewer.sendMessage(Component.text("Your subtitles: " + (selected == null ? "off" : selected),
            NamedTextColor.GREEN));
    }

    public void global(CommandSender sender, Screen screen, String action, String track) {
        VideoPlayer video = playable(screen, sender);
        if (video == null) return;
        SubtitleSettingsManager.Setting old = video.getSubtitleSetting();
        String selected = track == null ? old.track() : track.strip().toLowerCase(Locale.ROOT);
        if (track != null && video.getSubtitleCatalog().get(selected) == null) {
            sender.sendMessage(Component.text("Unknown subtitle track. Available: " +
                String.join(", ", video.getSubtitleCatalog().names()), NamedTextColor.RED));
            return;
        }
        if (!action.equals("off") && (selected == null || video.getSubtitleCatalog().get(selected) == null)) {
            selected = video.getSubtitleCatalog().firstName();
        }
        SubtitleSettingsManager.Setting setting = new SubtitleSettingsManager.Setting(!action.equals("off"), selected);
        File source = video.getVideoFile();
        if (!plugin.getSubtitleSettingsManager().save(source, setting)) {
            sender.sendMessage(Component.text("Could not save this video's subtitle setting.", NamedTextColor.RED));
            return;
        }
        video.setGlobalSubtitleSetting(setting);
        sender.sendMessage(Component.text(setting.enabled()
            ? "Screen subtitles: " + selected + " (saved for this video)."
            : "Screen subtitles off (saved for this video). Viewers may use personal subtitles.",
            NamedTextColor.GREEN));
    }

    private VideoPlayer playable(Screen screen, CommandSender sender) {
        VideoPlayer video = plugin.getVideoPlayer(screen);
        if (video == null || video.getVideoFile() == null || video.getSubtitleCatalog().isEmpty()) {
            sender.sendMessage(Component.text("This screen has no local video with text subtitle tracks.",
                NamedTextColor.YELLOW));
            return null;
        }
        return video;
    }
}
