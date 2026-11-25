package vn.autobuilder;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
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

/** Bộ điều khiển chính: quét block ảo -> xây từng tầng từ dưới lên. */
public final class Builder {

    private record Target(BlockPos pos) {}

    private boolean enabled;
    private int tick;
    private int rescanIn;

    private List<Target> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Integer> cooldownUntil = new HashMap<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final LinkedHashSet<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Map<Item, Integer> buyTries = new HashMap<>();
    private final Set<Item> unobtainable = new HashSet<>();

    private final AuctionBuyer buyer = new AuctionBuyer();

    public boolean isEnabled() { return enabled; }

    public void setEnabled(MinecraftClient mc, boolean on) {
        enabled = on;
        if (on) {
            targets.clear(); attempts.clear(); cooldownUntil.clear(); blacklist.clear();
            buyTries.clear(); unobtainable.clear(); rescanIn = 0;
            Util.chat(mc, "§aBẬT§r – đang xây theo block ảo Litematica.");
        } else {
            buyer.cancel(mc);
            Util.chat(mc, "§cTẮT§r.");
        }
    }

    public String statusText() {
        return "enabled=" + enabled + ", còn " + targets.size() + " vị trí (đã bỏ qua " + blacklist.size()
                + "), giàn giáo " + scaffolds.size();
    }

    // ------------------------------------------------------------------ tick

    public void onTick(MinecraftClient mc) {
        if (!enabled || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        buyer.tick(mc);
        if (buyer.busy()) return;
        if (mc.currentScreen != null) return;

        tick++;
        // dọn giàn giáo cần đập liên tục mỗi tick (đập block cần giữ nhiều tick)
        if (cleaningUp) { cleanupScaffolds(mc); return; }
        if (tick % Config.delayTicks != 0) return;

        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        if (sw == null) { Util.status(mc, "Chưa nạp schematic Litematica."); return; }

        if (rescanIn-- <= 0) { scan(mc, sw); rescanIn = 40; }

        step(mc, sw);
    }

    private boolean cleaningUp;

    // ------------------------------------------------------------------ quét

    /** Quét toàn bộ block ảo trong vùng quanh người chơi, sắp xếp từ dưới lên trên. */
    private void scan(MinecraftClient mc, WorldSchematic sw) {
        ClientWorld w = mc.world;
        BlockPos c = mc.player.getBlockPos();
        int r = Config.scanRadius, hh = Config.scanHeight;
        int minY = Math.max(w.getBottomY(), c.getY() - hh);
        int maxY = Math.min(w.getTopY() - 1, c.getY() + hh);

        List<BlockPos> found = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = minY; y <= maxY; y++)
            for (int x = c.getX() - r; x <= c.getX() + r; x++)
                for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                    m.set(x, y, z);
                    BlockState s = sw.getBlockState(m);
                    if (s.isAir()) continue;
                    StateDiff.Act a = StateDiff.diff(w.getBlockState(m), s);
                    if (a == StateDiff.Act.DONE || a == StateDiff.Act.SKIP) continue;
                    found.add(m.toImmutable());
                }

