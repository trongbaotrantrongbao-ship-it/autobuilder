package vn.autobuilder;

import java.util.Arrays;

/** Các voxel bề mặt của schematic trong vùng quét, dùng để vẽ xem trước 3D. */
public final class Snapshot {
    public int n;
    public int[] x = new int[4096], y = new int[4096], z = new int[4096], col = new int[4096];
    public boolean[] top = new boolean[4096];
    public int sx, sy, sz;         // kích thước hộp bao
    public int total;              // tổng số block (kể cả bên trong)
    public boolean truncated;

    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    public void add(int wx, int wy, int wz, int color, boolean isTop) {
        if (n == x.length) {
            int c = n * 2;
            x = Arrays.copyOf(x, c); y = Arrays.copyOf(y, c); z = Arrays.copyOf(z, c);
            col = Arrays.copyOf(col, c); top = Arrays.copyOf(top, c);
        }
        x[n] = wx; y[n] = wy; z[n] = wz; col[n] = color; top[n] = isTop; n++;
        minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
        maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
    }

    /** Dời toạ độ về gốc của hộp bao. */
    public void finish() {
        if (n == 0) { sx = sy = sz = 0; return; }
        for (int i = 0; i < n; i++) { x[i] -= minX; y[i] -= minY; z[i] -= minZ; }
        sx = maxX - minX + 1; sy = maxY - minY + 1; sz = maxZ - minZ + 1;
    }
}
