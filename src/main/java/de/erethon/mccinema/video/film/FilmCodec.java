package de.erethon.mccinema.video.film;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/** 16x16 carrier regions with spatial planes or a shared four-color palette.
 * Each region contains sixteen 4x4 image blocks.
 * Flat/gradient blocks retain unused selector cells to avoid needless updates.
 */
public final class FilmCodec {
    public static final int TILE_SIZE = 128;
    public static final int HEADER_SIZE = 16;
    public static final int RECORD_BITS = 109;
    public static final int MACRO_SIZE = 16;
    public static final int MACRO_DATA_SYMBOLS = 255;
    public static final int PAYLOAD_END = TILE_SIZE * TILE_SIZE;
    public static final int FRAME_ID_MASK = (1 << 21) - 1;
    private static final int SKIPPED_REFERENCE_TOLERANCE = 8;
    private static final int[] MAGIC = {77, 67, 67, 70, 73, 76, 77, 5, 26, 2, 4, 4, 8, 8, 4, 96};
    private static final byte[] SHADOW_SELECTOR = createShadowSelectors();
    private static final float[] CHROMA_FRACTION = {0.625f, 0.875f, 0.125f, 0.375f};
    private static final int[] LOCAL_MOTION_X = {0, 1, -1, 0, 0};
    private static final int[] LOCAL_MOTION_Y = {0, 0, 0, 1, -1};
    private static final ThreadLocal<DecodeScratch> DECODE_SCRATCH = ThreadLocal.withInitial(DecodeScratch::new);

    private static final class DecodeScratch {
        private static final int BLOCKS = 32 * 32;
        final int[] mode = new int[BLOCKS], low = new int[BLOCKS], high = new int[BLOCKS];
        final int[] cb = new int[BLOCKS], cr = new int[BLOCKS], detail = new int[BLOCKS];
        final int[] cbX = new int[BLOCKS], cbY = new int[BLOCKS];
        final int[] crX = new int[BLOCKS], crY = new int[BLOCKS];
        final int[] motionX = new int[BLOCKS], motionY = new int[BLOCKS];
        final int[] residual = new int[BLOCKS];
        final int[] motionDetailScale = new int[BLOCKS];
        final int[] paletteColor = new int[BLOCKS];
        final int[] corner00 = new int[BLOCKS], corner03 = new int[BLOCKS];
        final int[] corner30 = new int[BLOCKS], corner33 = new int[BLOCKS];
    }

    private FilmCodec() {}

    public static BufferedImage fit(BufferedImage source, int width, int height,
                                    double zoom, double focusX, double focusY) {
        return FilmImageFitter.fit(source, width, height, zoom, focusX, focusY);
    }

    public static int[] fitInto(BufferedImage source, BufferedImage fitted,
                                double zoom, double focusX, double focusY) {
        return FilmImageFitter.fitInto(source, fitted, zoom, focusX, focusY);
    }
    public static byte[] encodeTile(int[] rgb, int stride, int originX, int originY) {
        return encodeTile(rgb, stride, originX, originY, null);
    }

    public static byte[] encodeTile(int[] rgb, int stride, int originX, int originY, byte[] previous) {
        return encodeTile(rgb, stride, originX, originY, previous, null, null, 0, 0, 0, 0, false, null, 0);
    }

