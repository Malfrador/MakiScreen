package de.erethon.mccinema.video.film;

import java.awt.image.BufferedImage;
import java.util.Random;

/** Standalone regression for rolling reconstruction and motion residuals. */
public final class FilmReconstructionTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 128;

    public static void main(String[] args) {
        assertFitClearsBars();
        assertFlatPrefilterPreservesEdges();
        assertRegionPaletteTransitions();
        int[] source = new int[WIDTH * HEIGHT];
        Random random = new Random(0x4d434346494c4dL);
        for (int i = 0; i < source.length; i++) source[i] = random.nextInt(0x1000000);

        byte[][] keyTiles = new byte[2][];
        int[] key = encode(source, null, null, keyTiles, true, 0, 1, 0, null);
        if (!FilmCodec.isFullscreenEncoded(keyTiles[0]) || !FilmCodec.isFullscreenAnchor(keyTiles[0])) {
            throw new AssertionError("Keyframe carrier lost its version 11 header");
        }
        if (frameId(keyTiles[0], 0) != 1 || frameId(keyTiles[0], 3) != 0) {
            throw new AssertionError("Keyframe sequence metadata was not encoded");
        }
        assertMetadataBlack(key);

        byte[][] heldTiles = new byte[2][];
        int[] heldSource = brighten(key, 6);
        int[] heldBlocks = new int[2];
        int[] held = encode(heldSource, key, keyTiles, heldTiles, false, 0, 2, 1, heldBlocks);
        if (heldBlocks[0] + heldBlocks[1] < 1500) {
            throw new AssertionError("Small luminance shift did not retain motion blocks");
        }
        assertMotionPixelsNear(heldSource, held, heldTiles, 6, "stable residual");

        byte[][] litTiles = new byte[2][];
        int[] litSource = brighten(key, 12);
        int[] motionBlocks = new int[2];
        int[] lit = encode(litSource, key, keyTiles, litTiles, false, 0, 2, 1, motionBlocks);
        if (motionBlocks[0] + motionBlocks[1] < 1500) {
            throw new AssertionError("Constant residual did not select motion blocks");
        }
        assertMotionPixelsNear(litSource, lit, litTiles, 1, "constant residual");
        if (frameId(litTiles[0], 0) != 2 || frameId(litTiles[0], 3) != 1) {
            throw new AssertionError("Interframe sequence metadata was not encoded");
        }

        byte[][] detailedTiles = new byte[2][];
        int[] detailedSource = variedLuminance(key, 9);
        int[] detailed = encode(detailedSource, key, keyTiles, detailedTiles,
            false, 0, 3, 1, null);
        int detailedBlocks = countDetailedBlocks(detailedTiles);
        if (detailedBlocks < 1500) {
            throw new AssertionError("Pixel corrections were not encoded: " + detailedBlocks);
        }
        assertMotionPixelsNear(detailedSource, detailed, detailedTiles, 8, "pixel corrections");

        byte[][] shiftedTiles = new byte[2][];
        int[] shiftedSource = shifted(lit, 1);
        int[] shiftBlocks = new int[2];
        int[] shifted = encode(shiftedSource, lit, litTiles, shiftedTiles,
            false, 1, 3, 2, shiftBlocks);
        if (shiftBlocks[0] + shiftBlocks[1] < 1500) {
            throw new AssertionError("Rolling reference did not select one-pixel motion");
        }
        assertMotionPixelsNear(shiftedSource, shifted, shiftedTiles, 1, "rolling reference");
        assertMetadataBlack(shifted);
        byte[][] noisyTiles = new byte[2][];
        int[] noisySource = variedLuminance(shifted, 3);
        int[] noisyBlocks = new int[2];
        int[] noisy = encode(noisySource, shifted, shiftedTiles, noisyTiles,
            false, 0, 4, 3, noisyBlocks);
        if (noisyBlocks[0] + noisyBlocks[1] < 1500) {
            throw new AssertionError("Bounded imperfect predictions did not take the fast path");
        }
        assertMotionPixelsNear(noisySource, noisy, noisyTiles, 4, "bounded prediction");
        int[] hardCut = new int[source.length];
        for (int i = 0; i < hardCut.length; i++) hardCut[i] = random.nextInt(0x1000000);
        int[] cutInterframe = encode(hardCut, noisy, noisyTiles, new byte[2][],
            false, 0, 5, 4, null);
        int[] cutKeyframe = encode(hardCut, null, null, new byte[2][],
            true, 0, 5, 0, null);
        assertPicturesNear(cutKeyframe, cutInterframe, 8, "hard cut");
        assertSkippedFrameRepairs(key, keyTiles);
        assertSkippedBlackWhiteEdge();
        System.out.println("PASS: palette regions, rolling reference, skipped frames, and reconstruction");
    }

    private static void assertRegionPaletteTransitions() {
        int[] colors = {0x203040, 0x8090a0, 0xe0d0c0, 0x112233};
        int[] first = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                first[y * WIDTH + x] = colors[((x / 4) + (y / 4)) & 3];
            }
        }
        byte[][] keyTiles = new byte[2][];
        int[] key = encode(first, null, null, keyTiles, true, 0, 1, 0, null);
        if (!FilmRegionPalette.isPalette(keyTiles[1], 2, 2)) {
            throw new AssertionError("Four-color region did not use its palette mode");
        }
        for (int by = 8; by < 12; by++) {
            for (int bx = 8; bx < 12; bx++) {
                if (motionBlock(keyTiles[1], bx, by)) {
                    throw new AssertionError("Palette region retained a motion mode bit");
                }
            }
        }
        assertPicturesNear(first, key, 1, "four-color palette");

        int[] recolored = first.clone();
        for (int i = 0; i < recolored.length; i++) {
            if (recolored[i] == colors[0]) recolored[i] = 0x304050;
        }
        byte[][] recoloredTiles = new byte[2][];
        int[] middle = encode(recolored, key, keyTiles, recoloredTiles,
            false, 0, 2, 1, null);
        if (!FilmRegionPalette.isPalette(recoloredTiles[1], 2, 2)) {
            throw new AssertionError("Recolored region lost its palette mode");
        }
        assertPicturesNear(recolored, middle, 1, "recolored palette");

        int[] changed = recolored.clone();
        for (int y = 32; y < 48; y++) {
            for (int x = 160; x < 176; x++) {
                changed[y * WIDTH + x] = ((x + y) & 1) == 0 ? 0 : 0xffffff;
            }
        }
        byte[][] nextTiles = new byte[2][];
        int[] next = new int[WIDTH * HEIGHT], skipped = new int[WIDTH * HEIGHT];
        for (int tileX = 0; tileX < 2; tileX++) {
            nextTiles[tileX] = FilmCodec.encodeFullscreenTile(changed, middle, key,
                WIDTH, HEIGHT, tileX, 0, 2, 1, recoloredTiles[tileX],
                0, 0, false, 8, 3, 2, null, tileX);
            FilmCodec.reconstructFullscreenTile(nextTiles[tileX], middle, next,
                WIDTH, HEIGHT, tileX * 128, 0);
            FilmCodec.reconstructFullscreenTile(nextTiles[tileX], key, skipped,
                WIDTH, HEIGHT, tileX * 128, 0);
        }
        if (FilmRegionPalette.isPalette(nextTiles[1], 2, 2)
            || !FilmRegionPalette.isPalette(nextTiles[1], 3, 2)) {
            throw new AssertionError("Palette-to-spatial transition affected a neighboring region");
        }
        assertPicturesNear(changed, next, 8, "palette-to-spatial transition");
        assertPicturesNear(next, skipped, 8, "skipped palette transition");
    }

    private static void assertFlatPrefilterPreservesEdges() {
        int[] pixels = new int[8 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 8; x++) {
                pixels[y * 8 + x] = x < 4
                    ? ((x + y) & 1) == 0 ? 0x646464 : 0x6c6c6c
                    : x < 6 ? 0 : 0xffffff;
            }
        }
        FilmFlatPrefilter.apply(pixels, 8, 4, 12, 8);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 8; x++) {
                int expected = x < 4 ? 0x686868 : x < 6 ? 0 : 0xffffff;
                if (pixels[y * 8 + x] != expected) {
                    throw new AssertionError("Flat prefilter altered a high-contrast edge");
                }
            }
        }
    }

    private static void assertSkippedBlackWhiteEdge() {
        int[] first = blackRectangle(40);
        int[] second = blackRectangle(48);
        int[] third = blackRectangle(56);
        byte[][] firstTiles = new byte[2][], secondTiles = new byte[2][];
        int[] firstDecoded = encode(first, null, null, firstTiles,
            true, 0, 1, 0, null);
        int[] secondDecoded = encode(second, firstDecoded, firstTiles, secondTiles,
            false, 0, 2, 1, null);
        int[] server = new int[WIDTH * HEIGHT], client = new int[WIDTH * HEIGHT];
        for (int tileX = 0; tileX < 2; tileX++) {
            byte[] data = FilmCodec.encodeFullscreenTile(third, secondDecoded, firstDecoded,
                WIDTH, HEIGHT, tileX, 0, 2, 1, secondTiles[tileX],
                0, 0, false, 8, 3, 2, null, tileX);
            FilmCodec.reconstructFullscreenTile(data, secondDecoded, server,
                WIDTH, HEIGHT, tileX * FilmCodec.TILE_SIZE, 0);
            FilmCodec.reconstructFullscreenTile(data, firstDecoded, client,
                WIDTH, HEIGHT, tileX * FilmCodec.TILE_SIZE, 0);
        }
        assertPicturesNear(server, client, 8, "skipped black-and-white edge");
    }

    private static int[] blackRectangle(int left) {
        int[] image = new int[WIDTH * HEIGHT];
        java.util.Arrays.fill(image, 0xffffff);
        for (int y = 20; y < 110; y++) {
            java.util.Arrays.fill(image, y * WIDTH + left, y * WIDTH + left + 64, 0);
        }
        return image;
    }

    private static void assertSkippedFrameRepairs(int[] key, byte[][] keyTiles) {
        int[] source = key.clone();
        int[] bright = brighten(key, 12);
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(bright, y * WIDTH, source, y * WIDTH, WIDTH / 2);
        }
        byte[][] skippedTiles = new byte[2][];
        int[] skipped = encode(source, key, keyTiles, skippedTiles,
            false, 0, 2, 1, null);
        int[] recoveryMotion = new int[2];
        int[] server = new int[WIDTH * HEIGHT];
        int[] client = new int[WIDTH * HEIGHT];
        for (int tileX = 0; tileX < 2; tileX++) {
            byte[] data = FilmCodec.encodeFullscreenTile(source, skipped, key,
                WIDTH, HEIGHT, tileX, 0, 2, 1, skippedTiles[tileX],
                0, 0, false, 8, 3, 2, recoveryMotion, tileX);
            FilmCodec.reconstructFullscreenTile(data, skipped, server,
                WIDTH, HEIGHT, tileX * FilmCodec.TILE_SIZE, 0);
            FilmCodec.reconstructFullscreenTile(data, key, client,
                WIDTH, HEIGHT, tileX * FilmCodec.TILE_SIZE, 0);
        }
        assertPicturesNear(server, client, 8, "skipped-frame safe prediction");
        if (recoveryMotion[0] + recoveryMotion[1] < 500) {
            throw new AssertionError("Safe regions lost temporal coding");
        }
    }

    private static void assertFitClearsBars() {
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        BufferedImage target = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        int[] pixels = FilmCodec.fitInto(source, target, 1, 0.5, 0.5);
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xffffff;
        FilmCodec.fitInto(source, target, 1, 0.5, 0.5);
        for (int color : pixels) {
            if (color != 0) throw new AssertionError("Fast fit retained a stale pixel");
        }
    }

    private static int[] brighten(int[] input, int amount) {
        int[] result = new int[input.length];
        for (int i = 0; i < input.length; i++) {
            int color = input[i];
            int red = Math.min(255, ((color >>> 16) & 255) + amount);
            int green = Math.min(255, ((color >>> 8) & 255) + amount);
            int blue = Math.min(255, (color & 255) + amount);
            result[i] = red << 16 | green << 8 | blue;
        }
        return result;
    }

    private static int[] shifted(int[] reference, int pixels) {
        int[] result = new int[reference.length];
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(reference, y * WIDTH + pixels, result, y * WIDTH, WIDTH - pixels);
        }
        return result;
    }

    private static int[] variedLuminance(int[] input, int amount) {
        int[] result = new int[input.length];
        for (int i = 0; i < input.length; i++) {
            int delta = (i & 1) == 0 ? amount : -amount;
            int color = input[i];
            int red = Math.max(0, Math.min(255, ((color >>> 16) & 255) + delta));
            int green = Math.max(0, Math.min(255, ((color >>> 8) & 255) + delta));
            int blue = Math.max(0, Math.min(255, (color & 255) + delta));
            result[i] = red << 16 | green << 8 | blue;
        }
        return result;
    }

    private static int[] encode(int[] source, int[] reference, byte[][] previousTiles,
                                byte[][] tiles, boolean keyframe, int motionX,
                                int id, int expected, int[] motionCounts) {
        int[] decoded = new int[source.length];
        for (int tileX = 0; tileX < 2; tileX++) {
            byte[] data = FilmCodec.encodeFullscreenTile(source, reference, WIDTH, HEIGHT,
                tileX, 0, 2, 1, previousTiles == null ? null : previousTiles[tileX],
                motionX, 0, keyframe, 8, id, expected, motionCounts, tileX);
            FilmCodec.reconstructFullscreenTile(data, reference, decoded, WIDTH, HEIGHT,
                tileX * FilmCodec.TILE_SIZE, 0);
            tiles[tileX] = data;
        }
        return decoded;
    }

    private static int frameId(byte[] tile, int firstSymbol) {
        int id = 0;
        for (int i = 0; i < 3; i++) {
            int x = (firstSymbol + i) * 16 + 15;
            int symbol = (tile[47 * 128 + x] & 255) - 4;
            id |= symbol << (7 * i);
        }
        return id;
    }

    private static void assertMetadataBlack(int[] decoded) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 112; x++) {
                if (!metadataPixel(x, y)) continue;
                if (decoded[y * WIDTH + x] != 0) {
                    throw new AssertionError("Metadata block appeared in reconstructed picture");
                }
            }
        }
    }

    private static void assertMotionPixelsNear(int[] expected, int[] actual, byte[][] tiles,
                                               int tolerance, String label) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (metadataPixel(x, y)) continue;
                if (!motionBlock(tiles[x / 128], (x % 128) / 4, y / 4)) continue;
                int index = y * WIDTH + x;
                int a = expected[index], b = actual[index];
                int difference = Math.max(Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255)),
                    Math.max(Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)),
                        Math.abs((a & 255) - (b & 255))));
                if (difference > tolerance) {
                    throw new AssertionError(label + " differs by " + difference + " at " + x + "," + y);
                }
            }
        }
    }

    private static void assertPicturesNear(int[] expected, int[] actual,
                                           int tolerance, String label) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (metadataPixel(x, y)) continue;
                int a = expected[y * WIDTH + x], b = actual[y * WIDTH + x];
                int difference = Math.max(Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255)),
                    Math.max(Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)),
                        Math.abs((a & 255) - (b & 255))));
                if (difference > tolerance) {
                    throw new AssertionError(label + " differs by " + difference + " at " + x + "," + y);
                }
            }
        }
    }

    private static boolean motionBlock(byte[] tile, int blockX, int blockY) {
        int macroX = blockX / 4, macroY = blockY / 4;
        int record = (blockY % 4) * 4 + blockX % 4;
        int bit = 1736 + record * 3 + 1;
        int symbol = bit / 7, shift = bit % 7;
        int first = (tile[(macroY * 16 + symbol / 16) * 128
            + macroX * 16 + symbol % 16] & 255) - 4;
        int value = first;
        if (shift > 5) {
            int next = symbol + 1;
            value |= ((tile[(macroY * 16 + next / 16) * 128
                + macroX * 16 + next % 16] & 255) - 4) << 7;
        }
        return ((value >>> shift) & 3) == 3;
    }

    private static int countDetailedBlocks(byte[][] tiles) {
        int count = 0;
        for (byte[] tile : tiles) {
            for (int by = 0; by < 32; by++) {
                for (int bx = 0; bx < 32; bx++) {
                    if (!motionBlock(tile, bx, by)) continue;
                    int record = (by & 3) * 4 + (bx & 3);
                    int rawBit = record * 16 + 15;
                    int symbol = rawBit / 7;
                    int index = (by / 4 * 16 + symbol / 16) * 128 + bx / 4 * 16 + symbol % 16;
                    if ((((tile[index] & 255) - 4) >>> (rawBit % 7) & 1) != 0) count++;
                }
            }
        }
        return count;
    }

    private static boolean metadataPixel(int x, int y) {
        return y < 16 && (x < 16 || (x >= 48 && x < 64)
            || (x >= 96 && x < 112));
    }
}
