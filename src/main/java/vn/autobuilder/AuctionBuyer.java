package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Làm việc với chợ /ah theo luồng trong video:
 *  "/ah search <tên tiếng Anh>" -> menu "Tìm kiếm" -> (mua) bấm món rẻ nhất -> menu "Xác nhận mua" -> nút "Đồng ý".
 * Chế độ báo giá chỉ đọc danh sách và đóng menu, không mua gì.
 */
public final class AuctionBuyer {

    public enum Phase { IDLE, WAIT_RESULTS, WAIT_CONFIRM, WAIT_DONE }

    public record Listing(int count, double price) {
        public double unit() { return price / Math.max(1, count); }
    }

    private static final Pattern PRICE = Pattern.compile("(\\d[\\d.,]*)\\s*([kKmMbB]?)");

    private Phase phase = Phase.IDLE;
    private Item want;
    private int timer;
    private boolean lastOk;
    private boolean quote;
    private double pendingPrice;

    public final List<Listing> listings = new ArrayList<>(); // kết quả báo giá gần nhất
    public double spent;                                      // tổng đã chi (ước tính theo giá niêm yết)

    public boolean busy() { return phase != Phase.IDLE; }
    public boolean lastSucceeded() { return lastOk; }

    public boolean start(MinecraftClient mc, Item item) { return begin(mc, item, false); }
    public boolean startQuote(MinecraftClient mc, Item item) { return begin(mc, item, true); }

    private boolean begin(MinecraftClient mc, Item item, boolean q) {
        if (busy()) return false;
        want = item;
        quote = q;
        timer = 0;
        lastOk = false;
        listings.clear();
        phase = Phase.WAIT_RESULTS;
        String cmd = String.format(Config.ahCommand, Util.englishName(item));
        mc.player.networkHandler.sendChatCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        if (!q) Util.chat(mc, "Đang mua: §e" + Util.englishName(item) + "§r qua /ah");
        return true;
    }

    public void cancel(MinecraftClient mc) {
        if (busy() && mc.player != null && mc.currentScreen != null) mc.player.closeHandledScreen();
        phase = Phase.IDLE;
    }

    public void tick(MinecraftClient mc) {
        if (!busy() || mc.player == null) return;
        timer++;
        if (timer > (quote ? 120 : 240)) { fail(mc, "hết thời gian chờ menu /ah"); return; }

        if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
        GenericContainerScreenHandler h = gcs.getScreenHandler();
        String title = gcs.getTitle().getString().toLowerCase(Locale.ROOT);

        switch (phase) {
            case WAIT_RESULTS -> {
                if (title.contains(Config.confirmTitle)) { phase = Phase.WAIT_CONFIRM; timer = 0; return; }
                if (!title.contains(Config.resultTitle) || timer < 5) return;

                int bestSlot = -1;
                double bestUnit = Double.MAX_VALUE;
                double bestPrice = 0;
                for (int i = 0; i < h.getRows() * 9; i++) {
                    ItemStack s = h.getSlot(i).getStack();
                    if (s.isEmpty() || !s.isOf(want)) continue;
                    double price = parsePrice(s);
                    if (price < 0) continue;
                    if (quote) { listings.add(new Listing(s.getCount(), price)); continue; }
                    if (price > Config.maxPricePerListing) continue;
                    double unit = price / Math.max(1, s.getCount());
                    if (unit < bestUnit) { bestUnit = unit; bestSlot = i; bestPrice = price; }
                }
                if (quote) { // chỉ đọc giá rồi đóng
                    mc.player.closeHandledScreen();
                    lastOk = true;
                    phase = Phase.IDLE;
                    return;
                }
                if (bestSlot < 0) { fail(mc, "không có món phù hợp (hoặc quá giá)"); return; }

                pendingPrice = bestPrice;
                mc.interactionManager.clickSlot(h.syncId, bestSlot, 0, SlotActionType.PICKUP, mc.player);
                phase = Phase.WAIT_CONFIRM;
                timer = 0;
            }
            case WAIT_CONFIRM -> {
                if (!title.contains(Config.confirmTitle) || timer < 4) return;
                int btn = -1;
                for (int i = 0; i < h.getRows() * 9; i++) {
                    ItemStack s = h.getSlot(i).getStack();
                    if (s.isEmpty()) continue;
                    String name = s.getName().getString().toLowerCase(Locale.ROOT);
                    if (name.contains(Config.confirmButtonName) || name.contains("dong y")) { btn = i; break; }
                }
                if (btn < 0) {
                    for (int i = 0; i < h.getRows() * 9; i++) {
                        ItemStack s = h.getSlot(i).getStack();
                        if (s.isEmpty()) continue;
                        String id = Registries.ITEM.getId(s.getItem()).getPath();
                        if (id.startsWith("lime_") || id.startsWith("green_")) { btn = i; break; }
                    }
                }
                if (btn < 0) { fail(mc, "không thấy nút Đồng ý"); return; }
                mc.interactionManager.clickSlot(h.syncId, btn, 0, SlotActionType.PICKUP, mc.player);
                spent += pendingPrice;
                phase = Phase.WAIT_DONE;
                timer = 0;
            }
            case WAIT_DONE -> {
                if (timer >= 8) {
                    mc.player.closeHandledScreen();
                    lastOk = true;
                    phase = Phase.IDLE;
                }
            }
            default -> {}
        }
    }

    private void fail(MinecraftClient mc, String why) {
        if (!quote) Util.chat(mc, "§cMua thất bại: " + why);
        if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        lastOk = false;
        phase = Phase.IDLE;
    }

    /** Đọc giá từ lore, vd "Giá: $ 150K" -> 150000. Trả -1 nếu không đọc được. */
    private static double parsePrice(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            String t = line.getString();
            String low = t.toLowerCase(Locale.ROOT);
            if (!(low.contains("giá") || low.contains("gia") || low.contains("price") || t.contains("$"))) continue;
            int dollar = t.indexOf('$');
            Matcher m = PRICE.matcher(dollar >= 0 ? t.substring(dollar + 1) : t);
            if (m.find()) {
                try {
                    double v = Double.parseDouble(m.group(1).replace(",", ""));
                    return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                        case "k" -> v * 1_000;
                        case "m" -> v * 1_000_000;
                        case "b" -> v * 1_000_000_000;
                        default -> v;
                    };
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }
}
