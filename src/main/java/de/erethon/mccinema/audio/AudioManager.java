package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AudioManager {

    public static final int DEFAULT_CHUNK_DURATION_MS = 10000; // 10 seconds per chunk by default
    public static final String SOUND_NAMESPACE = "mcc";

    private final MCCinema plugin;
    private final Screen screen;
    private final String videoId;
    private final File audioDir;
    private final List<AudioChunk> chunks = new ArrayList<>();
    private final int chunkDurationMs; // 0 = single file mode
    private final boolean positionalAudio; // broken right now
    private Set<UUID> targetPlayerIds;
    private Predicate<Player> playbackFilter = player -> true;

    public void setPlaybackFilter(Predicate<Player> filter) {
        playbackFilter = filter;
    }

    private final AtomicInteger currentChunkIndex = new AtomicInteger(-1);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final Map<UUID, Set<Integer>> startedChunks = new ConcurrentHashMap<>();
    private BukkitTask chunkScheduler;

    private long totalDurationMs;
    private long pausedAtMs;
    private long playbackStartTime;

    /**
     * Creates an AudioManager with configurable chunk duration.
     * @param chunkDurationMs Duration of each chunk in milliseconds. Use 0 for single file mode (no chunking).
     * @param positionalAudio If true, audio is extracted as mono for 3D positional playback. If false, stereo is kept for global playback.
     */
    public AudioManager(MCCinema plugin, String videoId, int chunkDurationMs, boolean positionalAudio, Screen screen) {
        this.plugin = plugin;
        this.videoId = videoId;
        this.chunkDurationMs = chunkDurationMs;
        this.positionalAudio = positionalAudio;
        this.screen = screen;
        // Include chunk duration and audio mode in folder name to separate cached files
        String chunkSuffix = chunkDurationMs == 0 ? "_single" : "_" + chunkDurationMs + "ms";
        String audioSuffix = positionalAudio ? "_mono" : "_stereo";
        this.audioDir = new File(plugin.getDataFolder(), "audio/" + videoId + chunkSuffix + audioSuffix);
    }

    public int getChunkDurationMs() {
        return chunkDurationMs;
    }

    public boolean isSingleFileMode() {
        return chunkDurationMs == 0;
    }

    public String getVideoId() {
        return videoId;
    }

    public void setTargetPlayerIds(Collection<UUID> targetPlayerIds) {
        if (targetPlayerIds == null || targetPlayerIds.isEmpty()) {
            this.targetPlayerIds = null;
            return;
        }
        this.targetPlayerIds = new LinkedHashSet<>(targetPlayerIds);
    }

    public boolean extractAndSplitAudio(File videoFile) {
        return extractAndSplitAudio(videoFile, null);
    }

    public boolean extractAndSplitAudio(File videoFile, AudioProgressListener progressListener) {
        plugin.getLogger().info("Extracting audio from video" +
                                (isSingleFileMode() ? " (single file mode)" : " (chunk duration: " + chunkDurationMs + "ms)") + "...");
        try {
            AudioExtractor extractor = new AudioExtractor(
                plugin,
                videoFile,
                audioDir,
                chunkDurationMs,
                positionalAudio,
                progressListener == null ? null :
                    (stage, percent, detail) -> progressListener.onProgress(
                        stage.description(), percent, detail)
            );
            AudioExtractor.Result result = extractor.extract();
            totalDurationMs = result.durationMs();
            chunks.clear();
            chunks.addAll(result.chunks());
            plugin.getLogger().info("Audio extraction complete: " + chunks.size() + " chunk(s) created and cached");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to extract audio: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @FunctionalInterface
    public interface AudioProgressListener {
        void onProgress(String stage, int percent, String detail);
    }

    public File generateResourcePack() {
        if (chunks.isEmpty()) {
            return null;
        }

        File packDir = new File(plugin.getDataFolder(), "resourcepack");

        // Always start with a clean pack directory so that audio from previously-played
        // videos does not accumulate
        if (packDir.exists()) {
            try {
                Files.walk(packDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to clean resource pack directory: " + e.getMessage());
            }
        }

        File soundsDir = new File(packDir, "assets/" + SOUND_NAMESPACE + "/sounds/" + videoId);
        soundsDir.mkdirs();

        try {
            for (AudioChunk chunk : chunks) {
                File dest = new File(soundsDir, "chunk_" + chunk.index() + ".ogg");
                Files.copy(chunk.file().toPath(), dest.toPath(),
                          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            File packMeta = new File(packDir, "pack.mcmeta");
            String mcmeta = """
                {
                    "pack": {
                        "pack_format": 34,
                        "description": "MCCinema Audio Pack"
                    }
                }
                """;
            Files.writeString(packMeta.toPath(), mcmeta);

            File soundsJson = new File(packDir, "assets/" + SOUND_NAMESPACE + "/sounds.json");
            soundsJson.getParentFile().mkdirs();

            StringBuilder json = new StringBuilder("{\n");
            for (int i = 0; i < chunks.size(); i++) {
                AudioChunk chunk = chunks.get(i);
                String soundName = videoId + ".chunk_" + chunk.index();

                json.append("  \"").append(soundName).append("\": {\n");
                json.append("    \"sounds\": [\n");
                json.append("      {\n");
                json.append("        \"name\": \"").append(SOUND_NAMESPACE).append(":")
                    .append(videoId).append("/chunk_").append(chunk.index()).append("\",\n");
                // Preloading every chunk of a long video stalls resource reloads.
                json.append("        \"preload\": ").append(chunk.index() == 0).append(",\n");
                json.append("        \"stream\": false\n");
                json.append("      }\n");
                json.append("    ]\n");
                json.append("  }");

                if (i < chunks.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
            }
            json.append("}\n");

            Files.writeString(soundsJson.toPath(), json.toString());
            File zipFile = new File(plugin.getDataFolder(), "mcc_audio_" + videoId + ".zip");
            createZip(packDir, zipFile);

            plugin.getLogger().info("Resource pack generated: " + zipFile.getName());
            return zipFile;

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to generate resource pack: " + e.getMessage());
            return null;
        }
    }

    private void createZip(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            Path sourcePath = sourceDir.toPath();
            Files.walk(sourcePath)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    ZipEntry entry = new ZipEntry(sourcePath.relativize(path).toString()
                        .replace("\\", "/"));
                    try {
                        zos.putNextEntry(entry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    }

    public void play(Location location) {
        if (chunks.isEmpty() || isPlaying.get()) {
            return;
        }

        isPlaying.set(true);
        currentChunkIndex.set(0);
        playbackStartTime = System.currentTimeMillis();

        playChunk(0, location);

        // Only schedule chunk transitions in chunked mode
        if (!isSingleFileMode()) {
            scheduleNextChunks(location);
        }
    }

    public void pause() {
        if (!isPlaying.get()) {
            return;
        }

        isPlaying.set(false);
        pausedAtMs = System.currentTimeMillis() - playbackStartTime;

        if (chunkScheduler != null) {
            chunkScheduler.cancel();
        }

        stopAllSounds();
    }

    public void resume(Location location) {
        if (isPlaying.get() || chunks.isEmpty()) {
            return;
        }

        isPlaying.set(true);

        // For single file mode, just resume from beginning
        int chunkIndex = isSingleFileMode() ? 0 : (int) (pausedAtMs / chunkDurationMs);
        currentChunkIndex.set(chunkIndex);

        playbackStartTime = System.currentTimeMillis() - pausedAtMs;

        playChunk(chunkIndex, location);
        if (!isSingleFileMode()) {
            scheduleNextChunks(location);
        }
    }

    public void stop() {
        isPlaying.set(false);
        currentChunkIndex.set(-1);

        if (chunkScheduler != null) {
            chunkScheduler.cancel();
        }

        stopAllSounds();
    }

    public void seekTo(long timeMs, Location location) {
        boolean wasPlaying = isPlaying.get();
        seekTo(timeMs, location, wasPlaying);
    }

    public void seekTo(long timeMs, Location location, boolean resumePlayback) {
        stop();
        completeSeek(timeMs, location, resumePlayback);
    }

    public void completeSeek(long timeMs, Location location, boolean resumePlayback) {
        pausedAtMs = timeMs;

        if (resumePlayback) {
            resume(location);
        }
    }

    private void playChunk(int index, Location location) {
        if (index < 0 || index >= chunks.size()) {
            return;
        }

        AudioChunk chunk = chunks.get(index);
        String key = SOUND_NAMESPACE + ":" + videoId + ".chunk_" + chunk.index();
        for (Player player : getAudioRecipients()) {
            if (playbackFilter.test(player)) {
                player.playSound(location, key, SoundCategory.RECORDS, 1.0f, 1.0f);
                startedChunks.computeIfAbsent(player.getUniqueId(), id -> ConcurrentHashMap.newKeySet())
                    .add(chunk.index());
            }
        }
    }

    private void stopAllSounds() {
        for (var entry : startedChunks.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            for (int index : entry.getValue()) {
                player.stopSound(SOUND_NAMESPACE + ":" + videoId + ".chunk_" + index, SoundCategory.RECORDS);
            }
        }
        startedChunks.clear();
    }

    private Collection<Player> getAudioRecipients() {
        if (targetPlayerIds == null || targetPlayerIds.isEmpty()) {
            return screen.getViewers();
        }

        List<Player> players = new ArrayList<>(targetPlayerIds.size());
        for (UUID playerId : targetPlayerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private void scheduleNextChunks(Location location) {
        chunkScheduler = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isPlaying.get()) {
                    cancel();
                    return;
                }

                long elapsedMs = System.currentTimeMillis() - playbackStartTime;
                int current = currentChunkIndex.get();

                int expectedChunk = (int) (elapsedMs / chunkDurationMs);

                if (expectedChunk > current && expectedChunk < chunks.size()) {
                    currentChunkIndex.set(expectedChunk);
                    playChunk(expectedChunk, location);

                    long expectedMs = (long) expectedChunk * chunkDurationMs;
                    plugin.getLogger().fine("Chunk " + expectedChunk + " triggered at " + elapsedMs +
                                           "ms (expected: " + expectedMs + "ms, drift: " + (elapsedMs - expectedMs) + "ms)");
                }

                if (expectedChunk >= chunks.size()) {
                    isPlaying.set(false);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void cleanup() {
        stop();
    }

    public record AudioChunk(int index, long startMs, long durationMs, File file) {}
}

