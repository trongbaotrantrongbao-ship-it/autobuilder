package vn.autobuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class Util {

    public enum Sel { READY, WAIT, MISSING }

    public static int count(ClientPlayerEntity p, Item item) {
        int c = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getMainStacks().size(); i++) {
            ItemStack s = inv.getMainStacks().get(i);
            if (s.isOf(item)) c += s.getCount();
        }
        ItemStack off = p.getOffHandStack();
        if (off.isOf(item)) c += off.getCount();
        return c;
    }

    /** Còn chứa thêm được bao nhiêu cái item này trong kho (ô trống + chỗ trống trong stack cùng loại). */
    public static int freeSpace(ClientPlayerEntity p, Item item) {
        int max = new ItemStack(item).getMaxCount(), sp = 0;
        for (ItemStack s : p.getInventory().getMainStacks()) {
            if (s.isEmpty()) sp += max;
            else if (s.isOf(item)) sp += Math.max(0, max - s.getCount());
        }
        return sp;
    }

    /** Chọn ngay item nếu nó nằm trong hotbar (không chờ). false nếu không ở hotbar. */
    public static boolean selectNow(MinecraftClient mc, Item item) {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();
        if (inv.getMainStacks().get(inv.getSelectedSlot()).isOf(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (inv.getMainStacks().get(i).isOf(item)) {
                inv.setSelectedSlot(i);
                p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                return true;
            }
        }
        return false;
    }

    /** Cầm công cụ đào nhanh nhất trong hotbar cho block này. */
    public static void bestTool(MinecraftClient mc, net.minecraft.block.BlockState st) {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();
        int best = inv.getSelectedSlot();
        float bs = inv.getMainStacks().get(best).getMiningSpeedMultiplier(st);
        for (int i = 0; i < 9; i++) {
            float v = inv.getMainStacks().get(i).getMiningSpeedMultiplier(st);
            if (v > bs + 0.01f) { bs = v; best = i; }
        }
        if (best != inv.getSelectedSlot()) {
            inv.setSelectedSlot(best);
            p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(best));
        }
    }

    /** Chỉ báo hướng nhìn cho server (không xoay camera) – dùng khi đặt nhanh. */
    public static void silentLook(MinecraftClient mc, float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround(), p.horizontalCollision));
    }

    /** Đưa item vào tay chính. READY = đang cầm; WAIT = vừa hoán đổi, chờ tick sau; MISSING = không có. */
    public static Sel select(MinecraftClient mc, Item item) {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();

        for (int i = 0; i < 9; i++) {
            if (inv.getMainStacks().get(i).isOf(item)) {
                if (inv.getSelectedSlot() != i) {
                    inv.setSelectedSlot(i);
                    p.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
                    return Sel.WAIT;
                }
                return Sel.READY;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getMainStacks().get(i).isOf(item)) {
                // ô 9..35 của PlayerScreenHandler trùng chỉ số kho; SWAP với hotbar
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, Config.swapHotbarSlot,
                        SlotActionType.SWAP, p);
                return Sel.WAIT;
            }
        }
        return Sel.MISSING;
    }

    public static void look(MinecraftClient mc, float yaw, float pitch) {
        ClientPlayerEntity p = mc.player;
        if (Config.rotateHead) { // xoay đầu thật: game tự gửi hướng nhìn mỗi tick -> KHÔNG gửi gói thừa (gói thừa làm server kéo lại / giật)
            p.setYaw(yaw);
            p.setPitch(pitch);
        } else {                 // không xoay đầu thật: chỉ báo hướng cho server
            p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround(), p.horizontalCollision));
        }
    }

    public static void lookAt(MinecraftClient mc, Vec3d target) {
        Vec3d d = target.subtract(mc.player.getEyePos());
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 57.2957795 - 90.0);
        float pitch = (float) -(MathHelper.atan2(d.y, flat) * 57.2957795);
        look(mc, yaw, pitch);
    }

    /** Bấm chuột phải vào block. sneak=true để đặt block lên block có giao diện (rương, bàn...). */
    public static void useBlock(MinecraftClient mc, BlockHitResult hit, boolean sneak) {
        ClientPlayerEntity p = mc.player;
        if (sneak) {
            p.setSneaking(true);
            p.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, true, false)));
        }
        ActionResult r = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
        if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
        if (sneak) {
            p.setSneaking(false);
            p.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, false, false)));
        }
    }

    public static void useItem(MinecraftClient mc) {
        ActionResult r = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (r.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** minecraft:oak_planks -> "oak planks" (tên tiếng Anh dùng cho /ah search). */
    public static String englishName(Item item) {
        return Registries.ITEM.getId(item).getPath().replace('_', ' ');
    }

    public static Vec3d pos(net.minecraft.entity.Entity e) { return new Vec3d(e.getX(), e.getY(), e.getZ()); }

    public static String money(double v) {
        if (v >= 1e9) return String.format(java.util.Locale.ROOT, "$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(java.util.Locale.ROOT, "$%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(java.util.Locale.ROOT, "$%.1fK", v / 1e3);
        return "$" + (long) v;
    }

    public static void status(MinecraftClient mc, String msg) {
        if (Config.actionBar && mc.player != null) mc.player.sendMessage(Text.literal("[AutoBuilder] " + msg), true);
    }

    public static void chat(MinecraftClient mc, String msg) {
        if (Config.chatMessages && mc.player != null) mc.player.sendMessage(Text.literal("§b[AutoBuilder]§r " + msg), false);
    }

    private Util() {}
}
