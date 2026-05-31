package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final AtomicInteger currentChunkIndex = new AtomicInteger(-1);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
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

    public boolean extractAndSplitAudio(File videoFile) {
        plugin.getLogger().info("Extracting audio from video" +
                               (isSingleFileMode() ? " (single file mode)" : " (chunk duration: " + chunkDurationMs + "ms)") + "...");

        if (!audioDir.exists()) {
            audioDir.mkdirs();
        }

        File cacheMarker = new File(audioDir, ".extraction_complete");
        if (cacheMarker.exists()) {
            plugin.getLogger().info("Audio chunks already cached, loading from disk...");

            File[] chunkFiles = audioDir.listFiles((dir, name) -> name.startsWith("chunk_") && name.endsWith(".ogg"));
            if (chunkFiles != null && chunkFiles.length > 0) {
                Arrays.sort(chunkFiles, Comparator.comparingInt(f -> {
                    String name = f.getName();
                    return Integer.parseInt(name.substring(6, name.length() - 4));
                }));

                try {
                    String durationStr = Files.readString(cacheMarker.toPath()).trim();
                    totalDurationMs = Long.parseLong(durationStr);
                } catch (Exception e) {
                    if (!isSingleFileMode()) {
                        totalDurationMs = (long) chunkFiles.length * chunkDurationMs;
                    }
                }

                for (int i = 0; i < chunkFiles.length; i++) {
                    long startMs = isSingleFileMode() ? 0 : (long) i * chunkDurationMs;
                    long durationMs = isSingleFileMode() ? totalDurationMs : chunkDurationMs;
                    chunks.add(new AudioChunk(i, startMs, durationMs, chunkFiles[i]));
                }

                plugin.getLogger().info("Loaded " + chunks.size() + " cached audio chunk(s)");
                return true;
            }
        }

        try {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
            grabber.start();

            if (grabber.getAudioChannels() == 0) {
                plugin.getLogger().info("Video has no audio track");
                grabber.close();
                return false;
            }

            totalDurationMs = grabber.getLengthInTime() / 1000;

            int sampleRate = grabber.getSampleRate();
            int channels = grabber.getAudioChannels();
            int sampleFormat = grabber.getSampleFormat();

            plugin.getLogger().info("Audio format - SampleRate: " + sampleRate +
                                   ", Channels: " + channels + ", SampleFormat: " + sampleFormat +
                                   " (FLTP=" + avutil.AV_SAMPLE_FMT_FLTP + ")");

            // Handle multi-channel audio (5.1, 7.1, etc.) by downmixing to stereo
            int outputChannels = channels;
            if (channels > 2) {
                plugin.getLogger().info("Multi-channel audio detected (" + channels + " channels). " +
                                       "Downmixing to stereo for compatibility.");
                outputChannels = 2;
            }

            // Would be cool if this works
            if (positionalAudio && outputChannels > 1) {
                plugin.getLogger().info("Positional audio mode: downmixing " + outputChannels +
                                       " channels to mono for 3D positioning.");
                outputChannels = 1;
            }

            // Extract full audio first as OGG
            File fullAudio = new File(audioDir, "full_audio.ogg");
            extractFullAudio(grabber, fullAudio, sampleRate, outputChannels, sampleFormat);

            grabber.stop();
            grabber.close();

            if (isSingleFileMode()) {
                // just use the full audio as chunk 0
                plugin.getLogger().info("Single file mode - using full audio as single chunk");
                File singleChunk = new File(audioDir, "chunk_0.ogg");
                Files.copy(fullAudio.toPath(), singleChunk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                chunks.add(new AudioChunk(0, 0, totalDurationMs, singleChunk));
            } else {
                // Split into chunks
                int numChunks = (int) Math.ceil((double) totalDurationMs / chunkDurationMs);

                // Check if the last chunk would be too short (< 1 second) and adjust
                long lastChunkDuration = totalDurationMs - ((long)(numChunks - 1) * chunkDurationMs);
                if (lastChunkDuration < 1000 && numChunks > 1) {
                    plugin.getLogger().info("Last chunk would be too short (" + lastChunkDuration + "ms), " +
                                           "merging with previous chunk");
                    numChunks--;
                }
                plugin.getLogger().info("Splitting audio into " + numChunks + " chunks of " +
                                       (chunkDurationMs / 1000) + " seconds each");
                extractChunksSequentially(fullAudio, sampleRate, outputChannels);
            }

            fullAudio.delete();

            File completionMarker = new File(audioDir, ".extraction_complete");
            Files.writeString(completionMarker.toPath(), String.valueOf(totalDurationMs));

            plugin.getLogger().info("Audio extraction complete: " + chunks.size() + " chunk(s) created and cached");
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to extract audio: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void extractFullAudio(FFmpegFrameGrabber grabber, File outputFile,
                                  int sampleRate, int channels, int sampleFormat) throws Exception {
        plugin.getLogger().info("Extracting full audio as OGG - SampleRate: " + sampleRate +
                               ", Channels: " + channels + ", SampleFormat: " + sampleFormat);

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setAudioBitrate(192000);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.start();

        Frame frame;
        int audioFrameCount = 0;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples != null) {
                recorder.record(frame);
                audioFrameCount++;
            }
        }

        // Flush the encoder by recording null frame. Apparently needed?
        try {
            recorder.record((Frame) null);
        } catch (Exception e) {
            plugin.getLogger().fine("Could not flush with null frame: " + e.getMessage());
        }

        recorder.stop();
        recorder.close();

        plugin.getLogger().info("Recorded " + audioFrameCount + " audio frames to " + outputFile.getName() +
                               " (size: " + outputFile.length() + " bytes)");
    }

    private void extractChunksSequentially(File fullAudio, int sampleRate, int channels) throws Exception {
        plugin.getLogger().info("Extracting chunks using streaming sample-accurate processing...");

        int samplesPerChunk = sampleRate * (chunkDurationMs / 1000);

        plugin.getLogger().info("Sample rate: " + sampleRate + ", Samples per " + (chunkDurationMs / 1000) + "s chunk: " + samplesPerChunk);

        // Extract full audio as WAV (PCM) first to ensure we have a consistent, uncompressed format to stream from
        File fullWav = new File(audioDir, "full_audio.wav");
        convertToWav(fullAudio, fullWav, sampleRate, channels);

        // Stream through the WAV file, accumulating only one chunk at a time
        // This avoids loading the entire audio into memory (which can be several GB for long videos)
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(fullWav);
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16); // PCM 16-bit for WAV
        grabber.start();

        // Buffer for the current chunk only
        float[][] chunkBuffer = new float[channels][samplesPerChunk];
        int chunkBufferPos = 0;
        int chunkIndex = 0;
        long totalSamplesProcessed = 0;

        Frame frame;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples == null || frame.samples.length == 0) {
                continue;
            }

            float[][] frameSamples = extractSamplesAsFloat(frame, channels);
            if (frameSamples == null || frameSamples[0].length == 0) {
                continue;
            }

            int frameSampleCount = frameSamples[0].length;
            int framePos = 0;

            while (framePos < frameSampleCount) {
                int spaceInChunk = samplesPerChunk - chunkBufferPos;
                int toCopy = Math.min(spaceInChunk, frameSampleCount - framePos);

                for (int ch = 0; ch < channels; ch++) {
                    System.arraycopy(frameSamples[ch], framePos, chunkBuffer[ch], chunkBufferPos, toCopy);
                }
                chunkBufferPos += toCopy;
                framePos += toCopy;

                // Chunk buffer is full, flush it
                if (chunkBufferPos >= samplesPerChunk) {
                    writeChunk(chunkBuffer, chunkBufferPos, chunkIndex, sampleRate, channels);
                    totalSamplesProcessed += chunkBufferPos;
                    chunkIndex++;
                    chunkBufferPos = 0;
                    // Re-allocate buffer for next chunk
                    chunkBuffer = new float[channels][samplesPerChunk];
                }
            }
        }

        // Handle remaining samples in the last partial chunk
        if (chunkBufferPos > 0) {
            // Skip very short final chunks (< 0.5s)
            if (chunkBufferPos < sampleRate / 2 && chunkIndex > 0) {
                plugin.getLogger().info("Skipping final chunk with only " + chunkBufferPos + " samples (" +
                                       (chunkBufferPos * 1000 / sampleRate) + "ms)");
            } else {
                // Trim the buffer to actual size
                float[][] trimmedBuffer = new float[channels][chunkBufferPos];
                for (int ch = 0; ch < channels; ch++) {
                    System.arraycopy(chunkBuffer[ch], 0, trimmedBuffer[ch], 0, chunkBufferPos);
                }
                writeChunk(trimmedBuffer, chunkBufferPos, chunkIndex, sampleRate, channels);
                totalSamplesProcessed += chunkBufferPos;
            }
        }

        grabber.stop();
        grabber.close();
        fullWav.delete();

        plugin.getLogger().info("Streaming extraction complete: " + chunks.size() + " chunks created (" +
                               totalSamplesProcessed + " total samples processed)");
    }

    /**
     * Writes a single chunk buffer to WAV, converts to OGG, and registers it.
     */
    private void writeChunk(float[][] buffer, int sampleCount, int chunkIndex, int sampleRate, int channels) throws Exception {
        // Trim buffer if it's not full (last chunk)
        float[][] writeBuffer;
        if (sampleCount < buffer[0].length) {
            writeBuffer = new float[channels][sampleCount];
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(buffer[ch], 0, writeBuffer[ch], 0, sampleCount);
            }
        } else {
            writeBuffer = buffer;
        }

        // Write chunk as WAV first, then convert
        File chunkWav = new File(audioDir, "chunk_" + chunkIndex + ".wav");
        writeWavFromSamples(chunkWav, writeBuffer, sampleRate, channels);
        File chunkOgg = new File(audioDir, "chunk_" + chunkIndex + ".ogg");
        convertWavToOgg(chunkWav, chunkOgg, sampleRate, channels);
        chunkWav.delete();

        long startMs = (long) chunkIndex * chunkDurationMs;
        long actualDurationMs = (long) sampleCount * 1000 / sampleRate;
        AudioChunk chunk = new AudioChunk(chunkIndex, startMs, actualDurationMs, chunkOgg);
        chunks.add(chunk);

        plugin.getLogger().info("Created chunk " + chunkIndex +
                               " (" + sampleCount + " samples, " + actualDurationMs + "ms" +
                               ", file size: " + chunkOgg.length() + " bytes)");
    }

    private void convertToWav(File input, File output, int sampleRate, int channels) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input);
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
        grabber.start();

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0, 0, channels);
        recorder.setFormat("wav");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
        recorder.start();

        Frame frame;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples != null) {
                recorder.record(frame);
            }
        }

        recorder.stop();
        recorder.close();
        grabber.stop();
        grabber.close();
    }

    private float[][] extractSamplesAsFloat(Frame frame, int channels) {
        if (frame.samples == null || frame.samples.length == 0) {
            return null;
        }

        // interleaved S16 format
        Buffer buffer = frame.samples[0];
        if (buffer instanceof ShortBuffer shortBuffer) {
            shortBuffer.rewind();
            int totalSamples = shortBuffer.remaining();
            int samplesPerChannel = totalSamples / channels;

            float[][] result = new float[channels][samplesPerChannel];

            for (int i = 0; i < samplesPerChannel; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    short sample = shortBuffer.get();
                    result[ch][i] = sample / 32768.0f;
                }
            }

            return result;
        }

        // planar float format (FLTP)
        if (frame.samples.length >= channels) {
            int samplesPerChannel = frame.samples[0].capacity() / 4; // 4 bytes per float
            float[][] result = new float[channels][samplesPerChannel];

            for (int ch = 0; ch < channels; ch++) {
                FloatBuffer floatBuffer = ((ByteBuffer) frame.samples[ch]).asFloatBuffer();
                floatBuffer.rewind();
                floatBuffer.get(result[ch]);
            }

            return result;
        }

        return null;
    }

    private void writeWavFromSamples(File output, float[][] samples, int sampleRate, int channels) throws Exception {
        int samplesPerChannel = samples[0].length;

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output, 0, 0, channels);
        recorder.setFormat("wav");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
        recorder.start();

        int frameSamples = 1024; // Standard audio frame size
        for (int pos = 0; pos < samplesPerChannel; pos += frameSamples) {
            int remaining = Math.min(frameSamples, samplesPerChannel - pos);

            short[] interleaved = new short[remaining * channels];
            for (int i = 0; i < remaining; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    float sample = samples[ch][pos + i];
                    sample = Math.max(-1.0f, Math.min(1.0f, sample));
                    interleaved[i * channels + ch] = (short) (sample * 32767);
                }
            }

            ShortBuffer shortBuffer = ShortBuffer.wrap(interleaved);
            Frame frame = new Frame();
            frame.sampleRate = sampleRate;
            frame.audioChannels = channels;
            frame.samples = new Buffer[]{shortBuffer};

            recorder.record(frame);
        }
        recorder.stop();
        recorder.close();
    }

    private void convertWavToOgg(File wavFile, File oggFile, int sampleRate, int channels) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(wavFile);
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        grabber.start();

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(oggFile, 0, 0, channels);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setAudioBitrate(192000);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.setAudioOption("flags", "+global_header");
        recorder.setFrameNumber(0);
        recorder.start();

        Frame frame;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples != null) {
                recorder.record(frame);
            }
        }

        grabber.stop();
        grabber.close();
        recorder.stop();
        recorder.close();
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
                json.append("        \"preload\": true,\n");
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
        stop();

        pausedAtMs = timeMs;

        if (wasPlaying) {
            resume(location);
        }
    }

    private void playChunk(int index, Location location) {
        if (index < 0 || index >= chunks.size()) {
            return;
        }

        AudioChunk chunk = chunks.get(index);
        String key = SOUND_NAMESPACE + ":" + videoId + ".chunk_" + chunk.index();
        for (Player player : screen.getViewers()) {
            player.playSound(location, key, SoundCategory.RECORDS, 1.0f, 1.0f);
        }
    }

    private void stopAllSounds() {
        for (int i = 0; i < chunks.size(); i++) {
            String key = SOUND_NAMESPACE + ":" + videoId + ".chunk_" + i;

            for (Player player : screen.getViewers()) {
                player.stopSound(key);
            }
        }
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

