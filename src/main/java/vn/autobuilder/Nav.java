package vn.autobuilder;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
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
}
