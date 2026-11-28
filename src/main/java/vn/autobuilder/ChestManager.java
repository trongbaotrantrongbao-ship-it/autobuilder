package vn.autobuilder;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Kho rương: quét các rương (rương đơn/đôi, thùng) quanh bạn, nhớ tọa độ + đồ bên trong + rương nào đã hết,
 * rồi tự đi tới, chuột phải mở rương và lấy đồ khi cần xây.
 */
public final class ChestManager {

    public static final class Memo {
        public final String dim;
        public final BlockPos pos;            // vị trí đại diện (với rương đôi: nửa có tọa độ nhỏ hơn)
        public BlockPos other;                // nửa còn lại của rương đôi (hoặc null)
        public final Map<Item, Integer> items = new LinkedHashMap<>();
        public boolean scanned, blocked;
        int fails;

        Memo(String dim, BlockPos pos) { this.dim = dim; this.pos = pos; }
        String key() { return key(dim, pos); }
        public boolean emptied() { return scanned && items.isEmpty(); }
    }

    private enum Mode { NONE, SCAN, FETCH }
    private enum Phase { PICK, WALK, AIM, OPEN, READ, TAKE, CLOSE }

    private static String key(String dim, BlockPos p) { return dim + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ(); }

    private final Map<String, Memo> memos = new LinkedHashMap<>();
    private final Set<String> visited = new HashSet<>();
    private final Mover mover = new Mover();

    private Mode mode = Mode.NONE;
    private Phase phase = Phase.PICK;
    private Item item;
    private int goal, startCount, timer, replans, settle;
    private Memo cur;
    private int lastTook;
    private String summary = "";
    private boolean loaded;

    public boolean busy() { return mode != Mode.NONE; }
    public boolean scanning() { return mode == Mode.SCAN; }
    public int lastTook() { return lastTook; }
    public String summary() { return summary; }

    // ------------------------------------------------------------------ truy vấn

    private static String dim(MinecraftClient mc) { return mc.world.getRegistryKey().getValue().toString(); }

    private List<Memo> here(MinecraftClient mc) {
        List<Memo> out = new ArrayList<>();
        if (mc.world == null) return out;
        String d = dim(mc);
        for (Memo m : memos.values()) if (m.dim.equals(d)) out.add(m);
        return out;
    }

    public int total(MinecraftClient mc, Item it) {
        int n = 0;
        for (Memo m : here(mc)) if (!m.blocked) n += m.items.getOrDefault(it, 0);
        return n;
    }

    public boolean hasStock(MinecraftClient mc, Item it) { return total(mc, it) > 0; }

    /** {tổng số rương, đã quét, đã hết đồ, không mở được} */
    public int[] counts(MinecraftClient mc) {
        int all = 0, sc = 0, empty = 0, bl = 0;
        for (Memo m : here(mc)) {
            all++;
            if (m.scanned) sc++;
            if (m.emptied()) empty++;
            if (m.blocked) bl++;
        }
        return new int[]{all, sc, empty, bl};
    }

