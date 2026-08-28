package de.erethon.mccinema.screen;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

public class ScreenMapRenderer extends MapRenderer {

    private final MapTile tile;
    private byte[] lastRenderedData;

    public ScreenMapRenderer(MapTile tile) {
        super(false);
        this.tile = tile;
    }

    @Override
    public void render(@NotNull MapView mapView, @NotNull MapCanvas canvas, @NotNull Player player) {
        byte[] data = tile.getLastFrameData();
        if (data == null || data.length != MapTile.TOTAL_PIXELS || data == lastRenderedData) {
            return;
        }

        for (int y = 0; y < MapTile.SIZE; y++) {
            int rowOffset = y * MapTile.SIZE;
            for (int x = 0; x < MapTile.SIZE; x++) {
                canvas.setPixel(x, y, data[rowOffset + x]);
            }
        }
        lastRenderedData = data;
    }
}