    private static byte[] encodeTile(int[] rgb, int stride, int originX, int originY,
                                     byte[] previous, int[] anchorPixels, int[] olderPixels, int height,
                                     int motionX, int motionY, int temporalTolerance, boolean paletteEnabled,
                                     int[] motionBlockCounts, int tileIndex) {
        boolean reusePrevious = isEncoded(previous);
        boolean reuseFullscreen = anchorPixels != null && isFullscreenEncoded(previous);
        byte[] result = reuseFullscreen ? previous.clone() : new byte[TILE_SIZE * TILE_SIZE];
        if (!reuseFullscreen) Arrays.fill(result, (byte) 4);
        int[] luma = new int[16];
        int[] shadowSelectors = new int[16];
        double[] cbValues = new double[16];
        double[] crValues = new double[16];
        boolean[] paletteRegions = paletteEnabled ? new boolean[64] : null;
        boolean[] previousPalette = paletteEnabled ? new boolean[64] : null;
        int[] spatialRegions = paletteEnabled ? new int[64] : null;
        if (paletteEnabled && reuseFullscreen) {
            for (int my = 0; my < 8; my++) {
                for (int mx = 0; mx < 8; mx++) {
                    int region = my * 8 + mx;
                    if (!FilmRegionPalette.isPalette(previous, mx, my)) continue;
                    previousPalette[region] = true;
                    int[] palette = FilmRegionPalette.find(rgb, stride, originX, originY, mx, my);
                    if (palette != null) {
                        FilmRegionPalette.write(result, mx, my, palette);
                        paletteRegions[region] = true;
                    } else {
                        FilmRegionPalette.clear(result, mx, my);
                    }
                }
            }
        }
        int[] localVectors = anchorPixels == null ? null : FilmLocalMotion.estimateTile(
            rgb, anchorPixels, stride, height, originX, originY, motionX, motionY, paletteRegions);
        for (int by = 0; by < 32; by++) {
            for (int bx = 0; bx < 32; bx++) {
                int macroX = bx / 4, macroY = by / 4;
                int region = macroY * 8 + macroX;
                if (paletteEnabled && paletteRegions[region]) continue;
                boolean priorPalette = paletteEnabled && previousPalette[region];
                int recordBit = ((by & 3) * 4 + (bx & 3)) * RECORD_BITS;
                if (anchorPixels != null
                    && writeMotionBlock(result, previous, reuseFullscreen && !priorPalette,
                    rgb, anchorPixels, olderPixels, stride, height, originX, originY, bx, by,
                    motionX, motionY, localVectors[macroY * 8 + macroX], temporalTolerance)) {
                    // The predictor has already checked every output channel of
                    // all 16 pixels against the quality limit. A motion record
                    // changes only its vector, residual and mode symbols; doing
                    // a full spatial encode to compare it is the hot path.
                    if (motionBlockCounts != null) motionBlockCounts[tileIndex]++;
                    continue;
                }
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
                double averageCb = cb / 16;
                double averageCr = cr / 16;
                int cbByte = clamp((int) Math.round(128 + averageCb));
                int crByte = clamp((int) Math.round(128 + averageCr));
                boolean shadowCurve = selectLumaCurve(luma, shadowSelectors, min, max, lumaSum);
                writeBlock(result, macroX, macroY, recordBit, min, max, cbByte, crByte);
                encodeDetail(result, macroX, macroY, recordBit, cbValues, crValues,
                    averageCb, averageCr, shadowCurve);
                // Compact spatial modes leave selector slots unread by the shader.
                // Retain those carrier bytes across fullscreen interframes too;
                // a prior motion block has no spatial selectors to preserve.
                boolean previousSpatial = reusePrevious
                    || (reuseFullscreen && !priorPalette
                        && mode(previous, macroX, macroY, recordBit) != 3);
                selectBlockMode(result, previousSpatial ? previous : null, macroX, macroY, recordBit,
                    min, max, shadowCurve, luma);
                // Reuse only earlier spatial records. Fullscreen motion records
                // refer to the retained anchor and cannot stand in for a fresh
                // spatial block. Anchors themselves always encode afresh.
                if (previousSpatial && !sameBlock(result, previous, macroX, macroY, recordBit)
                    && visuallyEquivalent(result, previous, macroX, macroY, recordBit)) {
                    copyRecord(previous, result, macroX, macroY, recordBit);
                }
                if (paletteEnabled) spatialRegions[region]++;
            }
        }
        if (paletteEnabled) {
            for (int my = 0; my < 8; my++) {
                for (int mx = 0; mx < 8; mx++) {
                    int region = my * 8 + mx;
                    if (paletteRegions[region]) continue;
                    FilmRegionPalette.clearFlag(result, mx, my);
                    if (spatialRegions[region] < 8) continue;
                    if (anchorPixels != null && reuseFullscreen
                        && FilmRegionPalette.changedSymbols(result, previous, mx, my) < 40) continue;
                    int[] palette = FilmRegionPalette.find(rgb, stride, originX, originY, mx, my);
                    if (palette != null) FilmRegionPalette.write(result, mx, my, palette);
                }
            }
        }
        for (int i = 0; i < MAGIC.length; i++) {
            setSymbol(result, i % 8, i / 8, 255, MAGIC[i]);
        }
        return result;
    }