        found.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> p.getSquaredDistance(c)));
        targets = new ArrayList<>();
        for (BlockPos p : found) if (!blacklist.contains(p)) targets.add(new Target(p));
    }

    // ------------------------------------------------------------------ bước xây

    private void step(MinecraftClient mc, WorldSchematic sw) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();

        // tìm tầng thấp nhất còn việc
        Integer layer = null;
        BlockPos chosen = null;
        StateDiff.Act chosenAct = null;
        BlockPos nearestOut = null;

        for (Target t : targets) {
            BlockPos pos = t.pos();
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
            if (layer == null) { // hết việc
                if (!scaffolds.isEmpty()) { cleaningUp = true; return; }
                Util.chat(mc, "§aHoàn thành§r (trong vùng quét). Bỏ qua " + blacklist.size() + " vị trí không xử lý được.");
                enabled = false;
                return;
            }
            if (nearestOut != null)
                Util.status(mc, "Tầng Y=" + layer + ": hãy lại gần " + nearestOut.toShortString());
            return;
        }

        BlockState schem = sw.getBlockState(chosen);
        int n = attempts.merge(chosen, 1, Integer::sum);
        int limit = chosenAct == StateDiff.Act.ADJUST ? 10 : Config.maxAttempts;
        if (n > limit) {
            blacklist.add(chosen);
            Util.chat(mc, "§eBỏ qua " + chosen.toShortString() + " (" + schem.getBlock().getName().getString() + ") sau nhiều lần thử.");
            return;
        }
        cooldownUntil.put(chosen, tick + Config.ackTicks);

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
        ItemStack pick = schem.getBlock().getPickStack(sw, pos, schem);
        Item item = pick.isEmpty() ? schem.getBlock().asItem() : pick.getItem();
        if (item == Items.AIR) { blacklist.add(pos); return; }

        if (!ensureItem(mc, item, pos)) return;

        if (secondSlab) { // bấm vào nửa slab đã có để thành slab đôi
            BlockState ws = w.getBlockState(pos);
            boolean bottom = ws.get(Properties.SLAB_TYPE) == net.minecraft.block.enums.SlabType.BOTTOM;
            Direction face = bottom ? Direction.UP : Direction.DOWN;
            Vec3d hit = Vec3d.ofCenter(pos).add(0, bottom ? 0.5 : -0.5, 0);
            Util.lookAt(mc, hit);
            Util.useBlock(mc, new BlockHitResult(hit, face, pos, false), false);
            return;
        }

        PlacementSolver.Plan plan = PlacementSolver.solve(mc, pos, schem, item);
        if (plan == null) {
            placeScaffoldFor(mc, pos);
            return;
        }
        Util.look(mc, plan.yaw(), plan.pitch());
        Util.useBlock(mc, new BlockHitResult(plan.hit(), plan.face(), plan.support(), false), true);
    }

    /** Block lơ lửng không có điểm tựa: đặt giàn giáo tạm (ưu tiên bên dưới) rồi tiếp tục. */
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
        if (sw != null && !sw.getBlockState(c).isAir()) return false; // chỗ này sau sẽ có block thật
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

    // ------------------------------------------------------------------ chỉnh bằng chuột phải

    /** Repeater (delay), comparator (mode), note block (note), trapdoor/fence gate (open). */
    private void doAdjust(MinecraftClient mc, BlockPos pos) {
        Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5);
        Util.lookAt(mc, Vec3d.ofCenter(pos));
        Util.useBlock(mc, new BlockHitResult(hit, Direction.UP, pos, false), false);
    }

    // ------------------------------------------------------------------ đổ nước

    /** Block có waterlogged=true trong schematic (lá có nước, slab nước...): dùng xô nước lên block đó. */
    private void doWaterlog(MinecraftClient mc, BlockPos pos) {
        if (!ensureItem(mc, Items.WATER_BUCKET, pos)) return;
        Util.lookAt(mc, Vec3d.ofCenter(pos));
        Util.useItem(mc);
    }

    // ------------------------------------------------------------------ kho đồ + mua

    /** true nếu đang cầm sẵn item; nếu thiếu thì kích hoạt mua /ah. */
    private boolean ensureItem(MinecraftClient mc, Item item, BlockPos forPos) {
        Util.Sel s = Util.select(mc, item);
        if (s == Util.Sel.READY) return true;
        if (s == Util.Sel.WAIT) return false;

        // MISSING
        if (!Config.autoBuy || unobtainable.contains(item)) {
            Util.status(mc, "Thiếu " + Util.englishName(item));
            if (forPos != null) blacklist.add(forPos);
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
        // lần thử mua không tính vào số lần đặt
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
        list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()); // đập từ trên xuống
        for (BlockPos sp : list) {
            if (eye.distanceTo(Vec3d.ofCenter(sp)) > Config.reach) continue;
            Util.lookAt(mc, Vec3d.ofCenter(sp));
            mc.interactionManager.updateBlockBreakingProgress(sp, Direction.UP);
            mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            return;
        }
        Util.status(mc, "Lại gần giàn giáo " + list.get(0).toShortString() + " để dọn.");
    }
}
