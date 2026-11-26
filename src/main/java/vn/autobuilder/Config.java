package vn.autobuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Cấu hình lưu ở config/autobuilder.properties (UTF-8). Mọi field public static được tự lưu/nạp. */
public final class Config {
    // ---- phạm vi quét
    public static int scanRadius = 32;        // nửa cạnh vùng quét (X/Z)
    public static int scanUp = 24;            // quét lên trên bao nhiêu block
    public static int scanDown = 8;           // quét xuống dưới bao nhiêu block
    public static boolean lockAnchor = false; // khóa tâm vùng quét tại một điểm
    public static int anchorX = 0, anchorY = 64, anchorZ = 0;
    public static boolean showFrame = true;   // hiện khung phạm vi trong thế giới

    // ---- tốc độ
    public static int delayTicks = 3;         // delay giữa 2 lần đặt block (tick, 20 tick = 1 giây)
    public static int adjustDelayTicks = 4;   // delay giữa các lần chỉnh (repeater, comparator...)
    public static int ackTicks = 8;           // chờ server phản hồi sau mỗi lần đặt
    public static int maxAttempts = 4;

    // ---- hành vi
    public static boolean rotateHead = true;  // xoay đầu thật theo hướng đặt
    public static boolean autoMove = true;    // tự đi tới block cần đặt
    public static boolean autoBuy = true;     // tự mua đồ thiếu qua /ah (sau khi đã đồng ý báo giá)
    public static double reach = 4.0;          // tối đa 4 block (không đặt xa hơn)
    public static int swapHotbarSlot = 8;
    public static boolean previewSpin = true;

    // ---- /ah
    public static double maxPricePerListing = 5_000_000;
    public static double budgetFactor = 1.25; // cho phép chi vượt báo giá tối đa 25%
    public static int buyMaxTries = 3;
    public static boolean preferFullStack = true; // ưu tiên stack đầy (64); không thấy thì bấm mũi tên trang sau
    public static int maxPages = 8;               // tối đa số trang /ah được lật
    public static String ahCommand = "ah search %s";
    public static String resultTitle = "tìm kiếm";
    public static String confirmTitle = "xác nhận";
    public static String confirmButtonName = "đồng ý";
    public static String scaffoldItems = "minecraft:cobblestone,minecraft:dirt,minecraft:netherrack";

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autobuilder.properties");
    }

    public static void load() {
        Properties p = new Properties();
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) {}
        }
        for (Field fd : Config.class.getDeclaredFields()) {
            if (!Modifier.isStatic(fd.getModifiers()) || !Modifier.isPublic(fd.getModifiers())) continue;
            String v = p.getProperty(fd.getName());
            if (v == null) continue;
            try {
                Class<?> t = fd.getType();
                if (t == int.class) fd.setInt(null, Integer.parseInt(v.trim()));
                else if (t == double.class) fd.setDouble(null, Double.parseDouble(v.trim()));
                else if (t == boolean.class) fd.setBoolean(null, Boolean.parseBoolean(v.trim()));
                else if (t == String.class) fd.set(null, v);
            } catch (Exception ignored) {}
        }
        clamp();
        resultTitle = resultTitle.toLowerCase();
        confirmTitle = confirmTitle.toLowerCase();
        confirmButtonName = confirmButtonName.toLowerCase();
        save();
    }

    public static void clamp() {
        scanRadius = Math.max(4, Math.min(96, scanRadius));
        scanUp = Math.max(0, Math.min(96, scanUp));
        scanDown = Math.max(0, Math.min(96, scanDown));
        delayTicks = Math.max(1, Math.min(40, delayTicks));
        adjustDelayTicks = Math.max(1, Math.min(40, adjustDelayTicks));
        swapHotbarSlot = Math.max(0, Math.min(8, swapHotbarSlot));
        reach = Math.max(2.5, Math.min(4.0, reach));
    }

    public static void save() {
        clamp();
        Properties p = new Properties();
        for (Field fd : Config.class.getDeclaredFields()) {
            if (!Modifier.isStatic(fd.getModifiers()) || !Modifier.isPublic(fd.getModifiers())) continue;
            try { p.setProperty(fd.getName(), String.valueOf(fd.get(null))); } catch (Exception ignored) {}
        }
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            p.store(w, "AutoBuilder config");
        } catch (IOException ignored) {}
    }

    private Config() {}
}
