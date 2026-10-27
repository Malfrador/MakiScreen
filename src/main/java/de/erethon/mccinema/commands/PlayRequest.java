package de.erethon.mccinema.commands;

import de.erethon.mccinema.video.FrameProcessor;

import java.util.List;

public record PlayRequest(
    String rawInput,
    String source,
    boolean withAudio,
    int chunkDurationMs,
    FrameProcessor.DitheringMode ditheringMode,
    boolean experimental,
    boolean fullscreen,
    boolean acceptTools,
    List<String> targetPlayerNames
) {
}
