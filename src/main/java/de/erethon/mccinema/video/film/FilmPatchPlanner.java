package de.erethon.mccinema.video.film;

import de.erethon.mccinema.screen.MapTile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded exact delta planner. Allocates payloads only after choosing rectangles. */
public final class FilmPatchPlanner {
    private FilmPatchPlanner() {}

    public static List<MapTile.DirtyRegion> plan(byte[] current, byte[] previous,
                                                MapTile.DirtyRegion bounding,
                                                int limit, int overhead) {
        if (limit <= 1 || previous == null || previous.length != current.length
            || bounding.getDataSize() <= overhead
            || bounding.changedPixelCount() >= bounding.getDataSize() * 0.85) {
            return List.of(bounding);
        }
        // At most one initial rectangle per row, with no boxed keys or per-run objects.
        int[] left = new int[128], right = new int[128];
        int[] top = new int[128], bottom = new int[128];
        int count = 0;
        int endX = bounding.x() + bounding.width();
        for (int y = bounding.y(); y < bounding.y() + bounding.height(); y++) {
            int row = y * 128;
            int first = Arrays.mismatch(current, row + bounding.x(), row + endX,
                previous, row + bounding.x(), row + endX);
            if (first < 0) continue;
            int x = bounding.x() + first;
            int last = endX - 1;
            while (last > x && current[row + last] == previous[row + last]) last--;
            if (count > 0 && bottom[count - 1] == y
                && left[count - 1] == x && right[count - 1] == last + 1) {
                bottom[count - 1] = y + 1;
                continue;
            }
            left[count] = x;
            right[count] = last + 1;
            top[count] = y;
            bottom[count] = y + 1;
            count++;
        }
        if (count == 0) return List.of();
        // Adjacent vertical bands never overlap, so merging cannot duplicate payload.
        while (count > 1) {
            int best = -1, bestExtra = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < count; i++) {
                int merged = (Math.max(right[i], right[i + 1]) - Math.min(left[i], left[i + 1]))
                    * (bottom[i + 1] - top[i]);
                int extra = merged - (right[i] - left[i]) * (bottom[i] - top[i])
                    - (right[i + 1] - left[i + 1]) * (bottom[i + 1] - top[i + 1]);
                if (extra < bestExtra) { best = i; bestExtra = extra; }
            }
            if (count <= limit && bestExtra >= overhead) break;
            left[best] = Math.min(left[best], left[best + 1]);
            right[best] = Math.max(right[best], right[best + 1]);
            bottom[best] = bottom[best + 1];
            int remaining = count - best - 2;
            System.arraycopy(left, best + 2, left, best + 1, remaining);
            System.arraycopy(right, best + 2, right, best + 1, remaining);
            System.arraycopy(top, best + 2, top, best + 1, remaining);
            System.arraycopy(bottom, best + 2, bottom, best + 1, remaining);
            count--;
        }
        int payload = 0;
        for (int i = 0; i < count; i++) payload += (right[i] - left[i]) * (bottom[i] - top[i]);
        int boundingCost = bounding.getDataSize() + overhead;
        int rowCost = payload + count * overhead;
        List<MapTile.DirtyRegion> cells = planCells(current, previous, bounding, limit,
            overhead, Math.min(rowCost, boundingCost));
        if (cells != null) return cells;
        return rowCost < boundingCost
            ? materialize(current, left, right, top, bottom, count) : List.of(bounding);
    }

    private static List<MapTile.DirtyRegion> planCells(byte[] current, byte[] previous,
                                                        MapTile.DirtyRegion bounding, int limit,
                                                        int overhead, int bestCost) {
        int[] left = new int[16], right = new int[16], top = new int[16], bottom = new int[16];
        int count = 0;
        int endY = bounding.y() + bounding.height(), endX = bounding.x() + bounding.width();
        for (int cellY = bounding.y() / 32; cellY <= (endY - 1) / 32; cellY++) {
            for (int cellX = bounding.x() / 32; cellX <= (endX - 1) / 32; cellX++) {
                int minX = 128, maxX = -1, minY = 128, maxY = -1;
                int y0 = Math.max(bounding.y(), cellY * 32), y1 = Math.min(endY, (cellY + 1) * 32);
                int x0 = Math.max(bounding.x(), cellX * 32), x1 = Math.min(endX, (cellX + 1) * 32);
                for (int y = y0; y < y1; y++) {
                    int row = y * 128;
                    int first = Arrays.mismatch(current, row + x0, row + x1,
                        previous, row + x0, row + x1);
                    if (first < 0) continue;
                    int x = x0 + first, last = x1 - 1;
                    while (last > x && current[row + last] == previous[row + last]) last--;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, last + 1);
                    minY = Math.min(minY, y);
                    maxY = y + 1;
                }
                if (maxX < 0) continue;
                left[count] = minX;
                right[count] = maxX;
                top[count] = minY;
                bottom[count] = maxY;
                count++;
            }
        }
        if (count <= 1) return null;
        int packetLimit = Math.min(limit, 8);
        while (count > 1) {
            int bestA = -1, bestB = -1, bestExtra = Integer.MAX_VALUE;
            for (int a = 0; a < count; a++) {
                for (int b = a + 1; b < count; b++) {
                    int area = (Math.max(right[a], right[b]) - Math.min(left[a], left[b]))
                        * (Math.max(bottom[a], bottom[b]) - Math.min(top[a], top[b]));
                    int extra = area - (right[a] - left[a]) * (bottom[a] - top[a])
                        - (right[b] - left[b]) * (bottom[b] - top[b]);
                    if (extra < bestExtra) {
                        bestA = a;
                        bestB = b;
                        bestExtra = extra;
                    }
                }
            }
            if (count <= packetLimit && bestExtra >= overhead) break;
            left[bestA] = Math.min(left[bestA], left[bestB]);
            right[bestA] = Math.max(right[bestA], right[bestB]);
            top[bestA] = Math.min(top[bestA], top[bestB]);
            bottom[bestA] = Math.max(bottom[bestA], bottom[bestB]);
            int remaining = count - bestB - 1;
            System.arraycopy(left, bestB + 1, left, bestB, remaining);
            System.arraycopy(right, bestB + 1, right, bestB, remaining);
            System.arraycopy(top, bestB + 1, top, bestB, remaining);
            System.arraycopy(bottom, bestB + 1, bottom, bestB, remaining);
            count--;
        }
        int payload = 0;
        for (int i = 0; i < count; i++) payload += (right[i] - left[i]) * (bottom[i] - top[i]);
        if (payload + count * overhead >= bestCost) return null;
        return materialize(current, left, right, top, bottom, count);
    }

    private static List<MapTile.DirtyRegion> materialize(byte[] current,
                                                          int[] left, int[] right, int[] top, int[] bottom,
                                                          int count) {
        List<MapTile.DirtyRegion> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int width = right[i] - left[i], height = bottom[i] - top[i];
            byte[] data = new byte[width * height];
            for (int row = 0; row < height; row++) {
                System.arraycopy(current, (top[i] + row) * 128 + left[i], data, row * width, width);
            }
            result.add(new MapTile.DirtyRegion(left[i], top[i], width, height, data));
        }
        return result;
    }
}