    /** Tổng đồ nhớ được trong các rương, nhiều nhất trước. */
    public List<Map.Entry<Item, Integer>> topItems(MinecraftClient mc, int limit) {
        Map<Item, Integer> sum = new HashMap<>();
        for (Memo m : here(mc)) if (!m.blocked) for (var e : m.items.entrySet()) sum.merge(e.getKey(), e.getValue(), Integer::sum);
        List<Map.Entry<Item, Integer>> l = new ArrayList<>(sum.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return l.size() > limit ? l.subList(0, limit) : l;
    }

    public void clear(MinecraftClient mc) {
        cancel(mc);
        memos.clear();
        summary = "";
        save();
    }

    // ------------------------------------------------------------------ lập chỉ mục rương

    private static boolean isStorage(BlockState s) {
        return s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock;
    }

    /** Tìm mọi rương/thùng đã tải trong bán kính; trả về số rương mới. */
    public int index(MinecraftClient mc) {
        load();
        if (mc.player == null || mc.world == null) return 0;
        String d = dim(mc);
        BlockPos pp = mc.player.getBlockPos();
        int r = Math.max(8, Config.chestRadius);
        int added = 0;
        for (int cx = (pp.getX() - r) >> 4; cx <= (pp.getX() + r) >> 4; cx++) {
            for (int cz = (pp.getZ() - r) >> 4; cz <= (pp.getZ() + r) >> 4; cz++) {
                Chunk ch = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (ch == null) continue;
                for (BlockPos bp : new ArrayList<>(ch.getBlockEntityPositions())) {
                    if (Math.abs(bp.getX() - pp.getX()) > r || Math.abs(bp.getZ() - pp.getZ()) > r
                            || Math.abs(bp.getY() - pp.getY()) > r) continue;
                    BlockState s = mc.world.getBlockState(bp);
                    if (!isStorage(s)) continue;
                    BlockPos canon = bp, other = null;
                    if (s.getBlock() instanceof ChestBlock && s.contains(ChestBlock.CHEST_TYPE)
                            && s.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                        Direction f = s.get(ChestBlock.FACING);
                        Direction toOther = s.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT
                                ? f.rotateYClockwise() : f.rotateYCounterclockwise();
                        other = bp.offset(toOther);
                        if (other.asLong() < bp.asLong()) { canon = other; other = bp; }
                    }
                    String k = key(d, canon);
                    if (memos.containsKey(k)) continue;
                    Memo m = new Memo(d, canon);
                    m.other = other;
                    memos.put(k, m);
                    added++;
                }
            }
        }
        return added;
    }

    // ------------------------------------------------------------------ điều khiển

    /** Quét tất cả rương: đi tới từng rương, mở, ghi nhớ đồ bên trong. */
    public void startScan(MinecraftClient mc) {
        if (busy()) return;
        int added = index(mc);
        for (Memo m : here(mc)) { m.blocked = false; m.fails = 0; }
        mode = Mode.SCAN;
        beginRun();
        Util.chat(mc, "Quét rương: tìm thấy " + here(mc).size() + " rương (" + added + " mới). Bắt đầu đi mở từng rương...");
    }

    /** Lấy tối thiểu `goal` cái item từ các rương đã nhớ. */
    public void startFetch(MinecraftClient mc, Item it, int goalCount) {
        if (busy()) return;
        load();
        mode = Mode.FETCH;
        item = it;
        goal = Math.max(1, goalCount);
        startCount = Util.count(mc.player, it);
        lastTook = 0;
        beginRun();
        Util.chat(mc, "Lấy §e" + Util.englishName(it) + "§r từ rương (nhớ có " + total(mc, it) + ").");
    }

    private void beginRun() {
        visited.clear();
        phase = Phase.PICK;
        timer = 0; replans = 0; settle = 0;
        cur = null;
    }

    public void cancel(MinecraftClient mc) {
        if (mode == Mode.NONE) return;
        mode = Mode.NONE;
        mover.stop(mc);
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        save();
    }

    private void finish(MinecraftClient mc) {
        Mode m = mode;
        mode = Mode.NONE;
        mover.stop(mc);
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        if (m == Mode.FETCH) {
            lastTook = Math.max(0, Util.count(mc.player, item) - startCount);
            Util.chat(mc, "Lấy từ rương xong: +" + lastTook + " " + Util.englishName(item) + ".");
        } else {
            int[] c = counts(mc);
            summary = "Đã quét " + c[1] + "/" + c[0] + " rương, " + c[2] + " rương đã hết đồ, " + c[3] + " không mở được.";
            Util.chat(mc, summary);
        }
        save();
    }

    // ------------------------------------------------------------------ máy trạng thái

    public void tick(MinecraftClient mc, WorldSchematic sw) {
        if (mode == Mode.NONE || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GenericContainerScreen)) return;   // menu khác đang mở: tạm dừng
        ClientPlayerEntity p = mc.player;
        timer++;

        switch (phase) {
            case PICK -> {
                if (mode == Mode.FETCH && Util.count(p, item) - startCount >= goal) { finish(mc); return; }
                if (timer < 3) return;
                cur = next(mc);
                if (cur == null) { finish(mc); return; }
                replans = 0;
                goTo(mc, sw);
            }
            case WALK -> {
                mover.tick(mc);
                if (mover.failed()) {
                    mover.stop(mc);
                    if (++replans > 3) skip(mc); else goTo(mc, sw);
                } else if (mover.arrived()) {
                    mover.stop(mc);
                    phase = Phase.AIM; timer = 0; settle = 0;
                } else if (timer > 20 * 40) { mover.stop(mc); skip(mc); }
            }
            case AIM -> {
                Planner.Plan pl = Planner.solveClick(mc, p.getEyePos(), Util.pos(p), cur.pos, null, Set.of());
                if (pl == null) {
                    if (++replans > 3) skip(mc); else goTo(mc, sw);
                    return;
                }
                Util.look(mc, pl.yaw(), pl.pitch());
                if (++settle <= Math.max(1, Config.aimSettleTicks)) return;     // chờ đầu xoay xong rồi mới mở
                Util.useBlock(mc, new BlockHitResult(pl.hit(), pl.face(), pl.clicked(), false), false);
                phase = Phase.OPEN; timer = 0;
            }
            case OPEN -> {
                if (mc.currentScreen instanceof GenericContainerScreen) { phase = Phase.READ; timer = 0; return; }
                if (timer > 30) {                                  // không mở được (bị chặn phía trên, rương khóa...)
                    if (++replans > 2) { cur.fails++; skip(mc); }
                    else { phase = Phase.AIM; timer = 0; settle = 0; }
                }
            }
            case READ -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) { phase = Phase.OPEN; timer = 0; return; }
                if (timer < 3 + Config.chestDelayTicks) return;     // chờ server gửi đủ nội dung rương
                readInto(cur, gcs.getScreenHandler());
                phase = (mode == Mode.FETCH && cur.items.getOrDefault(item, 0) > 0) ? Phase.TAKE : Phase.CLOSE;
                timer = 0;
            }
            case TAKE -> {
                if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) { phase = Phase.PICK; timer = 0; return; }
                if (timer % Math.max(1, Config.chestDelayTicks) != 0) return;
                int took = Util.count(p, item) - startCount;
                if (took >= goal || Util.freeSpace(p, item) <= 0) { phase = Phase.CLOSE; timer = 0; return; }
                GenericContainerScreenHandler h = gcs.getScreenHandler();
                for (int i = 0; i < h.getRows() * 9; i++) {
                    ItemStack st = h.getSlot(i).getStack();
                    if (!st.isEmpty() && st.isOf(item)) {
                        mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
                        return;
                    }
                }
                phase = Phase.CLOSE; timer = 0;              // hết món này trong rương
            }
            case CLOSE -> {
                if (timer < 2) return;
                if (mc.currentScreen instanceof GenericContainerScreen gcs) {
                    readInto(cur, gcs.getScreenHandler());      // ghi nhớ lại phần còn lại (rương nào đã hết)
                    p.closeHandledScreen();
                }
                save();
                phase = Phase.PICK; timer = 0;
            }
        }
    }

    private void skip(MinecraftClient mc) {
        if (cur != null && cur.fails >= 2) cur.blocked = true;
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        phase = Phase.PICK;
        timer = 0;
    }

    private void goTo(MinecraftClient mc, WorldSchematic sw) {
        final BlockPos target = cur.pos;
        Planner.Found f = Planner.find(mc, sw, Vec3d.ofCenter(target),
                (eye, feet) -> Planner.solveClick(mc, eye, feet, target, null, Set.of()), null);
        if (f == null) { cur.fails++; skip(mc); return; }
        timer = 0;
        if (f.path().isEmpty()) { phase = Phase.AIM; settle = 0; }
        else { mover.follow(f.path()); phase = Phase.WALK; }
    }

    /** Rương kế tiếp cần ghé: gần nhất, chưa ghé, còn hợp với mục đích. */
    private Memo next(MinecraftClient mc) {
        Vec3d me = Util.pos(mc.player);
        Memo best = null;
        double bd = Double.MAX_VALUE;
        for (Memo m : here(mc)) {
            if (m.blocked || visited.contains(m.key())) continue;
            if (!mc.world.getChunkManager().isChunkLoaded(m.pos.getX() >> 4, m.pos.getZ() >> 4)) continue;
            if (!isStorage(mc.world.getBlockState(m.pos))) { m.blocked = true; continue; }
            if (mode == Mode.SCAN && m.scanned) continue;
            if (mode == Mode.FETCH && m.items.getOrDefault(item, 0) <= 0) continue;
            double d = Vec3d.ofCenter(m.pos).squaredDistanceTo(me);
            if (d < bd) { bd = d; best = m; }
        }
        if (best != null) visited.add(best.key());
        return best;
    }

    private static void readInto(Memo m, GenericContainerScreenHandler h) {
        m.items.clear();
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty()) m.items.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        m.scanned = true;
    }

    // ------------------------------------------------------------------ lưu / nạp (tọa độ rương được nhớ qua các lần chơi)

    private Path file() { return FabricLoader.getInstance().getConfigDir().resolve("autobuilder-chests.txt"); }

    public void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] a = line.split("\\|", -1);
                if (a.length < 10) continue;
                Memo m = new Memo(a[0], new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                if (!a[4].equals("-")) m.other = new BlockPos(Integer.parseInt(a[4]), Integer.parseInt(a[5]), Integer.parseInt(a[6]));
                m.scanned = a[7].equals("1");
                m.blocked = a[8].equals("1");
                if (!a[9].isEmpty()) {
                    for (String kv : a[9].split(";")) {
                        int eq = kv.lastIndexOf('=');
                        if (eq < 0) continue;
                        Item it = Registries.ITEM.get(Identifier.of(kv.substring(0, eq)));
                        m.items.put(it, Integer.parseInt(kv.substring(eq + 1)));
                    }
                }
                memos.put(m.key(), m);
            }
        } catch (Exception ignored) {}
    }

    public void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Memo m : memos.values()) {
                sb.append(m.dim).append('|').append(m.pos.getX()).append('|').append(m.pos.getY()).append('|').append(m.pos.getZ()).append('|');
                if (m.other == null) sb.append("-|-|-|");
                else sb.append(m.other.getX()).append('|').append(m.other.getY()).append('|').append(m.other.getZ()).append('|');
                sb.append(m.scanned ? '1' : '0').append('|').append(m.blocked ? '1' : '0').append('|');
                boolean first = true;
                for (var e : m.items.entrySet()) {
                    if (!first) sb.append(';');
                    sb.append(Registries.ITEM.getId(e.getKey())).append('=').append(e.getValue());
                    first = false;
                }
                sb.append('\n');
            }
            Files.writeString(file(), sb.toString());
        } catch (Exception ignored) {}
    }
}