    /** Version 11 adds region palettes to the rolling-reference motion codec. */
    public static byte[] encodeFullscreenTile(int[] rgb, int[] referencePixels, int stride, int height,
                                               int tileX, int tileY, int tilesWide, int tilesHigh,
                                               byte[] previousTile, int motionX, int motionY,
                                               boolean keyframe, int temporalTolerance,
                                               int frameId, int referenceId) {
        return encodeFullscreenTile(rgb, referencePixels, stride, height, tileX, tileY,
            tilesWide, tilesHigh, previousTile, motionX, motionY, keyframe,
            temporalTolerance, frameId, referenceId, null, 0);
    }

    public static byte[] encodeFullscreenTile(int[] rgb, int[] referencePixels, int stride, int height,
                                               int tileX, int tileY, int tilesWide, int tilesHigh,
                                               byte[] previousTile, int motionX, int motionY,
                                               boolean keyframe, int temporalTolerance,
                                               int frameId, int referenceId,
                                               int[] motionBlockCounts, int tileIndex) {
        return encodeFullscreenTile(rgb, referencePixels, null, stride, height,
            tileX, tileY, tilesWide, tilesHigh, previousTile, motionX, motionY,
            keyframe, temporalTolerance, frameId, referenceId, motionBlockCounts, tileIndex);
    }

    /** Spatially encode blocks whose prediction would be wrong after one missed render. */
    public static byte[] encodeFullscreenTile(int[] rgb, int[] referencePixels, int[] olderPixels,
                                               int stride, int height,
                                               int tileX, int tileY, int tilesWide, int tilesHigh,
                                               byte[] previousTile, int motionX, int motionY,
                                               boolean keyframe, int temporalTolerance,
                                               int frameId, int referenceId,
                                               int[] motionBlockCounts, int tileIndex) {
        int originX = tileX * TILE_SIZE, originY = tileY * TILE_SIZE;
        byte[] data = encodeTile(rgb, stride, originX, originY, previousTile,
            keyframe ? null : referencePixels, olderPixels, height, motionX, motionY,
            temporalTolerance, true, motionBlockCounts, tileIndex);
        int[] header = {77, 67, 67, 70, 73, 76, 77, 11,
            tileX, tileY, tilesWide, tilesHigh, 26, 3, 11, keyframe ? 100 : 99};
        for (int i = 0; i < header.length; i++) setSymbol(data, i % 8, i / 8, 255, header[i]);
        if (tileX == 0 && tileY == 0) {
            for (int i = 0; i < 3; i++) {
                setSymbol(data, i, 2, 255, (frameId >>> (7 * i)) & 127);
                setSymbol(data, i + 3, 2, 255, (referenceId >>> (7 * i)) & 127);
            }
        }
        return data;
    }

