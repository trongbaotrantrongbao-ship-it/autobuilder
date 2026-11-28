package vn.autobuilder;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Bộ điều khiển chính. Mỗi block là 1 "nhiệm vụ" chạy theo các pha:
 *   PLAN (lập kế hoạch) -> MOVE (đi tới chỗ đứng) -> AIM (quay đầu nhìn vào điểm bấm) -> bấm -> VERIFY (kiểm tra đã đặt chưa).
 * Nếu chưa đặt được: ghi nhớ cách đã thử hỏng và tự tìm cách khác (mặt/điểm bấm khác, chỗ đứng khác,
 * giàn giáo làm điểm tựa, hoặc dựng cột để leo lên).
 */
public final class Builder {

    public final Scanner scanner = new Scanner();
    public final Quoter quoter = new Quoter();
    public final AuctionBuyer buyer = new AuctionBuyer();
    public final Mover mover = new Mover();

    public BlockPos focus;

    private enum Kind { PLACE, CLICK, BUCKET }
    private enum Phase { PLAN, MOVE, AIM, VERIFY, PILLAR }

    private static final class Task {
        Kind kind;
        BlockPos pos;
        BlockState want;
        Item item;
        boolean scaffold;
        int depth;
        Direction needFace;
        Phase phase = Phase.PLAN;
        Planner.Found found;
        Planner.Plan last;
        final Set<String> banned = new HashSet<>();
        int fails, replans;
        int deadline;
        int aimSettle;
        BlockState before;
        Planner.Approach ap;
        // cột leo
        BlockPos pBase;
        int pH, pK, pStage, pStart;
    }

    private boolean enabled, approved, quoteWhenScanned, cleaningUp, cleanFinal;
    private int cleanedForLayer = Integer.MIN_VALUE;

    // đặt nhanh: block vừa đặt, chờ server xác nhận
    private final Map<BlockPos, Integer> recent = new HashMap<>();
    private final Map<BlockPos, String> recentKey = new HashMap<>();
    private final Map<BlockPos, Integer> fastFails = new HashMap<>();
    private final Map<BlockPos, Set<String>> fastBanned = new HashMap<>();
    // block bị bỏ qua vì thiếu đồ -> nhặt được đồ thì tự làm lại
    private final Map<Item, Set<BlockPos>> skippedPos = new HashMap<>();

    // thoát khi bị kẹt
    private boolean escaping, escWalking;
    private List<Nav.RStep> escape;
    private int escIdx, escDeadline, escFails, escapeCount;

    private double budgetCap, spentAtStart;
    private int tick, rescanIn, nextActionTick;

    // thứ tự xây theo tầng
    private final Set<BlockPos> deferred = new HashSet<>();   // block lỗi, thử lại sau khi xong phần còn lại của tầng
    private int curLayer = Integer.MIN_VALUE, layerLeft, layerRound;
    private int sweepMinZ, sweepMaxZ;
    private boolean sweepZFlip, sweepXFlip;
    private float refYaw;
    private final Random rng = new Random();

    private List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> targetFails = new HashMap<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final Set<BlockPos> badSpots = new HashSet<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Deque<Task> stack = new ArrayDeque<>();
    private final Map<Item, Integer> buyTries = new HashMap<>();
    private final Set<Item> unobtainable = new HashSet<>();
    private final Map<Item, Integer> skippedByItem = new LinkedHashMap<>(); // vị trí bị bỏ qua vì thiếu món này

    // dọn giàn giáo
    private BlockPos cleanTarget;
    private Planner.Found cleanFound;
    private int cleanDeadline;

    public Builder() { Planner.scaffoldCells = scaffolds; }

    public boolean isEnabled() { return enabled; }
    public boolean isApproved() { return approved; }
    public double budgetCap() { return budgetCap; }
    public double spentSinceStart() { return buyer.spent - spentAtStart; }
    public int remaining() { return targets.size(); }
    public int skipped() { return blacklist.size(); }
    public boolean hasLayer() { return enabled && curLayer != Integer.MIN_VALUE; }
    public int layerY() { return curLayer; }
    public int layerLeft() { return layerLeft; }

    // ------------------------------------------------------------------ điều khiển

    public void requestScan(MinecraftClient mc) {
        if (scanner.st != Scanner.St.RUNNING) scanner.start(mc);
    }

