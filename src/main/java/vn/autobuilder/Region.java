package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Vùng quét: tâm = người chơi (hoặc điểm đã khóa). */
public final class Region {

    /** {minX, minY, minZ, maxX, maxY, maxZ} (bao gồm). */
    public static int[] bounds(MinecraftClient mc) {
        int cx, cy, cz;
        if (Config.lockAnchor) { cx = Config.anchorX; cy = Config.anchorY; cz = Config.anchorZ; }
        else {
            BlockPos p = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
            cx = p.getX(); cy = p.getY(); cz = p.getZ();
        }
        int r = Config.scanRadius;
        int bottom = mc.world != null ? mc.world.getBottomY() : -64;
        int top = mc.world != null ? mc.world.getBottomY() + mc.world.getHeight() - 1 : 319;
        return new int[]{cx - r, Math.max(bottom, cy - Config.scanDown), cz - r,
                cx + r, Math.min(top, cy + Config.scanUp), cz + r};
    }

    public static void lockHere(MinecraftClient mc) {
        BlockPos p = mc.player.getBlockPos();
        Config.anchorX = p.getX(); Config.anchorY = p.getY(); Config.anchorZ = p.getZ();
        Config.lockAnchor = true;
    }

    private Region() {}
}