    private static boolean writeMotionBlock(byte[] data, byte[] previous, boolean reusePrevious,
                                            int[] rgb, int[] anchorPixels, int[] olderPixels,
                                            int stride, int height,
                                            int originX, int originY, int bx, int by,
                                            int motionX, int motionY, int localVector, int tolerance) {
        int x = originX + bx * 4, y = originY + by * 4;
        int residual = motionResidual(rgb, anchorPixels, stride, height, x, y, 0, 0, tolerance);
        long detailed = -1;
        boolean stationary = residual != Integer.MIN_VALUE;
        int dx = stationary ? 0 : motionX, dy = stationary ? 0 : motionY;
        int mx = bx / 4, my = by / 4;
        int base = ((by & 3) * 4 + (bx & 3)) * RECORD_BITS;
        if (!stationary) {
            int movingTolerance = Math.max(2, tolerance / 2);
            residual = (dx != 0 || dy != 0)
                ? motionResidual(rgb, anchorPixels, stride, height, x, y, dx, dy, movingTolerance)
                : Integer.MIN_VALUE;
            boolean matched = residual != Integer.MIN_VALUE;
            if (!matched && reusePrevious && mode(previous, mx, my, base) == 3) {
                int priorX = get(previous, mx, my, base, 8) - 64;
                int priorY = (get(previous, mx, my, base + 8, 8) & 127) - 64;
                for (int i = 0; i < LOCAL_MOTION_X.length; i++) {
                    int candidateX = priorX + LOCAL_MOTION_X[i], candidateY = priorY + LOCAL_MOTION_Y[i];
                    if (candidateX < -64 || candidateX > 63 || candidateY < -64 || candidateY > 63
                        || (candidateX == 0 && candidateY == 0)
                        || (candidateX == motionX && candidateY == motionY)
                        || !motionInBounds(stride, height, x, y, candidateX, candidateY)) continue;
                    int candidateResidual = motionResidual(rgb, anchorPixels, stride, height, x, y,
                        candidateX, candidateY, movingTolerance);
                    if (candidateResidual != Integer.MIN_VALUE) {
                        dx = candidateX;
                        dy = candidateY;
                        residual = candidateResidual;
                        matched = true;
                        break;
                    }
                }
            }
            // A moving edge may not have had its own motion vector last frame.
            // Borrow one from an already encoded or prior adjacent block, then
            // accept it only after checking all 16 current pixels against the anchor.
            for (int neighbor = 0; !matched && neighbor < 4; neighbor++) {
                if (neighbor >= 2 && !reusePrevious) break;
                int nx = bx - ((neighbor & 1) == 0 ? 1 : 0);
                int ny = by - ((neighbor & 1) == 1 ? 1 : 0);
                if (nx < 0 || ny < 0) continue;
                byte[] carrier = neighbor < 2 ? data : previous;
                int neighborMx = nx / 4, neighborMy = ny / 4;
                if (FilmRegionPalette.isPalette(carrier, neighborMx, neighborMy)) continue;
                int neighborBase = ((ny & 3) * 4 + (nx & 3)) * RECORD_BITS;
                if (mode(carrier, neighborMx, neighborMy, neighborBase) != 3) continue;
                int candidateX = get(carrier, neighborMx, neighborMy, neighborBase, 8) - 64;
                int candidateY = (get(carrier, neighborMx, neighborMy, neighborBase + 8, 8) & 127) - 64;
                if ((candidateX == 0 && candidateY == 0)
                    || (candidateX == motionX && candidateY == motionY)
                    || !motionInBounds(stride, height, x, y, candidateX, candidateY)) continue;
                int candidateResidual = motionResidual(rgb, anchorPixels, stride, height, x, y,
                    candidateX, candidateY, movingTolerance);
                if (candidateResidual != Integer.MIN_VALUE) {
                    dx = candidateX;
                    dy = candidateY;
                    residual = candidateResidual;
                    matched = true;
                }
            }
            if (!matched && localVector != 0) {
                int localX = (localVector >>> 8) - 64;
                int localY = (localVector & 255) - 64;
                if ((localX != motionX || localY != motionY)
                    && motionInBounds(stride, height, x, y, localX, localY)) {
                    int candidateResidual = motionResidual(rgb, anchorPixels, stride, height,
                        x, y, localX, localY, movingTolerance);
                    if (candidateResidual != Integer.MIN_VALUE) {
                        dx = localX;
                        dy = localY;
                        residual = candidateResidual;
                        matched = true;
                    }
                }
            }
            if (!matched) {
                detailed = FilmMotionDetail.fit(rgb, anchorPixels, stride, height,
                    x, y, 0, 0, tolerance);
                if (detailed >= 0) {
                    dx = 0;
                    dy = 0;
                } else if (motionX != 0 || motionY != 0) {
                    detailed = FilmMotionDetail.fit(rgb, anchorPixels, stride, height,
                        x, y, motionX, motionY, movingTolerance);
                    dx = motionX;
                    dy = motionY;
                }
                if (detailed < 0) return false;
                residual = FilmMotionDetail.dc(detailed);
            }
        }
        if (olderPixels != null && !safeAfterSkippedFrame(anchorPixels, olderPixels,
            stride, x, y, dx, dy)) return false;
        // Fullscreen interframes start as a copy of the prior carrier, so the
        // unused fields remain stable without copying 109 bits for each block.
        if (!reusePrevious || mode(previous, mx, my, base) != 3
            || ((get(previous, mx, my, base + 8, 8) & 128) != 0) != (detailed >= 0)) {
            // Spatial selectors left in motion blocks are high-entropy padding
            // inside rectangular map patches. Canonicalize them once so packet
            // compression can represent repeated motion regions cheaply.
            FilmCarrierMasks.clearMotionPadding(data, mx, my, base / RECORD_BITS);
        }
        put(data, mx, my, base, 8, dx + 64);
        put(data, mx, my, base + 8, 8, dy + 64 | (detailed >= 0 ? 128 : 0));
        int residualByte = detailed >= 0
            ? residual + 32 | (Long.numberOfTrailingZeros(FilmMotionDetail.scale(detailed)) << 6)
            : residual + 128;
        put(data, mx, my, base + 16, 8, residualByte);
        if (detailed >= 0) {
            for (int pixel = 0; pixel < 16; pixel++) {
                put(data, mx, my, base + 32 + pixel * 3, 3,
                    FilmMotionDetail.correction(detailed, pixel) + 4);
            }
        }
        putRaw(data, mx, my, 1736 + (base / RECORD_BITS) * 3 + 1, 2, 3);
        return true;
    }

