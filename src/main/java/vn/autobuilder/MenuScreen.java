package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Menu AutoBuilder – mở bằng Shift phải. */
public class MenuScreen extends Screen {

    // ---- bảng màu
    private static final int BG        = 0xF0101319;
    private static final int PANEL     = 0xFF171B23;
    private static final int PANEL_HI  = 0xFF1F2530;
    private static final int BORDER    = 0xFF2A3140;
    private static final int TEXT      = 0xFFE6EAF2;
    private static final int MUTED     = 0xFF8B94A7;
    private static final int ACCENT    = 0xFF4C8DFF;
    private static final int ACCENT_HI = 0xFF6FA3FF;
    private static final int GREEN     = 0xFF2FD07A;
    private static final int RED       = 0xFFFF5C5C;
    private static final int AMBER     = 0xFFFFB84D;

    private static final String[] TABS = {"Điều khiển", "Tốc độ", "Kho rương", "Tùy chọn", "Phạm vi", "Công trình 3D", "Báo giá"};

    private static final class Hit {
        int x, y, w, h;
        Runnable click;
        DoubleConsumer drag; // nhận tỉ lệ 0..1 theo chiều ngang
        boolean canvas;
        boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private Hit active;
    private boolean dragCanvas;
    private int tab = 0;
    private int quoteScroll = 0;
    private int frame = 0, lastVersion = -1, lastCut = -1;
    private int cutRel = 100000;
    private String toast = "";
    private int toastTicks = 0;

    private int mouseX, mouseY;

    public MenuScreen() { super(Text.literal("AutoBuilder")); }

    @Override public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        Builder b = AutoBuilderMod.BUILDER;
        if (b.scanner.st == Scanner.St.IDLE && b.scanner.version == 0) b.requestScan(MinecraftClient.getInstance());
    }

    @Override
    public void removed() { Config.save(); }

    // ================================================================= vẽ

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        hits.clear();
        if (toastTicks > 0) toastTicks--;

        int pw = Math.min(width - 10, 430), ph = Math.min(height - 10, 262);
        int px = (width - pw) / 2, py = (height - ph) / 2;

