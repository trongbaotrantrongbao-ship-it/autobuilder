package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.text.Normalizer;
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
    private int expectPage;     // đang chờ chuyển sang trang này (0 = không chờ)
    private int pagesVisited;

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
        expectPage = 0;
        pagesVisited = 1;
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
        if (timer > (quote ? 120 : 240) + Config.ahDelayTicks * 10) { fail(mc, "hết thời gian chờ menu /ah | " + describe(mc)); return; }

        if (!(mc.currentScreen instanceof GenericContainerScreen gcs)) return;
        GenericContainerScreenHandler h = gcs.getScreenHandler();
        String title = norm(gcs.getTitle().getString());

        switch (phase) {
            case WAIT_RESULTS -> {
                if (title.contains(norm(Config.confirmTitle))) { phase = Phase.WAIT_CONFIRM; timer = 0; return; }
                if (timer < 5 + Config.ahDelayTicks) return;
                // nhận diện menu kết quả theo tiêu đề, hoặc theo nội dung (có món cần tìm) nếu tiêu đề lạ
                if (!title.contains(norm(Config.resultTitle)) && !hasWanted(h)) return;

                int[] pg = page(title);                       // {trang hiện tại, tổng số trang}
                if (expectPage > 0 && pg != null && pg[0] != expectPage) { // chờ trang mới tải xong
                    if (timer < 40) return;
                    pagesVisited = Config.maxPages;           // bấm mà không chuyển trang -> thôi, dùng trang hiện tại
                }
                expectPage = 0;

                int fullSize = new ItemStack(want).getMaxCount();   // "stack đầy" (64, hoặc 16, 1...)
                int listingEnd = h.getRows() > 1 ? (h.getRows() - 1) * 9 : h.getRows() * 9; // bỏ hàng nút điều khiển
                int bestFull = -1, bestAny = -1;
                double uFull = Double.MAX_VALUE, uAny = Double.MAX_VALUE, pFull = 0, pAny = 0;
                boolean pageHasFull = false;

                for (int i = 0; i < listingEnd; i++) {
                    ItemStack st = h.getSlot(i).getStack();
                    if (st.isEmpty() || !st.isOf(want)) continue;
                    double price = parsePrice(st);
                    if (price < 0) continue;
                    boolean full = !Config.preferFullStack || st.getCount() >= fullSize;
                    if (quote) {
                        listings.add(new Listing(st.getCount(), price));
                        if (full) pageHasFull = true;
                        continue;
                    }
                    if (price > Config.maxPricePerListing) continue;
                    double unit = price / Math.max(1, st.getCount());
                    if (unit < uAny) { uAny = unit; bestAny = i; pAny = price; }
                    if (full && unit < uFull) { uFull = unit; bestFull = i; pFull = price; }
                }

                boolean canNext = canNextPage(h, pg);
                if (quote) {
                    if (!pageHasFull && canNext) { clickNext(mc, h, pg); return; }
                    mc.player.closeHandledScreen();
                    lastOk = true;
                    phase = Phase.IDLE;
                    return;
                }

                int pick; double pickPrice;
                if (bestFull >= 0) { pick = bestFull; pickPrice = pFull; }
                else if (canNext) { clickNext(mc, h, pg); return; }   // chưa thấy stack đầy -> sang trang sau
                else if (bestAny >= 0) { pick = bestAny; pickPrice = pAny; }
                else { fail(mc, "không có món phù hợp (hoặc quá giá)"); return; }

                pendingPrice = pickPrice;
                mc.interactionManager.clickSlot(h.syncId, pick, 0, SlotActionType.PICKUP, mc.player);
                phase = Phase.WAIT_CONFIRM;
                timer = 0;
            }
            case WAIT_CONFIRM -> {
                if (timer < 4 + Config.ahDelayTicks) return;
                if (!title.contains(norm(Config.confirmTitle)) && findConfirm(h, false) < 0) return;
                int btn = findConfirm(h, true);
                if (btn < 0) { fail(mc, "không thấy nút Đồng ý"); return; }
                mc.interactionManager.clickSlot(h.syncId, btn, 0, SlotActionType.PICKUP, mc.player);
                spent += pendingPrice;
                phase = Phase.WAIT_DONE;
                timer = 0;
            }
            case WAIT_DONE -> {
                if (timer >= 8 + Config.ahDelayTicks) {
                    mc.player.closeHandledScreen();
                    lastOk = true;
                    phase = Phase.IDLE;
                }
            }
            default -> {}
        }
    }



    /** Bỏ dấu, bỏ mã màu, chữ nhỏ-hoa (ᴛɪᴍ) -> chữ thường không dấu, để so khớp bền với mọi kiểu gõ của server. */
    static String norm(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : in.toCharArray()) {
            int k = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘꞯʀꜱᴛᴜᴠᴡxʏᴢ".indexOf(ch);
            sb.append(k >= 0 ? (char) ('a' + k) : ch);
        }
        String n = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replaceAll("§.", "").toLowerCase(Locale.ROOT).replace('đ', 'd');
        return n;
    }

    private boolean hasWanted(GenericContainerScreenHandler h) {
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty() && st.isOf(want) && parsePrice(st) >= 0) return true;
        }
        return false;
    }

    /** Tìm nút "Đồng ý" theo tên; dự phòng ô xanh lá nếu allowColor. */
    private int findConfirm(GenericContainerScreenHandler h, boolean allowColor) {
        String target = norm(Config.confirmButtonName);
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (norm(st.getName().getString()).contains(target)) return i;
        }
        if (allowColor) {
            for (int i = 0; i < h.getRows() * 9; i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (st.isEmpty()) continue;
                String id = Registries.ITEM.getId(st.getItem()).getPath();
                if (id.startsWith("lime_") || id.startsWith("green_")) return i;
            }
        }
        return -1;
    }

    /** Mô tả menu đang mở để chẩn đoán khi lỗi. */
    private String describe(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof GenericContainerScreen g)) {
            return "không có menu rương nào đang mở (màn hình: "
                    + (mc.currentScreen == null ? "không" : mc.currentScreen.getClass().getSimpleName()) + ")";
        }
        GenericContainerScreenHandler h = g.getScreenHandler();
        int match = 0, priced = 0;
        String lore = "";
        for (int i = 0; i < h.getRows() * 9; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty() || !st.isOf(want)) continue;
            match++;
            if (parsePrice(st) >= 0) priced++;
            else if (lore.isEmpty()) {
                LoreComponent l = st.get(DataComponentTypes.LORE);
                lore = l == null ? "(không có lore)" : l.lines().stream().limit(4).map(Text::getString).reduce((a, b2) -> a + " / " + b2).orElse("");
            }
        }
        return "tiêu đề='" + g.getTitle().getString() + "' hàng=" + h.getRows() + " khớp=" + match
                + " đọc được giá=" + priced + (lore.isEmpty() ? "" : " lore mẫu=[" + lore + "]");
    }

    /** Đọc "(1/8)" trong tiêu đề -> {1, 8}. null nếu không có. */
    private static int[] page(String title) {
        Matcher m = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)").matcher(title);
        if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        return null;
    }

    /** Mũi tên "trang sau" nằm ở ô góc dưới bên phải của menu. */
    private boolean canNextPage(GenericContainerScreenHandler h, int[] pg) {
        if (pagesVisited >= Config.maxPages) return false;
        if (pg != null && pg[0] >= pg[1]) return false;
        return !h.getSlot(h.getRows() * 9 - 1).getStack().isEmpty();
    }

    private void clickNext(MinecraftClient mc, GenericContainerScreenHandler h, int[] pg) {
        mc.interactionManager.clickSlot(h.syncId, h.getRows() * 9 - 1, 0, SlotActionType.PICKUP, mc.player);
        expectPage = pg != null ? pg[0] + 1 : 0;
        pagesVisited++;
        timer = 0;
    }

    private void fail(MinecraftClient mc, String why) {
        if (!quote) Util.chat(mc, "§cMua thất bại: " + why);
        if (mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
        lastOk = false;
        phase = Phase.IDLE;
    }

    /** Đọc giá từ đúng nội dung tooltip mà game hiện khi rê chuột vào món (lore + mọi dòng chú thích). */
    private static double parsePrice(ItemStack s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<String> lines = new ArrayList<>();
        try {
            for (Text t : Screen.getTooltipFromItem(mc, s)) lines.add(t.getString());
        } catch (Throwable ignored) {}
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore != null) for (Text t : lore.lines()) lines.add(t.getString());

        for (String t : lines) {
            String low = norm(t);
            if (!(low.contains("gia") || low.contains("price") || t.contains("$"))) continue;
            double v = firstMoney(t);
            if (v >= 0) return v;
        }
        // dự phòng: giá nằm ở thành phần dữ liệu khác của vật phẩm
        try {
            Matcher m = Pattern.compile("\\$\\s*(\\d[\\d.,]*)\\s*([kKmMbB]?)").matcher(s.getComponents().toString());
            if (m.find()) return money(m.group(1), m.group(2));
        } catch (Throwable ignored) {}
        return -1;
    }

    private static double firstMoney(String t) {
        int dollar = t.indexOf('$');
        Matcher m = PRICE.matcher(dollar >= 0 ? t.substring(dollar + 1) : t);
        if (m.find()) return money(m.group(1), m.group(2));
        return -1;
    }

    private static double money(String num, String suffix) {
        try {
            double v = Double.parseDouble(num.replace(",", ""));
            return switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> v * 1_000;
                case "m" -> v * 1_000_000;
                case "b" -> v * 1_000_000_000;
                default -> v;
            };
        } catch (NumberFormatException e) { return -1; }
    }
}
