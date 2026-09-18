package de.erethon.mccinema.commands;

public record DownloadRequest(
    String source,
    String customName,
    boolean convertFps,
    boolean acceptTools
) {
}
