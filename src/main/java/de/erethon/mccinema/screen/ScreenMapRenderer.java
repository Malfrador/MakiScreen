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
        byte[] data = tile.getLastFrameData();
        if (FilmCodec.isEncoded(data)) {
            MCCinema plugin = MCCinema.getInstance();
            var video = plugin.getVideoPlayer(screen);
            boolean canDecode = plugin.getFilmResourcePack().isLoaded(player)
                && (video == null || video.canSendTo(player));
            // Vanilla also sends item-frame map updates. Its canvas must agree with
            // our sent baseline, including constant headers and encoded black bars.
            // A contextual canvas prevents exposing transport pixels to other viewers.
            data = canDecode ? tile.getLastSentData() : BLACK;
        }
        if (data == null || data.length != MapTile.TOTAL_PIXELS || data == lastRenderedData.get(canvas)) {
            return;
        }

        for (int y = 0; y < MapTile.SIZE; y++) {
            int rowOffset = y * MapTile.SIZE;
            for (int x = 0; x < MapTile.SIZE; x++) {
                canvas.setPixel(x, y, data[rowOffset + x]);
            }
        }
        lastRenderedData.put(canvas, data);
    }
}
