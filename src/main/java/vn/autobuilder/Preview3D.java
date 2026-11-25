package vn.autobuilder;

import java.util.Arrays;

/** Xem trước 3D bằng bộ vẽ phần mềm (chiếu trực giao + z-buffer), xuất ra danh sách đoạn màu để vẽ bằng fill(). */
public final class Preview3D {
    public float yaw = 0.8f, pitch = 0.65f, zoom = 1f;
    public boolean dirty = true;

    public int n;
    private int[] px = new int[0];
    private float[] zb = new float[0];
    public int[] spanRow = new int[1024], spanX0 = new int[1024], spanX1 = new int[1024], spanCol = new int[1024];
    public int spanN;

    public void render(Snapshot s, int res, int cutY) {
        if (res != n) { n = res; px = new int[n * n]; zb = new float[n * n]; }
        Arrays.fill(px, 0);
        Arrays.fill(zb, Float.MAX_VALUE);
        spanN = 0;
        dirty = false;
        if (s == null || s.n == 0 || n < 8) return;

        float cx = s.sx / 2f, cy = s.sy / 2f, cz = s.sz / 2f;
        float diag = (float) Math.sqrt((double) s.sx * s.sx + (double) s.sy * s.sy + (double) s.sz * s.sz);
        float scale = zoom * n * 0.92f / Math.max(diag, 1f);
        int w = Math.max(1, (int) Math.ceil(scale));
        float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
        float half = n / 2f;

        for (int i = 0; i < s.n; i++) {
            if (s.y[i] > cutY) continue;
            float dx = s.x[i] + 0.5f - cx, dy = s.y[i] + 0.5f - cy, dz = s.z[i] + 0.5f - cz;
            float rx = dx * cosY - dz * sinY;
            float rz = dx * sinY + dz * cosY;
            float ry = dy * cosP + rz * sinP;
            float depth = rz * cosP - dy * sinP;

            int x0 = (int) (half + rx * scale - w / 2f);
            int y0 = (int) (half - ry * scale - w / 2f);

            int c = s.col[i];
            float k = s.top[i] ? 1.0f : 0.74f;
            int r = Math.min(255, (int) (((c >> 16) & 255) * k)) & 0xF8;
            int g = Math.min(255, (int) (((c >> 8) & 255) * k)) & 0xF8;
            int bl = Math.min(255, (int) ((c & 255) * k)) & 0xF8;
            int col = (r << 16) | (g << 8) | bl | 0x010101; // != 0 để phân biệt với ô trống

            for (int yy = Math.max(0, y0); yy < Math.min(n, y0 + w); yy++) {
                int row = yy * n;
                for (int xx = Math.max(0, x0); xx < Math.min(n, x0 + w); xx++) {
                    int idx = row + xx;
                    if (depth < zb[idx]) { zb[idx] = depth; px[idx] = col; }
                }
            }
        }

        for (int r = 0; r < n; r++) {
            int x = 0;
            while (x < n) {
                int c = px[r * n + x];
                if (c == 0) { x++; continue; }
                int start = x;
                while (x < n && px[r * n + x] == c) x++;
                addSpan(r, start, x, c);
            }
        }
    }

    private void addSpan(int row, int x0, int x1, int col) {
        if (spanN == spanRow.length) {
            int c = spanN * 2;
            spanRow = Arrays.copyOf(spanRow, c); spanX0 = Arrays.copyOf(spanX0, c);
            spanX1 = Arrays.copyOf(spanX1, c); spanCol = Arrays.copyOf(spanCol, c);
        }
        spanRow[spanN] = row; spanX0[spanN] = x0; spanX1[spanN] = x1; spanCol[spanN] = col; spanN++;
    }
}
