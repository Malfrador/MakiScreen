package de.erethon.mccinema.video.film;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/** Version 5: spatial 16x16 carrier regions with independent endpoint, chroma,
 * selector and mode planes. Each region contains sixteen 4x4 image blocks.
 * Flat/gradient blocks retain unused selector cells to avoid needless updates.
 */
public final class FilmCodec {
    public static final int TILE_SIZE = 128;
    public static final int HEADER_SIZE = 16;
    public static final int RECORD_BITS = 109;
    public static final int MACRO_SIZE = 16;
    public static final int MACRO_DATA_SYMBOLS = 255;
    public static final int PAYLOAD_END = TILE_SIZE * TILE_SIZE;
    private static final int[] MAGIC = {77, 67, 67, 70, 73, 76, 77, 5, 26, 2, 4, 4, 8, 8, 4, 96};
    private static final byte[] SHADOW_SELECTOR = createShadowSelectors();

    private FilmCodec() {}

    public static BufferedImage fit(BufferedImage source, int width, int height,
                                    double zoom, double focusX, double focusY) {
        BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        fitInto(source, fitted, zoom, focusX, focusY);
        return fitted;
    }

    public static int[] fitInto(BufferedImage source, BufferedImage fitted,
                                double zoom, double focusX, double focusY) {
        if (fitted.getType() != BufferedImage.TYPE_INT_RGB) {
            throw new IllegalArgumentException("Film target must use TYPE_INT_RGB");
        }
        int width = fitted.getWidth(), height = fitted.getHeight();
        int[] pixels = ((DataBufferInt) fitted.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, 0);
        double scale = Math.min((double) width / source.getWidth(), (double) height / source.getHeight()) * zoom;
        double targetX = width / 2.0 - source.getWidth() * scale * focusX;
        double targetY = height / 2.0 - source.getHeight() * scale * focusY;
        int integerX = (int) Math.round(targetX), integerY = (int) Math.round(targetY);
        boolean centered = Math.abs(focusX - 0.5) < 0.000001 && Math.abs(focusY - 0.5) < 0.000001;
        if (Math.abs(scale - 1.0) < 0.000001
            && (centered || (Math.abs(targetX - integerX) < 0.000001 && Math.abs(targetY - integerY) < 0.000001))
            && integerX >= 0 && integerY >= 0
            && integerX + source.getWidth() <= width && integerY + source.getHeight() <= height) {
            source.getRGB(0, 0, source.getWidth(), source.getHeight(), pixels,
                integerY * width + integerX, width);
            return pixels;
        }
        Graphics2D graphics = fitted.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            AffineTransform placement = new AffineTransform();
            placement.translate(targetX, targetY);
            placement.scale(scale, scale);
            graphics.drawImage(source, placement, null);
        } finally {
            graphics.dispose();
        }
        return pixels;
    }

    public static byte[] encodeTile(int[] rgb, int stride, int originX, int originY) {
        return encodeTile(rgb, stride, originX, originY, null);
    }

    public static byte[] encodeTile(int[] rgb, int stride, int originX, int originY, byte[] previous) {
        byte[] result = new byte[TILE_SIZE * TILE_SIZE];
        Arrays.fill(result, (byte) 4);
        boolean reusePrevious = isEncoded(previous);
        int[] luma = new int[16];
        int[] shadowSelectors = new int[16];
        double[] cbValues = new double[16];
        double[] crValues = new double[16];
        for (int by = 0; by < 32; by++) {
            for (int bx = 0; bx < 32; bx++) {
                int min = 255, max = 0;
                double cb = 0, cr = 0;
                int lumaSum = 0;
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int color = rgb[(originY + by * 4 + y) * stride + originX + bx * 4 + x];
                        int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
                        double brightness = 0.299 * r + 0.587 * g + 0.114 * b;
                        int value = clamp((int) Math.round(brightness));
                        int pixel = y * 4 + x;
                        luma[pixel] = value;
                        lumaSum += value;
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                        cbValues[pixel] = (b - brightness) / 1.772;
                        crValues[pixel] = (r - brightness) / 1.402;
                        cb += cbValues[pixel];
                        cr += crValues[pixel];
                    }
                }
                int macroX = bx / 4;
                int macroY = by / 4;
                int recordBit = ((by & 3) * 4 + (bx & 3)) * RECORD_BITS;
                double averageCb = cb / 16;
                double averageCr = cr / 16;
                int cbByte = clamp((int) Math.round(128 + averageCb));
                int crByte = clamp((int) Math.round(128 + averageCr));
                boolean shadowCurve = selectLumaCurve(luma, shadowSelectors, min, max, lumaSum);
                writeBlock(result, macroX, macroY, recordBit, min, max, cbByte, crByte);
                encodeDetail(result, macroX, macroY, recordBit, cbValues, crValues,
                    averageCb, averageCr, shadowCurve);
                selectBlockMode(result, reusePrevious ? previous : null, macroX, macroY, recordBit,
                    min, max, shadowCurve, luma);
                if (reusePrevious && !sameBlock(result, previous, macroX, macroY, recordBit)
                    && visuallyEquivalent(result, previous, macroX, macroY, recordBit)) {
                    copyRecord(previous, result, macroX, macroY, recordBit);
                }
            }
        }
        for (int i = 0; i < MAGIC.length; i++) {
            setSymbol(result, i % 8, i / 8, 255, MAGIC[i]);
        }
        return result;
    }

    // Keep all endpoint/chroma precision. Compact modes only approximate luma,
    // by at most one level relative to the existing detailed reconstruction.
    private static void selectBlockMode(byte[] data, byte[] previous, int mx, int my, int base,
                                         int min, int max, boolean shadow, int[] selectors) {
        int mode = 0;
        if (max - min <= 2) {
            mode = 1;
        } else {
            double a = selectorLuma(selectors[0], min, max, shadow);
            double b = selectorLuma(selectors[3], min, max, shadow);
            double c = selectorLuma(selectors[12], min, max, shadow);
            double d = selectorLuma(selectors[15], min, max, shadow);
            boolean fits = true;
            for (int y = 0; y < 4 && fits; y++) {
                for (int x = 0; x < 4; x++) {
                    double upper = a + (b - a) * x / 3.0;
                    double lower = c + (d - c) * x / 3.0;
                    double reconstructed = upper + (lower - upper) * y / 3.0;
                    if (Math.abs(reconstructed - selectorLuma(selectors[y * 4 + x], min, max, shadow)) > 1) {
                        fits = false;
                        break;
                    }
                }
            }
            if (fits) mode = 2;
        }
        putRaw(data, mx, my, 1736 + (base / RECORD_BITS) * 3 + 1, 2, mode);
        if (mode == 2) {
            put(data, mx, my, base + 32, 4, selectors[0]);
            put(data, mx, my, base + 36, 4, selectors[3]);
            put(data, mx, my, base + 40, 4, selectors[12]);
            put(data, mx, my, base + 44, 4, selectors[15]);
        } else if (mode == 0) {
            for (int i = 0; i < 16; i++) put(data, mx, my, base + 32 + i * 4, 4, selectors[i]);
        }
        if (mode != 0 && previous != null) {
            for (int i = mode == 1 ? 0 : 4; i < 16; i++) {
                int bit = base + 32 + i * 4;
                put(data, mx, my, bit, 4, get(previous, mx, my, bit, 4));
            }
        }
    }

    private static double selectorLuma(int selector, int min, int max, boolean shadow) {
        double fraction = selector / 15.0;
        if (shadow) fraction *= fraction;
        return min + (max - min) * fraction;
    }

    private static boolean visuallyEquivalent(byte[] current, byte[] previous,
                                               int macroX, int macroY, int recordBit) {
        int currentMin = get(current, macroX, macroY, recordBit, 8);
        int previousMin = get(previous, macroX, macroY, recordBit, 8);
        int currentMax = get(current, macroX, macroY, recordBit + 8, 8);
        int previousMax = get(previous, macroX, macroY, recordBit + 8, 8);
        double currentCb = get(current, macroX, macroY, recordBit + 16, 8) - 128.0;
        double previousCb = get(previous, macroX, macroY, recordBit + 16, 8) - 128.0;
        double currentCr = get(current, macroX, macroY, recordBit + 24, 8) - 128.0;
        double previousCr = get(previous, macroX, macroY, recordBit + 24, 8) - 128.0;
        int currentDetail = get(current, macroX, macroY, recordBit + 96, 13);
        int previousDetail = get(previous, macroX, macroY, recordBit + 96, 13);
        // Protect dark ramps and high-contrast text/edges. Modest midtone detail
        // may retain up to three levels; reference is retained data, never raw last input.
        double tolerance = currentMin < 32 || currentMax - currentMin > 64 ? 1.0 : 3.0;
        if (Math.abs(currentMin - previousMin) > 4 || Math.abs(currentMax - previousMax) > 4
            || Math.abs(currentCb - previousCb) > 3 || Math.abs(currentCr - previousCr) > 3) {
            return false;
        }

        for (int pixel = 0; pixel < 16; pixel++) {
            int x = pixel & 3;
            int y = pixel >> 2;
            double currentY = decodeLuma(current, macroX, macroY, recordBit, pixel,
                currentMin, currentMax, currentDetail);
            double previousY = decodeLuma(previous, macroX, macroY, recordBit, pixel,
                previousMin, previousMax, previousDetail);
            double currentPixelCb = decodeChroma(currentCb, currentDetail, x, y, 0);
            double previousPixelCb = decodeChroma(previousCb, previousDetail, x, y, 0);
            double currentPixelCr = decodeChroma(currentCr, currentDetail, x, y, 6);
            double previousPixelCr = decodeChroma(previousCr, previousDetail, x, y, 6);
            if (Math.abs((currentY + 1.402 * currentPixelCr) - (previousY + 1.402 * previousPixelCr)) > tolerance
                || Math.abs((currentY - 0.344136 * currentPixelCb - 0.714136 * currentPixelCr)
                    - (previousY - 0.344136 * previousPixelCb - 0.714136 * previousPixelCr)) > tolerance
                || Math.abs((currentY + 1.772 * currentPixelCb) - (previousY + 1.772 * previousPixelCb)) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBlock(byte[] current, byte[] previous,
                                     int macroX, int macroY, int recordBit) {
        if (mode(current, macroX, macroY, recordBit) != mode(previous, macroX, macroY, recordBit)) return false;
        for (int bit = 0; bit < RECORD_BITS; bit += 13) {
            int count = Math.min(13, RECORD_BITS - bit);
            if (get(current, macroX, macroY, recordBit + bit, count)
                != get(previous, macroX, macroY, recordBit + bit, count)) return false;
        }
        return true;
    }

    private static void copyRecord(byte[] source, byte[] target,
                                   int macroX, int macroY, int recordBit) {
        putRaw(target, macroX, macroY, 1736 + (recordBit / RECORD_BITS) * 3 + 1, 2,
            mode(source, macroX, macroY, recordBit));
        for (int bit = 0; bit < RECORD_BITS; bit += 13) {
            int count = Math.min(13, RECORD_BITS - bit);
            put(target, macroX, macroY, recordBit + bit, count,
                get(source, macroX, macroY, recordBit + bit, count));
        }
    }

    private static double decodeLuma(byte[] data, int macroX, int macroY, int recordBit, int pixel,
                                     int min, int max, int detail) {
        int mode = mode(data, macroX, macroY, recordBit);
        if (mode == 1) return (min + max) * 0.5;
        if (mode == 2) {
            boolean shadow = (detail & 4096) != 0;
            double a = selectorLuma(get(data, macroX, macroY, recordBit + 32, 4), min, max, shadow);
            double b = selectorLuma(get(data, macroX, macroY, recordBit + 36, 4), min, max, shadow);
            double c = selectorLuma(get(data, macroX, macroY, recordBit + 40, 4), min, max, shadow);
            double d = selectorLuma(get(data, macroX, macroY, recordBit + 44, 4), min, max, shadow);
            double x = (pixel & 3) / 3.0, y = (pixel >> 2) / 3.0;
            return (a + (b - a) * x) * (1 - y) + (c + (d - c) * x) * y;
        }
        double selector = get(data, macroX, macroY, recordBit + 32 + pixel * 4, 4) / 15.0;
        if ((detail & 4096) != 0) selector *= selector;
        return min + (max - min) * selector;
    }

    private static double decodeChroma(double average, int detail, int x, int y, int shift) {
        int slopeX = signedGradient((detail >> shift) & 7);
        int slopeY = signedGradient((detail >> (shift + 3)) & 7);
        return average + slopeX * (x - 1.5) + slopeY * (y - 1.5);
    }

    private static int signedGradient(int value) {
        return value >= 4 ? value - 8 : value;
    }

    private static boolean selectLumaCurve(int[] luma, int[] shadowSelectors,
                                           int min, int max, int lumaSum) {
        int range = max - min;
        if (range == 0) {
            Arrays.fill(luma, 0);
            return false;
        }
        long linearError = 0;
        long shadowError = 0;
        for (int i = 0; i < 16; i++) {
            int delta = luma[i] - min;
            int linear = (delta * 15 + range / 2) / range;
            int shadow = SHADOW_SELECTOR[range << 8 | delta] & 255;
            shadowSelectors[i] = shadow;
            long linearDifference = delta * 15L - range * linear;
            long shadowDifference = delta * 225L - range * shadow * shadow;
            linearError += linearDifference * linearDifference;
            shadowError += shadowDifference * shadowDifference;
            luma[i] = linear;
        }
        boolean useShadow = lumaSum < 16 * 96L
            && shadowError * 100 < linearError * 225L * 98;
        if (useShadow) System.arraycopy(shadowSelectors, 0, luma, 0, 16);
        return useShadow;
    }

    private static byte[] createShadowSelectors() {
        byte[] selectors = new byte[256 * 256];
        for (int range = 1; range < 256; range++) {
            for (int delta = 0; delta <= range; delta++) {
                selectors[range << 8 | delta] = (byte) Math.min(15,
                    Math.round((float) (Math.sqrt((double) delta / range) * 15)));
            }
        }
        return selectors;
    }

    private static void writeBlock(byte[] result, int macroX, int macroY, int recordBit,
                                   int min, int max,
                                   int cb, int cr) {
        put(result, macroX, macroY, recordBit, 8, min);
        put(result, macroX, macroY, recordBit + 8, 8, max);
        put(result, macroX, macroY, recordBit + 16, 8, cb);
        put(result, macroX, macroY, recordBit + 24, 8, cr);
    }

    private static void encodeDetail(byte[] result, int macroX, int macroY, int recordBit,
                                     double[] cb, double[] cr,
                                     double averageCb, double averageCr, boolean shadowCurve) {
        int cbX = quantizeSlope(slope(cb, true));
        int cbY = quantizeSlope(slope(cb, false));
        int crX = quantizeSlope(slope(cr, true));
        int crY = quantizeSlope(slope(cr, false));
        double flatError = error(cb, averageCb, 0, 0) + error(cr, averageCr, 0, 0);
        double gradientError = error(cb, averageCb, cbX, cbY)
            + error(cr, averageCr, crX, crY);
        boolean useful = gradientError + 4 < flatError * 0.9;
        if (useful) {
            put(result, macroX, macroY, recordBit + 96, 3, cbX & 7);
            put(result, macroX, macroY, recordBit + 99, 3, cbY & 7);
            put(result, macroX, macroY, recordBit + 102, 3, crX & 7);
            put(result, macroX, macroY, recordBit + 105, 3, crY & 7);
        }
        if (shadowCurve) put(result, macroX, macroY, recordBit + 108, 1, 1);
    }

    private static double slope(double[] values, boolean horizontal) {
        double sum = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                sum += values[y * 4 + x] * ((horizontal ? x : y) - 1.5);
            }
        }
        return sum / 20.0;
    }

    private static int quantizeSlope(double slope) {
        return Math.max(-4, Math.min(3, (int) Math.round(slope)));
    }

    private static double error(double[] values, double average, double slopeX, double slopeY) {
        double sum = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                double difference = values[y * 4 + x]
                    - average - slopeX * (x - 1.5) - slopeY * (y - 1.5);
                sum += difference * difference;
            }
        }
        return sum;
    }

    public static boolean isEncoded(byte[] bytes) {
        if (bytes == null || bytes.length != TILE_SIZE * TILE_SIZE) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (getSymbol(bytes, i % 8, i / 8, 255) != MAGIC[i]) return false;
        }
        return true;
    }

    private static void put(byte[] data, int macroX, int macroY, int bit, int count, int value) {
        int record = bit / RECORD_BITS, field = bit % RECORD_BITS;
        int available = field < 16 ? 16 - field : field < 32 ? 32 - field
            : field < 96 ? 96 - field : field < 108 ? 108 - field : 1;
        int first = Math.min(count, available);
        putRaw(data, macroX, macroY, planeBit(record, field), first, value);
        if (first < count) put(data, macroX, macroY, bit + first, count - first, value >>> first);
    }

    private static int get(byte[] data, int macroX, int macroY, int bit, int count) {
        int record = bit / RECORD_BITS, field = bit % RECORD_BITS;
        int available = field < 16 ? 16 - field : field < 32 ? 32 - field
            : field < 96 ? 96 - field : field < 108 ? 108 - field : 1;
        int first = Math.min(count, available);
        int value = getRaw(data, macroX, macroY, planeBit(record, field), first);
        if (first < count) value |= get(data, macroX, macroY, bit + first, count - first) << first;
        return value;
    }

    private static int planeBit(int record, int field) {
        if (field < 16) return record * 16 + field;
        if (field < 32) return 259 + record * 28 + field - 16;
        if (field < 96) return 707 + record * 64 + field - 32;
        if (field < 108) return 259 + record * 28 + 16 + field - 96;
        return 1736 + record * 3;
    }

    private static int mode(byte[] data, int mx, int my, int base) {
        return getRaw(data, mx, my, 1736 + (base / RECORD_BITS) * 3 + 1, 2);
    }

    private static void putRaw(byte[] data, int macroX, int macroY, int bit, int count, int value) {
        int firstSymbol = bit / 7;
        int shift = bit % 7;
        int symbolCount = (shift + count + 6) / 7;
        int packed = 0;
        for (int i = 0; i < symbolCount; i++) {
            packed |= getSymbol(data, macroX, macroY, firstSymbol + i) << (i * 7);
        }
        int valueMask = (1 << count) - 1;
        int mask = valueMask << shift;
        packed = (packed & ~mask) | (value & valueMask) << shift;
        for (int i = 0; i < symbolCount; i++) {
            setSymbol(data, macroX, macroY, firstSymbol + i, (packed >>> (i * 7)) & 127);
        }
    }

    private static int getRaw(byte[] data, int macroX, int macroY, int bit, int count) {
        int firstSymbol = bit / 7;
        int shift = bit % 7;
        int symbolCount = (shift + count + 6) / 7;
        int packed = 0;
        for (int i = 0; i < symbolCount; i++) {
            packed |= getSymbol(data, macroX, macroY, firstSymbol + i) << (i * 7);
        }
        return (packed >>> shift) & ((1 << count) - 1);
    }

    private static void setSymbol(byte[] data, int macroX, int macroY, int symbol, int value) {
        data[symbolIndex(macroX, macroY, symbol)] = (byte) (value + 4);
    }

    private static int getSymbol(byte[] data, int macroX, int macroY, int symbol) {
        return (data[symbolIndex(macroX, macroY, symbol)] & 255) - 4;
    }

    private static int symbolIndex(int macroX, int macroY, int symbol) {
        return (macroY * MACRO_SIZE + symbol / MACRO_SIZE) * TILE_SIZE
            + macroX * MACRO_SIZE + symbol % MACRO_SIZE;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
