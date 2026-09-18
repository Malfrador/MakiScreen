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
        if (payload + count * overhead >= bounding.getDataSize() + overhead) return List.of(bounding);
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
