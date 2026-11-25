package vn.autobuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Tìm cách đặt block để ra ĐÚNG trạng thái của block ảo (hướng, nửa trên/dưới, trục...).
 * Cách làm: thử mọi tổ hợp (mặt bấm x độ cao bấm x hướng nhìn) và mô phỏng
 * Block#getPlacementState giống hệt vanilla, chọn tổ hợp cho ra state khớp.
 */
public final class PlacementSolver {

    public record Plan(BlockPos support, Direction face, Vec3d hit, float yaw, float pitch, boolean exact) {}

    private static final float[] YAWS = {0f, 90f, 180f, 270f};
    private static final float[] PITCHES = {0f, 90f, -90f};

    public static Plan solve(MinecraftClient mc, BlockPos target, BlockState want, Item item) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) return null;

        Vec3d eye = p.getEyePos();
        Block wantBlock = want.getBlock();
        Block simBlock = item instanceof BlockItem bi ? bi.getBlock() : wantBlock;
        ItemStack stack = new ItemStack(item);

        Plan best = null;
        int bestScore = -1;
        float oldYaw = p.getYaw();
        float oldPitch = p.getPitch();

        try {
            for (Direction d : Direction.values()) {
                BlockPos n = target.offset(d);
                BlockState ns = w.getBlockState(n);
                if (ns.isReplaceable() || ns.getCollisionShape(w, n).isEmpty()) continue;

                Direction face = d.getOpposite();
                boolean horizontal = face.getAxis().isHorizontal();
                double[] heights = horizontal ? new double[]{0.25, 0.75} : new double[]{0.5};

                for (double hy : heights) {
                    Vec3d c = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                    Vec3d hit = horizontal ? new Vec3d(c.x, n.getY() + hy, c.z) : c;
                    if (eye.distanceTo(hit) > Config.reach) continue;

                    BlockHitResult bhr = new BlockHitResult(hit, face, n, false);
                    for (float yaw : YAWS) {
                        for (float pitch : PITCHES) {
                            p.setYaw(yaw);
                            p.setPitch(pitch);
                            ItemPlacementContext ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, stack, bhr);
                            if (!ctx.canPlace()) continue;

                            BlockState r = simBlock.getPlacementState(ctx);
                            if ((r == null || r.getBlock() != wantBlock) && simBlock != wantBlock) {
                                r = wantBlock.getPlacementState(ctx); // vd: đuốc tường
                            }
                            if (r == null || r.getBlock() != wantBlock) continue;
                            if (!r.canPlaceAt(w, target)) continue;

                            int score = score(r, want);
                            boolean exact = StateDiff.diffProps(r, want).isEmpty();
                            if (exact) {
                                return new Plan(n, face, hit, yaw, pitch, true);
                            }
                            if (score > bestScore) {
                                bestScore = score;
                                best = new Plan(n, face, hit, yaw, pitch, false);
                            }
                        }
                    }
                }
            }
        } finally {
            p.setYaw(oldYaw);
            p.setPitch(oldPitch);
        }
        return best;
    }

    /** Có điểm tựa (block cứng kề bên) trong tầm với không? */
    public static boolean hasSupport(MinecraftClient mc, BlockPos pos) {
        ClientWorld w = mc.world;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = w.getBlockState(n);
            if (!ns.isReplaceable() && !ns.getCollisionShape(w, n).isEmpty()) return true;
        }
        return false;
    }

    private static int score(BlockState r, BlockState want) {
        int s = 0;
        for (Property<?> p : want.getProperties()) {
            if (StateDiff.isIgnored(p, want.getBlock())) continue;
            if (r.contains(p) && r.get(p).equals(want.get(p))) s++;
        }
        return s;
    }

    private PlacementSolver() {}
}