    public Map<Item, Integer> missing(MinecraftClient mc) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : scanner.required.entrySet()) {
            int need = e.getValue() - Util.count(mc.player, e.getKey());
            if (need > 0) out.put(e.getKey(), need);
        }
        return out;
    }

    public void requestQuote(MinecraftClient mc) {
        if (enabled) return;
        quoter.reset();
        approved = false;
        if (scanner.st == Scanner.St.DONE) quoter.start(mc, scanner.required);
        else { quoteWhenScanned = true; requestScan(mc); }
    }

    public String tryStart(MinecraftClient mc, boolean viaQuote) {
        if (enabled) return null;
        if (SchematicWorldHandler.getSchematicWorld() == null) return "Chưa nạp schematic trong Litematica.";
        if (scanner.st != Scanner.St.DONE) { requestScan(mc); return "Đang quét block ảo, hãy bấm lại sau vài giây."; }
        Map<Item, Integer> miss = missing(mc);
        if (!miss.isEmpty() && !(viaQuote && quoter.st == Quoter.St.DONE))
            return "Còn thiếu " + miss.size() + " loại vật phẩm – mở tab Báo giá, kiểm tra giá rồi bấm Đồng ý.";
        approved = true;
        budgetCap = (quoter.st == Quoter.St.DONE ? quoter.total * Config.budgetFactor : 0) + 100_000;
        spentAtStart = buyer.spent;
        setEnabled(mc, true);
        return null;
    }

    /** Xây ngay bằng đồ có sẵn trong kho: không báo giá, không mua. Món nào thiếu thì bỏ qua vị trí cần nó. */
    public String startWithInventory(MinecraftClient mc) {
        if (enabled) return null;
        if (SchematicWorldHandler.getSchematicWorld() == null) return "Chưa nạp schematic trong Litematica.";
        if (scanner.st != Scanner.St.DONE) { requestScan(mc); return "Đang quét block ảo, hãy bấm lại sau vài giây."; }
        approved = false;
        budgetCap = 0;
        spentAtStart = buyer.spent;
        setEnabled(mc, true);
        return null;
    }

    public void setEnabled(MinecraftClient mc, boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            targets = new ArrayList<>(scanner.found);
            targetFails.clear(); blacklist.clear(); badSpots.clear(); stack.clear();
            buyTries.clear(); unobtainable.clear(); skippedByItem.clear(); rescanIn = 100; cleaningUp = false; cleanTarget = null;
            deferred.clear(); curLayer = Integer.MIN_VALUE; layerLeft = 0; layerRound = 0; restockItem = null; restockBusy = false;
            skippedPos.clear(); recent.clear(); recentKey.clear(); fastFails.clear(); fastBanned.clear(); escaping = false; escapeCount = 0; cleanFinal = false; cleanedForLayer = Integer.MIN_VALUE;
            refYaw = mc.player.getYaw();
            Util.chat(mc, "§aBẮT ĐẦU XÂY§r – " + targets.size() + " vị trí, từng tầng từ dưới lên"
                    + (approved ? "." : " (chỉ dùng đồ có sẵn trong kho)."));
        } else {
            buyer.cancel(mc);
            mover.stop(mc);
            mc.options.jumpKey.setPressed(false);
            stack.clear();
            focus = null;
            escaping = false;
            cleaningUp = false;
            curLayer = Integer.MIN_VALUE;
            Util.chat(mc, "§cĐÃ DỪNG§r.");
        }
    }

    public String statusText() {
        return "enabled=" + enabled + (hasLayer() ? ", tầng Y=" + curLayer + " còn " + layerLeft : "") + ", còn " + targets.size() + " vị trí (bỏ qua " + blacklist.size()
                + "), giàn giáo " + scaffolds.size();
    }

    // ------------------------------------------------------------------ tick

    public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        if (scanner.st == Scanner.St.RUNNING) {
            if (sw == null) scanner.st = Scanner.St.IDLE;
            else scanner.tick(mc, sw, (long) Math.max(1, enabled ? Config.scanMsPerTick / 2 : Config.scanMsPerTick) * 1_000_000L);
        }
        if (scanner.consumeFresh()) {
            if (enabled) targets = new ArrayList<>(scanner.found);
            if (quoteWhenScanned) { quoteWhenScanned = false; quoter.start(mc, scanner.required); }
        }

        buyer.tick(mc);
        quoter.tick(mc, buyer);

        if (!enabled) return;
        if (buyer.busy()) { mover.stop(mc); return; }
        if (restockItem != null && mc.currentScreen == null) { mover.stop(mc); tick++; restockStep(mc); return; }
        if (mc.currentScreen != null) return;
        if (sw == null) { Util.status(mc, "Chưa nạp schematic Litematica."); return; }

        tick++;
        mover.tick(mc);
        if (--rescanIn <= 0 && scanner.st != Scanner.St.RUNNING) { scanner.startQuick(mc); rescanIn = 200; }
        if (tick % 20 == 0) recoverItems(mc);
        verifyRecent(mc, sw);

        if (escaping) { escapeStep(mc, sw); return; }
        if (cleaningUp) { cleanupStep(mc); return; }

        if (stack.isEmpty()) {
            if (tick < nextActionTick) return;       // delay giữa các block do người dùng chỉnh
            if (Config.fastMode) {
                int n = fastBurst(mc, sw);
                if (n > 0) { nextActionTick = tick + Config.fastDelayTicks; return; }
                if (cleaningUp || escaping) return;
            }
        }
        if (stack.isEmpty()) {
            Task t = pickTask(mc, sw);
            if (t == null) {
                focus = null;
                if (cleaningUp) return;               // pickTask vừa yêu cầu dọn giàn giáo giữa các tầng
                if (!scaffolds.isEmpty()) { cleaningUp = true; cleanFinal = true; return; }
                Util.chat(mc, "§aHoàn thành§r (trong vùng quét). Bỏ qua " + blacklist.size() + " vị trí không xử lý được." + missingSummary());
                setEnabled(mc, false);
                return;
            }
            stack.push(t);
        }
        runTask(mc, sw, stack.peek());
    }

    // ------------------------------------------------------------------ đặt nhanh (kiểu Easy Place)

    /**
     * Đặt liền nhiều block trong 1 tick, ngay từ chỗ đang đứng: chỉ báo hướng nhìn cho server (không chờ xoay đầu),
     * vẫn dùng đúng bộ lập kế hoạch (nhìn thấy thẳng, trong 4 block, không xuyên tường). Block nào không đặt được từ
     * chỗ này thì dùng đường chậm (đi tới / giàn giáo / cột). Trả về số block đã đặt.
     */
    private int fastBurst(MinecraftClient mc, WorldSchematic sw) {
        ClientPlayerEntity p = mc.player;
        List<Cand> cands = collect(mc, sw);
        if (cands == null || cleaningUp) return 0;

        // mọi block của tầng đang xây nằm trong hình cầu bán kính tầm với quanh mắt, theo thứ tự xây
        Vec3d eye = p.getEyePos();
        double lim = Config.reach + 0.9;
        List<Cand> near = new ArrayList<>();
        for (Cand c : cands) {
            if (recent.containsKey(c.pos()) || fastFails.getOrDefault(c.pos(), 0) >= 2) continue;
            if (Vec3d.ofCenter(c.pos()).squaredDistanceTo(eye) <= lim * lim) near.add(c);
        }
        if (near.isEmpty()) return 0;
        near.sort(this::order);

        int placed = 0, max = Math.max(1, Config.placePerTick);
        long t0 = System.nanoTime();
        boolean progress = true;
        Set<BlockPos> tried = new HashSet<>();
        while (progress && placed < max && System.nanoTime() - t0 < 5_000_000L) {
            progress = false;
            for (Cand c : near) {
                if (placed >= max || System.nanoTime() - t0 > 5_000_000L) break;
                if (tried.contains(c.pos()) || recent.containsKey(c.pos())) continue;
                if (c.act() != StateDiff.Act.PLACE) continue;
                BlockState schem = sw.getBlockState(c.pos());
                if (!StateDiff.diff(mc.world.getBlockState(c.pos()), schem).equals(StateDiff.Act.PLACE)) continue;
                ItemStack pk = schem.getPickStack(sw, c.pos(), false);
                Item item = pk.isEmpty() ? schem.getBlock().asItem() : pk.getItem();
                if (item == Items.AIR || Util.count(p, item) <= 0) continue;

                Set<String> ban = fastBanned.getOrDefault(c.pos(), Set.of());
                Planner.Plan pl = Planner.solvePlace(mc, eye, Util.pos(p), c.pos(), schem, item, Set.of(), ban);
                if (pl == null) { continue; }                         // từ chỗ này chưa đặt được (chưa có điểm tựa...) -> thử lại sau khi đặt xong cái khác
                if (!Util.selectNow(mc, item)) { tried.add(c.pos()); continue; }   // món nằm trong kho, không ở hotbar -> đường chậm
                Util.silentLook(mc, pl.yaw(), pl.pitch());
                Util.useBlock(mc, new BlockHitResult(pl.hit(), pl.face(), pl.clicked(), false), false);
                recent.put(c.pos(), tick + Math.max(3, Config.ackTicks));
                recentKey.put(c.pos(), pl.key());
                focus = c.pos();
                placed++;
                progress = true;
            }
        }
        return placed;
    }

    /** Sau khi hết thời gian chờ: block nào vẫn chưa đúng thì cấm cách đặt đó; 2 lần hỏng thì chuyển sang đường chậm (kiểm tra kỹ). */
    private void verifyRecent(MinecraftClient mc, WorldSchematic sw) {
        Iterator<Map.Entry<BlockPos, Integer>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> e = it.next();
            if (e.getValue() > tick) continue;
            BlockPos pos = e.getKey();
            it.remove();
            String key = recentKey.remove(pos);
            StateDiff.Act a = StateDiff.diff(mc.world.getBlockState(pos), sw.getBlockState(pos));
            if (a == StateDiff.Act.DONE || a == StateDiff.Act.SKIP || a == StateDiff.Act.ADJUST || a == StateDiff.Act.WATERLOG
                    || a == StateDiff.Act.PLACE_SLAB) { fastFails.remove(pos); continue; }
            fastFails.merge(pos, 1, Integer::sum);
            if (key != null) fastBanned.computeIfAbsent(pos, k -> new HashSet<>()).add(key);
        }
    }

    /** Nhặt thêm được món từng bị coi là "không có" -> làm lại các vị trí đã bỏ qua vì nó. */
    private void recoverItems(MinecraftClient mc) {
        if (unobtainable.isEmpty()) return;
        Iterator<Item> it = unobtainable.iterator();
        while (it.hasNext()) {
            Item i = it.next();
            if (Util.count(mc.player, i) <= 0) continue;
            it.remove();
            Set<BlockPos> ps = skippedPos.remove(i);
            if (ps != null) blacklist.removeAll(ps);
            skippedByItem.remove(i);
            buyTries.remove(i);
        }
    }

    // ------------------------------------------------------------------ thoát khi bị kẹt

    /** Bị kẹt (cột giàn giáo, hộp kín...) -> tự tìm đường ra, đào những gì đào được (không động vào đá đỏ). */
    private boolean startEscape(MinecraftClient mc, WorldSchematic sw) {
        if (escapeCount >= 6) return false;
        Nav nav = new Nav(mc, sw);
        List<Nav.RStep> path = nav.escape(mc.player.getBlockPos(), scaffolds, 4000);
        if (path == null || path.isEmpty()) return false;
        escape = path;
        escIdx = 0; escFails = 0; escWalking = false;
        escDeadline = tick + 60 * path.size() + 300;
        escaping = true;
        escapeCount++;
        stack.clear();
        mover.stop(mc);
        Util.chat(mc, "Đang bị kẹt – tự tìm đường thoát (" + path.size() + " bước).");
        return true;
    }

    private void escapeStep(MinecraftClient mc, WorldSchematic sw) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (escape == null || escIdx >= escape.size() || tick > escDeadline) { finishEscape(mc); return; }
        Nav.RStep s = escape.get(escIdx);
        focus = s.pos;

        for (BlockPos c : s.dig) {                       // đào những ô còn chắn
            if (!w.getBlockState(c).getCollisionShape(w, c).isEmpty()) {
                mover.stop(mc);
                escWalking = false;
                digCell(mc, c);
                return;
            }
        }
        mc.interactionManager.cancelBlockBreaking();

        if (s.down) {                                    // đã đào sàn: chờ rơi xuống
            if (p.getBlockPos().getY() <= s.pos.getY() && p.isOnGround()) { escIdx++; escWalking = false; }
            return;
        }
        if (!escWalking) { mover.follow(List.of(s.pos)); escWalking = true; return; }
        if (mover.failed()) {
            mover.stop(mc);
            escWalking = false;
            if (++escFails > 6) finishEscape(mc);
        } else if (mover.arrived()) {
            mover.stop(mc);
            escIdx++;
            escWalking = false;
        }
    }

    private void digCell(MinecraftClient mc, BlockPos c) {
        ClientPlayerEntity p = mc.player;
        Util.bestTool(mc, mc.world.getBlockState(c));
        Direction face = Direction.UP;
        Planner.Plan pl = Planner.solveClick(mc, p.getEyePos(), Util.pos(p), c, null, Set.of());
        if (pl != null) { Util.look(mc, pl.yaw(), pl.pitch()); face = pl.face(); }
        else Util.lookAt(mc, Vec3d.ofCenter(c));
        mc.interactionManager.updateBlockBreakingProgress(c, face);
        p.swingHand(Hand.MAIN_HAND);
    }

    private void finishEscape(MinecraftClient mc) {
        escaping = false;
        escape = null;
        mover.stop(mc);
        mc.options.jumpKey.setPressed(false);
        mc.interactionManager.cancelBlockBreaking();
        stack.clear();
        badSpots.clear();
    }

    // ------------------------------------------------------------------ chọn việc

    private record Cand(BlockPos pos, StateDiff.Act act, int prio) {}

    /**
     * Xây NGHIÊM NGẶT từng tầng, từ dưới lên: chỉ chọn việc ở tầng thấp nhất còn việc. Block lỗi được hoãn
     * và thử lại (tối đa Config.layerRetries lần) trước khi bị bỏ qua – nên không bao giờ nhảy lên tầng trên khi tầng dưới còn dở.
     * Trong 1 tầng: làm các block có thể đứng lên/đặt trước (prio 0), rồi tới đồ mảnh/chỉnh/nước (prio 1);
     * trong mỗi nhóm đi theo đường rắn (hàng này hết mới sang hàng kế) nên không bị lộn xộn.
     */
    private Task pickTask(MinecraftClient mc, WorldSchematic sw) {
        List<Cand> cands = collect(mc, sw);
        if (cands == null) return null;
        Cand best = null;
        for (Cand c : cands) if (best == null || order(c, best) < 0) best = c;
        return makeTask(mc, sw, best);
    }

    /** Danh sách việc của tầng thấp nhất còn việc (null = hết việc, hoặc đang dọn giàn giáo giữa tầng). */
    private List<Cand> collect(MinecraftClient mc, WorldSchematic sw) {
        ClientWorld w = mc.world;

        for (int attempt = 0; attempt < 2; attempt++) {
            Integer layer = null;
            List<Cand> cands = new ArrayList<>();
            boolean hadDeferred = false;

            for (BlockPos pos : targets) {
                if (blacklist.contains(pos)) continue;
                if (layer != null && pos.getY() != layer) break;
                BlockState schem = sw.getBlockState(pos);
                StateDiff.Act act = StateDiff.diff(w.getBlockState(pos), schem);
                if (act == StateDiff.Act.DONE || act == StateDiff.Act.SKIP) continue;
                if (act == StateDiff.Act.BLOCKED) { blacklist.add(pos); continue; }
                if (!unobtainable.isEmpty() && act == StateDiff.Act.PLACE) {   // món đã biết là không có -> bỏ qua êm
                    ItemStack pk = schem.getPickStack(sw, pos, false);
                    Item it = pk.isEmpty() ? schem.getBlock().asItem() : pk.getItem();
                    if (unobtainable.contains(it)) {
                        skippedByItem.merge(it, 1, Integer::sum);
                        skippedPos.computeIfAbsent(it, k -> new HashSet<>()).add(pos);
                        blacklist.add(pos);
                        continue;
                    }
                }
                if (layer == null) layer = pos.getY();
                if (deferred.contains(pos)) { hadDeferred = true; layerLeft++; continue; }
                int prio = (act == StateDiff.Act.PLACE || act == StateDiff.Act.PLACE_SLAB)
                        && !schem.getCollisionShape(sw, pos).isEmpty() ? 0 : 1;
                cands.add(new Cand(pos, act, prio));
            }

            if (layer == null) { curLayer = Integer.MIN_VALUE; layerLeft = 0; return null; }   // xong hết
            if (layer != curLayer) {
                // sang tầng mới: phá hết giàn giáo của tầng trước cho gọn (tự tìm đường thoát nếu bị kẹt)
                if (curLayer != Integer.MIN_VALUE && !scaffolds.isEmpty() && cleanedForLayer != layer) {
                    cleanedForLayer = layer;
                    cleaningUp = true;
                    cleanFinal = false;
                    return null;
                }
                startLayer(mc, layer, cands);
            }
            layerLeft = cands.size() + (hadDeferred ? countDeferred(layer) : 0);

            if (cands.isEmpty()) {
                if (!hadDeferred) return null;
                deferred.clear();             // vòng thử lại các block lỗi của tầng này
                layerRound++;
                continue;
            }

            return cands;
        }
        return null;
    }

    private Task makeTask(MinecraftClient mc, WorldSchematic sw, Cand chosen) {
        ClientWorld w = mc.world;
        Util.status(mc, "Tầng Y=" + curLayer + " – còn " + layerLeft + " block" + (layerRound > 0 ? " (thử lại lần " + layerRound + ")" : ""));

        BlockPos best = chosen.pos();
        BlockState schem = sw.getBlockState(best);
        Task t = new Task();
        t.pos = best;
        t.want = schem;
        t.ap = new Planner.Approach(Config.approachMode, refYaw, rng.nextDouble() * Math.PI * 2);
        switch (chosen.act()) {
            case PLACE -> {
                ItemStack pick = schem.getPickStack(sw, best, false);
                Item item = pick.isEmpty() ? schem.getBlock().asItem() : pick.getItem();
                if (item == Items.AIR) { blacklist.add(best); return pickTask(mc, sw); }
                t.kind = Kind.PLACE; t.item = item;
            }
            case PLACE_SLAB -> {
                ItemStack pick = schem.getPickStack(sw, best, false);
                t.kind = Kind.CLICK; t.item = pick.getItem();
                boolean bottom = w.getBlockState(best).get(Properties.SLAB_TYPE) == SlabType.BOTTOM;
                t.needFace = bottom ? Direction.UP : Direction.DOWN;
            }
            case ADJUST -> { t.kind = Kind.CLICK; t.item = null; }
            case WATERLOG -> { t.kind = Kind.BUCKET; t.item = Items.WATER_BUCKET; }
            default -> { return null; }
        }
        return t;
    }

    private int countDeferred(int layer) {
        int n = 0;
        for (BlockPos p : deferred) if (p.getY() == layer && !blacklist.contains(p)) n++;
        return n;
    }

    /** Bắt đầu tầng mới: chọn góc quét gần người chơi nhất để đường đi liền mạch. */
    private void startLayer(MinecraftClient mc, int layer, List<Cand> cands) {
        curLayer = layer;
        layerRound = 0;
        deferred.clear();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : targets) {
            if (p.getY() != layer) continue;
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (minX == Integer.MAX_VALUE) { minX = maxX = minZ = maxZ = 0; }
        Vec3d pp = Util.pos(mc.player);
        sweepMinZ = minZ; sweepMaxZ = maxZ;
        sweepZFlip = Math.abs(pp.z - maxZ) < Math.abs(pp.z - minZ);
        sweepXFlip = Math.abs(pp.x - maxX) < Math.abs(pp.x - minX);
    }

    /** So sánh thứ tự xây: nhóm ưu tiên -> hàng (theo Z) -> theo X đảo chiều từng hàng (đường rắn). */
    private int order(Cand a, Cand b) {
        if (a.prio() != b.prio()) return Integer.compare(a.prio(), b.prio());
        int ra = row(a.pos()), rb = row(b.pos());
        if (ra != rb) return Integer.compare(ra, rb);
        int ca = col(a.pos(), ra), cb = col(b.pos(), rb);
        if (ca != cb) return Integer.compare(ca, cb);
        return Long.compare(a.pos().asLong(), b.pos().asLong());
    }

    private int row(BlockPos p) { return sweepZFlip ? sweepMaxZ - p.getZ() : p.getZ() - sweepMinZ; }

    private int col(BlockPos p, int row) { return ((row & 1) == 0) != sweepXFlip ? p.getX() : -p.getX(); }

    // ------------------------------------------------------------------ chạy nhiệm vụ

    private void runTask(MinecraftClient mc, WorldSchematic sw, Task t) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        BlockState ws = w.getBlockState(t.pos);
        focus = t.pos;

        if (t.phase != Phase.PILLAR) {
            if (isDone(t, ws, sw)) { finish(mc, t, true); return; }
            if (isBlocked(t, ws, sw)) { finish(mc, t, false); return; }
        }

        switch (t.phase) {
            case PLAN -> plan(mc, sw, t);

            case MOVE -> {
                if (mover.failed()) {
                    mover.stop(mc);
                    if (++t.replans > 3) {
                        if (startEscape(mc, sw)) return;
                        fail(mc, t, "không đi tới được chỗ đứng");
                        return;
                    }
                    t.phase = Phase.PLAN;
                } else if (mover.arrived()) {
                    mover.stop(mc);
                    t.phase = Phase.AIM;
                }
            }

            case AIM -> aim(mc, sw, t);

            case VERIFY -> {
                if (tick >= t.deadline) {            // chưa thấy block xuất hiện -> cách này không được
                    if (t.last != null) t.banned.add(t.last.key());
                    if (++t.fails >= Config.maxAttempts) fail(mc, t, "đặt không được sau nhiều cách");
                    else t.phase = Phase.PLAN;
                }
            }

            case PILLAR -> pillar(mc, sw, t);
        }
    }

    private boolean isDone(Task t, BlockState ws, WorldSchematic sw) {
        BlockState schem = sw.getBlockState(t.pos);
        return switch (t.kind) {
            case PLACE -> t.scaffold ? ws.getBlock() == t.want.getBlock() : ws.getBlock() == schem.getBlock();
            case CLICK -> t.before != null && !ws.equals(t.before);
            case BUCKET -> StateDiff.diff(ws, schem) == StateDiff.Act.DONE;
        };
    }

    private boolean isBlocked(Task t, BlockState ws, WorldSchematic sw) {
        if (t.kind == Kind.PLACE) {
            Block expected = t.scaffold ? t.want.getBlock() : sw.getBlockState(t.pos).getBlock();
            return !ws.isReplaceable() && ws.getBlock() != expected;
        }
        return ws.getBlock() != sw.getBlockState(t.pos).getBlock();
    }

    private void finish(MinecraftClient mc, Task t, boolean ok) {
        stack.remove(t);
        mover.stop(mc);
        if (ok) {
            if (t.scaffold) scaffolds.add(t.pos);
            else { targetFails.remove(t.pos); deferred.remove(t.pos); badSpots.clear(); escapeCount = 0; }
            nextActionTick = tick + (t.kind == Kind.CLICK ? Config.adjustDelayTicks : Config.delayTicks);
        } else {
            if (t.scaffold) badSpots.add(t.pos);
            else blacklist.add(t.pos);
        }
    }

    private void fail(MinecraftClient mc, Task t, String why) {
        mover.stop(mc);
        mc.options.jumpKey.setPressed(false);
        if (t.scaffold) {
            stack.remove(t);
            badSpots.add(t.pos);
            if (!stack.isEmpty()) stack.peek().phase = Phase.PLAN; // nhiệm vụ cha tìm cách khác
            return;
        }
        int n = targetFails.merge(t.pos, 1, Integer::sum);
        stack.clear();
        if (n >= Math.max(1, Config.layerRetries)) {
            blacklist.add(t.pos);
            deferred.remove(t.pos);
            Util.chat(mc, "§eBỏ qua " + t.pos.toShortString() + " (" + t.want.getBlock().getName().getString() + "): " + why);
        } else {
            deferred.add(t.pos);      // để sau, làm xong phần còn lại của tầng rồi thử lại
            badSpots.clear();
        }
    }

    // ------------------------------------------------------------------ PLAN

    private Planner.Solver solverFor(MinecraftClient mc, Task t, Set<BlockPos> extra) {
        return switch (t.kind) {
            case PLACE -> (eye, feet) -> Planner.solvePlace(mc, eye, feet, t.pos, t.want, t.item, extra, t.banned);
            case CLICK -> (eye, feet) -> Planner.solveClick(mc, eye, feet, t.pos, t.needFace, t.banned);
            case BUCKET -> (eye, feet) -> Planner.solveClick(mc, eye, feet, t.pos, null, t.banned);
        };
    }

    private void plan(MinecraftClient mc, WorldSchematic sw, Task t) {
        if (t.item != null && !haveOrBuy(mc, t.item, t)) return;

        Planner.Found f = Planner.find(mc, sw, Vec3d.ofCenter(t.pos), solverFor(mc, t, Set.of()), t.ap);
        if (f != null) {
            t.found = f;
            if (f.path().isEmpty()) t.phase = Phase.AIM;
            else { mover.follow(f.path()); t.phase = Phase.MOVE; }
            return;
        }
        // không có cách trực tiếp -> thử giàn giáo làm điểm tựa, rồi dựng cột để leo
        if (t.kind == Kind.PLACE && trySupportScaffold(mc, sw, t)) return;
        if (tryPillar(mc, sw, t)) return;
        if (startEscape(mc, sw)) return;          // có thể đang bị kẹt trên cột / trong hộp kín
        fail(mc, t, "không tìm được cách đặt hợp lệ (không xuyên tường, trong 4 block)");
    }

    private boolean trySupportScaffold(MinecraftClient mc, WorldSchematic sw, Task t) {
        if (t.depth >= 3) return false;
        Item si = pickScaffoldItem(mc);
        if (si == null) return false;

        BlockPos bestS = null;
        double bestC = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos s = t.pos.offset(d);
            if (badSpots.contains(s) || scaffoldOnStack(s)) continue;
            if (!mc.world.getBlockState(s).isReplaceable()) continue;
            if (!sw.getBlockState(s).isAir()) continue;               // không chiếm chỗ của block thật sau này
            Planner.Found f = Planner.find(mc, sw, Vec3d.ofCenter(t.pos), solverFor(mc, t, Set.of(s)));
            if (f == null) continue;
            double c = f.cost() + (d == Direction.DOWN ? 0 : 1) - 1.5 * pendingNeighbours(mc, sw, s, t.pos);
            if (c < bestC) { bestC = c; bestS = s; }
        }
        if (bestS == null) return false;

        Task st = new Task();
        st.kind = Kind.PLACE;
        st.pos = bestS;
        st.want = ((BlockItem) si).getBlock().getDefaultState();
        st.item = si;
        st.scaffold = true;
        st.depth = t.depth + 1;
        stack.push(st);
        return true;
    }

    /** Số block kế bên ô s còn cần xây (ngoài chính nó): giàn giáo đặt chỗ nhiều block cần tựa sẽ được dùng lại -> gọn hơn. */
    private int pendingNeighbours(MinecraftClient mc, WorldSchematic sw, BlockPos s, BlockPos self) {
        int n = 0;
        for (Direction d : Direction.values()) {
            BlockPos q = s.offset(d);
            if (q.equals(self) || sw.getBlockState(q).isAir() || blacklist.contains(q)) continue;
            StateDiff.Act a = StateDiff.diff(mc.world.getBlockState(q), sw.getBlockState(q));
            if (a == StateDiff.Act.PLACE || a == StateDiff.Act.PLACE_SLAB) n++;
        }
        return n;
    }

    private boolean scaffoldOnStack(BlockPos p) {
        for (Task x : stack) if (x.pos.equals(p)) return true;
        return false;
    }

    private boolean tryPillar(MinecraftClient mc, WorldSchematic sw, Task t) {
        Item si = pickScaffoldItem(mc);
        if (si == null) return false;
        Nav nav = new Nav(mc, sw);
        Vec3d center = Vec3d.ofCenter(t.pos);

        Nav.Node bestN = null;
        int bestH = 0;
        double bestC = Double.MAX_VALUE;
        int checked = 0;
        for (Nav.Node n : nav.reachable(mc.player.getBlockPos(), 8, 600)) {
            double hx = n.pos.getX() + 0.5 - center.x, hz = n.pos.getZ() + 0.5 - center.z;
            if (Math.sqrt(hx * hx + hz * hz) > Config.reach) continue;
            if (++checked > 60) break;
            for (int h = 1; h <= 8; h++) {
                if (!columnFree(mc, sw, n.pos, h)) break;
                BlockPos stand = n.pos.up(h);
                Vec3d eye = Planner.eyeAt(stand);
                if (eye.distanceTo(center) > Config.reach + 1.0) continue;
                if (solverFor(mc, t, Set.of()).solve(eye, Planner.feetAt(stand)) != null) {
                    double c = n.cost + h * 3.0;
                    if (c < bestC) { bestC = c; bestN = n; bestH = h; }
                    break;
                }
            }
        }
        if (bestN == null) return false;

        t.pBase = bestN.pos;
        t.pH = bestH;
        t.pK = 0;
        t.pStage = 0;
        t.phase = Phase.PILLAR;
        mover.follow(bestN.path());
        Util.chat(mc, "Dựng cột " + bestH + " block để leo tới " + t.pos.toShortString());
        return true;
    }

    private boolean columnFree(MinecraftClient mc, WorldSchematic sw, BlockPos base, int h) {
        for (int k = 0; k <= h + 1; k++) {
            BlockPos c = base.up(k);
            if (!mc.world.getBlockState(c).isReplaceable() && k > 0) return false;
            if (!sw.getBlockState(c).isAir()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ AIM + bấm

    private void aim(MinecraftClient mc, WorldSchematic sw, Task t) {
        ClientPlayerEntity p = mc.player;

        // tính lại kế hoạch từ vị trí mắt THỰC TẾ
        Planner.Plan pl = solverFor(mc, t, Set.of()).solve(p.getEyePos(), Util.pos(p));
        if (pl == null) {
            if (++t.replans > 4) { if (t.last != null) t.banned.add(t.last.key()); t.replans = 0; t.fails++; }
            if (t.fails >= Config.maxAttempts) fail(mc, t, "không còn cách nào nhìn thấy điểm bấm");
            else t.phase = Phase.PLAN;
            return;
        }

        if (t.item != null) {
            Util.Sel s = Util.select(mc, t.item);
            if (s == Util.Sel.WAIT) return;
            if (s == Util.Sel.MISSING) { t.phase = Phase.PLAN; return; }
        }

        // quay đầu mượt về hướng điểm bấm, quay XONG và đứng yên rồi mới đặt
        if (Config.rotateHead) {
            float dy = MathHelper.wrapDegrees(pl.yaw() - p.getYaw());
            float dp = pl.pitch() - p.getPitch();
            float step = Config.aimSpeed;
            if (Math.abs(dy) > 1.0f || Math.abs(dp) > 1.0f) {
                Util.look(mc, p.getYaw() + MathHelper.clamp(dy, -step, step),
                        p.getPitch() + MathHelper.clamp(dp, -step, step));
                t.aimSettle = 0;
                return;
            }
            Util.look(mc, pl.yaw(), pl.pitch());              // khớp chính xác hướng nhìn
            if (t.aimSettle++ < Config.aimSettleTicks) return; // giữ yên vài tick cho server nhận hướng
        } else {
            Util.look(mc, pl.yaw(), pl.pitch());
        }
        t.aimSettle = 0;

        t.before = mc.world.getBlockState(t.pos);
        t.last = pl;
        t.deadline = tick + Config.ackTicks;
        t.phase = Phase.VERIFY;
        BlockHitResult hit = new BlockHitResult(pl.hit(), pl.face(), pl.clicked(), false);
        if (t.kind == Kind.BUCKET) Util.useItem(mc);
        else Util.useBlock(mc, hit, false);
    }

    // ------------------------------------------------------------------ dựng cột

    private void pillar(MinecraftClient mc, WorldSchematic sw, Task t) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;

        if (t.pStage == 0) {
            if (mover.failed()) { mover.stop(mc); fail(mc, t, "không đi tới được chỗ dựng cột"); return; }
            if (mover.busy()) return;
            mover.stop(mc);
            t.pStage = 1;
            t.pStart = tick;
        }

        if (tick - t.pStart > 400) { mover.holdJump(mc, false); fail(mc, t, "dựng cột quá lâu"); return; }
        if (t.pK >= t.pH) { mover.holdJump(mc, false); t.phase = Phase.PLAN; t.pStage = 0; return; }

        Item si = pickScaffoldItem(mc);
        if (si == null || !haveOrBuy(mc, si, t)) { mover.holdJump(mc, false); return; }
        if (Util.select(mc, si) != Util.Sel.READY) { mover.holdJump(mc, false); return; }

        BlockPos cell = t.pBase.up(t.pK);
        if (!w.getBlockState(cell).isReplaceable()) {      // ô này đã có block -> sang ô kế
            scaffolds.add(cell);
            t.pK++;
            mover.holdJump(mc, false);
            return;
        }
        // đứng đúng cột
        if (Math.abs(p.getX() - (cell.getX() + 0.5)) > 0.35 || Math.abs(p.getZ() - (cell.getZ() + 0.5)) > 0.35) {
            Util.status(mc, "Đứng giữa cột để dựng...");
        }
        Util.look(mc, p.getYaw(), 90f);
        if (p.isOnGround()) {
            mover.holdJump(mc, true);
        } else if (p.getY() >= cell.getY() + 1.1) {         // đã lên đủ cao (chừa biên cho độ trễ server), ô dưới chân đã trống
            mover.holdJump(mc, false);
            Vec3d hit = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
            Util.useBlock(mc, new BlockHitResult(hit, Direction.UP, cell.down(), false), false);
        }
    }

    // ------------------------------------------------------------------ kho đồ + mua

    private boolean haveOrBuy(MinecraftClient mc, Item item, Task t) {
        if (Util.count(mc.player, item) > 0) return true;
        if (!Config.autoBuy || !approved || unobtainable.contains(item)) {
            skipMissing(mc, t, item);
            return false;
        }
        if (spentSinceStart() >= budgetCap) {
            Util.chat(mc, "§cĐã chạm ngân sách đã đồng ý (" + Util.money(budgetCap) + "). Dừng mua thêm.");
            Config.autoBuy = false;
            return false;
        }
        // bắt đầu nạp hàng: mua LIÊN TIẾP nhiều stack cho tới khi đủ số lượng còn cần (hoặc đầy kho) rồi mới làm tiếp
        restockItem = item;
        restockGoal = Math.max(1, Math.min(remainingNeed(mc, item), Util.freeSpace(mc.player, item)));
        restockFails = 0;
        restockWait = 0;
        restockBusy = buyer.start(mc, item);
        return false;
    }

    private int restockGoal, restockFails, restockWait;
    private boolean restockBusy;
    private Item restockItem;

    /** Mỗi tick khi đang nạp hàng: mua tiếp nếu chưa đủ; đủ rồi (hoặc hết cách) thì thôi. */
    private void restockStep(MinecraftClient mc) {
        if (restockBusy) {                      // vừa mua xong 1 lần
            if (buyer.busy()) return;
            if (restockWait == 0) restockWait = tick + 4;       // chờ server cập nhật kho
            if (tick < restockWait) return;
            restockBusy = false;
            if (buyer.lastSucceeded()) { restockFails = 0; buyTries.remove(restockItem); }
            else restockFails++;
        }
        int have = Util.count(mc.player, restockItem);
        Item it = restockItem;
        if (have >= restockGoal || Util.freeSpace(mc.player, it) <= 0) {
            Util.chat(mc, "Đã đủ §e" + Util.englishName(it) + "§r: có " + have + " (cần nạp " + restockGoal + ").");
            restockItem = null;
            return;
        }
        if (restockFails >= Math.max(1, Config.buyMaxTries)) {
            if (have <= 0) { unobtainable.add(it); }
            Util.chat(mc, "§eKhông mua thêm được §f" + Util.englishName(it) + "§e (có " + have + "/" + restockGoal + ").");
            restockItem = null;
            return;
        }
        if (spentSinceStart() >= budgetCap) {
            Util.chat(mc, "§cĐã chạm ngân sách đã đồng ý (" + Util.money(budgetCap) + "). Dừng mua thêm.");
            Config.autoBuy = false;
            restockItem = null;
            return;
        }
        restockWait = 0;
        restockBusy = buyer.start(mc, it);
    }

    /** Số cái của item còn cần cho các block chưa xây. */
    private int remainingNeed(MinecraftClient mc, Item item) {
        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        if (sw == null) return 1;
        int n = 0;
        for (BlockPos pos : targets) {
            if (blacklist.contains(pos)) continue;
            BlockState s = sw.getBlockState(pos);
            StateDiff.Act a = StateDiff.diff(mc.world.getBlockState(pos), s);
            if (item == Items.WATER_BUCKET) {
                if (a == StateDiff.Act.WATERLOG
                        || (a == StateDiff.Act.PLACE && s.contains(Properties.WATERLOGGED) && s.get(Properties.WATERLOGGED))) n++;
                continue;
            }
            if (a != StateDiff.Act.PLACE && a != StateDiff.Act.PLACE_SLAB) continue;
            ItemStack pk = s.getPickStack(sw, pos, false);
            Item it = pk.isEmpty() ? s.getBlock().asItem() : pk.getItem();
            if (it == item) n++;
        }
        return Math.max(n, 1);
    }


    /** Thiếu vật phẩm mà không mua được: bỏ qua vị trí này, báo 1 lần cho mỗi loại, các vị trí khác cần nó sẽ bị bỏ qua êm. */
    private void skipMissing(MinecraftClient mc, Task t, Item item) {
        int n = skippedByItem.merge(item, 1, Integer::sum);
        if (n == 1) {
            Util.chat(mc, "§eKhông có §f" + Util.englishName(item) + "§e ("
                    + (approved && Config.autoBuy ? "hết hàng ở /ah hoặc không mua được" : "không có trong kho, đang ở chế độ không mua")
                    + ") – bỏ qua mọi vị trí cần nó.");
        }
        if (!approved || !Config.autoBuy) unobtainable.add(item);
        mover.stop(mc);
        mc.options.jumpKey.setPressed(false);
        if (t.scaffold) {
            stack.remove(t);
            badSpots.add(t.pos);
            if (!stack.isEmpty()) stack.peek().phase = Phase.PLAN;
        } else {
            stack.clear();
            blacklist.add(t.pos);
            skippedPos.computeIfAbsent(item, k -> new HashSet<>()).add(t.pos);
        }
    }

    private String missingSummary() {
        if (skippedByItem.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n§eCòn thiếu (cần tự kiếm): ");
        boolean first = true;
        for (Map.Entry<Item, Integer> e : skippedByItem.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(Util.englishName(e.getKey())).append(" ×").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private Item pickScaffoldItem(MinecraftClient mc) {
        List<Item> list = new ArrayList<>();
        for (String s : Config.scaffoldItems.split(",")) {
            Item it = Registries.ITEM.get(Identifier.of(s.trim()));
            if (it instanceof BlockItem) list.add(it);
        }
        if (list.isEmpty()) return null;
        for (Item it : list) if (Util.count(mc.player, it) > 0) return it;
        for (Item it : list) if (!unobtainable.contains(it)) return it;   // chưa có trong kho: thử mua (nếu được phép)
        return null;
    }

    // ------------------------------------------------------------------ dọn giàn giáo

    private void cleanupStep(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        scaffolds.removeIf(sp -> w.getBlockState(sp).isReplaceable());
        if (scaffolds.isEmpty()) {
            cleaningUp = false;
            mover.stop(mc);
            mc.interactionManager.cancelBlockBreaking();
            if (!cleanFinal) return;                 // dọn giữa các tầng: xong thì xây tiếp
            Util.chat(mc, "§aHoàn thành§r và đã dọn giàn giáo. Bỏ qua " + blacklist.size() + " vị trí không xử lý được." + missingSummary());
            setEnabled(mc, false);
            return;
        }

        // đang đứng trên giàn giáo -> đào xuống (rơi xuống ô thấp hơn)
        BlockPos below = p.getBlockPos().down();
        if (p.isOnGround() && scaffolds.contains(below)) {
            mover.stop(mc);
            Util.bestTool(mc, w.getBlockState(below));
            Util.look(mc, p.getYaw(), 90f);
            mc.interactionManager.updateBlockBreakingProgress(below, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
            focus = below;
            return;
        }

        if (cleanTarget == null || !scaffolds.contains(cleanTarget)) {
            BlockPos hi = null;
            for (BlockPos s : scaffolds) if (hi == null || s.getY() > hi.getY()) hi = s;
            WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
            final BlockPos hiF = hi;
            Planner.Found f = Planner.find(mc, sw, Vec3d.ofCenter(hiF), (eye, feet) -> Planner.solveClick(mc, eye, feet, hiF, null, Set.of()));
            if (f == null) {
                if (startEscape(mc, sw)) return;       // có thể đang kẹt: tự thoát rồi dọn tiếp
                Util.chat(mc, "§eKhông dọn được giàn giáo ở " + hi.toShortString());
                scaffolds.remove(hi);
                return;
            }
            cleanTarget = hi;
            cleanFound = f;
            cleanDeadline = tick + 300;
            if (!f.path().isEmpty()) mover.follow(f.path());
            return;
        }
        focus = cleanTarget;
        if (tick > cleanDeadline) { scaffolds.remove(cleanTarget); cleanTarget = null; mover.stop(mc); return; }
        if (mover.busy()) return;
        if (mover.failed()) { mover.stop(mc); scaffolds.remove(cleanTarget); cleanTarget = null; return; }

        Planner.Plan pl = Planner.solveClick(mc, p.getEyePos(), Util.pos(p), cleanTarget, null, Set.of());
        if (pl == null) { cleanTarget = null; return; }       // chọn lại
        Util.bestTool(mc, w.getBlockState(cleanTarget));
        Util.look(mc, pl.yaw(), pl.pitch());
        mc.interactionManager.updateBlockBreakingProgress(cleanTarget, pl.face());
        p.swingHand(Hand.MAIN_HAND);
    }
}
