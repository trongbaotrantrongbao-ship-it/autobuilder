package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/** Đi theo đường BFS từng điểm một: quay đầu mượt, nhảy khi cần, phát hiện kẹt. */
public final class Mover {
    private List<BlockPos> path;
    private int idx;
    private boolean arrived, failed;
    private boolean pressedFwd, pressedJump;
    private int stuckTicks;
    private double lastX, lastZ;

    public boolean busy() { return path != null && !arrived && !failed; }
    public boolean arrived() { return arrived; }
    public boolean failed() { return failed; }

    public void follow(List<BlockPos> p) {
        path = p;
        idx = 0;
        arrived = p == null || p.isEmpty();
        failed = false;
        stuckTicks = 0;
    }

    public void stop(MinecraftClient mc) {
        path = null;
        arrived = failed = false;
        releaseKeys(mc);
    }

    public void holdJump(MinecraftClient mc, boolean on) {
        if (mc.options == null) return;
        mc.options.jumpKey.setPressed(on);
        pressedJump = on;
    }

    private void releaseKeys(MinecraftClient mc) {
        if (mc.options == null) return;
        if (pressedFwd) { mc.options.forwardKey.setPressed(false); pressedFwd = false; }
        if (pressedJump) { mc.options.jumpKey.setPressed(false); pressedJump = false; }
    }

    public void tick(MinecraftClient mc) {
        if (!busy() || mc.player == null) return;
        ClientPlayerEntity p = mc.player;
        BlockPos wp = path.get(idx);
        boolean last = idx == path.size() - 1;

        double cx = wp.getX() + 0.5, cz = wp.getZ() + 0.5;
        double dx = cx - p.getX(), dz = cz - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        double tol = last ? 0.25 : 0.5;

        if (hd <= tol && Math.abs(p.getY() - wp.getY()) < 1.0) {
            idx++;
            if (idx >= path.size()) { arrived = true; releaseKeys(mc); return; }
            return;
        }

        // kẹt: gần như không dịch chuyển trong 2 giây
        if (++stuckTicks % 40 == 0) {
            if (Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 0.15) { failed = true; releaseKeys(mc); return; }
            lastX = p.getX(); lastZ = p.getZ();
        }
        if (stuckTicks == 1) { lastX = p.getX(); lastZ = p.getZ(); }

        float want = (float) (MathHelper.atan2(dz, dx) * 57.2957795 - 90.0);
        float diff = MathHelper.wrapDegrees(want - p.getYaw());
        p.setYaw(p.getYaw() + MathHelper.clamp(diff, -40f, 40f));

        boolean fwd = Math.abs(diff) < 60 && !(last && hd < 0.45);
        boolean jump = p.isOnGround() && (wp.getY() > p.getBlockPos().getY() || p.horizontalCollision);
        mc.options.forwardKey.setPressed(fwd); pressedFwd = fwd;
        mc.options.jumpKey.setPressed(jump);   pressedJump = jump;
    }
}