        c.fill(0, 0, width, height, 0x55000000);
        c.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, BORDER);
        c.fill(px, py, px + pw, py + ph, BG);

        // header
        c.fillGradient(px, py, px + pw, py + 24, 0xFF1B2433, 0xFF141A24);
        c.fill(px, py + 24, px + pw, py + 25, ACCENT);
        c.drawTextWithShadow(textRenderer, "AutoBuilder", px + 10, py + 8, TEXT);
        c.drawTextWithShadow(textRenderer, "v" + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("autobuilder").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""), px + 10 + textRenderer.getWidth("AutoBuilder ") + 2, py + 8, MUTED);

        Builder b = AutoBuilderMod.BUILDER;
        String st; int stc;
        if (b.isEnabled()) { st = "ĐANG XÂY"; stc = GREEN; }
        else if (b.scanner.st == Scanner.St.RUNNING) { st = "ĐANG QUÉT " + (int) (b.scanner.progress() * 100) + "%"; stc = AMBER; }
        else { st = "ĐÃ DỪNG"; stc = MUTED; }
        int sw = textRenderer.getWidth(st);
        c.drawTextWithShadow(textRenderer, st, px + pw - sw - 28, py + 8, stc);
        button(c, px + pw - 20, py + 5, 14, 14, "x", RED, true, this::close);

        // sidebar
        int sbw = pw < 340 ? 70 : 92;
        c.fill(px, py + 25, px + sbw, py + ph, PANEL);
        c.fill(px + sbw, py + 25, px + sbw + 1, py + ph, BORDER);
        for (int i = 0; i < TABS.length; i++) {
            int ty = py + 30 + i * 22;
            boolean sel = tab == i;
            Hit h = hit(px + 5, ty, sbw - 10, 19);
            final int idx = i;
            h.click = () -> { tab = idx; if (idx == 5) lastVersion = -1; };
            boolean hov = h.in(mx, my);
            c.fill(h.x, h.y, h.x + h.w, h.y + h.h, sel ? ACCENT : (hov ? PANEL_HI : PANEL));
            if (sel) c.fill(h.x, h.y, h.x + 2, h.y + h.h, 0xFFFFFFFF);
            c.drawTextWithShadow(textRenderer, TABS[i], h.x + 8, h.y + 6, sel ? 0xFFFFFFFF : TEXT);
        }

        int cx = px + sbw + 10, cy = py + 33, cw = pw - sbw - 20, ch = ph - 40;
        switch (tab) {
            case 0 -> tabControl(c, cx, cy, cw, ch);
            case 1 -> tabSpeed(c, cx, cy, cw, ch);
            case 2 -> tabChests(c, cx, cy, cw, ch);
            case 3 -> tabOptions(c, cx, cy, cw, ch);
            case 4 -> tabRange(c, cx, cy, cw, ch);
            case 5 -> tabPreview(c, cx, cy, cw, ch);
            default -> tabQuote(c, cx, cy, cw, ch);
        }

        if (toastTicks > 0) {
            int tw = textRenderer.getWidth(toast) + 12;
            int tx = px + (pw - tw) / 2, ty = py + ph - 18;
            c.fill(tx, ty, tx + tw, ty + 14, 0xF0000000);
            c.drawTextWithShadow(textRenderer, toast, tx + 6, ty + 3, AMBER);
        }
    }

    // ----------------------------------------------------------------- tab 0
    private void tabControl(DrawContext c, int x, int y, int w, int h) {
        Builder b = AutoBuilderMod.BUILDER;
        MinecraftClient mc = MinecraftClient.getInstance();

        boolean on = b.isEnabled();
        if (on) {
            button(c, x, y, w, 22, "DỪNG XÂY", RED, true, () -> b.setEnabled(mc, false));
        } else {
            int bw2 = (w - 8) / 2;
            button(c, x, y, bw2, 22, "Xây (đã báo giá)", GREEN, true, () -> {
                String err = b.tryStart(mc, false);
                if (err != null) toast(err); else close();
            });
            button(c, x + bw2 + 8, y, bw2, 22, "Xây bằng đồ có sẵn", ACCENT, true, () -> {
                String err = b.startWithInventory(mc);
                if (err != null) toast(err); else close();
            });
        }
        y += 28;

        button(c, x, y, w, 16, "Góc đặt block: " + Planner.Approach.NAMES[Math.floorMod(Config.approachMode, Planner.Approach.NAMES.length)]
                + "  (bấm để đổi)", PANEL_HI, true,
                () -> Config.approachMode = (Config.approachMode + 1) % Planner.Approach.NAMES.length);
        y += 22;

        int col = w >= 250 ? (w - 8) / 2 : w;
        int x2 = w >= 250 ? x + col + 8 : x;
        toggle(c, x, y, col, "Xoay đầu theo hướng đặt", Config.rotateHead, v -> Config.rotateHead = v);
        if (w >= 250) toggle(c, x2, y, col, "Tự di chuyển", Config.autoMove, v -> Config.autoMove = v);
        y += 18;
        if (w < 250) { toggle(c, x, y, col, "Tự di chuyển", Config.autoMove, v -> Config.autoMove = v); y += 18; }
        toggle(c, x, y, col, "Tự mua đồ qua /ah", Config.autoBuy, v -> Config.autoBuy = v);
        if (w >= 250) toggle(c, x2, y, col, "Hiện khung phạm vi", Config.showFrame, v -> Config.showFrame = v);
        y += 18;
        if (w < 250) { toggle(c, x, y, col, "Hiện khung phạm vi", Config.showFrame, v -> Config.showFrame = v); y += 18; }
        y += 6;

        if (b.hasLayer()) { line(c, x, y, "Đang xây tầng Y=" + b.layerY() + "  (còn " + b.layerLeft() + " block trong tầng)", GREEN); y += 12; }
        if (b.isEnabled() || b.placed() > 0) { line(c, x, y, "Tiến độ: " + b.percent() + "%   ·   đã đặt " + b.placed() + " block", GREEN); y += 12; }
        line(c, x, y, "Còn lại: " + b.remaining() + " vị trí   Bỏ qua: " + b.skipped(), TEXT); y += 12;
        line(c, x, y, "Đã chi: " + Util.money(b.spentSinceStart()) + (b.isApproved() ? " / ngân sách " + Util.money(b.budgetCap()) : ""), MUTED);
    }

    // ----------------------------------------------------------------- tab Kho rương
    private void tabChests(DrawContext c, int x, int y, int w, int h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ChestManager cm = AutoBuilderMod.CHESTS;
        boolean busy = cm.busy();
        int bw = (w - 16) / 3;
        button(c, x, y, bw, 20, busy ? "Đang chạy..." : "Quét rương kho", ACCENT, !busy, () -> { cm.startScan(mc); close(); });
        button(c, x + bw + 8, y, bw, 20, "Dừng", RED, busy, () -> cm.cancel(mc));
        button(c, x + 2 * (bw + 8), y, bw, 20, "Xóa nhớ", AMBER, !busy, () -> { cm.clear(mc); toast("Đã xóa nhớ rương"); });
        y += 26;
        toggle(c, x, y, w, "Lấy đồ từ rương trước khi mua", Config.useChests, v -> Config.useChests = v);
        y += 20;
        slider(c, x, y, w, "Bán kính tìm rương", 8, 128, Config.chestRadius, Config.chestRadius + " block",
                v -> Config.chestRadius = (int) Math.round(v));
        y += 24;
        slider(c, x, y, w, "Nhịp mở / lấy đồ", 1, 10, Config.chestDelayTicks, Config.chestDelayTicks + " tick",
                v -> Config.chestDelayTicks = (int) Math.round(v));
        y += 26;
        if (mc.world != null) {
            int[] k = cm.counts(mc);
            line(c, x, y, "Rương nhớ: " + k[0] + "  ·  đã quét: " + k[1] + "  ·  hết đồ: " + k[2] + "  ·  không mở được: " + k[3], TEXT);
            y += 13;
            for (var e : cm.topItems(mc, 7)) {
                line(c, x, y, Util.englishName(e.getKey()) + " ×" + e.getValue(), MUTED);
                y += 11;
            }
        }
    }

    // ----------------------------------------------------------------- tab Tùy chọn
    private void tabOptions(DrawContext c, int x, int y, int w, int h) {
        toggle(c, x, y, w, "Thông báo của mod trong chat", Config.chatMessages, v -> Config.chatMessages = v); y += 20;
        toggle(c, x, y, w, "Dòng trạng thái trên hotbar", Config.actionBar, v -> Config.actionBar = v); y += 20;
        toggle(c, x, y, w, "Báo tiến độ trong chat mỗi 10%", Config.progressChat, v -> Config.progressChat = v); y += 20;
        toggle(c, x, y, w, "Sửa block sai: đào bỏ rồi đặt lại", Config.fixWrong, v -> Config.fixWrong = v); y += 20;
        toggle(c, x, y, w, "Viền đỏ quanh block sai", Config.showWrong, v -> Config.showWrong = v); y += 20;
        toggle(c, x, y, w, "Giàn giáo bằng kính (nếu có)", Config.scaffoldGlass, v -> Config.scaffoldGlass = v); y += 24;
        line(c, x, y, "Tắt thông báo chat vẫn giữ dòng trạng thái và tiến độ trong menu.", MUTED);
    }

    // ----------------------------------------------------------------- tab Tốc độ
    private void tabSpeed(DrawContext c, int x, int y, int w, int h) {
        int bw = (w - 16) / 3;
        button(c, x, y, bw, 18, "Như video", GREEN, true, () -> {
            Config.fastMode = true; Config.placePerTick = 3; Config.fastDelayTicks = 0;
            Config.adjustDelayTicks = 1; Config.aimSettleTicks = 0; toast("Đã chọn: như video (nhanh)");
        });
        button(c, x + bw + 8, y, bw, 18, "Vừa", ACCENT, true, () -> {
            Config.fastMode = false; Config.delayTicks = 2; Config.adjustDelayTicks = 3;
            Config.aimSpeed = 40; Config.aimSettleTicks = 1; toast("Đã chọn: vừa");
        });
        button(c, x + 2 * (bw + 8), y, bw, 18, "Chậm / an toàn", AMBER, true, () -> {
            Config.fastMode = false; Config.delayTicks = 6; Config.adjustDelayTicks = 6;
            Config.aimSpeed = 20; Config.aimSettleTicks = 3; toast("Đã chọn: chậm, an toàn");
        });
        y += 24;
        toggle(c, x, y, w, "Đặt nhanh (nhiều block/tick, không chờ xoay)", Config.fastMode, v -> Config.fastMode = v);
        y += 20;
        if (Config.fastMode) {
            slider(c, x, y, w, "Số block mỗi tick", 1, 8, Config.placePerTick, Config.placePerTick + " block/tick  (~" + (Config.placePerTick * 20) + "/s)",
                    v -> Config.placePerTick = (int) Math.round(v));
            y += 24;
            slider(c, x, y, w, "Nghỉ giữa các đợt", 0, 10, Config.fastDelayTicks, Config.fastDelayTicks + " tick",
                    v -> Config.fastDelayTicks = (int) Math.round(v));
            y += 24;
        } else {
            slider(c, x, y, w, "Delay giữa các khối", 0, 20, Config.delayTicks,
                    Config.delayTicks + " tick  (" + String.format("%.2f", Config.delayTicks * 0.05) + "s)",
                    v -> Config.delayTicks = (int) Math.round(v));
            y += 24;
            slider(c, x, y, w, "Tốc độ xoay đầu", 5, 90, Config.aimSpeed, Config.aimSpeed + " độ/tick",
                    v -> Config.aimSpeed = (int) Math.round(v));
            y += 24;
        }
        slider(c, x, y, w, "Bán kính đặt (hình cầu, tối đa 4)", 2.5, 4.0, Config.reach, String.format("%.1f block", Config.reach),
                v -> Config.reach = Math.round(v * 10) / 10.0);
        y += 24;
        slider(c, x, y, w, "Delay khi chỉnh (repeater...)", 0, 20, Config.adjustDelayTicks,
                Config.adjustDelayTicks + " tick", v -> Config.adjustDelayTicks = (int) Math.round(v));
        y += 24;
        slider(c, x, y, w, "Tốc độ quét", 2, 45, Config.scanMsPerTick, Config.scanMsPerTick + " ms/tick",
                v -> Config.scanMsPerTick = (int) Math.round(v));
        y += 24;
        slider(c, x, y, w, "Độ trễ xem /ah", 0, 40, Config.ahDelayTicks, Config.ahDelayTicks + " tick",
                v -> Config.ahDelayTicks = (int) Math.round(v));
    }

    // ----------------------------------------------------------------- tab 1
    private void tabRange(DrawContext c, int x, int y, int w, int h) {
        Builder b = AutoBuilderMod.BUILDER;
        MinecraftClient mc = MinecraftClient.getInstance();

        slider(c, x, y, w, "Bán kính (X/Z)", 4, 96, Config.scanRadius, Config.scanRadius + " block  (" + (Config.scanRadius * 2 + 1) + " x " + (Config.scanRadius * 2 + 1) + ")",
                v -> { Config.scanRadius = (int) Math.round(v); b.requestScan(mc); });
        y += 24;
        slider(c, x, y, w, "Quét lên trên", 0, 96, Config.scanUp, Config.scanUp + " block",
                v -> { Config.scanUp = (int) Math.round(v); });
        y += 24;
        slider(c, x, y, w, "Quét xuống dưới", 0, 96, Config.scanDown, Config.scanDown + " block",
                v -> { Config.scanDown = (int) Math.round(v); });
        y += 28;

        toggle(c, x, y, w, "Hiện khung phạm vi trong thế giới", Config.showFrame, v -> Config.showFrame = v);
        y += 18;
        toggle(c, x, y, w, Config.lockAnchor ? "Tâm vùng: ĐÃ KHÓA (" + Config.anchorX + ", " + Config.anchorY + ", " + Config.anchorZ + ")" : "Tâm vùng: theo người chơi",
                Config.lockAnchor, v -> { if (v) Region.lockHere(mc); else Config.lockAnchor = false; b.requestScan(mc); });
        y += 22;

        int bw = (w - 8) / 2;
        button(c, x, y, bw, 18, "Khóa tâm tại đây", ACCENT, true, () -> { Region.lockHere(mc); b.requestScan(mc); toast("Đã khóa tâm vùng quét"); });
        button(c, x + bw + 8, y, bw, 18, "Quét lại", ACCENT, true, () -> { b.requestScan(mc); toast("Đang quét..."); });
        y += 26;

        int[] bd = Region.bounds(mc);
        line(c, x, y, "Vùng: " + (bd[3] - bd[0] + 1) + " x " + (bd[4] - bd[1] + 1) + " x " + (bd[5] - bd[2] + 1)
                + "   Y " + bd[1] + " → " + bd[4], TEXT); y += 12;
        if (b.scanner.st == Scanner.St.RUNNING)
            line(c, x, y, "Đang quét " + (int) (b.scanner.progress() * 100) + "%", AMBER);
        else if (b.scanner.snap != null)
            line(c, x, y, "Có " + b.scanner.snap.total + " block ảo, " + b.scanner.found.size() + " cần xây", MUTED);
    }

    // ----------------------------------------------------------------- tab 2
    private final Preview3D pv = AutoBuilderMod.PREVIEW;

    private void tabPreview(DrawContext c, int x, int y, int w, int h) {
        Builder b = AutoBuilderMod.BUILDER;
        MinecraftClient mc = MinecraftClient.getInstance();
        Snapshot s = b.scanner.snap;

        int side = 118;
        int canvas = Math.max(80, Math.min(h - 4, w - side - 8));
        int cell = canvas >= 170 ? 2 : 1;
        int res = canvas / cell;
        canvas = res * cell;

        // khung nền
        c.fill(x - 1, y - 1, x + canvas + 1, y + canvas + 1, BORDER);
        c.fillGradient(x, y, x + canvas, y + canvas, 0xFF0C1018, 0xFF1A2232);

        Hit ch = hit(x, y, canvas, canvas);
        ch.canvas = true;

        // dựng lại ảnh khi cần
        frame++;
        boolean spin = Config.previewSpin && !dragCanvas && s != null;
        if (spin && frame % 2 == 0) { pv.yaw += 0.03f; pv.dirty = true; }
        if (b.scanner.version != lastVersion || cutRel != lastCut) { pv.dirty = true; lastVersion = b.scanner.version; lastCut = cutRel; }
        if (pv.dirty || pv.n != res) pv.render(s, res, cutRel);

        for (int i = 0; i < pv.spanN; i++) {
            int rowY = y + pv.spanRow[i] * cell;
            c.fill(x + pv.spanX0[i] * cell, rowY, x + pv.spanX1[i] * cell, rowY + cell, 0xFF000000 | pv.spanCol[i]);
        }

        if (s == null || s.n == 0) {
            String t = b.scanner.st == Scanner.St.RUNNING ? "Đang quét..." : "Chưa có dữ liệu";
            c.drawCenteredTextWithShadow(textRenderer, t, x + canvas / 2, y + canvas / 2 - 8, MUTED);
            String t2 = "Nạp schematic trong Litematica rồi bấm Quét lại";
            if (textRenderer.getWidth(t2) < canvas) c.drawCenteredTextWithShadow(textRenderer, t2, x + canvas / 2, y + canvas / 2 + 4, MUTED);
        }

        // cột điều khiển
        int sx = x + canvas + 8, sw = w - canvas - 8, sy = y;
        if (sw < 60) return;
        line(c, sx, sy, "Kéo chuột: xoay", MUTED); sy += 11;
        line(c, sx, sy, "Lăn chuột: zoom", MUTED); sy += 14;
        toggle(c, sx, sy, sw, "Tự xoay", Config.previewSpin, v -> Config.previewSpin = v); sy += 20;

        int maxY = s == null ? 1 : Math.max(1, s.sy);
        int shown = Math.min(cutRel + 1, maxY);
        slider(c, sx, sy, sw, "Cắt tầng", 0, maxY, shown, shown + "/" + maxY, v -> { cutRel = (int) Math.round(v) - 1; if (cutRel < 0) cutRel = 0; if (v >= maxY) cutRel = 100000; });
        sy += 26;
        button(c, sx, sy, sw, 16, "Quét lại", ACCENT, true, () -> { b.requestScan(mc); toast("Đang quét..."); }); sy += 22;
        if (s != null) {
            line(c, sx, sy, "Kích thước:", MUTED); sy += 10;
            line(c, sx, sy, s.sx + " x " + s.sy + " x " + s.sz, TEXT); sy += 12;
            line(c, sx, sy, s.total + " block", TEXT); sy += 11;
            if (s.truncated) line(c, sx, sy, "(xem trước đã giản lược)", AMBER);
        }
    }

    // ----------------------------------------------------------------- tab 3
    private void tabQuote(DrawContext c, int x, int y, int w, int h) {
        Builder b = AutoBuilderMod.BUILDER;
        MinecraftClient mc = MinecraftClient.getInstance();
        Quoter q = b.quoter;

        int bw = (w - 16) / 3;
        boolean running = q.st == Quoter.St.RUNNING || b.scanner.st == Scanner.St.RUNNING;
        button(c, x, y, bw, 20, running ? "Đang kiểm tra..." : "Kiểm tra giá", ACCENT, !running && !b.isEnabled(), () -> {
            quoteScroll = 0;
            b.requestQuote(mc);
        });
        boolean canGo = q.st == Quoter.St.DONE && !b.isEnabled();
        button(c, x + bw + 8, y, bw, 20, "Đồng ý & xây", GREEN, canGo, () -> {
            String err = b.tryStart(mc, true);
            if (err != null) toast(err); else close();
        });
        button(c, x + 2 * (bw + 8), y, bw, 20, "Bỏ qua mua", AMBER, !b.isEnabled() && !running, () -> {
            String err = b.startWithInventory(mc);
            if (err != null) toast(err); else close();
        });
        y += 26;
        slider(c, x, y, w, "Tốc độ xem /ah (độ trễ mỗi bước)", 0, 40, Config.ahDelayTicks,
                Config.ahDelayTicks + " tick  (" + String.format("%.2f", Config.ahDelayTicks * 0.05) + "s)",
                v -> Config.ahDelayTicks = (int) Math.round(v));
        y += 26;

        if (q.st == Quoter.St.IDLE) {
            line(c, x, y, "Mod sẽ đếm tất cả vật phẩm cần cho công trình,", MUTED); y += 11;
            line(c, x, y, "trừ đồ bạn đang có, rồi tra giá từng món ở /ah.", MUTED); y += 11;
            line(c, x, y, "Sau khi bạn bấm Đồng ý, mod tự mua khi xây.", MUTED); y += 11;
            line(c, x, y, "\"Bỏ qua mua\": xây ngay bằng đồ có sẵn trong kho.", MUTED);
            return;
        }
        if (q.st == Quoter.St.RUNNING) {
            line(c, x, y, "Đang tra: " + q.currentName() + "  (" + q.progressDone() + "/" + q.progressTotal() + ")", AMBER); y += 12;
        }

        // bảng
        int rowH = 12;
        int footer = 30;
        int listH = Math.max(rowH, h - (y - (h > 0 ? 0 : 0)) );
        int top = y;
        int visible = Math.max(1, (h - 52 - 12 - footer) / rowH);
        c.fill(x, top, x + w, top + 12, PANEL_HI);
        c.drawTextWithShadow(textRenderer, "Vật phẩm", x + 4, top + 2, MUTED);
        String c1 = "Cần", c2 = "Giá";
        c.drawTextWithShadow(textRenderer, c1, x + w - 110, top + 2, MUTED);
        c.drawTextWithShadow(textRenderer, c2, x + w - 4 - textRenderer.getWidth(c2), top + 2, MUTED);
        top += 13;

        int n = q.lines.size();
        quoteScroll = Math.max(0, Math.min(quoteScroll, Math.max(0, n - visible)));
        for (int i = 0; i < visible && i + quoteScroll < n; i++) {
            Quoter.Line l = q.lines.get(i + quoteScroll);
            int ry = top + i * rowH;
            if (i % 2 == 0) c.fill(x, ry, x + w, ry + rowH, 0x22FFFFFF);
            String nm = Util.englishName(l.item());
            int maxw = w - 130;
            while (textRenderer.getWidth(nm) > maxw && nm.length() > 3) nm = nm.substring(0, nm.length() - 1);
            c.drawTextWithShadow(textRenderer, nm, x + 4, ry + 2, l.enough() ? TEXT : RED);
            c.drawTextWithShadow(textRenderer, "x" + l.needed(), x + w - 110, ry + 2, MUTED);
            String pr = l.enough() ? Util.money(l.cost()) : (l.available() == 0 ? "hết hàng" : "thiếu hàng");
            c.drawTextWithShadow(textRenderer, pr, x + w - 4 - textRenderer.getWidth(pr), ry + 2, l.enough() ? AMBER : RED);
        }
        if (n > visible) {
            int bx = x + w - 3, bh = visible * rowH;
            c.fill(bx, top, bx + 2, top + bh, BORDER);
            int th = Math.max(8, bh * visible / n);
            int ty = top + (bh - th) * quoteScroll / Math.max(1, n - visible);
            c.fill(bx, ty, bx + 2, ty + th, ACCENT);
        }

        // tổng
        int fy = top + visible * rowH + 4;
        c.fill(x, fy, x + w, fy + 1, BORDER);
        fy += 4;
        String tot = "TỔNG: " + Util.money(q.total) + "  (" + q.totalItems + " món cần mua)";
        c.drawTextWithShadow(textRenderer, tot, x + 2, fy, q.st == Quoter.St.DONE ? GREEN : MUTED);
        if (q.st == Quoter.St.DONE && !q.allEnough)
            c.drawTextWithShadow(textRenderer, "Có món không đủ hàng ở /ah", x + 2, fy + 11, RED);
        else if (q.st == Quoter.St.DONE && q.lines.isEmpty())
            c.drawTextWithShadow(textRenderer, "Bạn đã đủ đồ – không cần mua gì", x + 2, fy + 11, GREEN);
    }

    // ================================================================= widget

    private Hit hit(int x, int y, int w, int h) {
        Hit hh = new Hit();
        hh.x = x; hh.y = y; hh.w = w; hh.h = h;
        hits.add(hh);
        return hh;
    }

    private void line(DrawContext c, int x, int y, String s, int color) {
        c.drawTextWithShadow(textRenderer, s, x, y, color);
    }

    private void button(DrawContext c, int x, int y, int w, int h, String label, int color, boolean enabled, Runnable r) {
        Hit hh = hit(x, y, w, h);
        boolean hov = enabled && hh.in(mouseX, mouseY);
        if (enabled) hh.click = r;
        int base = enabled ? color : 0xFF3A404D;
        c.fill(x, y, x + w, y + h, hov ? lighten(base) : base);
        c.fill(x, y + h - 1, x + w, y + h, 0x55000000);
        int tw = textRenderer.getWidth(label);
        c.drawTextWithShadow(textRenderer, label, x + (w - tw) / 2, y + (h - 8) / 2, enabled ? 0xFFFFFFFF : MUTED);
    }

    private void toggle(DrawContext c, int x, int y, int w, String label, boolean val, Consumer<Boolean> set) {
        Hit hh = hit(x, y, w, 16);
        hh.click = () -> set.accept(!val);
        boolean hov = hh.in(mouseX, mouseY);
        c.fill(x, y, x + w, y + 16, hov ? PANEL_HI : PANEL);
        // công tắc
        int sx = x + w - 26, sy = y + 3;
        c.fill(sx, sy, sx + 22, sy + 10, val ? GREEN : 0xFF3A404D);
        int kx = val ? sx + 13 : sx + 1;
        c.fill(kx, sy + 1, kx + 8, sy + 9, 0xFFFFFFFF);
        String t = label;
        int maxw = w - 34;
        while (textRenderer.getWidth(t) > maxw && t.length() > 3) t = t.substring(0, t.length() - 1);
        c.drawTextWithShadow(textRenderer, t, x + 5, y + 4, TEXT);
    }

    private void slider(DrawContext c, int x, int y, int w, String label, double min, double max, double val,
                        String valText, DoubleConsumer set) {
        Hit hh = hit(x, y + 10, w, 10);
        hh.drag = t -> set.accept(min + (max - min) * Math.max(0, Math.min(1, t)));
        c.drawTextWithShadow(textRenderer, label, x, y, TEXT);
        int vw = textRenderer.getWidth(valText);
        c.drawTextWithShadow(textRenderer, valText, x + w - vw, y, ACCENT_HI);
        int by = y + 13;
        c.fill(x, by, x + w, by + 4, 0xFF2A3140);
        double t = (val - min) / (max - min);
        int fx = x + (int) (w * Math.max(0, Math.min(1, t)));
        c.fill(x, by, fx, by + 4, ACCENT);
        c.fill(fx - 2, by - 2, fx + 2, by + 6, 0xFFFFFFFF);
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 24), g = Math.min(255, ((c >> 8) & 255) + 24), b = Math.min(255, (c & 255) + 24);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void toast(String s) { toast = s; toastTicks = 120; }

    // ================================================================= nhập liệu

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.in(click.x(), click.y())) continue;
            if (h.canvas) { dragCanvas = true; return true; }
            if (h.drag != null) { active = h; h.drag.accept((click.x() - h.x) / (double) h.w); return true; }
            if (h.click != null) { h.click.run(); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (active != null) { active.drag.accept((click.x() - active.x) / (double) active.w); return true; }
        if (dragCanvas) {
            pv.yaw += (float) dx * 0.02f;
            pv.pitch = Math.max(0.05f, Math.min(1.5f, pv.pitch + (float) dy * 0.02f));
            pv.dirty = true;
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        active = null;
        dragCanvas = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (tab == 5) { pv.zoom = (float) Math.max(0.4, Math.min(4.0, pv.zoom * (v > 0 ? 1.1 : 0.9))); pv.dirty = true; return true; }
        if (tab == 6) { quoteScroll -= (int) Math.signum(v) * 2; return true; }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) { close(); return true; }
        return super.keyPressed(input);
    }
}
