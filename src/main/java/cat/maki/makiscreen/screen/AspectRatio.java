package cat.maki.makiscreen.screen;

public enum AspectRatio {
    RATIO_16_9("16:9", 16, 9),
    RATIO_21_9("21:9 (UltraWide)", 21, 9),
    RATIO_2_39_1("2.39:1 (CinemaScope)", 239, 100),
    RATIO_2_35_1("2.35:1 (Anamorphic)", 235, 100),
    RATIO_1_85_1("1.85:1 (Theatrical)", 185, 100),
    RATIO_4_3("4:3", 4, 3),
    RATIO_1_1("1:1", 1, 1),
    RATIO_2_1("2:1", 2, 1),
    RATIO_32_9("32:9 (Super UltraWide)", 32, 9),
    CUSTOM("Custom", 0, 0);

    private final String displayName;
    private final int widthRatio;
    private final int heightRatio;

    AspectRatio(String displayName, int widthRatio, int heightRatio) {
        this.displayName = displayName;
        this.widthRatio = widthRatio;
        this.heightRatio = heightRatio;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getWidthRatio() {
        return widthRatio;
    }

    public int getHeightRatio() {
        return heightRatio;
    }

    public static AspectRatio fromString(String name) {
        for (AspectRatio ratio : values()) {
            if (ratio.name().equalsIgnoreCase(name) || ratio.displayName.equalsIgnoreCase(name)) {
                return ratio;
            }
        }
        return RATIO_16_9;
    }

    public int[] calculateMapDimensions(int targetMaps) {
        if (this == CUSTOM) {
            throw new IllegalStateException("CUSTOM ratio requires explicit dimensions");
        }

        int bestWidth = 1;
        int bestHeight = 1;
        int bestTotal = 1;

        for (int width = 1; width <= 64; width++) {
            int height = (int) Math.round((double) width * heightRatio / widthRatio);
            if (height < 1) height = 1;
            if (height > 32) continue;

            int total = width * height;
            if (total <= targetMaps && total > bestTotal) {
                bestWidth = width;
                bestHeight = height;
                bestTotal = total;
            }
        }

        return new int[]{bestWidth, bestHeight};
    }

    public int[] calculatePixelDimensions(int mapWidth, int mapHeight) {
        return new int[]{mapWidth * 128, mapHeight * 128};
    }
}

