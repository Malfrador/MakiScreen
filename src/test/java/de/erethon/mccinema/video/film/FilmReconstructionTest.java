package de.erethon.mccinema.video.film;

import java.util.Arrays;
import java.util.Random;

/** Standalone regression for the fullscreen encoder's retained anchor reference. */
public final class FilmReconstructionTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 128;

    public static void main(String[] args) {
        int[] source = new int[WIDTH * HEIGHT];
        Random random = new Random(0x4d434346494c4dL);
        for (int i = 0; i < source.length; i++) source[i] = random.nextInt(0x1000000);

        byte[][] firstTiles = new byte[2][];
        int[] reference = encode(source, null, null, firstTiles, true, 0);
        if (!FilmCodec.isFullscreenEncoded(firstTiles[0])) {
            throw new AssertionError("Anchor carrier lost its fullscreen header");
        }
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                if (reference[y * WIDTH + x] != 0) {
                    throw new AssertionError("Anchor marker did not reconstruct as black");
                }
            }
        }

        byte[][] stillTiles = new byte[2][];
        int[] still = encode(reference, reference, firstTiles, stillTiles, false, 0);
        if (!Arrays.equals(reference, still)) {
            throw new AssertionError("Stationary copies changed the reconstructed reference");
        }
        int[] reusedBlocks = new int[2];
        for (int tileX = 0; tileX < 2; tileX++) {
            FilmCodec.encodeFullscreenTile(reference, reference, WIDTH, HEIGHT,
                tileX, 0, 2, 1, firstTiles[tileX], 0, 0, false, 0,
                reusedBlocks, tileX);
        }
        if (reusedBlocks[0] != 1024 || reusedBlocks[1] != 1024) {
            throw new AssertionError("Stationary block reuse was not counted accurately");
        }

        byte[][] previousTiles = stillTiles;
        for (int frame = 1; frame <= 4; frame++) {
            int shift = frame * 4;
            int[] shifted = new int[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) {
                System.arraycopy(reference, y * WIDTH + shift, shifted, y * WIDTH, WIDTH - shift);
            }
            byte[][] tiles = new byte[2][];
            // Deliberately ignore the last decoded frame: a client may never render it.
            int[] decoded = encode(shifted, reference, previousTiles, tiles, false, shift);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH - shift; x++) {
                    if (decoded[y * WIDTH + x] != shifted[y * WIDTH + x]) {
                        throw new AssertionError("Motion copy drifted at frame " + frame + ", pixel " + x + "," + y);
                    }
                }
            }
            previousTiles = tiles;
        }
        byte[][] fourPixelTiles = new byte[2][];
        int[] fourPixelShift = shifted(reference, 4);
        encode(fourPixelShift, reference, stillTiles, fourPixelTiles, false, 4);
        int[] fivePixelShift = shifted(reference, 5);
        byte[][] fivePixelTiles = new byte[2][];
        int[] localMotion = encode(fivePixelShift, reference, fourPixelTiles,
            fivePixelTiles, false, 0);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH - 8; x++) {
                if (localMotion[y * WIDTH + x] != fivePixelShift[y * WIDTH + x]) {
                    throw new AssertionError("Previous block motion did not follow a one-pixel move");
                }
            }
        }
        int[] interruptedMotion = shifted(reference, 4);
        for (int y = 0; y < HEIGHT; y++) {
            Arrays.fill(interruptedMotion, y * WIDTH + 64, y * WIDTH + 128, 0x123456);
        }
        byte[][] interruptedTiles = new byte[2][];
        encode(interruptedMotion, reference, stillTiles, interruptedTiles, false, 4);
        int[] resumedMotion = shifted(reference, 4);
        byte[][] resumedTiles = new byte[2][];
        int[] propagated = encode(resumedMotion, reference, interruptedTiles,
            resumedTiles, false, 0);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 64; x < 124; x++) {
                if (propagated[y * WIDTH + x] != resumedMotion[y * WIDTH + x]) {
                    throw new AssertionError("Neighbor motion did not reach a previous spatial block");
                }
            }
        }
        System.out.println("PASS: fullscreen reconstructed stationary and moving references");
    }

    private static int[] shifted(int[] reference, int pixels) {
        int[] result = new int[reference.length];
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(reference, y * WIDTH + pixels, result, y * WIDTH, WIDTH - pixels);
        }
        return result;
    }

    private static int[] encode(int[] source, int[] reference, byte[][] previousTiles,
                                byte[][] tiles, boolean anchor, int motionX) {
        int[] decoded = new int[source.length];
        for (int tileX = 0; tileX < 2; tileX++) {
            byte[] data = FilmCodec.encodeFullscreenTile(source, reference, WIDTH, HEIGHT,
                tileX, 0, 2, 1, previousTiles == null ? null : previousTiles[tileX],
                motionX, 0, anchor, 0);
            FilmCodec.reconstructFullscreenTile(data, reference, decoded, WIDTH, HEIGHT,
                tileX * FilmCodec.TILE_SIZE, 0);
            tiles[tileX] = data;
        }
        return decoded;
    }
}
