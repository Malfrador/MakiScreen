package de.erethon.mccinema.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class ExternalProcess {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private ExternalProcess() {
    }

    static Result run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        return run(command, timeout, Map.of());
    }

    static Result run(List<String> command, Duration timeout, Map<String, String> environment)
        throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().name("MCCinema-ProcessOutput").start(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int retained = 0;
                while ((read = stream.read(buffer)) >= 0) {
                    int writable = Math.min(read, MAX_OUTPUT_BYTES - retained);
                    if (writable > 0) {
                        output.write(buffer, 0, writable);
                        retained += writable;
                    }
                }
            } catch (IOException ignored) {
                // The stream is expected to close when a timed-out process is destroyed.
            }
        });

        boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        reader.join(Duration.ofSeconds(2));

        String text = output.toString(StandardCharsets.UTF_8);
        return new Result(finished ? process.exitValue() : -1, !finished, text);
    }

    record Result(int exitCode, boolean timedOut, String output) {
        boolean successful() {
            return !timedOut && exitCode == 0;
        }
    }
}
