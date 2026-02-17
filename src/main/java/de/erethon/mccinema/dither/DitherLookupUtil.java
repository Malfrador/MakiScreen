/*
 * MIT License
 *
 * Copyright (c) 2021 Brandon Li
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package de.erethon.mccinema.dither;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

public final class DitherLookupUtil {

  public static int[] PALETTE;
  public static byte[] COLOR_MAP;
  public static int[] FULL_COLOR_MAP;
  
  private static volatile boolean initialized = false;

  public static synchronized void init() {
    if (initialized) {
      return;
    }
    
    COLOR_MAP = new byte[128 * 128 * 128];
    FULL_COLOR_MAP = new int[128 * 128 * 128];
    final List<Integer> colors = getPaletteColors();
    PALETTE = new int[colors.size()];
    updateIndices(colors);
    createLookupTableParallel();
    
    initialized = true;
  }

  private static void createLookupTableParallel() {
    int numThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    
    byte[][] results = new byte[128][];
    int[][] fullResults = new int[128][];
    CountDownLatch latch = new CountDownLatch(128);

    for (int r = 0; r < 128; r++) {
      final int redIndex = r;
      executor.submit(() -> {
        try {
          byte[] colorMap = new byte[16384];
          int[] fullColorMap = new int[16384];
          int red = redIndex << 1;

          int index = 0;
          for (int g = 0; g < 256; g += 2) {
            for (int b = 0; b < 256; b += 2) {
              byte bestMatch = findClosestColor(red, g, b);
              colorMap[index] = bestMatch;
              fullColorMap[index] = PALETTE[Byte.toUnsignedInt(bestMatch)];
              index++;
            }
          }
          
          results[redIndex] = colorMap;
          fullResults[redIndex] = fullColorMap;
        } finally {
          latch.countDown();
        }
      });
    }

    try {
      latch.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    executor.shutdown();

    for (int i = 0; i < 128; i++) {
      if (results[i] != null) {
        int ci = i << 14;
        System.arraycopy(results[i], 0, COLOR_MAP, ci, 16384);
        System.arraycopy(fullResults[i], 0, FULL_COLOR_MAP, ci, 16384);
      }
    }
  }

  private static byte findClosestColor(int r, int g, int b) {
    int bestDistance = Integer.MAX_VALUE;
    byte bestIndex = 0;

    for (int i = 4; i < PALETTE.length; i++) {
      int color = PALETTE[i];
      int pr = (color >> 16) & 0xFF;
      int pg = (color >> 8) & 0xFF;
      int pb = color & 0xFF;

      int dr = r - pr;
      int dg = g - pg;
      int db = b - pb;

      int rMean = (r + pr) >> 1;
      int distance = ((512 + rMean) * dr * dr >> 8) + 4 * dg * dg + ((767 - rMean) * db * db >> 8);

      if (distance < bestDistance) {
        bestDistance = distance;
        bestIndex = (byte) i;
      }
    }

    return bestIndex;
  }

  private static void updateIndices(@NotNull final List<Integer> colors) {
    int index = 0;
    for (final int color : colors) {
      PALETTE[index++] = color;
    }
    PALETTE[0] = 0;
  }

  private static @NotNull List<Integer> getPaletteColors() {
    final List<Integer> colors = new ArrayList<>();
    for (int i = 0; i < 256; ++i) {
      try {
        final Color color = MapPalette.getColor((byte) i);
        colors.add(color.getRGB());
      } catch (final IndexOutOfBoundsException e) {
        break;
      }
    }
    return colors;
  }

  public static int[] getPalette() {
    return PALETTE;
  }

  public static byte[] getColorMap() {
    return COLOR_MAP;
  }

  public static int[] getFullColorMap() {
    return FULL_COLOR_MAP;
  }
}