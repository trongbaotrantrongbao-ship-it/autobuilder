package vn.autobuilder;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/** Tìm đường đi bộ (BFS) tới các chỗ đứng quanh người chơi. */
public final class Nav {

    public static final class Node {
        public final BlockPos pos;
        public final Node parent;
        public final int cost;
        Node(BlockPos pos, Node parent, int cost) { this.pos = pos; this.parent = parent; this.cost = cost; }

        /** Đường đi từ vị trí xuất phát (không gồm vị trí xuất phát) tới node này. */
        public List<BlockPos> path() {
            LinkedList<BlockPos> l = new LinkedList<>();
            for (Node n = this; n != null && n.parent != null; n = n.parent) l.addFirst(n.pos);
            return l;
        }
    }

    private final ClientWorld w;
    private final WorldSchematic sw;

    public Nav(MinecraftClient mc, WorldSchematic sw) { this.w = mc.world; this.sw = sw; }

    private static boolean danger(BlockState s) {
        return s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE)
                || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.WITHER_ROSE)
                || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS);
    }

    public boolean passable(BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (!s.getCollisionShape(w, p).isEmpty()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        return !danger(s);
    }

    private boolean floorOk(BlockPos p) {
        BlockState s = w.getBlockState(p);
        var shape = s.getCollisionShape(w, p);
        if (shape.isEmpty() || danger(s) || s.getFluidState().isIn(FluidTags.LAVA)) return false;
        return shape.getMax(Direction.Axis.Y) >= 0.999; // chỉ đứng trên block cao đủ 1.0 (để mắt đúng độ cao)
    }

    private boolean freeOfSchem(BlockPos p) { return sw == null || sw.getBlockState(p).isAir(); }

    public boolean standable(BlockPos p) {
        return passable(p) && passable(p.up()) && floorOk(p.down()) && freeOfSchem(p) && freeOfSchem(p.up());
    }

    /** Các chỗ đứng đi tới được, theo thứ tự gần -> xa. */
    public List<Node> reachable(BlockPos start, int radius, int maxNodes) {
        List<Node> out = new ArrayList<>();
        Map<BlockPos, Node> seen = new HashMap<>();
        ArrayDeque<Node> q = new ArrayDeque<>();
        Node root = new Node(start, null, 0);
        seen.put(start, root);
        q.add(root);
        out.add(root);

        while (!q.isEmpty() && out.size() < maxNodes) {
            Node cur = q.poll();
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos n = cur.pos.offset(d);
                if (Math.abs(n.getX() - start.getX()) > radius || Math.abs(n.getZ() - start.getZ()) > radius) continue;
                BlockPos target = null;

                if (standable(n)) target = n;
                else if (passable(cur.pos.up(2)) && standable(n.up())) target = n.up();          // bước lên 1 (nhảy)
                else if (passable(n) && passable(n.up())) {                                         // bước xuống tối đa 3
                    for (int k = 1; k <= 3; k++) {
                        BlockPos m = n.down(k);
                        if (!passable(m)) break;
                        if (standable(m)) { target = m; break; }
                    }
                }
                if (target == null || Math.abs(target.getY() - start.getY()) > 8) continue;
                if (seen.containsKey(target)) continue;
                Node nn = new Node(target, cur, cur.cost + 1);
                seen.put(target, nn);
                q.add(nn);
                out.add(nn);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ thoát khi bị kẹt (có thể phải đào)

    /** Một bước thoát: đứng ở pos sau khi đào các ô trong dig; down = đào ô dưới chân rồi rơi xuống. */
    public static final class RStep {
        public final BlockPos pos;
        public final List<BlockPos> dig;
        public final boolean down;
        RStep(BlockPos pos, List<BlockPos> dig, boolean down) { this.pos = pos; this.dig = dig; this.down = down; }
    }

    private static final class EN {
        final BlockPos pos; final EN parent; final double cost; final List<BlockPos> dig; final boolean down;
        EN(BlockPos pos, EN parent, double cost, List<BlockPos> dig, boolean down) {
            this.pos = pos; this.parent = parent; this.cost = cost; this.dig = dig; this.down = down;
        }
    }

    /** Đá đỏ / cơ cấu: tuyệt đối không phá. */
    static boolean redstoneish(BlockState s) {
        String id = Registries.BLOCK.getId(s.getBlock()).getPath();
        for (String k : new String[]{"redstone", "repeater", "comparator", "piston", "observer", "lever", "button", "pressure_plate",
                "tripwire", "rail", "dispenser", "dropper", "hopper", "target", "note_block", "daylight", "sculk", "lectern",
                "command_block", "bell", "lightning_rod", "crafter", "door", "trapdoor", "fence_gate", "bed", "chest", "barrel",
                "furnace", "shulker", "sign", "banner", "head", "skull", "spawner", "beacon", "portal", "frame"}) {
            if (id.contains(k)) return true;
        }
        return s.emitsRedstonePower();
    }

    public boolean breakable(BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.getHardness(w, p) < 0) return false;
        if (s.hasBlockEntity()) return false;
        return !redstoneish(s);
    }

    /** Chi phí đào 1 ô: giàn giáo rẻ nhất, đất đá ngoài công trình vừa, block thuộc công trình đắt (đào xong sẽ được đặt lại). */
    private double digCost(BlockPos p, Set<BlockPos> scaffolds) {
        if (scaffolds.contains(p)) return 2;
        if (sw == null || sw.getBlockState(p).isAir()) return 4;
        return 12;
    }

    private boolean openArea(BlockPos p) { return reachable(p, 3, 12).size() >= 8; }

    /**
     * Tìm đường thoát khi bị kẹt (đứng trên cột giàn giáo, bị bao quanh...). Dijkstra cho phép đào các ô "đào được"
     * (không bao giờ đá đỏ/cơ cấu/rương). Trả null nếu không kẹt hoặc không có đường.
     */
    public List<RStep> escape(BlockPos start, Set<BlockPos> scaffolds, int maxNodes) {
        Set<BlockPos> own = new HashSet<>();
        for (Node n : reachable(start, 12, 150)) own.add(n.pos);
        if (own.size() >= 150) return null;                       // đang ở chỗ thoáng, không kẹt

        PriorityQueue<EN> pq = new PriorityQueue<>(Comparator.comparingDouble(e -> e.cost));
        Map<BlockPos, Double> best = new HashMap<>();
        EN root = new EN(start, null, 0, List.of(), false);
        pq.add(root);
        best.put(start, 0.0);
        int expanded = 0;

        while (!pq.isEmpty() && expanded < maxNodes) {
            EN cur = pq.poll();
            if (cur.cost > best.getOrDefault(cur.pos, 1e18)) continue;
            expanded++;

            if (cur.parent != null && !own.contains(cur.pos) && standable(cur.pos) && openArea(cur.pos)) {
                LinkedList<RStep> out = new LinkedList<>();
                for (EN e = cur; e != null && e.parent != null; e = e.parent) out.addFirst(new RStep(e.pos, e.dig, e.down));
                return out;
            }

            // đào xuống 1 ô (đứng trên giàn giáo/cột)
            BlockPos below = cur.pos.down();
            if (!passable(below) && breakable(below) && floorOk(below.down())) {
                relax(pq, best, cur, below, List.of(below), digCost(below, scaffolds) + 2, true);
            }

            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos n = cur.pos.offset(d);
                if (Math.abs(n.getX() - start.getX()) > 24 || Math.abs(n.getZ() - start.getZ()) > 24) continue;
                // đi ngang
                edge(pq, best, cur, n, scaffolds, floorOk(n.down()), new BlockPos[]{n, n.up()});
                // bước lên 1
                BlockPos up = n.up();
                edge(pq, best, cur, up, scaffolds, floorOk(up.down()), new BlockPos[]{cur.pos.up(2), up, up.up()});
                // bước xuống 1..3
                for (int k = 1; k <= 3; k++) {
                    BlockPos dn = n.down(k);
                    if (!floorOk(dn.down())) continue;
                    BlockPos[] cells = new BlockPos[k + 2];
                    for (int j = 0; j <= k + 1; j++) cells[j] = n.up(1 - j);
                    edge(pq, best, cur, dn, scaffolds, true, cells);
                    break;
                }
            }
        }
        return null;
    }

    private void edge(PriorityQueue<EN> pq, Map<BlockPos, Double> best, EN cur, BlockPos target, Set<BlockPos> scaffolds,
                      boolean floor, BlockPos[] cells) {
        if (!floor) return;
        List<BlockPos> dig = new ArrayList<>();
        double cost = 1;
        for (BlockPos c : cells) {
            if (passable(c)) continue;
            if (!breakable(c)) return;
            dig.add(c);
            cost += digCost(c, scaffolds);
        }
        relax(pq, best, cur, target, dig, cost, false);
    }

    private void relax(PriorityQueue<EN> pq, Map<BlockPos, Double> best, EN cur, BlockPos target, List<BlockPos> dig,
                       double cost, boolean down) {
        double nc = cur.cost + cost;
        if (nc >= best.getOrDefault(target, 1e18)) return;
        best.put(target, nc);
        pq.add(new EN(target, cur, nc, dig, down));
    }
}
