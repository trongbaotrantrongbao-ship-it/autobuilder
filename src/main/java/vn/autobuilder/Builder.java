package vn.autobuilder;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** Bộ điều khiển chính: quét block ảo -> (báo giá) -> xây từng tầng từ dưới lên. */
public final class Builder {

    public final Scanner scanner = new Scanner();
    public final Quoter quoter = new Quoter();
    public final AuctionBuyer buyer = new AuctionBuyer();
    public final Mover mover = new Mover();

    public BlockPos focus;                 // block đang được xử lý (để vẽ khung xanh)

    private boolean enabled;
    private boolean approved;              // người chơi đã đồng ý báo giá -> được tự mua
    private double budgetCap;
    private double spentAtStart;
    private boolean quoteWhenScanned;
    private boolean cleaningUp;
    private int tick;
    private int rescanIn;

    private List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Integer> cooldownUntil = new HashMap<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Map<Item, Integer> buyTries = new HashMap<>();
    private final Set<Item> unobtainable = new HashSet<>();

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

    /** Yêu cầu báo giá: quét xong (nếu cần) rồi tra /ah cho các món còn thiếu. */
    public void requestQuote(MinecraftClient mc) {
        if (enabled) return;
        quoter.reset();
        approved = false;
        if (scanner.st == Scanner.St.DONE) quoter.start(mc, scanner.required);
        else { quoteWhenScanned = true; requestScan(mc); }
    }

