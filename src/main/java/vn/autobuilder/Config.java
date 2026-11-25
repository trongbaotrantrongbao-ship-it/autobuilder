package vn.autobuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Cấu hình lưu ở config/autobuilder.properties (UTF-8). */
public final class Config {
    public static int scanRadius = 32;        // bán kính quét ngang quanh người chơi
    public static int scanHeight = 24;        // quét lên/xuống bao nhiêu block
    public static int delayTicks = 3;         // số tick giữa 2 hành động
    public static int maxAttempts = 4;        // số lần thử đặt/đổ nước trước khi bỏ qua
    public static int ackTicks = 6;           // chờ server phản hồi sau mỗi hành động
    public static int swapHotbarSlot = 8;     // ô hotbar (0-8) dùng để hoán đổi đồ từ kho
    public static double reach = 4.4;         // tầm với tối đa
    public static boolean autoBuy = true;     // tự mua đồ thiếu qua /ah
    public static double maxPricePerListing = 2_000_000;
    public static int buyMaxTries = 3;

    public static String ahCommand = "ah search %s";   // %s = tên tiếng Anh của item
    public static String resultTitle = "tìm kiếm";      // tiêu đề menu kết quả (viết thường)
    public static String confirmTitle = "xác nhận";     // tiêu đề menu xác nhận
    public static String confirmButtonName = "đồng ý";  // tên nút xanh xác nhận
    public static String scaffoldItems = "minecraft:cobblestone,minecraft:dirt,minecraft:netherrack";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autobuilder.properties");
    }

    public static void load() {
        Path f = file();
        Properties p = new Properties();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {}
        }
        scanRadius = i(p, "scanRadius", scanRadius);
        scanHeight = i(p, "scanHeight", scanHeight);
        delayTicks = Math.max(1, i(p, "delayTicks", delayTicks));
        maxAttempts = i(p, "maxAttempts", maxAttempts);
        ackTicks = i(p, "ackTicks", ackTicks);
        swapHotbarSlot = Math.max(0, Math.min(8, i(p, "swapHotbarSlot", swapHotbarSlot)));
        reach = d(p, "reach", reach);
        autoBuy = Boolean.parseBoolean(p.getProperty("autoBuy", String.valueOf(autoBuy)));
        maxPricePerListing = d(p, "maxPricePerListing", maxPricePerListing);
        buyMaxTries = i(p, "buyMaxTries", buyMaxTries);
        ahCommand = p.getProperty("ahCommand", ahCommand);
        resultTitle = p.getProperty("resultTitle", resultTitle).toLowerCase();
        confirmTitle = p.getProperty("confirmTitle", confirmTitle).toLowerCase();
        confirmButtonName = p.getProperty("confirmButtonName", confirmButtonName).toLowerCase();
        scaffoldItems = p.getProperty("scaffoldItems", scaffoldItems);
        save();
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("scanRadius", String.valueOf(scanRadius));
        p.setProperty("scanHeight", String.valueOf(scanHeight));
        p.setProperty("delayTicks", String.valueOf(delayTicks));
        p.setProperty("maxAttempts", String.valueOf(maxAttempts));
        p.setProperty("ackTicks", String.valueOf(ackTicks));
        p.setProperty("swapHotbarSlot", String.valueOf(swapHotbarSlot));
        p.setProperty("reach", String.valueOf(reach));
        p.setProperty("autoBuy", String.valueOf(autoBuy));
        p.setProperty("maxPricePerListing", String.valueOf(maxPricePerListing));
        p.setProperty("buyMaxTries", String.valueOf(buyMaxTries));
        p.setProperty("ahCommand", ahCommand);
        p.setProperty("resultTitle", resultTitle);
        p.setProperty("confirmTitle", confirmTitle);
        p.setProperty("confirmButtonName", confirmButtonName);
        p.setProperty("scaffoldItems", scaffoldItems);
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            p.store(w, "AutoBuilder config");
        } catch (IOException ignored) {}
    }

    private static int i(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }

    private static double d(Properties p, String k, double def) {
        try { return Double.parseDouble(p.getProperty(k, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }

    private Config() {}
}
