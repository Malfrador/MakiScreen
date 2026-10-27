package de.erethon.mccinema.video;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

/** Named text tracks available for one local video or DCP composition. */
public final class SubtitleCatalog {
    private final Map<String, SubtitleTrack> tracks;

    public SubtitleCatalog(Map<String, SubtitleTrack> tracks) {
        Map<String, SubtitleTrack> available = new LinkedHashMap<>();
        tracks.forEach((name, track) -> {
            if (name != null && !name.isBlank() && track != null && !track.isEmpty()) {
                available.putIfAbsent(name.strip().toLowerCase(Locale.ROOT), track);
            }
        });
        this.tracks = Collections.unmodifiableMap(available);
    }

    public boolean isEmpty() { return tracks.isEmpty(); }
    public List<String> names() { return List.copyOf(tracks.keySet()); }
    public String firstName() { return tracks.keySet().stream().findFirst().orElse(null); }
    public SubtitleTrack get(String name) {
        return name == null ? null : tracks.get(name.toLowerCase(Locale.ROOT));
    }
}
