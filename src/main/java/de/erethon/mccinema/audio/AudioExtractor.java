package de.erethon.mccinema.audio;

import de.erethon.mccinema.MCCinema;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class AudioExtractor {

    private static final String CACHE_FORMAT = "pcm-wav-v2-vorbis-padding";
    private static final int VORBIS_FRAME_SAMPLES = 1024;

    enum Stage {
        CACHE("Loading cached audio"),
        EXTRACTING("Extracting and downmixing audio"),
        CHUNKING("Encoding audio chunks"),
        COMPLETE("Audio conversion complete");

        private final String description;

        Stage(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(Stage stage, int percent, String detail);
    }

    record Result(long durationMs, List<AudioManager.AudioChunk> chunks) {
    }

    private final MCCinema plugin;
    private final File videoFile;
    private final File audioDir;
    private final int chunkDurationMs;
    private final boolean positionalAudio;
    private final int audioBitrate;
    private final int encoderThreads;
    private final ProgressListener progressListener;
    private final ProgressReporter progressReporter = new ProgressReporter();

    AudioExtractor(MCCinema plugin, File videoFile, File audioDir, int chunkDurationMs,
                   boolean positionalAudio, ProgressListener progressListener) {
        this.plugin = plugin;
        this.videoFile = videoFile;
        this.audioDir = audioDir;
        this.chunkDurationMs = chunkDurationMs;
        this.positionalAudio = positionalAudio;
        this.audioBitrate = plugin.getConfig().getInt("audio.bitrate", 192000);
        this.encoderThreads = resolveEncoderThreads(plugin.getConfig().getInt("audio.conversion-threads", 0));
        this.progressListener = progressListener;
    }

    Result extract() throws Exception {
        Files.createDirectories(audioDir.toPath());
        File cacheMarker = new File(audioDir, ".extraction_complete");
        Result cached = loadCached(cacheMarker);
        if (cached != null) {
            report(Stage.CACHE, 100, cached.chunks().size() + " cached chunk(s)");
            return cached;
        }

        clearIncompleteOutput(cacheMarker);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
        if (videoFile.getName().endsWith(".ffconcat")) grabber.setOption("safe", "0");
        File fullAudio = new File(audioDir, "full_audio.ogg");
        try {
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
            grabber.start();
            if (grabber.getAudioChannels() == 0) {
                throw new IllegalArgumentException("Video has no audio track");
            }

            long durationMs = grabber.getLengthInTime() / 1000;
            int sampleRate = grabber.getSampleRate();
            int sourceChannels = grabber.getAudioChannels();
            int outputChannels = positionalAudio ? 1 : Math.min(sourceChannels, 2);
            boolean dcpAudio = videoFile.getName().endsWith(".ffconcat");
            if (dcpAudio && !List.of(1, 2, 6, 8, 12, 16).contains(sourceChannels)) {
                throw new IllegalArgumentException("Unsupported DCP sound channel count: " + sourceChannels);
            }
            if (dcpAudio && positionalAudio) {
                throw new IllegalArgumentException("DCP sound requires stereo output");
            }

            plugin.getLogger().info("Audio conversion: " + sampleRate + " Hz, " + sourceChannels +
                " source channel(s) -> " + outputChannels + " output channel(s)");
            extractFullAudio(grabber, fullAudio, sampleRate, outputChannels, durationMs, dcpAudio);

            List<AudioManager.AudioChunk> chunks;
            if (chunkDurationMs == 0) {
                File singleChunk = new File(audioDir, "chunk_0.ogg");
                Files.copy(fullAudio.toPath(), singleChunk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                chunks = List.of(new AudioManager.AudioChunk(0, 0, durationMs, singleChunk));
            } else {
                chunks = encodeChunks(fullAudio, sampleRate, outputChannels, durationMs);
            }

            Files.writeString(cacheMarker.toPath(), CACHE_FORMAT + System.lineSeparator() + durationMs);
            report(Stage.COMPLETE, 100, chunks.size() + " chunk(s)");
            return new Result(durationMs, chunks);
        } finally {
            closeGrabber(grabber);
            Files.deleteIfExists(fullAudio.toPath());
        }
    }

    private Result loadCached(File cacheMarker) {
        if (!cacheMarker.exists()) {
            return null;
        }
        File[] chunkFiles = audioDir.listFiles((dir, name) ->
            name.startsWith("chunk_") && name.endsWith(".ogg"));
        if (chunkFiles == null || chunkFiles.length == 0) {
            return null;
        }

        try {
            List<String> markerLines = Files.readAllLines(cacheMarker.toPath());
            if (markerLines.size() != 2 || !CACHE_FORMAT.equals(markerLines.getFirst().trim())) {
                plugin.getLogger().info("Audio cache uses an outdated conversion format; rebuilding it");
                return null;
            }
            long durationMs = Long.parseLong(markerLines.get(1).trim());
            Arrays.sort(chunkFiles, Comparator.comparingInt(AudioExtractor::chunkIndex));
            List<AudioManager.AudioChunk> chunks = new ArrayList<>(chunkFiles.length);
            for (File chunkFile : chunkFiles) {
                int index = chunkIndex(chunkFile);
                long startMs = chunkDurationMs == 0 ? 0 : (long) index * chunkDurationMs;
                long remainingMs = Math.max(0, durationMs - startMs);
                long chunkLengthMs = chunkDurationMs == 0 ? durationMs : Math.min(chunkDurationMs, remainingMs);
                chunks.add(new AudioManager.AudioChunk(index, startMs, chunkLengthMs, chunkFile));
            }
            return new Result(durationMs, chunks);
        } catch (Exception e) {
            plugin.getLogger().warning("Ignoring invalid audio cache: " + e.getMessage());
            return null;
        }
    }

    private void clearIncompleteOutput(File cacheMarker) throws Exception {
        Files.deleteIfExists(cacheMarker.toPath());
        File[] staleFiles = audioDir.listFiles((dir, name) ->
            name.startsWith("chunk_") || name.equals("full_audio.ogg") || name.equals("full_audio.wav"));
        if (staleFiles == null) {
            return;
        }
        for (File staleFile : staleFiles) {
            Files.deleteIfExists(staleFile.toPath());
        }
    }

    private void extractFullAudio(FFmpegFrameGrabber grabber, File outputFile,
                                  int sampleRate, int outputChannels, long durationMs,
                                  boolean dcpAudio) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, outputChannels);
        try {
            recorder.setFormat("ogg");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(outputChannels);
            recorder.setAudioBitrate(audioBitrate);
            recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
            recorder.setAudioOption("threads", "1");
            recorder.start();

            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) {
                    continue;
                }
                recorder.record(dcpAudio ? downmixDcp(frame) : frame);
                int percent = percent(grabber.getTimestamp() / 1000, durationMs);
                report(Stage.EXTRACTING, percent, formatTime(grabber.getTimestamp() / 1000) +
                    " / " + formatTime(durationMs));
            }
        } finally {
            closeRecorder(recorder);
        }
        report(Stage.EXTRACTING, 100, formatTime(durationMs) + " extracted");
    }

    private static Frame downmixDcp(Frame frame) {
        int channels = frame.audioChannels;
        if (channels == 2) return frame;
        if (channels != 1 && channels != 6 && channels != 8 && channels != 12 && channels != 16) {
            throw new IllegalArgumentException("Unsupported DCP sound channel count: " + channels);
        }
        if (frame.samples.length < channels) {
            throw new IllegalArgumentException("Expected planar DCP audio samples");
        }
        FloatBuffer[] input = new FloatBuffer[channels];
        for (int channel = 0; channel < channels; channel++) {
            Buffer samples = frame.samples[channel];
            if (samples instanceof FloatBuffer floats) input[channel] = floats.duplicate();
            else if (samples instanceof ByteBuffer bytes) input[channel] = bytes.duplicate().asFloatBuffer();
            else throw new IllegalArgumentException("Expected floating-point DCP audio samples");
        }
        int count = input[0].remaining();
        FloatBuffer left = FloatBuffer.allocate(count);
        FloatBuffer right = FloatBuffer.allocate(count);
        float divisor = channels >= 12 ? 3.6642f : 2.6642f;
        for (int i = 0; i < count; i++) {
            if (channels == 1) {
                float center = input[0].get();
                left.put(center);
                right.put(center);
                continue;
            }
            float l = input[0].get();
            float r = input[1].get();
            float c = input[2].get();
            float lfe = input[3].get();
            float ls = input[4].get();
            float rs = input[5].get();
            // DCP channels 7/8 carry accessibility audio, not the rear channels.
            for (int channel = 6; channel < channels; channel++) {
                float sample = input[channel].get();
                if (channel == 8 || channel == 10) l += 0.5f * sample;
                if (channel == 9 || channel == 11) r += 0.5f * sample;
            }
            left.put((l + 0.7071f * c + 0.7071f * ls + 0.25f * lfe) / divisor);
            right.put((r + 0.7071f * c + 0.7071f * rs + 0.25f * lfe) / divisor);
        }
        left.flip();
        right.flip();
        Frame stereo = new Frame();
        stereo.sampleRate = frame.sampleRate;
        stereo.audioChannels = 2;
        stereo.samples = new Buffer[]{left, right};
        return stereo;
    }

    private List<AudioManager.AudioChunk> encodeChunks(File fullAudio, int sampleRate,
                                                       int channels, long durationMs) throws Exception {
        int samplesPerChunk = Math.max(1, (int) ((long) sampleRate * chunkDurationMs / 1000));
        int expectedChunks = Math.max(1, (int) Math.ceil((double) durationMs / chunkDurationMs));
        AtomicInteger encodedChunks = new AtomicInteger();
        AtomicInteger readPercent = new AtomicInteger();
        ThreadPoolExecutor executor = createEncoderExecutor();
        List<Future<AudioManager.AudioChunk>> futures = new ArrayList<>(expectedChunks);
        File fullWav = new File(audioDir, "full_audio.wav");
        convertToWav(fullAudio, fullWav, sampleRate, channels, durationMs);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(fullWav);
        boolean inputComplete = false;

        try {
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            grabber.start();
            float[][] chunkBuffer = new float[channels][samplesPerChunk];
            int chunkBufferPosition = 0;
            int nextChunkIndex = 0;

            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                float[][] frameSamples = extractSamplesAsFloat(frame, channels);
                if (frameSamples == null || frameSamples[0].length == 0) {
                    continue;
                }

                int framePosition = 0;
                int frameSampleCount = frameSamples[0].length;
                while (framePosition < frameSampleCount) {
                    int copiedSamples = Math.min(samplesPerChunk - chunkBufferPosition,
                        frameSampleCount - framePosition);
                    for (int channel = 0; channel < channels; channel++) {
                        System.arraycopy(frameSamples[channel], framePosition, chunkBuffer[channel],
                            chunkBufferPosition, copiedSamples);
                    }
                    chunkBufferPosition += copiedSamples;
                    framePosition += copiedSamples;

                    if (chunkBufferPosition == samplesPerChunk) {
                        submitChunk(futures, executor, chunkBuffer, chunkBufferPosition,
                            nextChunkIndex++, sampleRate, channels, encodedChunks, readPercent, expectedChunks);
                        chunkBuffer = new float[channels][samplesPerChunk];
                        chunkBufferPosition = 0;
                    }
                }

                readPercent.set(percent(grabber.getTimestamp() / 1000, durationMs));
                reportChunkProgress(readPercent.get(), encodedChunks.get(), expectedChunks);
            }

            if (chunkBufferPosition >= sampleRate / 2 || nextChunkIndex == 0) {
                float[][] finalBuffer = trimBuffer(chunkBuffer, chunkBufferPosition, channels);
                submitChunk(futures, executor, finalBuffer, chunkBufferPosition,
                    nextChunkIndex, sampleRate, channels, encodedChunks, readPercent, expectedChunks);
            }
            readPercent.set(100);
            inputComplete = true;
        } finally {
            closeGrabber(grabber);
            Files.deleteIfExists(fullWav.toPath());
            if (inputComplete) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
        }

        if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
            executor.shutdownNow();
            throw new IllegalStateException("Timed out while encoding audio chunks");
        }

        List<AudioManager.AudioChunk> chunks = new ArrayList<>(futures.size());
        for (Future<AudioManager.AudioChunk> future : futures) {
            chunks.add(future.get());
        }
        chunks.sort(Comparator.comparingInt(AudioManager.AudioChunk::index));
        report(Stage.CHUNKING, 100, chunks.size() + " chunk(s) encoded");
        return chunks;
    }

    private void submitChunk(List<Future<AudioManager.AudioChunk>> futures, ThreadPoolExecutor executor,
                             float[][] buffer, int sampleCount, int chunkIndex, int sampleRate, int channels,
                             AtomicInteger encodedChunks, AtomicInteger readPercent, int expectedChunks) {
        futures.add(executor.submit(() -> {
            AudioManager.AudioChunk chunk = encodeChunk(buffer, sampleCount, chunkIndex, sampleRate, channels);
            int completed = encodedChunks.incrementAndGet();
            reportChunkProgress(readPercent.get(), completed, expectedChunks);
            return chunk;
        }));
    }

    private AudioManager.AudioChunk encodeChunk(float[][] samples, int sampleCount, int chunkIndex,
                                                int sampleRate, int channels) throws Exception {
        File wavFile = new File(audioDir, "chunk_" + chunkIndex + ".wav");
        File outputFile = new File(audioDir, "chunk_" + chunkIndex + ".ogg");
        boolean complete = false;
        try {
            float[][] encoderSamples = padForVorbisEncoder(samples, sampleCount, channels);
            writeWavFromSamples(wavFile, encoderSamples, sampleRate, channels);
            convertWavToOgg(wavFile, outputFile, sampleRate, channels);
            complete = true;
        } finally {
            Files.deleteIfExists(wavFile.toPath());
            if (!complete) {
                Files.deleteIfExists(outputFile.toPath());
            }
        }

        long startMs = (long) chunkIndex * chunkDurationMs;
        long actualDurationMs = (long) sampleCount * 1000 / sampleRate;
        return new AudioManager.AudioChunk(chunkIndex, startMs, actualDurationMs, outputFile);
    }

    private static float[][] padForVorbisEncoder(float[][] samples, int sampleCount, int channels) {
        int padding = (VORBIS_FRAME_SAMPLES - sampleCount % VORBIS_FRAME_SAMPLES) % VORBIS_FRAME_SAMPLES;
        if (padding == 0) {
            return samples;
        }

        // JavaCV 1.5.10 leaves the final partial native Vorbis frame queued when the recorder closes.
        // Without padding, each chunk is truncated to the preceding 1024-sample boundary. Keep the
        // real chunk duration unchanged and add only trailing silence to make the encoder input whole.
        float[][] padded = new float[channels][sampleCount + padding];
        for (int channel = 0; channel < channels; channel++) {
            System.arraycopy(samples[channel], 0, padded[channel], 0, sampleCount);
        }
        return padded;
    }

    private void convertToWav(File inputFile, File outputFile, int sampleRate,
                              int channels, long durationMs) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputFile);
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        try {
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            grabber.start();
            recorder.setFormat("wav");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            recorder.start();

            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) {
                    continue;
                }
                recorder.record(frame);
                report(Stage.CHUNKING, 0, "Preparing PCM audio " +
                    formatTime(grabber.getTimestamp() / 1000) + " / " + formatTime(durationMs));
            }
        } finally {
            closeRecorder(recorder);
            closeGrabber(grabber);
        }
    }

    private void writeWavFromSamples(File outputFile, float[][] samples,
                                     int sampleRate, int channels) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, 0, 0, channels);
        try {
            recorder.setFormat("wav");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            recorder.start();

            int samplesPerChannel = samples[0].length;
            for (int position = 0; position < samplesPerChannel; position += 1024) {
                int length = Math.min(1024, samplesPerChannel - position);
                short[] interleaved = new short[length * channels];
                for (int sampleIndex = 0; sampleIndex < length; sampleIndex++) {
                    for (int channel = 0; channel < channels; channel++) {
                        float sample = Math.max(-1.0f,
                            Math.min(1.0f, samples[channel][position + sampleIndex]));
                        interleaved[sampleIndex * channels + channel] = (short) (sample * 32767.0f);
                    }
                }

                Frame frame = new Frame();
                frame.sampleRate = sampleRate;
                frame.audioChannels = channels;
                frame.samples = new Buffer[]{ShortBuffer.wrap(interleaved)};
                recorder.record(frame);
            }
        } finally {
            closeRecorder(recorder);
        }
    }

    private void convertWavToOgg(File wavFile, File oggFile,
                                 int sampleRate, int channels) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(wavFile);
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(oggFile, 0, 0, channels);
        try {
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
            grabber.start();
            recorder.setFormat("ogg");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setAudioBitrate(audioBitrate);
            recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_FLTP);
            recorder.setAudioOption("flags", "+global_header");
            recorder.setAudioOption("threads", "1");
            recorder.setFrameNumber(0);
            recorder.start();

            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples != null) {
                    recorder.record(frame);
                }
            }
        } finally {
            closeGrabber(grabber);
            closeRecorder(recorder);
        }
    }

    private ThreadPoolExecutor createEncoderExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            encoderThreads,
            encoderThreads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(encoderThreads * 2),
            runnable -> {
                Thread thread = new Thread(runnable,
                    "MCCinema-AudioEncoder-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        plugin.getLogger().info("Encoding audio chunks with " + encoderThreads + " worker thread(s)");
        return executor;
    }

    private float[][] extractSamplesAsFloat(Frame frame, int channels) {
        if (frame.samples == null || frame.samples.length == 0) {
            return null;
        }
        Buffer first = frame.samples[0];
        if (first instanceof ShortBuffer shortBuffer) {
            ShortBuffer input = shortBuffer.duplicate();
            int samplesPerChannel = input.remaining() / channels;
            float[][] result = new float[channels][samplesPerChannel];
            for (int i = 0; i < samplesPerChannel; i++) {
                for (int channel = 0; channel < channels; channel++) {
                    result[channel][i] = input.get() / 32768.0f;
                }
            }
            return result;
        }

        if (frame.samples.length < channels) {
            return null;
        }
        int samplesPerChannel = sampleCount(frame.samples[0]);
        float[][] result = new float[channels][samplesPerChannel];
        for (int channel = 0; channel < channels; channel++) {
            Buffer plane = frame.samples[channel];
            FloatBuffer input = plane instanceof FloatBuffer floatBuffer
                ? floatBuffer.duplicate()
                : ((ByteBuffer) plane).duplicate().asFloatBuffer();
            input.get(result[channel], 0, Math.min(input.remaining(), samplesPerChannel));
        }
        return result;
    }

    private static int sampleCount(Buffer buffer) {
        if (buffer instanceof FloatBuffer floatBuffer) {
            return floatBuffer.remaining();
        }
        return buffer.remaining() / Float.BYTES;
    }

    private static float[][] trimBuffer(float[][] source, int length, int channels) {
        float[][] trimmed = new float[channels][length];
        for (int channel = 0; channel < channels; channel++) {
            System.arraycopy(source[channel], 0, trimmed[channel], 0, length);
        }
        return trimmed;
    }

    private void reportChunkProgress(int readPercent, int completedChunks, int expectedChunks) {
        int encodePercent = percent(completedChunks, expectedChunks);
        int overallPercent = (readPercent + encodePercent) / 2;
        report(Stage.CHUNKING, overallPercent,
            completedChunks + " / " + expectedChunks + " chunk(s) encoded");
    }

    private void report(Stage stage, int percent, String detail) {
        progressReporter.report(stage, Math.max(0, Math.min(100, percent)), detail);
    }

    private static int resolveEncoderThreads(int configuredThreads) {
        if (configuredThreads > 0) {
            return Math.max(1, Math.min(8, configuredThreads));
        }
        int logicalProcessors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, logicalProcessors / 4));
    }

    private static int percent(long completed, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100, completed * 100 / total);
    }

    private static int chunkIndex(File file) {
        String name = file.getName();
        return Integer.parseInt(name.substring(6, name.length() - 4));
    }

    private static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static void closeGrabber(FFmpegFrameGrabber grabber) {
        try {
            grabber.stop();
        } catch (Exception ignored) {
        }
        try {
            grabber.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeRecorder(FFmpegFrameRecorder recorder) {
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.close();
        } catch (Exception ignored) {
        }
    }

    private final class ProgressReporter {
        private static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(1);

        private Stage lastStage;
        private int lastPercent = -1;
        private long lastReportNanos;

        synchronized void report(Stage stage, int percent, String detail) {
            if (progressListener == null) {
                return;
            }
            long now = System.nanoTime();
            boolean progressChanged = stage != lastStage || percent != lastPercent;
            if (!progressChanged && now - lastReportNanos < HEARTBEAT_NANOS) {
                return;
            }
            lastStage = stage;
            lastPercent = percent;
            lastReportNanos = now;
            progressListener.onProgress(stage, percent, detail);
        }
    }
}
