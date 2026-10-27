package de.erethon.mccinema.video.film;

import de.erethon.mccinema.screen.MapTile;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;

/** Manual benchmark: pass a video path and optional map width/height. */
public final class FilmThroughputBenchmark {
    public static void main(String[] args) throws Exception {
        int tilesWide = args.length > 1 ? Integer.parseInt(args[1]) : 17;
        int tilesHigh = args.length > 2 ? Integer.parseInt(args[2]) : 7;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int frames = args.length > 4 ? Integer.parseInt(args[4]) : 12;
        int tolerance = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        int patchLimit = args.length > 6 ? Integer.parseInt(args[6]) : 8;
        int stabilize = args.length > 7 ? Integer.parseInt(args[7]) : 0;
        int pixelate = args.length > 8 ? Integer.parseInt(args[8]) : 1;
        int carrierLayout = args.length > 9 ? Integer.parseInt(args[9]) : 0;
        int cellSize = args.length > 10 ? Integer.parseInt(args[10]) : 32;
        int width = tilesWide * 128, height = tilesHigh * 128;
        int totalTiles = tilesWide * tilesHigh;
        BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        byte[][] previousTiles = new byte[totalTiles][];
        byte[][] previousCarriers = new byte[totalTiles][];
        int[] reference = null, reconstructed = new int[width * height];
        int[] motionCounts = new int[totalTiles];
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(args[0])) {
            grabber.start();
            int sourceWidth = grabber.getImageWidth(), sourceHeight = grabber.getImageHeight();
            double scale = Math.min(1.0, Math.min((double) width / sourceWidth,
                (double) height / sourceHeight));
            grabber.setImageWidth(Math.max(1, (int) Math.round(sourceWidth * scale)));
            grabber.setImageHeight(Math.max(1, (int) Math.round(sourceHeight * scale)));
            Java2DFrameConverter converter = new Java2DFrameConverter();
            for (int frame = 0; frame < frames; frame++) {
                Frame decoded = grabber.grabImage();
                if (decoded == null) break;
                long fitStart = System.nanoTime();
                int[] pixels = FilmCodec.fitInto(converter.convert(decoded), fitted, 1, 0.5, 0.5);
                int[] original = pixels.clone();
                if (pixelate > 1) {
                    for (int y = 0; y < height; y += pixelate) {
                        for (int x = 0; x < width; x += pixelate) {
                            int color = pixels[y * width + x];
                            for (int yy = y; yy < Math.min(height, y + pixelate); yy++) {
                                Arrays.fill(pixels, yy * width + x,
                                    yy * width + Math.min(width, x + pixelate), color);
                            }
                        }
                    }
                }
                if (reference != null && stabilize > 0) {
                    for (int i = 0; i < pixels.length; i++) {
                        int a = pixels[i], b = reference[i];
                        if (Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255)) <= stabilize
                            && Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255)) <= stabilize
                            && Math.abs((a & 255) - (b & 255)) <= stabilize) {
                            pixels[i] = b;
                        }
                    }
                }
                long fitEnd = System.nanoTime();
                Arrays.fill(motionCounts, 0);
                int changed = 0, motion = 0, packets = 0;
                long payload = 0, compressed = 0;
                List<Future<TileTraffic>> futures = new ArrayList<>(totalTiles);
                int[] frameReference = reference;
                int[] frameReconstructed = reconstructed;
                for (int tileY = 0; tileY < tilesHigh; tileY++) {
                    for (int tileX = 0; tileX < tilesWide; tileX++) {
                        int index = tileY * tilesWide + tileX;
                        int x = tileX, y = tileY, number = frame;
                        futures.add(executor.submit(() -> {
                            byte[] encoded = FilmCodec.encodeFullscreenTile(pixels, frameReference,
                                width, height, x, y, tilesWide, tilesHigh,
                                previousTiles[index], 0, 0, number % 121 == 0, tolerance,
                                number + 1, number, motionCounts, index);
                            byte[] carrier = carrierLayout == 1 ? recordMajorCarrier(encoded)
                                : carrierLayout == 2 ? motionFrontCarrier(encoded)
                                : carrierLayout == 3 ? blockMajorCarrier(encoded) : encoded;
                            byte[] priorCarrier = previousCarriers[index];
                            int tileChanges = 0;
                            int minX = 128, minY = 128, maxX = -1, maxY = -1;
                            if (priorCarrier != null) {
                                for (int i = 0; i < carrier.length; i++) {
                                    if (carrier[i] != priorCarrier[i]) {
                                        tileChanges++;
                                        int px = i & 127, py = i >> 7;
                                        minX = Math.min(minX, px);
                                        minY = Math.min(minY, py);
                                        maxX = Math.max(maxX, px);
                                        maxY = Math.max(maxY, py);
                                    }
                                }
                            }
                            int tilePayload = 0;
                            int tileCompressed = 0;
                            int tilePackets = 0;
                            if (tileChanges > 0) {
                                int patchWidth = maxX - minX + 1, patchHeight = maxY - minY + 1;
                                byte[] patchData = new byte[patchWidth * patchHeight];
                                for (int row = 0; row < patchHeight; row++) {
                                    System.arraycopy(carrier, (minY + row) * 128 + minX,
                                        patchData, row * patchWidth, patchWidth);
                                }
                                MapTile.DirtyRegion bounds = new MapTile.DirtyRegion(minX, minY,
                                    patchWidth, patchHeight, patchData, tileChanges);
                                if (number % 121 == 0) {
                                    tilePayload = bounds.getDataSize() + 16;
                                    tileCompressed = compressedSize(bounds.data()) + 16;
                                    tilePackets = 1;
                                } else {
                                    for (MapTile.DirtyRegion patch : FilmPatchPlanner.plan(carrier,
                                        priorCarrier, bounds, patchLimit, 16, cellSize)) {
                                        tilePayload += patch.getDataSize() + 16;
                                        tileCompressed += compressedSize(patch.data()) + 16;
                                        tilePackets++;
                                    }
                                }
                            }
                            FilmCodec.reconstructFullscreenTile(encoded, frameReference, frameReconstructed,
                                width, height, x * 128, y * 128);
                            previousTiles[index] = encoded;
                            previousCarriers[index] = carrier;
                            return new TileTraffic(tileChanges, tilePayload, tileCompressed, tilePackets);
                        }));
                    }
                }
                for (int i = 0; i < totalTiles; i++) {
                    TileTraffic result = futures.get(i).get();
                    changed += result.changed();
                    payload += result.payload();
                    compressed += result.compressed();
                    packets += result.packets();
                    motion += motionCounts[i];
                }
                long end = System.nanoTime();
                long difference = 0;
                for (int i = 0; i < pixels.length; i++) {
                    int actual = original[i], predicted = reconstructed[i];
                    difference += Math.abs(((actual >>> 16) & 255) - ((predicted >>> 16) & 255));
                    difference += Math.abs(((actual >>> 8) & 255) - ((predicted >>> 8) & 255));
                    difference += Math.abs((actual & 255) - (predicted & 255));
                }
                System.out.printf("frame=%d source=%dx%d fit=%.1fms codec=%.1fms motion=%d/%d changed=%d packets=%d payload=%d zlib=%d mae=%.2f%n",
                    frame, decoded.imageWidth, decoded.imageHeight,
                    (fitEnd - fitStart) / 1e6, (end - fitEnd) / 1e6,
                    motion, totalTiles * 1024, changed, packets, payload, compressed,
                    difference / (3.0 * pixels.length));
                reference = reconstructed;
                reconstructed = new int[width * height];
            }
        }
        finally {
            executor.shutdownNow();
        }
    }

    private record TileTraffic(int changed, int payload, int compressed, int packets) {}

    private static byte[] blockMajorCarrier(byte[] input) {
        byte[] packed = recordMajorCarrier(input);
        byte[] result = new byte[input.length];
        for (int macroY = 0; macroY < 8; macroY++) {
            for (int macroX = 0; macroX < 8; macroX++) {
                for (int symbol = 0; symbol < 256; symbol++) {
                    int block = symbol / 16, within = symbol % 16;
                    int destination = ((block / 4) * 4 + within / 4) * 16
                        + (block % 4) * 4 + within % 4;
                    int src = (macroY * 16 + symbol / 16) * 128 + macroX * 16 + symbol % 16;
                    int dst = (macroY * 16 + destination / 16) * 128
                        + macroX * 16 + destination % 16;
                    result[dst] = packed[src];
                }
            }
        }
        return result;
    }

    private static byte[] motionFrontCarrier(byte[] input) {
        byte[] result = new byte[input.length];
        for (int macroY = 0; macroY < 8; macroY++) {
            for (int macroX = 0; macroX < 8; macroX++) {
                for (int symbol = 0; symbol < 256; symbol++) {
                    int destination = symbol <= 100 || symbol == 255 ? symbol
                        : symbol >= 248 ? symbol - 147 : symbol + 7;
                    int src = (macroY * 16 + symbol / 16) * 128 + macroX * 16 + symbol % 16;
                    int dst = (macroY * 16 + destination / 16) * 128
                        + macroX * 16 + destination % 16;
                    result[dst] = input[src];
                }
            }
        }
        return result;
    }

    /** Offline transport experiment; the production decoder still uses plane-major symbols. */
    private static byte[] recordMajorCarrier(byte[] input) {
        byte[] result = new byte[input.length];
        Arrays.fill(result, (byte) 4);
        for (int macroY = 0; macroY < 8; macroY++) {
            for (int macroX = 0; macroX < 8; macroX++) {
                int[] outputSymbols = new int[256];
                for (int record = 0; record < 16; record++) {
                    for (int field = 0; field < 109; field++) {
                        int oldBit = field < 16 ? record * 16 + field
                            : field < 32 ? 259 + record * 28 + field - 16
                            : field < 96 ? 707 + record * 64 + field - 32
                            : field < 108 ? 259 + record * 28 + field - 80
                            : 1736 + record * 3;
                        int oldSymbol = oldBit / 7;
                        int oldIndex = (macroY * 16 + oldSymbol / 16) * 128
                            + macroX * 16 + oldSymbol % 16;
                        int bit = ((input[oldIndex] & 255) - 4) >>> (oldBit % 7) & 1;
                        int newBit = record * 111 + field;
                        outputSymbols[newBit / 7] |= bit << (newBit % 7);
                    }
                    for (int modeBit = 0; modeBit < 2; modeBit++) {
                        int oldBit = 1736 + record * 3 + 1 + modeBit;
                        int oldSymbol = oldBit / 7;
                        int oldIndex = (macroY * 16 + oldSymbol / 16) * 128
                            + macroX * 16 + oldSymbol % 16;
                        int bit = ((input[oldIndex] & 255) - 4) >>> (oldBit % 7) & 1;
                        int newBit = record * 111 + 109 + modeBit;
                        outputSymbols[newBit / 7] |= bit << (newBit % 7);
                    }
                }
                for (int symbol = 0; symbol < 255; symbol++) {
                    int index = (macroY * 16 + symbol / 16) * 128 + macroX * 16 + symbol % 16;
                    result[index] = (byte) (outputSymbols[symbol] + 4);
                }
                int headerIndex = (macroY * 16 + 15) * 128 + macroX * 16 + 15;
                result[headerIndex] = input[headerIndex];
            }
        }
        return result;
    }

    private static int compressedSize(byte[] input) {
        if (input.length < 256) return input.length;
        Deflater compressor = new Deflater();
        try {
            compressor.setInput(input);
            compressor.finish();
            byte[] buffer = new byte[input.length + 64];
            return compressor.deflate(buffer);
        } finally {
            compressor.end();
        }
    }
}
