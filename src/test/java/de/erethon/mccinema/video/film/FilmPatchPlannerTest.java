package de.erethon.mccinema.video.film;

import de.erethon.mccinema.screen.MapTile;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Standalone check that cheaper patch plans still reproduce every carrier byte. */
public final class FilmPatchPlannerTest {
    public static void main(String[] args) {
        Random random = new Random(0x50415443484cL);
        byte[] previous = new byte[128 * 128];
        random.nextBytes(previous);

        byte[] separated = previous.clone();
        for (int y = 0; y < 128; y += 3) {
            separated[y * 128 + 2]++;
            separated[y * 128 + 105]++;
        }
        verify(previous, separated, 8);

        byte[] scattered = previous.clone();
        for (int i = 0; i < 450; i++) scattered[random.nextInt(scattered.length)]++;
        verify(previous, scattered, 8);

        byte[] dense = previous.clone();
        random.nextBytes(dense);
        verify(previous, dense, 8);

        byte[] corner = previous.clone();
        corner[0]++;
        corner[corner.length - 1]++;
        verify(previous, corner, 2);
        System.out.println("PASS: film patches reproduce each carrier and never cost more than a bounding patch");
    }

    private static void verify(byte[] previous, byte[] current, int limit) {
        int minX = 128, minY = 128, maxX = -1, maxY = -1, changed = 0;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                if (current[y * 128 + x] == previous[y * 128 + x]) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                changed++;
            }
        }
        int width = maxX - minX + 1, height = maxY - minY + 1;
        byte[] box = new byte[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(current, (minY + y) * 128 + minX, box, y * width, width);
        }
        var bounding = new MapTile.DirtyRegion(minX, minY, width, height, box, changed);
        List<MapTile.DirtyRegion> patches = FilmPatchPlanner.plan(current, previous, bounding, limit, 16);
        if (patches.size() > limit) throw new AssertionError("Packet limit exceeded");
        byte[] applied = previous.clone();
        int cost = 0;
        for (MapTile.DirtyRegion patch : patches) {
            cost += patch.getDataSize() + 16;
            for (int y = 0; y < patch.height(); y++) {
                System.arraycopy(patch.data(), y * patch.width(), applied,
                    (patch.y() + y) * 128 + patch.x(), patch.width());
            }
        }
        if (!Arrays.equals(applied, current)) throw new AssertionError("Patch omitted a carrier change");
        if (cost > box.length + 16) throw new AssertionError("Patch plan increased wire cost");
    }
}
