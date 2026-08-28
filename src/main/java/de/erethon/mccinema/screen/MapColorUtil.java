package de.erethon.mccinema.screen;

import de.erethon.mccinema.dither.MapPalette;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class MapColorUtil {

    public static final byte DEFAULT_BLANK_COLOR = 34;

    private static final Map<String, Color> NAMED_COLORS = Map.ofEntries(
        Map.entry("WHITE", Color.WHITE),
        Map.entry("BLACK", Color.BLACK),
        Map.entry("GRAY", Color.GRAY),
        Map.entry("GREY", Color.GRAY),
        Map.entry("LIGHT_GRAY", Color.LIGHT_GRAY),
        Map.entry("LIGHT_GREY", Color.LIGHT_GRAY),
        Map.entry("DARK_GRAY", Color.DARK_GRAY),
        Map.entry("DARK_GREY", Color.DARK_GRAY),
        Map.entry("RED", Color.RED),
        Map.entry("GREEN", Color.GREEN),
        Map.entry("BLUE", Color.BLUE),
        Map.entry("YELLOW", Color.YELLOW),
        Map.entry("CYAN", Color.CYAN),
        Map.entry("MAGENTA", Color.MAGENTA),
        Map.entry("ORANGE", Color.ORANGE),
        Map.entry("PINK", Color.PINK)
    );

    private MapColorUtil() {
    }

    public static Optional<Byte> parseMapColor(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("TRANSPARENT")) {
            return Optional.of((byte) 0);
        }

        Optional<Byte> numeric = parseNumericByte(normalized);
        if (numeric.isPresent()) {
            return numeric;
        }

        Optional<Color> hex = parseHexColor(normalized);
        if (hex.isPresent()) {
            return Optional.of(findClosestMapColor(hex.get()));
        }

        Color namedColor = NAMED_COLORS.get(normalized.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        if (namedColor != null) {
            return Optional.of(findClosestMapColor(namedColor));
        }

        return Optional.empty();
    }

    public static byte visibleBlack() {
        return findClosestMapColor(Color.BLACK);
    }

    private static Optional<Byte> parseNumericByte(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 255) {
                return Optional.empty();
            }
            return Optional.of((byte) parsed);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Color> parseHexColor(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6 || !hex.matches("[0-9a-fA-F]{6}")) {
            return Optional.empty();
        }

        int rgb = Integer.parseInt(hex, 16);
        return Optional.of(new Color(rgb));
    }

    private static byte findClosestMapColor(Color target) {
        int bestIndex = Byte.toUnsignedInt(DEFAULT_BLANK_COLOR);
        long bestDistance = Long.MAX_VALUE;

        for (int i = 4; i < MapPalette.NMS_PALETTE.length; i++) {
            Color candidate = MapPalette.NMS_PALETTE[i];
            long distance = colorDistanceSquared(target, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        return (byte) bestIndex;
    }

    private static long colorDistanceSquared(Color left, Color right) {
        long red = left.getRed() - right.getRed();
        long green = left.getGreen() - right.getGreen();
        long blue = left.getBlue() - right.getBlue();
        return red * red + green * green + blue * blue;
    }
}
