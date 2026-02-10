package cat.maki.makiscreen.audio;

import cat.maki.makiscreen.MakiScreen;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
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
    public static final String SOUND_NAMESPACE = "makiscreen";

    private final MakiScreen plugin;
    private final String videoId;
    private final File audioDir;
    private final List<AudioChunk> chunks = new ArrayList<>();
    private final int chunkDurationMs; // 0 = single file mode

    private final AtomicInteger currentChunkIndex = new AtomicInteger(-1);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private BukkitTask chunkScheduler;

    private long totalDurationMs;
    private long pausedAtMs;
    private long playbackStartTime;

    /**
     * Creates an AudioManager with default chunk duration.
     */
    public AudioManager(MakiScreen plugin, String videoId) {
        this(plugin, videoId, DEFAULT_CHUNK_DURATION_MS);
    }

    /**
     * Creates an AudioManager with configurable chunk duration.
     * @param chunkDurationMs Duration of each chunk in milliseconds. Use 0 for single file mode (no chunking).
     */
    public AudioManager(MakiScreen plugin, String videoId, int chunkDurationMs) {
        this.plugin = plugin;
        this.videoId = videoId;
        this.chunkDurationMs = chunkDurationMs;
        // Include chunk duration in folder name to separate cached files
        String folderSuffix = chunkDurationMs == 0 ? "_single" : "_" + chunkDurationMs + "ms";
        this.audioDir = new File(plugin.getDataFolder(), "audio/" + videoId + folderSuffix);
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

            // Load existing chunks
            File[] chunkFiles = audioDir.listFiles((dir, name) -> name.startsWith("chunk_") && name.endsWith(".ogg"));
            if (chunkFiles != null && chunkFiles.length > 0) {
                Arrays.sort(chunkFiles, Comparator.comparingInt(f -> {
                    String name = f.getName();
                    return Integer.parseInt(name.substring(6, name.length() - 4));
                }));

                // Read total duration from cache marker
                try {
                    String durationStr = Files.readString(cacheMarker.toPath()).trim();
                    totalDurationMs = Long.parseLong(durationStr);
                } catch (Exception e) {
                    // Estimate from chunk count (only valid for non-single mode)
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
            // Set the sample format
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

            // Extract full audio first as OGG
            File fullAudio = new File(audioDir, "full_audio.ogg");
            extractFullAudio(grabber, fullAudio, sampleRate, channels, sampleFormat);

            grabber.stop();
            grabber.close();

            if (isSingleFileMode()) {
                // Single file mode - just use the full audio as chunk 0
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

                // Split audio by reading sequentially - sample-accurate chunking
                extractChunksSequentially(fullAudio, sampleRate, channels);
            }

            // Keep full audio file for reference/debugging
            // fullAudio.delete();

            // Create cache marker to skip processing next time
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

        // Flush the encoder by recording null frame
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
        plugin.getLogger().info("Extracting chunks using sample-accurate WAV intermediate...");

        // Calculate exact samples per chunk
        int samplesPerChunk = sampleRate * (chunkDurationMs / 1000);

        plugin.getLogger().info("Sample rate: " + sampleRate + ", Samples per " + (chunkDurationMs / 1000) + "s chunk: " + samplesPerChunk);

        // Step 1: Extract full audio as WAV (PCM) for sample-accurate processing
        File fullWav = new File(audioDir, "full_audio.wav");
        convertToWav(fullAudio, fullWav, sampleRate, channels);

        // Step 2: Read the WAV and count total samples using grabber timestamps
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(fullWav);
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16); // PCM 16-bit for WAV
        grabber.start();

        // Collect all audio samples into a buffer, tracking by grabber timestamp
        List<float[][]> allSamples = new ArrayList<>();
        long totalSamplesCollected = 0;

        Frame frame;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples == null || frame.samples.length == 0) {
                continue;
            }

            // Convert frame samples to float arrays for easier manipulation
            float[][] frameSamples = extractSamplesAsFloat(frame, channels);
            if (frameSamples != null && frameSamples[0].length > 0) {
                allSamples.add(frameSamples);
                totalSamplesCollected += frameSamples[0].length;
            }
        }

        grabber.stop();
        grabber.close();

        plugin.getLogger().info("Collected " + totalSamplesCollected + " total samples from " + allSamples.size() + " frames");

        // Step 3: Flatten all samples into one continuous buffer per channel
        float[][] continuousBuffer = new float[channels][(int) totalSamplesCollected];
        int writePos = 0;
        for (float[][] frameSamples : allSamples) {
            int frameLength = frameSamples[0].length;
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(frameSamples[ch], 0, continuousBuffer[ch], writePos, frameLength);
            }
            writePos += frameLength;
        }
        allSamples.clear(); // Free memory

        plugin.getLogger().info("Created continuous buffer with " + writePos + " samples per channel");

        int totalSamples = writePos;
        int chunkIndex = 0;
        int samplePos = 0;

        while (samplePos < totalSamples) {
            int remainingSamples = totalSamples - samplePos;
            int chunkSamples = Math.min(samplesPerChunk, remainingSamples);

            // Skip very short final chunks (less than 0.5 seconds)
            if (chunkSamples < sampleRate / 2 && chunkIndex > 0) {
                plugin.getLogger().info("Skipping final chunk with only " + chunkSamples + " samples (" +
                                       (chunkSamples * 1000 / sampleRate) + "ms)");
                break;
            }

            // Extract chunk samples - no padding, use actual duration
            float[][] chunkBuffer = new float[channels][chunkSamples];
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(continuousBuffer[ch], samplePos, chunkBuffer[ch], 0, chunkSamples);
            }

            // Write chunk as WAV first (sample-accurate)
            File chunkWav = new File(audioDir, "chunk_" + chunkIndex + ".wav");
            writeWavFromSamples(chunkWav, chunkBuffer, sampleRate, channels);

            // Convert WAV to OGG
            File chunkOgg = new File(audioDir, "chunk_" + chunkIndex + ".ogg");
            convertWavToOgg(chunkWav, chunkOgg, sampleRate, channels);

            // Delete intermediate WAV
            chunkWav.delete();

            long startMs = (long) chunkIndex * chunkDurationMs;
            long actualDurationMs = (long) chunkSamples * 1000 / sampleRate;
            AudioChunk chunk = new AudioChunk(chunkIndex, startMs, actualDurationMs, chunkOgg);
            chunks.add(chunk);

            plugin.getLogger().info("Created chunk " + chunkIndex +
                                   " (" + chunkSamples + " samples, " + actualDurationMs + "ms" +
                                   ", file size: " + chunkOgg.length() + " bytes)");

            chunkIndex++;
            samplePos += chunkSamples;
        }

        // Clean up intermediate files
        fullWav.delete();

        plugin.getLogger().info("Sample-accurate extraction complete: " + chunks.size() + " chunks created");
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

        // Handle interleaved S16 format (common for WAV)
        java.nio.Buffer buffer = frame.samples[0];
        if (buffer instanceof java.nio.ShortBuffer shortBuffer) {
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

        // Handle planar float format (FLTP)
        if (frame.samples.length >= channels) {
            int samplesPerChannel = frame.samples[0].capacity() / 4; // 4 bytes per float
            float[][] result = new float[channels][samplesPerChannel];

            for (int ch = 0; ch < channels; ch++) {
                java.nio.FloatBuffer floatBuffer = ((java.nio.ByteBuffer) frame.samples[ch]).asFloatBuffer();
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

        // Write samples in chunks to avoid huge single frames
        int frameSamples = 1024; // Standard audio frame size
        for (int pos = 0; pos < samplesPerChannel; pos += frameSamples) {
            int remaining = Math.min(frameSamples, samplesPerChannel - pos);

            // Create interleaved short buffer
            short[] interleaved = new short[remaining * channels];
            for (int i = 0; i < remaining; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    float sample = samples[ch][pos + i];
                    // Clamp to [-1, 1] and convert to short
                    sample = Math.max(-1.0f, Math.min(1.0f, sample));
                    interleaved[i * channels + ch] = (short) (sample * 32767);
                }
            }

            java.nio.ShortBuffer shortBuffer = java.nio.ShortBuffer.wrap(interleaved);
            Frame frame = new Frame();
            frame.sampleRate = sampleRate;
            frame.audioChannels = channels;
            frame.samples = new java.nio.Buffer[]{shortBuffer};

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
        // Use global header flag - this helps with encoder initialization and flushing
        recorder.setAudioOption("flags", "+global_header");
        // Set frame size to match Vorbis block size for cleaner encoding
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

    // Helper class to store frame data with timestamp (kept for potential future use)
    private record FrameData(Frame frame, long timestampUs) {}

    private FFmpegFrameRecorder createChunkRecorder(File outputFile, int sampleRate, int channels) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setAudioBitrate(192000);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        return recorder;
    }

    private void extractAudioChunkFromVideo(File videoFile, File outputFile, long startMs, long endMs,
                                            int sampleRate, int channels) throws Exception {
        long durationMs = endMs - startMs;

        plugin.getLogger().info("Extracting chunk from video: " + startMs + "ms to " + endMs +
                               "ms (duration: " + durationMs + "ms)");

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        grabber.start();

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setAudioBitrate(192000);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.start();

        // Seek to start position
        if (startMs > 0) {
            grabber.setTimestamp(startMs * 1000); // Convert to microseconds
        }

        Frame frame;
        int frameCount = 0;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples == null) {
                continue;
            }

            long currentMs = grabber.getTimestamp() / 1000;
            if (currentMs >= endMs) {
                break;
            }

            recorder.record(frame);
            frameCount++;
        }

        // Flush the encoder
        try {
            recorder.record((Frame) null);
        } catch (Exception e) {
            // Ignore
        }

        recorder.stop();
        recorder.close();
        grabber.stop();
        grabber.close();

        plugin.getLogger().info("Extracted chunk with " + frameCount + " frames (" +
                               durationMs + "ms) to " + outputFile.getName() +
                               " (size: " + outputFile.length() + " bytes)");
    }

    private void extractAudioChunkFFmpeg(File inputFile, File outputFile, long startMs, long durationMs) throws Exception {
        // Use JavaCV's FFmpeg (bundled, no separate installation needed)
        long endMs = startMs + durationMs;

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputFile);
        grabber.start();

        int sampleRate = grabber.getSampleRate();
        int channels = grabber.getAudioChannels();

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setAudioBitrate(192000);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.start();

        // Seek to the start position
        if (startMs > 0) {
            long seekTimestamp = startMs * 1000; // Convert to microseconds
            grabber.setTimestamp(seekTimestamp);
        }

        Frame frame;
        int frameCount = 0;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples == null) {
                continue;
            }

            long currentMs = grabber.getTimestamp() / 1000;
            if (currentMs >= endMs) {
                break;
            }

            recorder.record(frame);
            frameCount++;
        }

        // Flush the encoder
        try {
            recorder.record((Frame) null);
        } catch (Exception e) {
            // Ignore
        }

        recorder.stop();
        recorder.close();
        grabber.stop();
        grabber.close();

        plugin.getLogger().info("Created chunk: " + outputFile.getName() +
                               " (" + outputFile.length() + " bytes, " + frameCount + " frames)");
    }

    private void extractAudioChunk(File audioFile, File outputFile, long startMs, long endMs,
                                   int sampleRate, int channels, int sampleFormat) throws Exception {
        long durationMs = endMs - startMs;

        // Vorbis encoder has issues with very short chunks - minimum 500ms
        if (durationMs < 500) {
            plugin.getLogger().warning("Skipping chunk that is too short: " + durationMs + "ms");
            throw new Exception("Chunk duration too short for Vorbis encoding: " + durationMs + "ms");
        }

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audioFile);
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        grabber.start();

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
        recorder.setSampleRate(sampleRate);
        recorder.setAudioChannels(channels);
        recorder.setAudioBitrate(192000);
        recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
        recorder.start();

        // Seek to start - this works better with audio-only files
        if (startMs > 0) {
            grabber.setTimestamp(startMs * 1000);
        }

        Frame frame;
        int frameCount = 0;
        while ((frame = grabber.grabSamples()) != null) {
            if (frame.samples == null) {
                continue;
            }

            long currentMs = grabber.getTimestamp() / 1000;
            if (currentMs >= endMs) {
                break;
            }

            recorder.record(frame);
            frameCount++;
        }

        // Check if we got enough frames
        if (frameCount < 10) {
            plugin.getLogger().warning("Very few frames recorded for chunk: " + frameCount +
                                      " (duration: " + durationMs + "ms). This may cause encoding issues.");
        }

        // Flush the encoder
        try {
            recorder.record((Frame) null);
        } catch (Exception e) {
            // Ignore
        }

        recorder.stop();
        recorder.close();
        grabber.stop();
        grabber.close();

        plugin.getLogger().info("Extracted chunk with " + frameCount + " frames (" +
                               durationMs + "ms) to " + outputFile.getName());
    }

    public File generateResourcePack() {
        if (chunks.isEmpty()) {
            return null;
        }

        File packDir = new File(plugin.getDataFolder(), "resourcepack");
        File soundsDir = new File(packDir, "assets/" + SOUND_NAMESPACE + "/sounds/" + videoId);
        soundsDir.mkdirs();

        try {
            // Copy audio files
            for (AudioChunk chunk : chunks) {
                File dest = new File(soundsDir, "chunk_" + chunk.index() + ".ogg");
                Files.copy(chunk.file().toPath(), dest.toPath(),
                          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Generate pack.mcmeta
            File packMeta = new File(packDir, "pack.mcmeta");
            String mcmeta = """
                {
                    "pack": {
                        "pack_format": 34,
                        "description": "MakiScreen Audio Pack"
                    }
                }
                """;
            Files.writeString(packMeta.toPath(), mcmeta);

            // Generate sounds.json
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
                // preload=true: Load when pack loads, not when played - reduces playback latency
                // stream=false: 5-second chunks are small enough to load fully into memory
                //               This provides more precise playback timing than streaming
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

            // Create ZIP
            File zipFile = new File(plugin.getDataFolder(), "makiscreen_audio_" + videoId + ".zip");
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

    public void play() {
        if (chunks.isEmpty() || isPlaying.get()) {
            return;
        }

        isPlaying.set(true);
        currentChunkIndex.set(0);
        playbackStartTime = System.currentTimeMillis();

        playChunk(0);

        // Only schedule chunk transitions in chunked mode
        if (!isSingleFileMode()) {
            scheduleNextChunks();
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

    public void resume() {
        if (isPlaying.get() || chunks.isEmpty()) {
            return;
        }

        isPlaying.set(true);

        // For single file mode, just resume from beginning or use pausedAtMs for seeking
        // For chunked mode, calculate which chunk to resume from
        int chunkIndex = isSingleFileMode() ? 0 : (int) (pausedAtMs / chunkDurationMs);
        currentChunkIndex.set(chunkIndex);

        playbackStartTime = System.currentTimeMillis() - pausedAtMs;

        playChunk(chunkIndex);
        if (!isSingleFileMode()) {
            scheduleNextChunks();
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

    public void seekTo(long timeMs) {
        boolean wasPlaying = isPlaying.get();
        stop();

        pausedAtMs = timeMs;

        if (wasPlaying) {
            resume();
        }
    }

    private void playChunk(int index) {
        if (index < 0 || index >= chunks.size()) {
            return;
        }

        AudioChunk chunk = chunks.get(index);
        Key soundKey = Key.key(SOUND_NAMESPACE, videoId + ".chunk_" + chunk.index());

        Sound sound = Sound.sound(soundKey, Sound.Source.RECORD, 1.0f, 1.0f);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(sound);
        }
    }

    private void stopAllSounds() {
        for (int i = 0; i < chunks.size(); i++) {
            Key soundKey = Key.key(SOUND_NAMESPACE, videoId + ".chunk_" + i);
            SoundStop stop = SoundStop.named(soundKey);

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.stopSound(stop);
            }
        }
    }

    private void scheduleNextChunks() {
        chunkScheduler = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isPlaying.get()) {
                    cancel();
                    return;
                }

                long elapsedMs = System.currentTimeMillis() - playbackStartTime;
                int current = currentChunkIndex.get();

                // Calculate which chunk should be playing based on configured chunk duration
                int expectedChunk = (int) (elapsedMs / chunkDurationMs);

                // Play next chunk if we've moved to a new chunk
                if (expectedChunk > current && expectedChunk < chunks.size()) {
                    currentChunkIndex.set(expectedChunk);
                    playChunk(expectedChunk);

                    // Log timing info for debugging
                    long expectedMs = (long) expectedChunk * chunkDurationMs;
                    plugin.getLogger().fine("Chunk " + expectedChunk + " triggered at " + elapsedMs +
                                           "ms (expected: " + expectedMs + "ms, drift: " + (elapsedMs - expectedMs) + "ms)");
                }

                // Check if playback is complete
                if (expectedChunk >= chunks.size()) {
                    isPlaying.set(false);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L); // Check every tick (50ms) for precise timing
    }


    public boolean isPlaying() {
        return isPlaying.get();
    }

    public int getCurrentChunkIndex() {
        return currentChunkIndex.get();
    }

    public int getTotalChunks() {
        return chunks.size();
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void cleanup() {
        stop();
        // Note: We keep the audio files and resource pack for reuse
        // They can be manually deleted from the audio/ folder if needed
    }

    public record AudioChunk(int index, long startMs, long durationMs, File file) {}
}

