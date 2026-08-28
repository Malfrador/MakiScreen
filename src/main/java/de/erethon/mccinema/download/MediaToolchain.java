package de.erethon.mccinema.download;

import de.erethon.mccinema.MCCinema;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MediaToolchain {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern YT_DLP_VERSION = Pattern.compile("^\\d{4}[.-]\\d{1,2}[.-]\\d{1,2}.*$");
    private static final Pattern VERSION_NUMBER = Pattern.compile("(?:v|deno\\s+)?(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Pattern SHA256 = Pattern.compile("(?i)(?<![0-9a-f])([0-9a-f]{64})(?![0-9a-f])");
    private static final Set<PosixFilePermission> PRIVATE_EXECUTABLE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );

    private final MCCinema plugin;
    private final Path dataDirectory;
    private final Path toolsDirectory;
    private final Path ytDlpExecutable;
    private final Path denoExecutable;
    private final Path cookiesFile;
    private final HttpClient httpClient;
    private final Platform platform;

    private volatile State state = State.NOT_STARTED;
    private volatile CompletableFuture<Void> initialization;
    private volatile String ytDlpVersion;
    private volatile String jsRuntimeSpec;
    private volatile String jsRuntimeVersion;
    private volatile String failureMessage;

    MediaToolchain(MCCinema plugin) {
        this.plugin = plugin;
        this.dataDirectory = plugin.getDataFolder().toPath();
        this.toolsDirectory = dataDirectory.resolve("tools");
        this.platform = Platform.detect();
        this.ytDlpExecutable = dataDirectory.resolve(platform.windows ? "yt-dlp.exe" : "yt-dlp");
        this.denoExecutable = toolsDirectory.resolve(platform.windows ? "deno.exe" : "deno");
        this.cookiesFile = dataDirectory.resolve("youtube_cookies.txt");
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    }

    synchronized CompletableFuture<Void> initializeAsync() {
        if (state == State.READY || state == State.REPAIRING) {
            return CompletableFuture.completedFuture(null);
        }
        if (state == State.INITIALIZING && initialization != null) {
            return initialization;
        }

        state = State.INITIALIZING;
        failureMessage = null;
        initialization = CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(toolsDirectory);
                ensureYtDlp();
                detectOrInstallJavaScriptRuntime();
                state = State.READY;
                plugin.getLogger().info("Media resolver ready (yt-dlp " + ytDlpVersion +
                    (jsRuntimeVersion == null ? ", no JavaScript runtime" : ", " + jsRuntimeVersion) + ")");
            } catch (Exception e) {
                state = State.FAILED;
                failureMessage = concise(e);
                throw new CompletionException(e);
            }
        });
        return initialization;
    }

    synchronized CompletableFuture<Void> repairAsync() {
        if (initialization != null && !initialization.isDone()) {
            return initialization;
        }
        boolean toolsRemainUsable = state == State.READY;
        state = toolsRemainUsable ? State.REPAIRING : State.INITIALIZING;
        failureMessage = null;
        initialization = CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(toolsDirectory);
                installYtDlp();
                try {
                    installDeno();
                    ToolCheck installed = checkDeno(denoExecutable);
                    if (installed.valid) {
                        useRuntime("deno:" + denoExecutable.toAbsolutePath(), "Deno " + installed.version);
                    } else {
                        detectOrInstallJavaScriptRuntime();
                    }
                } catch (Exception denoFailure) {
                    plugin.getLogger().warning("Could not refresh the managed JavaScript runtime: " + concise(denoFailure));
                    detectOrInstallJavaScriptRuntime();
                }
                state = State.READY;
            } catch (Exception e) {
                state = toolsRemainUsable ? State.READY : State.FAILED;
                failureMessage = concise(e);
                throw new CompletionException(e);
            }
        });
        return initialization;
    }

    synchronized RepairRequest requestRepairAsync() {
        boolean started = initialization == null || initialization.isDone();
        return new RepairRequest(repairAsync(), started);
    }

    private void ensureYtDlp() throws Exception {
        ToolCheck existing = checkYtDlp(ytDlpExecutable);
        int updateDays = Math.max(0, plugin.getConfig().getInt("youtube.tools.update-interval-days", 7));
        boolean stale = existing.valid && updateDays > 0 && Files.getLastModifiedTime(ytDlpExecutable)
            .toInstant().isBefore(Instant.now().minus(Duration.ofDays(updateDays)));

        if (existing.valid && !stale) {
            ytDlpVersion = existing.version;
            return;
        }

        try {
            installYtDlp();
        } catch (Exception updateFailure) {
            if (existing.valid) {
                ytDlpVersion = existing.version;
                plugin.getLogger().warning("Could not refresh yt-dlp; continuing with last working version " +
                    existing.version + ": " + concise(updateFailure));
                return;
            }
            throw updateFailure;
        }
    }

    private void installYtDlp() throws Exception {
        String channel = plugin.getConfig().getString("youtube.tools.yt-dlp-channel", "stable")
            .toLowerCase(Locale.ROOT);
        String repository = channel.equals("nightly") ? "yt-dlp/yt-dlp-nightly-builds" : "yt-dlp/yt-dlp";
        String baseUrl = "https://github.com/" + repository + "/releases/latest/download/";
        String asset = platform.ytDlpAsset();
        Path temporary = Files.createTempFile(toolsDirectory, "yt-dlp-", ".download");
        try {
            download(URI.create(baseUrl + asset), temporary);
            verifyChecksum(temporary, asset, downloadText(URI.create(baseUrl + "SHA2-256SUMS")));
            makeExecutable(temporary);
            ToolCheck downloaded = checkYtDlp(temporary);
            if (!downloaded.valid) {
                throw new IOException("Downloaded yt-dlp failed its health check: " + downloaded.details);
            }
            replace(temporary, ytDlpExecutable);
            makeExecutable(ytDlpExecutable);
            ytDlpVersion = downloaded.version;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ToolCheck checkYtDlp(Path executable) {
        if (!Files.isRegularFile(executable)) {
            return ToolCheck.invalid("file is missing");
        }
        try {
            if (!isNativeExecutable(executable)) {
                return ToolCheck.invalid("the installed file is a script-based launcher, not a self-contained executable");
            }
            makeExecutable(executable);
            ExternalProcess.Result result = ExternalProcess.run(
                List.of(executable.toAbsolutePath().toString(), "--version"), PROCESS_TIMEOUT
            );
            String firstLine = firstNonBlankLine(result.output());
            if (!result.successful()) {
                return ToolCheck.invalid(processFailure(result));
            }
            if (firstLine == null || !YT_DLP_VERSION.matcher(firstLine).matches()) {
                return ToolCheck.invalid("unexpected version output: " + sanitize(firstLine));
            }
            return ToolCheck.valid(firstLine);
        } catch (Exception e) {
            return ToolCheck.invalid(concise(e));
        }
    }

    private boolean isNativeExecutable(Path executable) throws IOException {
        byte[] header = new byte[4];
        try (InputStream input = Files.newInputStream(executable)) {
            if (input.read(header) < header.length) {
                return false;
            }
        }
        if (platform.windows) {
            return header[0] == 'M' && header[1] == 'Z';
        }
        if (platform.linux) {
            return (header[0] & 0xff) == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
        }
        int magic = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16) |
            ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        return magic == 0xfeedfacf || magic == 0xcffaedfe ||
            magic == 0xcafebabe || magic == 0xbebafeca ||
            magic == 0xcafebabf || magic == 0xbfbafeca;
    }

    private void detectOrInstallJavaScriptRuntime() {
        ToolCheck managedDeno = checkDeno(denoExecutable);
        if (managedDeno.valid) {
            useRuntime("deno:" + denoExecutable.toAbsolutePath(), "Deno " + managedDeno.version);
            return;
        }

        ToolCheck systemDeno = checkDeno(Path.of(platform.windows ? "deno.exe" : "deno"));
        if (systemDeno.valid) {
            useRuntime("deno", "Deno " + systemDeno.version);
            return;
        }

        for (String command : List.of("node", "nodejs")) {
            ToolCheck node = checkNode(command);
            if (node.valid) {
                useRuntime("node", "Node.js " + node.version);
                return;
            }
        }

        try {
            installDeno();
            ToolCheck installed = checkDeno(denoExecutable);
            if (!installed.valid) {
                throw new IOException("Managed Deno failed its health check: " + installed.details);
            }
            useRuntime("deno:" + denoExecutable.toAbsolutePath(), "Deno " + installed.version);
        } catch (Exception e) {
            jsRuntimeSpec = null;
            jsRuntimeVersion = null;
            plugin.getLogger().warning("YouTube JavaScript support is unavailable: " + concise(e));
            plugin.getLogger().warning("Twitch and some YouTube media may still work; run /mcc media for diagnostics.");
        }
    }

    private void installDeno() throws Exception {
        String asset = platform.denoAsset();
        String baseUrl = "https://github.com/denoland/deno/releases/latest/download/";
        Path archive = Files.createTempFile(toolsDirectory, "deno-", ".zip");
        Path extracted = Files.createTempFile(toolsDirectory, "deno-", ".download");
        try {
            download(URI.create(baseUrl + asset), archive);
            verifyChecksum(archive, asset, downloadText(URI.create(baseUrl + asset + ".sha256sum")));
            extractExecutable(archive, extracted, platform.windows ? "deno.exe" : "deno");
            makeExecutable(extracted);
            ToolCheck check = checkDeno(extracted);
            if (!check.valid) {
                throw new IOException(check.details);
            }
            replace(extracted, denoExecutable);
            makeExecutable(denoExecutable);
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(extracted);
        }
    }

    private ToolCheck checkDeno(Path executable) {
        try {
            String command = executable.getNameCount() == 1 && !Files.exists(executable)
                ? executable.toString() : executable.toAbsolutePath().toString();
            ExternalProcess.Result result = ExternalProcess.run(List.of(command, "--version"), PROCESS_TIMEOUT);
            if (!result.successful()) {
                return ToolCheck.invalid(processFailure(result));
            }
            Matcher matcher = VERSION_NUMBER.matcher(result.output());
            if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 2 ||
                (Integer.parseInt(matcher.group(1)) == 2 && Integer.parseInt(matcher.group(2)) < 3)) {
                return ToolCheck.invalid("Deno 2.3 or newer is required");
            }
            return ToolCheck.valid(matcher.group(1) + "." + matcher.group(2) +
                (matcher.group(3) == null ? "" : "." + matcher.group(3)));
        } catch (Exception e) {
            return ToolCheck.invalid(concise(e));
        }
    }

    private ToolCheck checkNode(String command) {
        try {
            ExternalProcess.Result result = ExternalProcess.run(List.of(command, "--version"), PROCESS_TIMEOUT);
            if (!result.successful()) {
                return ToolCheck.invalid(processFailure(result));
            }
            Matcher matcher = VERSION_NUMBER.matcher(result.output());
            if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 22) {
                return ToolCheck.invalid("Node.js 22 or newer is required");
            }
            return ToolCheck.valid(matcher.group(1) + "." + matcher.group(2) +
                (matcher.group(3) == null ? "" : "." + matcher.group(3)));
        } catch (Exception e) {
            return ToolCheck.invalid(concise(e));
        }
    }

    private void useRuntime(String spec, String version) {
        jsRuntimeSpec = spec;
        jsRuntimeVersion = version;
    }

    List<String> baseCommand(boolean useCookies) {
        if (state != State.READY) {
            throw new IllegalStateException("Media resolver is not ready");
        }
        List<String> command = new ArrayList<>();
        command.add(ytDlpExecutable.toAbsolutePath().toString());
        command.add("--no-config");
        command.add("--cache-dir");
        command.add(dataDirectory.resolve("cache").toAbsolutePath().toString());
        if (jsRuntimeSpec != null) {
            command.add("--js-runtimes");
            command.add(jsRuntimeSpec);
        }
        if (useCookies && cookieStatus().usable()) {
            command.add("--cookies");
            command.add(cookiesFile.toAbsolutePath().toString());
        }
        return command;
    }

    CookieStatus cookieStatus() {
        if (!Files.isRegularFile(cookiesFile)) {
            return new CookieStatus(false, "Not configured");
        }
        if (Files.isSymbolicLink(cookiesFile)) {
            return new CookieStatus(false, "Symbolic-link cookie files are not accepted");
        }
        try {
            List<String> lines = Files.readAllLines(cookiesFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return new CookieStatus(false, "File is empty");
            }
            String header = lines.getFirst().replace("\uFEFF", "").trim();
            if (!header.equals("# Netscape HTTP Cookie File") && !header.equals("# HTTP Cookie File")) {
                return new CookieStatus(false, "Expected a Netscape cookie-file header");
            }
            boolean youtubeCookie = false;
            for (String rawLine : lines) {
                if (rawLine.isBlank() || (rawLine.startsWith("#") && !rawLine.startsWith("#HttpOnly_"))) {
                    continue;
                }
                String line = rawLine.startsWith("#HttpOnly_") ? rawLine.substring("#HttpOnly_".length()) : rawLine;
                String[] columns = line.split("\\t", -1);
                if (columns.length < 7) {
                    return new CookieStatus(false, "A cookie row does not contain seven tab-separated fields");
                }
                String domain = columns[0].toLowerCase(Locale.ROOT);
                if (domain.equals("youtube.com") || domain.endsWith(".youtube.com")) {
                    long expires;
                    try {
                        expires = Long.parseLong(columns[4]);
                    } catch (NumberFormatException e) {
                        return new CookieStatus(false, "A YouTube cookie has an invalid expiry value");
                    }
                    if (!columns[5].isBlank() && (expires == 0 || expires > Instant.now().getEpochSecond())) {
                        youtubeCookie = true;
                    }
                }
            }
            if (!youtubeCookie) {
                return new CookieStatus(false, "No unexpired YouTube cookies were found");
            }
            restrictPermissions(cookiesFile);
            return new CookieStatus(true, "Valid Netscape-format YouTube cookie file");
        } catch (Exception e) {
            return new CookieStatus(false, "Could not read cookie file: " + concise(e));
        }
    }

    private void download(URI uri, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("User-Agent", "MCCinema media toolchain")
            .GET()
            .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download returned HTTP " + response.statusCode() + " for " + uri.getHost());
        }
        if (Files.size(target) == 0) {
            throw new IOException("Downloaded file is empty");
        }
    }

    private String downloadText(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Checksum download returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private void verifyChecksum(Path file, String assetName, String checksumText) throws Exception {
        String expected = null;
        for (String line : checksumText.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.endsWith(assetName)) {
                Matcher matcher = SHA256.matcher(trimmed);
                if (matcher.find()) {
                    expected = matcher.group(1);
                    break;
                }
            }
        }
        if (expected == null) {
            Matcher matcher = SHA256.matcher(checksumText);
            if (matcher.find()) {
                expected = matcher.group(1);
                if (matcher.find()) {
                    throw new IOException("Checksum response was ambiguous for " + assetName);
                }
            }
        }
        if (expected == null) {
            throw new IOException("No checksum was published for " + assetName);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("Checksum verification failed for " + assetName);
        }
    }

    private void extractExecutable(Path archive, Path target, String expectedName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = Path.of(entry.getName()).getFileName().toString();
                if (!entry.isDirectory() && name.equals(expectedName)) {
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("Archive did not contain " + expectedName);
    }

    private void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void makeExecutable(Path path) throws IOException {
        if (!platform.windows) {
            try {
                Files.setPosixFilePermissions(path, PRIVATE_EXECUTABLE);
            } catch (UnsupportedOperationException e) {
                if (!path.toFile().setExecutable(true, true)) {
                    throw new IOException("Could not make " + path.getFileName() + " executable");
                }
            }
        }
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // POSIX permissions are unavailable on Windows and some mounted filesystems.
        }
    }

    private static String firstNonBlankLine(String output) {
        return output.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse(null);
    }

    private static String processFailure(ExternalProcess.Result result) {
        if (result.timedOut()) {
            return "process timed out";
        }
        return "exit code " + result.exitCode() + ": " + sanitize(firstNonBlankLine(result.output()));
    }

    private static String concise(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "no output";
        }
        String clean = value.replace('\r', ' ').replace('\n', ' ').strip();
        return clean.length() > 240 ? clean.substring(0, 240) + "..." : clean;
    }

    boolean isReady() {
        return state == State.READY || state == State.REPAIRING;
    }

    boolean isInitializing() {
        return state == State.INITIALIZING;
    }

    Path executable() {
        return ytDlpExecutable;
    }

    String ytDlpVersion() {
        return ytDlpVersion;
    }

    String jsRuntimeVersion() {
        return jsRuntimeVersion;
    }

    String failureMessage() {
        return failureMessage;
    }

    record CookieStatus(boolean usable, String description) {
    }

    record RepairRequest(CompletableFuture<Void> completion, boolean started) {
    }

    private record ToolCheck(boolean valid, String version, String details) {
        static ToolCheck valid(String version) {
            return new ToolCheck(true, version, null);
        }

        static ToolCheck invalid(String details) {
            return new ToolCheck(false, null, details);
        }
    }

    private enum State {
        NOT_STARTED,
        INITIALIZING,
        REPAIRING,
        READY,
        FAILED
    }

    private record Platform(boolean windows, boolean mac, boolean linux, boolean arm64, boolean musl) {

        static Platform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean windows = os.contains("win");
            boolean mac = os.contains("mac") || os.contains("darwin");
            boolean linux = os.contains("linux");
            boolean arm64 = architecture.equals("aarch64") || architecture.equals("arm64");
            boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64") || architecture.equals("x64");
            if (!windows && !mac && !linux) {
                throw new IllegalStateException("Unsupported operating system: " + os);
            }
            if (!arm64 && !x64) {
                throw new IllegalStateException("Unsupported CPU architecture: " + architecture);
            }
            return new Platform(windows, mac, linux, arm64, linux && detectsMusl());
        }

        private static boolean detectsMusl() {
            if (Files.exists(Path.of("/etc/alpine-release"))) {
                return true;
            }
            for (Path directory : List.of(Path.of("/lib"), Path.of("/usr/lib"))) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "ld-musl-*.so.1")) {
                    if (entries.iterator().hasNext()) {
                        return true;
                    }
                } catch (IOException ignored) {
                }
            }
            return false;
        }

        String ytDlpAsset() {
            if (windows) {
                return arm64 ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
            }
            if (mac) {
                return "yt-dlp_macos";
            }
            if (musl) {
                return arm64 ? "yt-dlp_musllinux_aarch64" : "yt-dlp_musllinux";
            }
            return arm64 ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        }

        String denoAsset() {
            if (windows) {
                return "deno-" + (arm64 ? "aarch64" : "x86_64") + "-pc-windows-msvc.zip";
            }
            if (mac) {
                return "deno-" + (arm64 ? "aarch64" : "x86_64") + "-apple-darwin.zip";
            }
            return "deno-" + (arm64 ? "aarch64" : "x86_64") + "-unknown-linux-gnu.zip";
        }
    }
}
