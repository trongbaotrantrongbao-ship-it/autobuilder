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
        return c;
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
        p.setYaw(yaw);
        p.setPitch(pitch);
        p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround(), p.horizontalCollision));
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
        if (r.shouldSwingHand()) p.swingHand(Hand.MAIN_HAND);
        if (sneak) {
            p.setSneaking(false);
            p.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, false, false)));
        }
    }

    public static void useItem(MinecraftClient mc) {
        ActionResult r = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (r.shouldSwingHand()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** minecraft:oak_planks -> "oak planks" (tên tiếng Anh dùng cho /ah search). */
    public static String englishName(Item item) {
        return Registries.ITEM.getId(item).getPath().replace('_', ' ');
    }

    public static void status(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoBuilder] " + msg), true);
    }

    public static void chat(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("§b[AutoBuilder]§r " + msg), false);
    }

    private Util() {}
}
