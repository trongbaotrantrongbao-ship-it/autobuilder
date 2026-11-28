package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Báo giá: tra /ah cho từng vật phẩm còn thiếu và cộng tổng tiền. */
public final class Quoter {

    public enum St { IDLE, RUNNING, DONE }

    public record Line(Item item, int needed, int available, double cost, double unit, boolean enough) {}

    public St st = St.IDLE;
    public final List<Line> lines = new ArrayList<>();
    public double total;
    public boolean allEnough = true;
    public int totalItems;

    private final List<Map.Entry<Item, Integer>> queue = new ArrayList<>();
    private int idx;
    private Item current;

    public int progressDone() { return idx; }
    public int progressTotal() { return queue.size(); }
    public String currentName() { return current == null ? "" : Util.englishName(current); }

    public void start(MinecraftClient mc, Map<Item, Integer> required) {
        lines.clear(); queue.clear();
        total = 0; allEnough = true; totalItems = 0; idx = 0; current = null;
        for (Map.Entry<Item, Integer> e : required.entrySet()) {
            int have = Util.count(mc.player, e.getKey()) + (Config.useChests ? AutoBuilderMod.CHESTS.total(mc, e.getKey()) : 0);
            int need = e.getValue() - have;
            if (need > 0) queue.add(Map.entry(e.getKey(), need));
        }
        queue.sort(Comparator.comparing(e -> Util.englishName(e.getKey())));
        st = queue.isEmpty() ? St.DONE : St.RUNNING;
    }

    private int gap;

    public void reset() { gap = 0; st = St.IDLE; lines.clear(); queue.clear(); total = 0; current = null; }

    public void tick(MinecraftClient mc, AuctionBuyer buyer) {
        if (st != St.RUNNING || buyer.busy() || mc.currentScreen != null) return;
        if (gap > 0) { gap--; return; }

        if (current != null) { // vừa xong 1 món -> tính dòng
            addLine(current, queue.get(idx).getValue(), buyer);
            idx++;
            current = null;
            gap = Config.ahDelayTicks * 2;      // nghỉ giữa 2 món để không tra /ah quá dồn dập
            return;
        }
        if (idx >= queue.size()) { st = St.DONE; return; }
        current = queue.get(idx).getKey();
        buyer.startQuote(mc, current, queue.get(idx).getValue());
    }

    private void addLine(Item item, int need, AuctionBuyer buyer) {
        List<AuctionBuyer.Listing> all = new ArrayList<>(buyer.listings);
        Comparator<AuctionBuyer.Listing> byUnit = Comparator.comparingDouble(AuctionBuyer.Listing::unit);
        List<AuctionBuyer.Listing> ls = new ArrayList<>();
        if (Config.preferFullStack) {   // stack đầy rẻ nhất trước; thiếu thì mới lấy thêm lẻ (cũng rẻ nhất trước)
            int full = new net.minecraft.item.ItemStack(item).getMaxCount();
            List<AuctionBuyer.Listing> fulls = new ArrayList<>(), rest = new ArrayList<>();
            for (AuctionBuyer.Listing l : all) (l.count() >= full ? fulls : rest).add(l);
            fulls.sort(byUnit);
            rest.sort(byUnit);
            ls.addAll(fulls);
            ls.addAll(rest);
        } else {
            all.sort(byUnit);
            ls = all;
        }
        int got = 0;
        double cost = 0;
        for (AuctionBuyer.Listing l : ls) {
            if (got >= need) break;
            got += l.count();
            cost += l.price();
        }
        boolean enough = got >= need;
        double unit = got > 0 ? cost / got : 0;
        lines.add(new Line(item, need, got, cost, unit, enough));
        total += cost;
        totalItems += need;
        if (!enough) allEnough = false;
    }
}
