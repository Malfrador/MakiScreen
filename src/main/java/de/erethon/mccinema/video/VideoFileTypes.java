package de.erethon.mccinema.video;

import java.util.Locale;
import java.util.Set;

public final class VideoFileTypes {
    private static final Set<String> EXTENSIONS = Set.of(".mp4", ".mkv", ".avi", ".webm", ".mov");

    private VideoFileTypes() {}

    public static boolean isSupported(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
