package vn.autobuilder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AutoBuilderMod implements ClientModInitializer {

    public static final Builder BUILDER = new Builder();
    public static final ChestManager CHESTS = new ChestManager();
    public static final Preview3D PREVIEW = new Preview3D();

    private static KeyBinding toggleKey;
    private static KeyBinding menuKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        CHESTS.load();

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("autobuilder", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuilder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuilder.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, cat));

        FrameRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) mc.setScreen(new MenuScreen());
            }
            while (toggleKey.wasPressed()) toggle(mc);
            BUILDER.onTick(mc);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                dispatcher.register(ClientCommandManager.literal("autobuilder")
                        .then(ClientCommandManager.literal("start").executes(c -> { toggleOn(MinecraftClient.getInstance()); return 1; }))
                        .then(ClientCommandManager.literal("stop").executes(c -> { BUILDER.setEnabled(MinecraftClient.getInstance(), false); return 1; }))
                        .then(ClientCommandManager.literal("menu").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            mc.send(() -> mc.setScreen(new MenuScreen())); return 1; }))
                        .then(ClientCommandManager.literal("status").executes(c -> { Util.chat(MinecraftClient.getInstance(), BUILDER.statusText()); return 1; }))
                        .then(ClientCommandManager.literal("reload").executes(c -> { Config.load(); Util.chat(MinecraftClient.getInstance(), "Đã nạp lại config."); return 1; }))
                        .then(ClientCommandManager.literal("radius")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(4, 96))
                                        .executes(c -> {
                                            Config.scanRadius = IntegerArgumentType.getInteger(c, "n");
                                            Config.save();
                                            Util.chat(MinecraftClient.getInstance(), "Bán kính quét = " + Config.scanRadius);
                                            return 1; })))));
    }

    private static void toggle(MinecraftClient mc) {
        if (BUILDER.isEnabled()) BUILDER.setEnabled(mc, false);
        else toggleOn(mc);
    }

    private static void toggleOn(MinecraftClient mc) {
        String err = BUILDER.tryStart(mc, false);
        if (err != null) Util.chat(mc, "§e" + err);
    }
}
