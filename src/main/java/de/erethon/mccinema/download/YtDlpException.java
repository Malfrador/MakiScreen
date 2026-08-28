package de.erethon.mccinema.download;

import java.util.Locale;

public final class YtDlpException extends Exception {

    public enum Reason {
        AUTHENTICATION_REQUIRED,
        COOKIES_INVALID,
        JAVASCRIPT_RUNTIME,
        OFFLINE,
        RATE_LIMITED,
        UNSUPPORTED,
        TIMEOUT,
        TOOL_UNAVAILABLE,
        EXTRACTION_FAILED
    }

    private final Reason reason;
    private final String diagnostics;

    private YtDlpException(Reason reason, String userMessage, String diagnostics) {
        super(userMessage);
        this.reason = reason;
        this.diagnostics = diagnostics;
    }

    static YtDlpException fromProcess(ExternalProcess.Result result, boolean cookiesUsed) {
        String output = result.output() == null ? "" : result.output().strip();
        String lower = output.toLowerCase(Locale.ROOT);
        if (result.timedOut()) {
            return new YtDlpException(Reason.TIMEOUT, "The media service took too long to respond.", output);
        }
        if (lower.contains("not currently live") || lower.contains("is offline") || lower.contains("offline")) {
            return new YtDlpException(Reason.OFFLINE, "This channel or video is not currently live.", output);
        }
        if (lower.contains("unsupported url") || lower.contains("no suitable extractor")) {
            return new YtDlpException(Reason.UNSUPPORTED, "This media URL is not supported.", output);
        }
        if (lower.contains("no supported javascript runtime") || lower.contains("javascript runtime") ||
            lower.contains("signature solving failed") || lower.contains("n challenge solving failed")) {
            return new YtDlpException(Reason.JAVASCRIPT_RUNTIME,
                "YouTube support is temporarily unavailable on this server.", output);
        }
        if (lower.contains("python3") && (lower.contains("no such file") || lower.contains("not found"))) {
            return new YtDlpException(Reason.TOOL_UNAVAILABLE,
                "The media resolver installation is damaged and will be repaired automatically.", output);
        }
        if (lower.contains("http error 429") || lower.contains("too many requests") ||
            lower.contains("rate limit") || lower.contains("temporarily blocked")) {
            return new YtDlpException(Reason.RATE_LIMITED,
                "The media provider is rate-limiting this server. Please try again later.", output);
        }
        if (lower.contains("cookie") && (lower.contains("invalid") || lower.contains("failed to load") ||
            lower.contains("http error 400"))) {
            return new YtDlpException(Reason.COOKIES_INVALID,
                "The configured YouTube login has expired or is invalid.", output);
        }
        if (lower.contains("sign in") || lower.contains("login") || lower.contains("authentication") ||
            lower.contains("private video") || lower.contains("members-only") || lower.contains("age-restricted") ||
            lower.contains("confirm you’re not a bot") || lower.contains("confirm you're not a bot")) {
            String message = cookiesUsed
                ? "The configured YouTube login was rejected or has expired."
                : "This media requires a YouTube login.";
            return new YtDlpException(Reason.AUTHENTICATION_REQUIRED, message, output);
        }
        return new YtDlpException(Reason.EXTRACTION_FAILED,
            "The media provider could not resolve this URL.", output);
    }

    public Reason reason() {
        return reason;
    }

    public String diagnostics() {
        return diagnostics;
    }
}
