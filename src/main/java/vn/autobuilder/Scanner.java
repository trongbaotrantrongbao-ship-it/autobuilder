package vn.autobuilder;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Quét block ảo của Litematica trong vùng đã chọn, chia nhỏ qua nhiều tick để không giật.
 * Kết quả: danh sách vị trí cần làm (từ dưới lên), tổng vật phẩm cần, và snapshot 3D.
 */
public final class Scanner {

    public enum St { IDLE, RUNNING, DONE }

    public St st = St.IDLE;
    public int version;                    // tăng mỗi lần quét xong
    public List<BlockPos> found = new ArrayList<>();
    public Map<Item, Integer> required = new LinkedHashMap<>();
    public Snapshot snap;
    public List<BlockPos> wrong = new ArrayList<>();   // block sai chỗ (vẽ viền đỏ)
    public int blocked;                    // số vị trí không xử lý được (block sai chỗ...)
    public int[] bounds;

    private List<BlockPos> wFound, wWrong;
    private Map<Item, Integer> wReq;
    private Snapshot wSnap;
    private int wBlocked;
    private int[] b;
    private int x, y, z;
    private long scanned, total;
    private boolean fresh, quick;

    private static final int VOXEL_CAP = 120_000;

    public double progress() { return total <= 0 ? 0 : Math.min(1.0, scanned / (double) total); }

    /** true đúng 1 lần sau khi vừa quét xong. */
    public boolean consumeFresh() { boolean f = fresh; fresh = false; return f; }

    /** Quét lại khi đang xây: bỏ qua dựng xem trước 3D cho nhanh. */
    public void startQuick(MinecraftClient mc) { start(mc); quick = true; }

    public void start(MinecraftClient mc) {
        quick = false;
        b = Region.bounds(mc);
        x = b[0]; y = b[1]; z = b[2];
        wFound = new ArrayList<>();
        wWrong = new ArrayList<>();
        wReq = new LinkedHashMap<>();
        wSnap = new Snapshot();
        wBlocked = 0;
        scanned = 0;
        total = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        st = St.RUNNING;
    }

    public void tick(MinecraftClient mc, WorldSchematic sw, long maxNanos) {
        if (st != St.RUNNING) return;
        final long t0 = System.nanoTime();
        ClientWorld w = mc.world;
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockPos.Mutable m2 = new BlockPos.Mutable();

        for (int i = 0; ; i++) {
            if ((i & 127) == 127 && System.nanoTime() - t0 > maxNanos) return;
            m.set(x, y, z);
            scanned++;
            BlockState s = sw.getBlockState(m);
            if (!s.isAir()) {
                wSnap.total++;
                if (quick) {
                    // không cần snapshot khi quét nhanh
                } else if (wSnap.n < VOXEL_CAP) {
                    boolean exposed = false;
                    boolean top = false;
                    for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                        m2.set(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
                        BlockState nb = sw.getBlockState(m2);
                        if (!nb.isOpaque()) {
                            exposed = true;
                            if (d == net.minecraft.util.math.Direction.UP) top = true;
                        }
                    }
                    if (exposed) {
                        int c = s.getMapColor(sw, m).color;
                        if (c == 0) c = 0x808080;
                        wSnap.add(x, y, z, c, top);
                    }
                } else wSnap.truncated = true;

                if (w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    StateDiff.Act a = StateDiff.diff(w.getBlockState(m), s);
                    switch (a) {
                        case DONE, SKIP -> {}
                        case BLOCKED -> {
                            wBlocked++;
                            wWrong.add(m.toImmutable());
                            wFound.add(m.toImmutable());       // sẽ đào bỏ rồi đặt lại đúng
                            countNeeds(sw, m, s, w.getBlockState(m), StateDiff.Act.PLACE);
                        }
                        default -> {
                            wFound.add(m.toImmutable());
                            countNeeds(sw, m, s, w.getBlockState(m), a);
                        }
                    }
                }
            }
            // tiến vị trí: z -> x -> y (tầng thấp trước)
            if (++z > b[5]) {
                z = b[2];
                if (++x > b[3]) {
                    x = b[0];
                    if (++y > b[4]) { finish(mc); return; }
                }
            }
        }
    }

    private void countNeeds(WorldSchematic sw, BlockPos pos, BlockState s, BlockState worldState, StateDiff.Act a) {
        if (a == StateDiff.Act.WATERLOG) { add(Items.WATER_BUCKET, 1); return; }
        if (a == StateDiff.Act.ADJUST) return;
        ItemStack pick = s.getPickStack(sw, pos, false);
        Item item = pick.isEmpty() ? s.getBlock().asItem() : pick.getItem();
        if (item == Items.AIR) return;
        int n = 1;
        if (s.getBlock() instanceof SlabBlock && s.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) {
            n = (worldState.getBlock() == s.getBlock()) ? 1 : 2;
        }
        add(item, n);
        if (a == StateDiff.Act.PLACE && s.contains(Properties.WATERLOGGED) && s.get(Properties.WATERLOGGED)) {
            add(Items.WATER_BUCKET, 1);
        }
    }

    private void add(Item i, int n) { wReq.merge(i, n, Integer::sum); }

    private void finish(MinecraftClient mc) {
        BlockPos c = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        wFound.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> p.getSquaredDistance(c)));
        if (!quick) { wSnap.finish(); snap = wSnap; }
        found = wFound;
        wrong = wWrong;
        required = wReq;
        blocked = wBlocked;
        bounds = b;
        version++;
        fresh = true;
        st = St.DONE;
    }
}
