package de.erethon.mccinema.download;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import de.erethon.mccinema.MCCinema;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LivestreamResolver {

    private static final String LIVE_FORMAT =
        "bestvideo[protocol^=m3u8]/best[vcodec!=none][protocol^=m3u8]/bestvideo/best[vcodec!=none]";

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
            throw new IllegalStateException("Media resolver is not ready");
        }
        try {
            return resolve(url, false);
        } catch (YtDlpException error) {
            if (error.reason() == YtDlpException.Reason.AUTHENTICATION_REQUIRED && isYouTubeUrl(url)) {
                MediaToolchain.CookieStatus cookies = downloadManager.cookieStatus();
                if (cookies.usable()) {
                    plugin.getLogger().info("Livestream requires authentication; retrying with configured cookies.");
                    return resolve(url, true);
                }
                if (!cookies.description().equals("Not configured")) {
                    throw YtDlpException.fromProcess(new ExternalProcess.Result(
                        1, false, "Cookie file is invalid: " + cookies.description()), true);
                }
            }
            throw error;
        }
    }

    private boolean isYouTubeUrl(String source) {
        try {
            String host = URI.create(source).getHost();
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.equals("youtu.be") || lower.equals("youtube.com") || lower.endsWith(".youtube.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Livestream resolve(String url, boolean useCookies) throws Exception {
        List<String> arguments = List.of(
            "-f", LIVE_FORMAT,
            "--dump-single-json",
            "--no-playlist",
            url
        );
        ExternalProcess.Result result = downloadManager.executeResolver(arguments, Duration.ofSeconds(60), useCookies);
        String jsonOutput = result.output().lines().map(String::trim)
            .filter(line -> line.startsWith("{") && line.endsWith("}"))
            .reduce((first, second) -> second).orElse(null);
        if (!result.successful() || jsonOutput == null) {
            throw YtDlpException.fromProcess(result, useCookies);
        }

        JSONObject json = JSON.parseObject(jsonOutput);
        String streamUrl = findStreamUrl(json);
        if (streamUrl == null || streamUrl.isBlank()) {
            throw YtDlpException.fromProcess(
                new ExternalProcess.Result(1, false, "No playable video URL was present in yt-dlp output"), useCookies
            );
        }

        String title = firstNonBlank(json.getString("title"), "Livestream");
        String uploader = firstNonBlank(json.getString("uploader"), json.getString("channel"));
        Boolean live = json.getBoolean("is_live");
        String liveStatus = json.getString("live_status");
        if (Boolean.FALSE.equals(live) && liveStatus != null && !liveStatus.equalsIgnoreCase("is_live")) {
            plugin.getLogger().info("Resolved URL is not marked as live by yt-dlp: " + liveStatus);
        }

        return new Livestream(url, streamUrl, title, uploader, liveStatus, readHeaders(json));
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

    private Map<String, String> readHeaders(JSONObject json) {
        JSONObject source = json.getJSONObject("http_headers");
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String name = entry.getKey().replace("\r", "").replace("\n", "").strip();
            String value = String.valueOf(entry.getValue()).replace("\r", "").replace("\n", "").strip();
            if (!name.isBlank() && !value.isBlank()) {
                headers.put(name, value);
            }
        }
        return Map.copyOf(headers);
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    public record Livestream(String pageUrl, String streamUrl, String title, String uploader, String liveStatus,
                             Map<String, String> headers) {
        public String displayName() {
            if (uploader != null && !uploader.isBlank()) {
                return title + " - " + uploader;
            }
            return title;
        }
    }
}
