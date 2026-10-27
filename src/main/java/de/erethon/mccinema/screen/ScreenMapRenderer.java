package de.erethon.mccinema.screen;

import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.video.film.FilmCodec;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

public class ScreenMapRenderer extends MapRenderer {

    private final MapTile tile;
    private final Screen screen;
    private final Map<MapCanvas, byte[]> lastRenderedData = new WeakHashMap<>();
    private final Map<MapCanvas, byte[]> lastSourceData = new WeakHashMap<>();
    private static final byte[] BLACK = new byte[MapTile.TOTAL_PIXELS];

    static {
        Arrays.fill(BLACK, MapColorUtil.visibleBlack());
    }

    public ScreenMapRenderer(MapTile tile, Screen screen) {
        super(true);
        this.tile = tile;
        this.screen = screen;
    }

    @Override
    public void render(@NotNull MapView mapView, @NotNull MapCanvas canvas, @NotNull Player player) {
        byte[] data = tile.getLastSentData();
        if (FilmCodec.isEncoded(data) || FilmCodec.isEncoded(tile.getLastFrameData())) {
            MCCinema plugin = MCCinema.getInstance();
            var video = plugin.getVideoPlayer(screen);
            boolean canDecode = plugin.getFilmResourcePack().isLoaded(player)
                && (video == null || video.canSendTo(player));
            // Vanilla also sends item-frame map updates. Its canvas must agree with
            // our sent baseline, including constant headers and encoded black bars.
            // A contextual canvas prevents exposing transport pixels to other viewers.
            data = canDecode ? data : BLACK;
        } else {
            var video = MCCinema.getInstance().getVideoPlayer(screen);
            if (video != null && video.hasTargetPlayerLimit() && !video.canSendTo(player)) data = BLACK;
        }
        if (data == null || data.length != MapTile.TOTAL_PIXELS) {
            return;
        }
        if (data == lastSourceData.get(canvas)) return;

        byte[] rendered = lastRenderedData.get(canvas);
        boolean firstRender = rendered == null;
        if (rendered == null) {
            rendered = new byte[MapTile.TOTAL_PIXELS];
            lastRenderedData.put(canvas, rendered);
        }
        for (int y = 0; y < MapTile.SIZE; y++) {
            int rowOffset = y * MapTile.SIZE;
            for (int x = 0; x < MapTile.SIZE; x++) {
                int index = rowOffset + x;
                if (firstRender || rendered[index] != data[index]) {
                    canvas.setPixel(x, y, data[index]);
                    rendered[index] = data[index];
                }
            }
        }
        lastSourceData.put(canvas, data);
    }
}
