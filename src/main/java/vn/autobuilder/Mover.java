package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Tự đi tới gần block cần đặt: quay đầu, đi thẳng, nhảy khi vướng, dừng khi phía trước nguy hiểm. */
public final class Mover {
    private BlockPos goal;
    private boolean active;
    private boolean pressedFwd, pressedJump, pressedSneak;

    public boolean isActive() { return active; }

    public void moveTo(BlockPos g) { goal = g; active = true; }

    public void release(MinecraftClient mc) {
        active = false;
        goal = null;
        if (mc.options == null) return;
        if (pressedFwd) { mc.options.forwardKey.setPressed(false); pressedFwd = false; }
        if (pressedJump) { mc.options.jumpKey.setPressed(false); pressedJump = false; }
        if (pressedSneak) { mc.options.sneakKey.setPressed(false); pressedSneak = false; }
    }

    public void tick(MinecraftClient mc) {
        if (!active || goal == null || mc.player == null) return;
        ClientPlayerEntity p = mc.player;
        Vec3d gc = Vec3d.ofCenter(goal);

        if (p.getEyePos().distanceTo(gc) <= Config.reach - 0.7) { release(mc); return; }

        double dx = gc.x - p.getX(), dz = gc.z - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        float want = (float) (MathHelper.atan2(dz, dx) * 57.2957795 - 90.0);
        float diff = MathHelper.wrapDegrees(want - p.getYaw());
        p.setYaw(p.getYaw() + MathHelper.clamp(diff, -35f, 35f)); // quay đầu mượt

        boolean flying = p.getAbilities().flying;
        boolean fwd = hd > 0.9 && Math.abs(diff) < 50 && (flying || safeAhead(mc, p));
        boolean jump = false, sneak = false;

        if (flying) {
            double dy = gc.y - p.getEyeY();
            jump = dy > 1.2;
            sneak = dy < -2.5;
        } else if (p.horizontalCollision && p.isOnGround()) {
            jump = true;
        }
        if (!fwd && hd <= 0.9 && !flying) {
            Util.status(mc, "Mục tiêu quá cao/thấp so với chỗ đứng – hãy tự di chuyển lại.");
            release(mc);
            return;
        }
        set(mc, fwd, jump, sneak);
    }

    private void set(MinecraftClient mc, boolean fwd, boolean jump, boolean sneak) {
        mc.options.forwardKey.setPressed(fwd);     pressedFwd = fwd;
        mc.options.jumpKey.setPressed(jump);       pressedJump = jump;
        mc.options.sneakKey.setPressed(sneak);     pressedSneak = sneak;
    }

    private boolean safeAhead(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d dir = Vec3d.fromPolar(0f, p.getYaw());
        BlockPos ahead = BlockPos.ofFloored(p.getX() + dir.x, p.getY(), p.getZ() + dir.z);
        var w = mc.world;
        if (w.getFluidState(ahead).isIn(FluidTags.LAVA)) return false;
        for (int d = 1; d <= 3; d++) {
            BlockPos q = ahead.down(d);
            if (w.getFluidState(q).isIn(FluidTags.LAVA)) return false;
            if (!w.getBlockState(q).isAir()) return true; // có nền (hoặc nước)
        }
        return false; // rơi quá 3 block
    }
}