    /** Reconstruct exactly the quantized picture retained by the fullscreen shader. */
    public static void reconstructFullscreenTile(byte[] data, int[] anchorPixels, int[] reconstructed,
                                                  int stride, int height, int originX, int originY) {
        DecodeScratch scratch = DECODE_SCRATCH.get();
        boolean metadataBlock = originX == 0 && originY == 0;
        for (int by = 0; by < 32; by++) {
            for (int bx = 0; bx < 32; bx++) {
                int index = by * 32 + bx;
                int mx = bx / 4, my = by / 4;
                int base = ((by & 3) * 4 + (bx & 3)) * RECORD_BITS;
                if (FilmRegionPalette.isPalette(data, mx, my)) {
                    int color = FilmRegionPalette.color(data, mx, my,
                        (by & 3) * 4 + (bx & 3));
                    scratch.mode[index] = 4;
                    scratch.paletteColor[index] = color;
                    int red = color >>> 16 & 255, green = color >>> 8 & 255, blue = color & 255;
                    double luma = 0.299 * red + 0.587 * green + 0.114 * blue;
                    scratch.low[index] = scratch.high[index] = clamp((int) Math.round(luma));
                    scratch.cb[index] = clamp((int) Math.round(128 + (blue - luma) / 1.772)) - 128;
                    scratch.cr[index] = clamp((int) Math.round(128 + (red - luma) / 1.402)) - 128;
                    scratch.cbX[index] = scratch.cbY[index] = 0;
                    scratch.crX[index] = scratch.crY[index] = 0;
                    scratch.detail[index] = 0;
                    continue;
                }
                int mode = mode(data, mx, my, base);
                scratch.mode[index] = mode;
                if (mode == 3) {
                    scratch.motionX[index] = get(data, mx, my, base, 8) - 64;
                    int vertical = get(data, mx, my, base + 8, 8);
                    int residualByte = get(data, mx, my, base + 16, 8);
                    scratch.motionY[index] = (vertical & 127) - 64;
                    scratch.motionDetailScale[index] = (vertical & 128) == 0
                        ? 0 : 1 << (residualByte >>> 6);
                    scratch.residual[index] = (vertical & 128) == 0
                        ? residualByte - 128 : (residualByte & 63) - 32;
                    continue;
                }
                scratch.low[index] = get(data, mx, my, base, 8);
                scratch.high[index] = get(data, mx, my, base + 8, 8);
                scratch.cb[index] = get(data, mx, my, base + 16, 8) - 128;
                scratch.cr[index] = get(data, mx, my, base + 24, 8) - 128;
                scratch.detail[index] = get(data, mx, my, base + 96, 13);
                int detail = scratch.detail[index];
                scratch.cbX[index] = signedGradient(detail & 7);
                scratch.cbY[index] = signedGradient((detail >> 3) & 7);
                scratch.crX[index] = signedGradient((detail >> 6) & 7);
                scratch.crY[index] = signedGradient((detail >> 9) & 7);
                if (mode == 2) {
                    scratch.corner00[index] = get(data, mx, my, base + 32, 4);
                    scratch.corner03[index] = get(data, mx, my, base + 36, 4);
                    scratch.corner30[index] = get(data, mx, my, base + 40, 4);
                    scratch.corner33[index] = get(data, mx, my, base + 44, 4);
                }
            }
        }
        for (int by = 0; by < 32; by++) {
            for (int bx = 0; bx < 32; bx++) {
                int index = by * 32 + bx;
                for (int iy = 0; iy < 4; iy++) {
                    int py = by * 4 + iy, y = originY + py;
                    for (int ix = 0; ix < 4; ix++) {
                        int px = bx * 4 + ix, x = originX + px;
                        if (metadataBlock && py < 16
                            && (px < 16 || (px >= 48 && px < 64)
                                || (px >= 96 && px < 112))) {
                            reconstructed[y * stride + x] = 0;
                            continue;
                        }
                        if (scratch.mode[index] == 4) {
                            reconstructed[y * stride + x] = FilmPixelMath.quantizeSpatialRed(
                                scratch.paletteColor[index]);
                        } else if (scratch.mode[index] == 3) {
                            if (anchorPixels == null) throw new IllegalStateException("Temporal block without anchor");
                            int sourceX = clamp(x + scratch.motionX[index], 0, stride - 1);
                            int sourceY = clamp(y + scratch.motionY[index], 0, height - 1);
                            int color = anchorPixels[sourceY * stride + sourceX];
                            int residual = scratch.residual[index];
                            if (scratch.motionDetailScale[index] != 0) {
                                int base = ((by & 3) * 4 + (bx & 3)) * RECORD_BITS;
                                int pixel = iy * 4 + ix;
                                residual += (get(data, bx / 4, by / 4, base + 32 + pixel * 3, 3) - 4)
                                    * scratch.motionDetailScale[index];
                            }
                            reconstructed[y * stride + x] = FilmPixelMath.applyResidual(color, residual);
                        } else {
                            reconstructed[y * stride + x] = FilmPixelMath.quantizeSpatialRed(
                                decodeFreshPixel(data, scratch, index, bx, by, px, py));
                        }
                    }
                }
            }
        }
    }

