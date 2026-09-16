package de.erethon.mccinema.video.film;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/** Independent 4x4 YCbCr blocks, carried by opaque vanilla map palette entries 4..131.
 * Each block has 8-bit Y endpoints, averaged Cb/Cr, sixteen 4-bit Y selectors and
 * optional quantized horizontal/vertical chroma slopes.
 */
public final class FilmCodec {
    public static final int TILE_SIZE = 128;
    public static final int HEADER_SIZE = 16;
    public static final int BLOCK_BYTES = 14;
    public static final int BLOCK_PAYLOAD_END = HEADER_SIZE + 32 * 32 * BLOCK_BYTES;
    private static final int GRADIENT_BITS = 13;
    public static final int PAYLOAD_END = BLOCK_PAYLOAD_END + (32 * 32 * GRADIENT_BITS + 6) / 7;
    private static final int[] MAGIC = {77, 67, 67, 70, 73, 76, 77, 3, 26, 2, 4, 4, 8, 8, 4, 96};
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
        byte[] result = new byte[TILE_SIZE * TILE_SIZE];
        Arrays.fill(result, (byte) 4);
        for (int i = 0; i < MAGIC.length; i++) result[i] = (byte) (MAGIC[i] + 4);
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
                int block = by * 32 + bx;
                int offset = HEADER_SIZE + block * BLOCK_BYTES;
                double averageCb = cb / 16;
                double averageCr = cr / 16;
                int cbByte = clamp((int) Math.round(128 + averageCb));
                int crByte = clamp((int) Math.round(128 + averageCr));
                boolean shadowCurve = selectLumaCurve(luma, shadowSelectors, min, max, lumaSum);
                writeBlock(result, offset, min, max, cbByte, crByte, luma);
                encodeDetail(result, block, cbValues, crValues, averageCb, averageCr, shadowCurve);
            }
        }
        return result;
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

    private static void writeBlock(byte[] result, int offset, int min, int max,
                                   int cb, int cr, int[] selectors) {
        long low = min | (long) max << 8 | (long) cb << 16 | (long) cr << 24;
        long high = 0;
        for (int i = 0; i < 8; i++) low |= (long) selectors[i] << (32 + i * 4);
        for (int i = 8; i < 16; i++) high |= (long) selectors[i] << ((i - 8) * 4);
        for (int symbol = 0; symbol < 9; symbol++) {
            result[offset + symbol] = (byte) (((low >>> (symbol * 7)) & 127) + 4);
        }
        result[offset + 9] = (byte) (((low >>> 63 | (high & 63) << 1) & 127) + 4);
        for (int symbol = 10; symbol < 14; symbol++) {
            int shift = 6 + (symbol - 10) * 7;
            result[offset + symbol] = (byte) (((high >>> shift) & 127) + 4);
        }
    }

    private static void encodeDetail(byte[] result, int block, double[] cb, double[] cr,
                                     double averageCb, double averageCr, boolean shadowCurve) {
        int cbX = quantizeSlope(slope(cb, true));
        int cbY = quantizeSlope(slope(cb, false));
        int crX = quantizeSlope(slope(cr, true));
        int crY = quantizeSlope(slope(cr, false));
        double flatError = error(cb, averageCb, 0, 0) + error(cr, averageCr, 0, 0);
        double gradientError = error(cb, averageCb, cbX, cbY)
            + error(cr, averageCr, crX, crY);
        boolean useful = gradientError + 4 < flatError * 0.9;
        int bit = block * GRADIENT_BITS;
        if (useful) {
            put(result, BLOCK_PAYLOAD_END, bit, 3, cbX & 7);
            put(result, BLOCK_PAYLOAD_END, bit + 3, 3, cbY & 7);
            put(result, BLOCK_PAYLOAD_END, bit + 6, 3, crX & 7);
            put(result, BLOCK_PAYLOAD_END, bit + 9, 3, crY & 7);
        }
        if (shadowCurve) put(result, BLOCK_PAYLOAD_END, bit + 12, 1, 1);
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
            if ((bytes[i] & 255) != MAGIC[i] + 4) return false;
        }
        return true;
    }

    private static void put(byte[] data, int base, int bit, int count, int value) {
        for (int i = 0; i < count; i++) {
            int index = base + (bit + i) / 7;
            int symbol = (data[index] & 255) - 4;
            symbol |= ((value >>> i) & 1) << ((bit + i) % 7);
            data[index] = (byte) (symbol + 4);
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
