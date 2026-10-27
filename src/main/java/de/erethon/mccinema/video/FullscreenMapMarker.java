package de.erethon.mccinema.video;

import de.erethon.mccinema.screen.MapTile;

/** Addresses a normal map tile for the 26.3 fullscreen text shader. */
final class FullscreenMapMarker {
    private static final int[] HEADER = {77, 67, 67, 70, 73, 76, 77, 7,
        0, 0, 0, 0, 26, 3, 7, 98};

    private FullscreenMapMarker() {}

    static void mark(byte[] mapData, MapTile tile, int tilesWide, int tilesHigh) {
        for (int i = 0; i < HEADER.length; i++) {
            int symbol = switch (i) {
                case 8 -> tile.getPixelOffsetX() / MapTile.SIZE;
                case 9 -> tile.getPixelOffsetY() / MapTile.SIZE;
                case 10 -> tilesWide;
                case 11 -> tilesHigh;
                default -> HEADER[i];
            };
            // The first 128 opaque map colors are the shader's 7-bit symbols.
            int x = (i % 8) * 16 + 15;
            int y = (i / 8) * 16 + 15;
            mapData[y * MapTile.SIZE + x] = (byte) (symbol + 4);
        }
    }
}
