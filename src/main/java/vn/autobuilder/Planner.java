package vn.autobuilder;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.*;
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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import java.util.List;
import java.util.Set;

/**
 * Lập kế hoạch đặt/bấm block "như người chơi thật":
 *  - chỉ bấm vào điểm nhìn thấy thẳng từ mắt (tia không bị block nào chắn -> không xuyên tường),
 *  - khoảng cách mắt -> điểm bấm <= Config.reach (tối đa 4 block),
 *  - hướng nhìn = đúng hướng từ mắt tới điểm bấm; state sinh ra được mô phỏng bằng chính code vanilla.
 * Nếu từ chỗ đang đứng không được, tìm chỗ đứng khác (đi bộ tới được).
 */
public final class Planner {

    public record Plan(Vec3d eye, BlockPos clicked, Direction face, Vec3d hit, float yaw, float pitch) {
        public String key() { return clicked.asLong() + ":" + face.ordinal(); }
    }

    public record Found(Plan plan, List<BlockPos> path, double cost) {}

    public interface Solver { Plan solve(Vec3d eye, Vec3d feet); }

    private static final double[] G = {0.2, 0.5, 0.8};

    public static Vec3d eyeAt(BlockPos feet) { return new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5); }
    public static Vec3d feetAt(BlockPos feet) { return new Vec3d(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5); }

    // ------------------------------------------------------------------ tìm chỗ đứng

    public static Found find(MinecraftClient mc, WorldSchematic sw, Vec3d focus, Solver s) {
        return find(mc, sw, focus, s, null);
    }

    /** Góc tiếp cận do người dùng chọn: 0 tự động, 1 cao, 2 thấp, 3 phải, 4 trái, 5 chéo, 6 ngẫu nhiên (rand = góc 0..2π). */
    public record Approach(int mode, float refYaw, double rand) {
        public static final String[] NAMES = {"Tự động", "Cao", "Thấp", "Phải", "Trái", "Chéo", "Ngẫu nhiên"};

        /** 0 = đúng ý muốn, lớn = lệch ý muốn (so sánh vị trí mắt với tâm block cần đặt). */
        public double penalty(Vec3d eye, Vec3d t) {
            double dx = eye.x - t.x, dy = eye.y - t.y, dz = eye.z - t.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            double yaw = Math.toRadians(refYaw);
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);     // hướng "phải" khi nhìn theo refYaw
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);      // hướng "trước"
            double hh = Math.max(h, 0.3);
            return switch (mode) {
                case 1 -> -dy / 2.0;                                              // đứng càng cao càng tốt
                case 2 -> dy / 2.0;                                               // đứng càng thấp càng tốt
                case 3 -> -(dx * rx + dz * rz) / hh;                              // đứng bên phải
                case 4 -> (dx * rx + dz * rz) / hh;                               // đứng bên trái
                case 5 -> h < 0.3 ? 1.0 : Math.abs(Math.abs(dx * rx + dz * rz) - Math.abs(dx * fx + dz * fz)) / h; // chéo 45°
                case 6 -> 1.0 - (dx * Math.cos(rand) + dz * Math.sin(rand)) / hh; // hướng ngẫu nhiên
                default -> 0;
            };
        }
    }

    private static final double APPROACH_W = 4.0;

    /** Các ô giàn giáo hiện có (Builder cập nhật) – block cần bám không được dựa vào giàn giáo vì sẽ rơi khi phá giàn. */
    public static Set<BlockPos> scaffoldCells = Set.of();

    /** Tìm chỗ đứng. Nếu ap != null và mode != 0: chọn chỗ đứng hợp góc muốn (cân bằng với quãng đường đi). */
    public static Found find(MinecraftClient mc, WorldSchematic sw, Vec3d focus, Solver s, Approach ap) {
        ClientPlayerEntity p = mc.player;
        boolean pick = ap != null && ap.mode() != 0;
        Plan cur = s.solve(p.getEyePos(), Util.pos(p));
        Found best = null;
        double bestScore = Double.MAX_VALUE;
        if (cur != null) {
            if (!pick) return new Found(cur, List.of(), 0);
            double pen = ap.penalty(p.getEyePos(), focus);
            if (pen <= 0.15) return new Found(cur, List.of(), 0);
            best = new Found(cur, List.of(), 0);
            bestScore = pen * APPROACH_W;
        }

        Nav nav = new Nav(mc, sw);
        // pha 1: quanh người chơi; pha 2 (khi mục tiêu ở xa): tìm đường đi bộ tới tận chỗ có block cần đặt
        int[][] phases = {{8, 600, 90}, {48, 9000, 140}};
        for (int[] ph : phases) {
            if (best != null) break;
            List<Nav.Node> nodes = nav.reachable(p.getBlockPos(), ph[0], ph[1]);
            int checked = 0, feasible = 0;
            for (Nav.Node n : nodes) {
                if (n.pos.equals(p.getBlockPos())) continue;
                Vec3d eye = eyeAt(n.pos);
                if (eye.distanceTo(focus) > Config.reach + 1.2) continue;
                if (++checked > ph[2]) break;
                Plan pl = s.solve(eye, feetAt(n.pos));
                if (pl == null) continue;
                if (!pick) return new Found(pl, n.path(), n.cost);
                double sc = n.cost + ap.penalty(eye, focus) * APPROACH_W;
                if (sc < bestScore) { bestScore = sc; best = new Found(pl, n.path(), n.cost); }
                if (++feasible >= 30) break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ đặt block

    public static Plan solvePlace(MinecraftClient mc, Vec3d eye, Vec3d feet, BlockPos target, BlockState want,
                                  Item item, Set<BlockPos> extra, Set<String> banned) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        if (hitsPlayer(w, target, want, feet)) return null;

        WorldSchematic sws = SchematicWorldHandler.getSchematicWorld();
        Block wantBlock = want.getBlock();
        Block simBlock = item instanceof BlockItem bi ? bi.getBlock() : wantBlock;
        ItemStack stack = new ItemStack(item);

        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        float oldYaw = p.getYaw(), oldPitch = p.getPitch();
        try {
            for (Direction d : Direction.values()) {
                BlockPos x = target.offset(d);
                boolean hyp = extra.contains(x);
                BlockState xs = w.getBlockState(x);
                if (!hyp && (xs.isReplaceable() || xs.getCollisionShape(w, x).isEmpty() || isInteractive(xs))) continue;
                Direction face = d.getOpposite();
                if (banned.contains(key(x, face))) continue;

                for (double a : G) for (double b : G) {
                    Vec3d pt = facePoint(x, face, a, b);
                    double d0 = eye.distanceTo(pt);
                    if (d0 > Config.reach || !outer(eye, pt, face)) continue;

                    Vec3d hit;
                    BlockHitResult r = ray(w, p, eye, pt);
                    if (hyp) {
                        if (r != null && r.getPos().distanceTo(eye) < d0 - 0.03) continue; // có gì chắn trước điểm bấm
                        hit = pt;
                    } else {
                        if (r == null || !r.getBlockPos().equals(x) || r.getSide() != face) continue; // phải thấy thẳng mặt này
                        hit = r.getPos();
                    }
                    double dh = eye.distanceTo(hit);
                    if (dh > Config.reach) continue;

                    float[] rot = rot(eye, hit);
                    p.setYaw(rot[0]);
                    p.setPitch(rot[1]);
                    ItemPlacementContext ctx = new ItemPlacementContext(p, Hand.MAIN_HAND, stack,
                            new BlockHitResult(hit, face, x, false));
                    if (!ctx.canPlace()) continue;

                    BlockState got = simBlock.getPlacementState(ctx);
                    if ((got == null || got.getBlock() != wantBlock) && simBlock != wantBlock)
                        got = wantBlock.getPlacementState(ctx);

                    double score = dh;
                    if (hyp) {
                        if (got != null && got.getBlock() != wantBlock) continue;
                        // block cần bám (đuốc, nút...) không được dựa vào giàn giáo: phá giàn là rơi
                        if (got != null && sws != null && !got.canPlaceAt(sws, target)) continue;
                    } else {
                        if (got == null || got.getBlock() != wantBlock) continue;
                        if (!got.canPlaceAt(w, target)) continue;
                        if (sws != null && scaffoldCells.contains(x) && !got.canPlaceAt(sws, target)) continue;
                        List<Property<?>> diff = StateDiff.diffProps(got, want);
                        if (!diff.isEmpty()) {
                            if (!StateDiff.softOnly(diff)) continue;
                            score += 0.5;
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Plan(eye, x, face, hit, rot[0], rot[1]);
                    }
                }
            }
        } finally {
            p.setYaw(oldYaw);
            p.setPitch(oldPitch);
        }
        return best;
    }

    // ------------------------------------------------------------------ bấm vào 1 block có sẵn (chỉnh, xô nước, đập)

    public static Plan solveClick(MinecraftClient mc, Vec3d eye, Vec3d feet, BlockPos x, Direction needFace, Set<String> banned) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        Plan best = null;
        double bestD = Double.MAX_VALUE;
        Direction[] faces = needFace != null ? new Direction[]{needFace} : Direction.values();
        for (Direction face : faces) {
            for (double a : G) for (double b : G) {
                Vec3d pt = facePoint(x, face, a, b);
                if (eye.distanceTo(pt) > Config.reach || !outer(eye, pt, face)) continue;
                BlockHitResult r = ray(w, p, eye, pt);
                if (r == null || !r.getBlockPos().equals(x)) continue;
                if (needFace != null && r.getSide() != needFace) continue;
                if (banned.contains(key(x, r.getSide()))) continue;
                double dh = eye.distanceTo(r.getPos());
                if (dh > Config.reach || dh >= bestD) continue;
                float[] rot = rot(eye, r.getPos());
                bestD = dh;
                best = new Plan(eye, x, r.getSide(), r.getPos(), rot[0], rot[1]);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ tiện ích

    /** Kiểm tra điểm bấm vẫn nhìn thấy thẳng từ mắt hiện tại (không xuyên tường, trong tầm). */
    public static boolean stillValid(MinecraftClient mc, Plan pl) {
        Vec3d eye = mc.player.getEyePos();
        if (eye.distanceTo(pl.hit()) > Config.reach) return false;
        BlockHitResult r = ray(mc.world, mc.player, eye, pl.hit());
        return r != null && r.getBlockPos().equals(pl.clicked());
    }

    public static String key(BlockPos x, Direction f) { return x.asLong() + ":" + f.ordinal(); }

    public static float[] rot(Vec3d eye, Vec3d to) {
        Vec3d d = to.subtract(eye);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 57.2957795 - 90.0);
        float pitch = (float) -(MathHelper.atan2(d.y, flat) * 57.2957795);
        return new float[]{yaw, pitch};
    }

    private static BlockHitResult ray(ClientWorld w, ClientPlayerEntity p, Vec3d eye, Vec3d pt) {
        Vec3d dir = pt.subtract(eye);
        double len = dir.length();
        if (len < 1e-4) return null;
        Vec3d end = pt.add(dir.multiply(0.1 / len));
        BlockHitResult r = w.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));
        return r.getType() == HitResult.Type.BLOCK ? r : null;
    }

    private static boolean outer(Vec3d eye, Vec3d pt, Direction face) {
        Vec3d n = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
        return eye.subtract(pt).dotProduct(n) > 0.01;
    }

    private static Vec3d facePoint(BlockPos x, Direction face, double a, double b) {
        double bx = x.getX(), by = x.getY(), bz = x.getZ();
        return switch (face.getAxis()) {
            case Y -> new Vec3d(bx + a, by + (face == Direction.UP ? 1 : 0), bz + b);
            case X -> new Vec3d(bx + (face == Direction.EAST ? 1 : 0), by + a, bz + b);
            default -> new Vec3d(bx + a, by + b, bz + (face == Direction.SOUTH ? 1 : 0));
        };
    }

    private static boolean hitsPlayer(ClientWorld w, BlockPos target, BlockState want, Vec3d feet) {
        Box pb = new Box(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
        for (Box b : want.getCollisionShape(w, target).getBoundingBoxes()) {
            if (b.offset(target).intersects(pb)) return true;
        }
        return false;
    }

    /** Block bấm vào sẽ mở giao diện/đổi trạng thái -> không dùng làm điểm tựa (tránh phải ngồi xổm). */
    public static boolean isInteractive(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof BedBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof AbstractRedstoneGateBlock
                || b instanceof NoteBlock || b instanceof DaylightDetectorBlock || b instanceof CakeBlock;
    }

    private Planner() {}
}