    /** @return null nếu bắt đầu được, ngược lại là lý do. */
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
            attempts.clear(); cooldownUntil.clear(); blacklist.clear();
            buyTries.clear(); unobtainable.clear(); rescanIn = 100; cleaningUp = false;
            Util.chat(mc, "§aBẮT ĐẦU XÂY§r – " + targets.size() + " vị trí.");
        } else {
            buyer.cancel(mc);
            mover.release(mc);
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

        if (!enabled) { mover.release(mc); return; }
        if (buyer.busy()) { mover.release(mc); return; }
        if (mc.currentScreen != null) return;

        tick++;
        mover.tick(mc);
        if (cleaningUp) { cleanupScaffolds(mc); return; }
        if (tick % Config.delayTicks != 0) return;

        if (sw == null) { Util.status(mc, "Chưa nạp schematic Litematica."); return; }

        if (--rescanIn <= 0 && scanner.st != Scanner.St.RUNNING) { scanner.start(mc); rescanIn = 100; }

        step(mc, sw);
    }

    // ------------------------------------------------------------------ bước xây

    private void step(MinecraftClient mc, WorldSchematic sw) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();

        Integer layer = null;
        BlockPos chosen = null;
        StateDiff.Act chosenAct = null;
        BlockPos nearestOut = null;

        for (BlockPos pos : targets) {
            if (blacklist.contains(pos)) continue;
            if (layer != null && pos.getY() != layer) break;

            BlockState schem = sw.getBlockState(pos);
            StateDiff.Act act = StateDiff.diff(w.getBlockState(pos), schem);
            if (act == StateDiff.Act.DONE || act == StateDiff.Act.SKIP) { attempts.remove(pos); continue; }
            if (act == StateDiff.Act.BLOCKED) { blacklist.add(pos); continue; }

            if (layer == null) layer = pos.getY();
            if (cooldownUntil.getOrDefault(pos, 0) > tick) continue;
            if (eye.distanceTo(Vec3d.ofCenter(pos)) > Config.reach + 0.3) {
                if (nearestOut == null) nearestOut = pos;
                continue;
            }
            chosen = pos; chosenAct = act;
            break;
        }

        if (chosen == null) {
            focus = null;
            if (layer == null) { // hết việc
                mover.release(mc);
                if (!scaffolds.isEmpty()) { cleaningUp = true; return; }
                Util.chat(mc, "§aHoàn thành§r (trong vùng quét). Bỏ qua " + blacklist.size() + " vị trí không xử lý được.");
                setEnabled(mc, false);
                return;
            }
            if (nearestOut != null) {
                focus = nearestOut;
                if (Config.autoMove) { mover.moveTo(nearestOut); Util.status(mc, "Đang đi tới " + nearestOut.toShortString()); }
                else Util.status(mc, "Tầng Y=" + layer + ": hãy lại gần " + nearestOut.toShortString());
            }
            return;
        }

        mover.release(mc);
        focus = chosen;
        BlockState schem = sw.getBlockState(chosen);
        int n = attempts.merge(chosen, 1, Integer::sum);
        int limit = chosenAct == StateDiff.Act.ADJUST ? 10 : Config.maxAttempts;
        if (n > limit) {
            blacklist.add(chosen);
            Util.chat(mc, "§eBỏ qua " + chosen.toShortString() + " (" + schem.getBlock().getName().getString() + ") sau nhiều lần thử.");
            return;
        }
        cooldownUntil.put(chosen, tick + (chosenAct == StateDiff.Act.ADJUST ? Config.adjustDelayTicks : Config.ackTicks));

        switch (chosenAct) {
            case PLACE, PLACE_SLAB -> doPlace(mc, sw, chosen, schem, chosenAct == StateDiff.Act.PLACE_SLAB);
            case ADJUST -> doAdjust(mc, chosen);
            case WATERLOG -> doWaterlog(mc, chosen);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ đặt block

    private void doPlace(MinecraftClient mc, WorldSchematic sw, BlockPos pos, BlockState schem, boolean secondSlab) {
        ClientWorld w = mc.world;
        ItemStack pick = schem.getPickStack(sw, pos, false);
        Item item = pick.isEmpty() ? schem.getBlock().asItem() : pick.getItem();
        if (item == Items.AIR) { blacklist.add(pos); return; }

        if (!ensureItem(mc, item, pos)) return;

        if (secondSlab) {
            BlockState ws = w.getBlockState(pos);
            boolean bottom = ws.get(Properties.SLAB_TYPE) == net.minecraft.block.enums.SlabType.BOTTOM;
            Direction face = bottom ? Direction.UP : Direction.DOWN;
            Vec3d hit = Vec3d.ofCenter(pos).add(0, bottom ? 0.5 : -0.5, 0);
            Util.lookAt(mc, hit);
            Util.useBlock(mc, new BlockHitResult(hit, face, pos, false), false);
            return;
        }

        PlacementSolver.Plan plan = PlacementSolver.solve(mc, pos, schem, item);
        if (plan == null) { placeScaffoldFor(mc, pos); return; }
        Util.look(mc, plan.yaw(), plan.pitch());
        Util.useBlock(mc, new BlockHitResult(plan.hit(), plan.face(), plan.support(), false), true);
    }

    private void placeScaffoldFor(MinecraftClient mc, BlockPos pos) {
        BlockPos spot = findScaffoldSpot(mc, pos, 0);
        if (spot == null) {
            Util.chat(mc, "§eKhông tìm được chỗ đặt giàn giáo cho " + pos.toShortString());
            return;
        }
        Item si = pickScaffoldItem(mc);
        if (si == null) return;
        if (!ensureItem(mc, si, pos)) return;

        Block sb = ((BlockItem) si).getBlock();
        PlacementSolver.Plan plan = PlacementSolver.solve(mc, spot, sb.getDefaultState(), si);
        if (plan == null) return;
        Util.look(mc, plan.yaw(), plan.pitch());
        Util.useBlock(mc, new BlockHitResult(plan.hit(), plan.face(), plan.support(), false), true);
        scaffolds.add(spot);
    }

    private BlockPos findScaffoldSpot(MinecraftClient mc, BlockPos pos, int depth) {
        if (depth > 5) return null;
        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        Vec3d eye = mc.player.getEyePos();

        for (Direction d : order) {
            BlockPos c = pos.offset(d);
            if (!canScaffold(mc, sw, c, eye)) continue;
            if (PlacementSolver.hasSupport(mc, c)) return c;
        }
        for (Direction d : order) {
            BlockPos c = pos.offset(d);
            if (!canScaffold(mc, sw, c, eye)) continue;
            BlockPos deeper = findScaffoldSpot(mc, c, depth + 1);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private boolean canScaffold(MinecraftClient mc, WorldSchematic sw, BlockPos c, Vec3d eye) {
        if (!mc.world.getBlockState(c).isReplaceable()) return false;
        if (sw != null && !sw.getBlockState(c).isAir()) return false;
        return eye.distanceTo(Vec3d.ofCenter(c)) <= Config.reach;
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

    // ------------------------------------------------------------------ chỉnh / nước

    private void doAdjust(MinecraftClient mc, BlockPos pos) {
        Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5);
        Util.lookAt(mc, Vec3d.ofCenter(pos));
        Util.useBlock(mc, new BlockHitResult(hit, Direction.UP, pos, false), false);
    }

    private void doWaterlog(MinecraftClient mc, BlockPos pos) {
        if (!ensureItem(mc, Items.WATER_BUCKET, pos)) return;
        Util.lookAt(mc, Vec3d.ofCenter(pos));
        Util.useItem(mc);
    }

    // ------------------------------------------------------------------ kho đồ + mua

    private boolean ensureItem(MinecraftClient mc, Item item, BlockPos forPos) {
        Util.Sel s = Util.select(mc, item);
        if (s == Util.Sel.READY) return true;
        if (s == Util.Sel.WAIT) return false;

        // thiếu đồ
        if (!Config.autoBuy || !approved || unobtainable.contains(item)) {
            Util.status(mc, "Thiếu " + Util.englishName(item));
            if (forPos != null) blacklist.add(forPos);
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
            if (forPos != null) blacklist.add(forPos);
            return false;
        }
        buyer.start(mc, item);
        if (forPos != null) attempts.merge(forPos, -1, Integer::sum);
        return false;
    }

    // ------------------------------------------------------------------ dọn giàn giáo

    private void cleanupScaffolds(MinecraftClient mc) {
        ClientWorld w = mc.world;
        Vec3d eye = mc.player.getEyePos();
        scaffolds.removeIf(sp -> w.getBlockState(sp).isAir());
        if (scaffolds.isEmpty()) { cleaningUp = false; return; }

        List<BlockPos> list = new ArrayList<>(scaffolds);
        list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
        for (BlockPos sp : list) {
            if (eye.distanceTo(Vec3d.ofCenter(sp)) > Config.reach) continue;
            Util.lookAt(mc, Vec3d.ofCenter(sp));
            mc.interactionManager.updateBlockBreakingProgress(sp, Direction.UP);
            mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            return;
        }
        if (Config.autoMove) mover.moveTo(list.get(0)); else Util.status(mc, "Lại gần giàn giáo " + list.get(0).toShortString() + " để dọn.");
    }
}