    private static int decodeFreshPixel(byte[] data, DecodeScratch s, int index,
                                        int bx, int by, int px, int py) {
        int detail = s.detail[index];
        float low = s.low[index], high = s.high[index];
        float luma;
        if (s.mode[index] == 1) {
            luma = (low + high) * 0.5f;
        } else if (s.mode[index] == 2) {
            float a = selectorLuma(s.corner00[index], low, high, detail);
            float b = selectorLuma(s.corner03[index], low, high, detail);
            float c = selectorLuma(s.corner30[index], low, high, detail);
            float d = selectorLuma(s.corner33[index], low, high, detail);
            float x = (px & 3) / 3.0f, y = (py & 3) / 3.0f;
            luma = (a + (b - a) * x) * (1.0f - y) + (c + (d - c) * x) * y;
        } else {
            int base = ((by & 3) * 4 + (bx & 3)) * RECORD_BITS;
            int selector = get(data, bx / 4, by / 4, base + 32 + ((py & 3) * 4 + (px & 3)) * 4, 4);
            luma = selectorLuma(selector, low, high, detail);
        }

        float cb = 0.0f, cr = 0.0f;
        boolean blended = false;
        if (high > 1.0f) {
            int firstX = bx + ((px & 3) < 2 ? -1 : 0);
            int firstY = by + ((py & 3) < 2 ? -1 : 0);
            float fx = CHROMA_FRACTION[px & 3], fy = CHROMA_FRACTION[py & 3];
            float sumCb = 0.0f, sumCr = 0.0f, total = 0.0f;
            float guide = (low + high) * 0.5f;
            float blendRange = guide < 72.0f || (detail & 4096) != 0 ? 32.0f : 20.0f;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int neighborX = clamp(firstX + dx, 0, 31);
                    int neighborY = clamp(firstY + dy, 0, 31);
                    int neighbor = neighborY * 32 + neighborX;
                    if (s.mode[neighbor] == 3) continue;
                    float spatial = (dx == 0 ? 1.0f - fx : fx) * (dy == 0 ? 1.0f - fy : fy);
                    float mean = (s.low[neighbor] + s.high[neighbor]) * 0.5f;
                    float difference = Math.abs(mean - guide) / blendRange;
                    float weight = spatial / (1.0f + 4.0f * difference * difference);
                    float localX = Math.max(-2.0f, Math.min(2.0f, px + 0.5f - (neighborX * 4.0f + 2.0f)));
                    float localY = Math.max(-2.0f, Math.min(2.0f, py + 0.5f - (neighborY * 4.0f + 2.0f)));
                    sumCb += (s.cb[neighbor] + s.cbX[neighbor] * localX + s.cbY[neighbor] * localY) * weight;
                    sumCr += (s.cr[neighbor] + s.crX[neighbor] * localX + s.crY[neighbor] * localY) * weight;
                    total += weight;
                }
            }
            if (total >= 0.0001f) {
                cb = sumCb / total;
                cr = sumCr / total;
                blended = true;
            }
        }
        if (!blended) {
            float localX = px + 0.5f - (bx * 4.0f + 2.0f);
            float localY = py + 0.5f - (by * 4.0f + 2.0f);
            cb = s.cb[index] + s.cbX[index] * localX + s.cbY[index] * localY;
            cr = s.cr[index] + s.crX[index] * localX + s.crY[index] * localY;
        }
        int red = clamp(Math.round(luma + 1.402f * cr));
        int green = clamp(Math.round(luma - 0.344136f * cb - 0.714136f * cr));
        int blue = clamp(Math.round(luma + 1.772f * cb));
        return red << 16 | green << 8 | blue;
    }

    private static float selectorLuma(int selector, float low, float high, int detail) {
        float fraction = selector / 15.0f;
        if ((detail & 4096) != 0) fraction *= fraction;
        return low + (high - low) * fraction;
    }

    private static int motionResidual(int[] current, int[] previous, int stride, int height,
                                      int x, int y, int dx, int dy, int tolerance) {
        if (!motionInBounds(stride, height, x, y, dx, dy)) return Integer.MIN_VALUE;
        int minRed = 255, maxRed = 0, minGreen = 255, maxGreen = 0, minBlue = 255, maxBlue = 0;
        int differenceSum = 0, zeroResidualError = 0;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int a = current[(y + row) * stride + x + col];
                int b = previous[(y + row + dy) * stride + x + col + dx];
                int red = (a >>> 16) & 255, green = (a >>> 8) & 255, blue = a & 255;
                minRed = Math.min(minRed, red);
                maxRed = Math.max(maxRed, red);
                minGreen = Math.min(minGreen, green);
                maxGreen = Math.max(maxGreen, green);
                minBlue = Math.min(minBlue, blue);
                maxBlue = Math.max(maxBlue, blue);
                int redDelta = red - ((b >>> 16) & 255);
                int greenDelta = green - ((b >>> 8) & 255);
                int blueDelta = blue - (b & 255);
                differenceSum += redDelta + greenDelta + blueDelta;
                zeroResidualError = Math.max(zeroResidualError, Math.max(Math.abs(redDelta),
                    Math.max(Math.abs(greenDelta), Math.abs(blueDelta))));
            }
        }
        int localRange = Math.max(maxRed - minRed,
            Math.max(maxGreen - minGreen, maxBlue - minBlue));
        int allowed = localRange <= 20 ? Math.min(tolerance, 4)
            : localRange <= 40 ? Math.min(tolerance, 8) : tolerance;
        // Stable blocks do not need a new residual symbol on every small
        // source fluctuation. The rolling reference itself retains the pixel.
        if (zeroResidualError <= allowed) return 0;
        int residual = clamp(Math.round(differenceSum / 48.0f), -128, 127);
        int largestDifference = 0;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int a = current[(y + row) * stride + x + col];
                int b = previous[(y + row + dy) * stride + x + col + dx];
                int red = (a >>> 16) & 255, green = (a >>> 8) & 255, blue = a & 255;
                largestDifference = Math.max(largestDifference,
                    Math.max(Math.abs(red - clamp(((b >>> 16) & 255) + residual)),
                        Math.max(Math.abs(green - clamp(((b >>> 8) & 255) + residual)),
                            Math.abs(blue - clamp((b & 255) + residual)))));
                if (largestDifference > allowed) return Integer.MIN_VALUE;
            }
        }
        return residual;
    }

    private static boolean safeAfterSkippedFrame(int[] previous, int[] older,
                                                  int stride, int x, int y, int dx, int dy) {
        for (int row = 0; row < 4; row++) {
            int offset = (y + row + dy) * stride + x + dx;
            for (int col = 0; col < 4; col++) {
                int a = previous[offset + col], b = older[offset + col];
                if (Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)) > SKIPPED_REFERENCE_TOLERANCE
                    || Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)) > SKIPPED_REFERENCE_TOLERANCE
                    || Math.abs((a & 255) - (b & 255)) > SKIPPED_REFERENCE_TOLERANCE) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean motionInBounds(int stride, int height, int x, int y, int dx, int dy) {
        return x + dx >= 0 && x + dx + 4 <= stride && y + dy >= 0 && y + dy + 4 <= height;
    }

    public static boolean isFullscreenEncoded(byte[] bytes) {
        if (bytes == null || bytes.length != TILE_SIZE * TILE_SIZE) return false;
        for (int i = 0; i < 7; i++) {
            if (getSymbol(bytes, i % 8, i / 8, 255) != MAGIC[i]) return false;
        }
        return getSymbol(bytes, 7, 0, 255) == 11;
    }

    public static boolean isFullscreenAnchor(byte[] bytes) {
        return isFullscreenEncoded(bytes) && getSymbol(bytes, 7, 1, 255) == 100;
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
        int detail = shadowCurve ? 4096 : 0;
        if (useful) detail |= (cbX & 7) | ((cbY & 7) << 3)
            | ((crX & 7) << 6) | ((crY & 7) << 9);
        put(result, macroX, macroY, recordBit + 96, 13, detail);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
