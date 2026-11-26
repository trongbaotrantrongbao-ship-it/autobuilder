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
        // cột leo
        BlockPos pBase;
        int pH, pK, pStage, pStart;
    }

    private boolean enabled, approved, quoteWhenScanned, cleaningUp;
    private double budgetCap, spentAtStart;
    private int tick, rescanIn, nextActionTick;

    private List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> targetFails = new HashMap<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final Set<BlockPos> badSpots = new HashSet<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Deque<Task> stack = new ArrayDeque<>();
    private final Map<Item, Integer> buyTries = new HashMap<>();
    private final Set<Item> unobtainable = new HashSet<>();

    // dọn giàn giáo
    private BlockPos cleanTarget;
    private Planner.Found cleanFound;
    private int cleanDeadline;

    public boolean isEnabled() { return enabled; }
    public boolean isApproved() { return approved; }
    public double budgetCap() { return budgetCap; }
    public double spentSinceStart() { return buyer.spent - spentAtStart; }
    public int remaining() { return targets.size(); }
    public int skipped() { return blacklist.size(); }

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

    public void setEnabled(MinecraftClient mc, boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            targets = new ArrayList<>(scanner.found);
            targetFails.clear(); blacklist.clear(); badSpots.clear(); stack.clear();
            buyTries.clear(); unobtainable.clear(); rescanIn = 100; cleaningUp = false; cleanTarget = null;
            Util.chat(mc, "§aBẮT ĐẦU XÂY§r – " + targets.size() + " vị trí.");
        } else {
            buyer.cancel(mc);
            mover.stop(mc);
            mc.options.jumpKey.setPressed(false);
            stack.clear();
            focus = null;
            Util.chat(mc, "§cĐÃ DỪNG§r.");
        }
    }

    public String statusText() {
        return "enabled=" + enabled + ", còn " + targets.size() + " vị trí (bỏ qua " + blacklist.size()
                + "), giàn giáo " + scaffolds.size();
    }

    // ------------------------------------------------------------------ tick

    public void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        if (scanner.st == Scanner.St.RUNNING) {
            if (sw == null) scanner.st = Scanner.St.IDLE;
            else scanner.tick(mc, sw, 40_000);
        }
        if (scanner.consumeFresh()) {
            if (enabled) targets = new ArrayList<>(scanner.found);
            if (quoteWhenScanned) { quoteWhenScanned = false; quoter.start(mc, scanner.required); }
        }

        buyer.tick(mc);
        quoter.tick(mc, buyer);

        if (!enabled) return;
        if (buyer.busy()) { mover.stop(mc); return; }
        if (mc.currentScreen != null) return;
        if (sw == null) { Util.status(mc, "Chưa nạp schematic Litematica."); return; }

        tick++;
        mover.tick(mc);
        if (--rescanIn <= 0 && scanner.st != Scanner.St.RUNNING) { scanner.start(mc); rescanIn = 100; }

        if (cleaningUp) { cleanupStep(mc); return; }

        if (stack.isEmpty()) {
            if (tick < nextActionTick) return;       // delay giữa các block do người dùng chỉnh
            Task t = pickTask(mc, sw);
            if (t == null) {
                focus = null;
                if (!scaffolds.isEmpty()) { cleaningUp = true; return; }
                Util.chat(mc, "§aHoàn thành§r (trong vùng quét). Bỏ qua " + blacklist.size() + " vị trí không xử lý được.");
                setEnabled(mc, false);
                return;
            }
            stack.push(t);
        }
        runTask(mc, sw, stack.peek());
    }

    // ------------------------------------------------------------------ chọn việc

    private Task pickTask(MinecraftClient mc, WorldSchematic sw) {
        ClientWorld w = mc.world;
        Vec3d pp = Util.pos(mc.player);
        Integer layer = null;
        BlockPos best = null;
        StateDiff.Act bestAct = null;
        double bestD = Double.MAX_VALUE;

        for (BlockPos pos : targets) {
            if (blacklist.contains(pos)) continue;
            if (layer != null && pos.getY() != layer) break;
            BlockState schem = sw.getBlockState(pos);
            StateDiff.Act act = StateDiff.diff(w.getBlockState(pos), schem);
            if (act == StateDiff.Act.DONE || act == StateDiff.Act.SKIP) continue;
            if (act == StateDiff.Act.BLOCKED) { blacklist.add(pos); continue; }
            if (layer == null) layer = pos.getY();
            double d = Vec3d.ofCenter(pos).squaredDistanceTo(pp);
            if (d < bestD) { bestD = d; best = pos; bestAct = act; }
        }
        if (best == null) return null;

        BlockState schem = sw.getBlockState(best);
        Task t = new Task();
        t.pos = best;
        t.want = schem;
        switch (bestAct) {
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
                    if (++t.replans > 3) { fail(mc, t, "không đi tới được chỗ đứng"); return; }
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
            else { targetFails.remove(t.pos); badSpots.clear(); }
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
        if (n >= 2) {
            blacklist.add(t.pos);
            Util.chat(mc, "§eBỏ qua " + t.pos.toShortString() + " (" + t.want.getBlock().getName().getString() + "): " + why);
        } else {
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

        Planner.Found f = Planner.find(mc, sw, Vec3d.ofCenter(t.pos), solverFor(mc, t, Set.of()));
        if (f != null) {
            t.found = f;
            if (f.path().isEmpty()) t.phase = Phase.AIM;
            else { mover.follow(f.path()); t.phase = Phase.MOVE; }
            return;
        }
        // không có cách trực tiếp -> thử giàn giáo làm điểm tựa, rồi dựng cột để leo
        if (t.kind == Kind.PLACE && trySupportScaffold(mc, sw, t)) return;
        if (tryPillar(mc, sw, t)) return;
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
            double c = f.cost() + (d == Direction.DOWN ? 0 : 1);
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
        } else if (p.getY() > cell.getY() + 1.0) {          // đang ở trên không, ô dưới chân đã trống
            mover.holdJump(mc, false);
            Vec3d hit = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
            Util.useBlock(mc, new BlockHitResult(hit, Direction.UP, cell.down(), false), false);
        }
    }

    // ------------------------------------------------------------------ kho đồ + mua

    private boolean haveOrBuy(MinecraftClient mc, Item item, Task t) {
        if (Util.count(mc.player, item) > 0) return true;
        if (!Config.autoBuy || !approved || unobtainable.contains(item)) {
            Util.status(mc, "Thiếu " + Util.englishName(item));
            fail(mc, t, "thiếu " + Util.englishName(item));
            return false;
        }
        if (spentSinceStart() >= budgetCap) {
            Util.chat(mc, "§cĐã chạm ngân sách đã đồng ý (" + Util.money(budgetCap) + "). Dừng mua thêm.");
            Config.autoBuy = false;
            return false;
        }
        int tries = buyTries.merge(item, 1, Integer::sum);
        if (tries > Config.buyMaxTries) {
            unobtainable.add(item);
            Util.chat(mc, "§cKhông mua được " + Util.englishName(item) + ", bỏ qua các vị trí cần nó.");
            fail(mc, t, "không mua được " + Util.englishName(item));
            return false;
        }
        buyer.start(mc, item);
        return false;
    }

    private Item pickScaffoldItem(MinecraftClient mc) {
        List<Item> list = new ArrayList<>();
        for (String s : Config.scaffoldItems.split(",")) {
            Item it = Registries.ITEM.get(Identifier.of(s.trim()));
            if (it instanceof BlockItem) list.add(it);
        }
        if (list.isEmpty()) return null;
        for (Item it : list) if (Util.count(mc.player, it) > 0) return it;
        return list.get(0);
    }

    // ------------------------------------------------------------------ dọn giàn giáo

    private void cleanupStep(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        scaffolds.removeIf(sp -> w.getBlockState(sp).isReplaceable());
        if (scaffolds.isEmpty()) {
            cleaningUp = false;
            mover.stop(mc);
            Util.chat(mc, "§aHoàn thành§r và đã dọn giàn giáo. Bỏ qua " + blacklist.size() + " vị trí không xử lý được.");
            setEnabled(mc, false);
            return;
        }

        // đang đứng trên giàn giáo -> đào xuống (rơi xuống ô thấp hơn)
        BlockPos below = p.getBlockPos().down();
        if (p.isOnGround() && scaffolds.contains(below)) {
            mover.stop(mc);
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
        Util.look(mc, pl.yaw(), pl.pitch());
        mc.interactionManager.updateBlockBreakingProgress(cleanTarget, pl.face());
        p.swingHand(Hand.MAIN_HAND);
    }
}
