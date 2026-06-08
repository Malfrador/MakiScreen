package de.erethon.mccinema.download;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import de.erethon.mccinema.MCCinema;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class LivestreamResolver {

    private static final String LIVE_FORMAT = "best[protocol^=m3u8]/best";

    private final MCCinema plugin;
    private final YoutubeDownloadManager downloadManager;

    public LivestreamResolver(MCCinema plugin, YoutubeDownloadManager downloadManager) {
        this.plugin = plugin;
        this.downloadManager = downloadManager;
    }

    public boolean isSupportedUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public Livestream resolve(String url) throws Exception {
        if (!downloadManager.isReady()) {
            throw new Exception("yt-dlp is not ready yet. Please wait and try again.");
        }

        List<String> command = downloadManager.createYtDlpBaseCommand();
        command.add("-f");
        command.add(LIVE_FORMAT);
        command.add("--dump-json");
        command.add("--no-playlist");
        command.add("--ignore-errors");
        command.add("--no-abort-on-error");
        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        StringBuilder jsonOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                String trimmed = line.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    jsonOutput.append(trimmed);
                }
            }
        }

        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("Timed out while resolving livestream URL");
        }

        if (process.exitValue() != 0 || jsonOutput.isEmpty()) {
            throw new Exception(formatResolverError(output.toString()));
        }

        JSONObject json = JSON.parseObject(jsonOutput.toString());
        String streamUrl = findStreamUrl(json);
        if (streamUrl == null || streamUrl.isBlank()) {
            throw new Exception("Could not find a playable livestream URL");
        }

        String title = firstNonBlank(json.getString("title"), "Livestream");
        String uploader = firstNonBlank(json.getString("uploader"), json.getString("channel"));
        Boolean live = json.getBoolean("is_live");
        String liveStatus = json.getString("live_status");
        if (Boolean.FALSE.equals(live) && liveStatus != null && !liveStatus.equalsIgnoreCase("is_live")) {
            plugin.getLogger().info("Resolved URL is not marked as live by yt-dlp: " + liveStatus);
        }

        return new Livestream(url, streamUrl, title, uploader, liveStatus);
    }

    private String findStreamUrl(JSONObject json) {
        String directUrl = json.getString("url");
        if (directUrl != null && !directUrl.isBlank()) {
            return directUrl;
        }

        JSONArray formats = json.getJSONArray("formats");
        if (formats == null) {
            return null;
        }

        for (int i = formats.size() - 1; i >= 0; i--) {
            JSONObject format = formats.getJSONObject(i);
            String protocol = format.getString("protocol");
            String url = format.getString("url");
            if (url != null && !url.isBlank() && protocol != null && protocol.startsWith("m3u8")) {
                return url;
            }
        }

        for (int i = formats.size() - 1; i >= 0; i--) {
            JSONObject format = formats.getJSONObject(i);
            String url = format.getString("url");
            if (url != null && !url.isBlank()) {
                return url;
            }
        }

        return null;
    }

    private String formatResolverError(String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("offline") || lower.contains("not currently live")) {
            return "This channel or video is not currently live.";
        }
        if (lower.contains("private") || lower.contains("members-only") || lower.contains("subscriber-only")) {
            return "This stream is private or restricted.";
        }
        if (lower.contains("sign in") || lower.contains("login") || lower.contains("authentication") || lower.contains("cookies")) {
            return "This stream requires authentication. Add cookies to youtube_cookies.txt if this is a YouTube stream.";
        }
        if (lower.contains("unsupported url")) {
            return "Unsupported livestream URL. YouTube and Twitch links are supported.";
        }
        if (output.isBlank()) {
            return "yt-dlp did not return a playable livestream.";
        }
        return "Failed to resolve livestream: " + output.strip();
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    public record Livestream(String pageUrl, String streamUrl, String title, String uploader, String liveStatus) {
        public String displayName() {
            if (uploader != null && !uploader.isBlank()) {
                return title + " - " + uploader;
            }
            return title;
        }
    }
}
