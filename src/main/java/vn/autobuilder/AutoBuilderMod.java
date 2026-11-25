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
import org.lwjgl.glfw.GLFW;

public class AutoBuilderMod implements ClientModInitializer {

    public static final Builder BUILDER = new Builder();
    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        Config.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuilder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K,
                KeyBinding.Category.create(net.minecraft.util.Identifier.of("autobuilder", "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.wasPressed()) BUILDER.setEnabled(mc, !BUILDER.isEnabled());
            BUILDER.onTick(mc);
        });

        // Lệnh chat (tiện cho bản mobile): /autobuilder start | stop | status | radius <n> | reload
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
                dispatcher.register(ClientCommandManager.literal("autobuilder")
                        .then(ClientCommandManager.literal("start").executes(c -> {
                            BUILDER.setEnabled(MinecraftClient.getInstance(), true); return 1; }))
                        .then(ClientCommandManager.literal("stop").executes(c -> {
                            BUILDER.setEnabled(MinecraftClient.getInstance(), false); return 1; }))
                        .then(ClientCommandManager.literal("status").executes(c -> {
                            Util.chat(MinecraftClient.getInstance(), BUILDER.statusText()); return 1; }))
                        .then(ClientCommandManager.literal("reload").executes(c -> {
                            Config.load(); Util.chat(MinecraftClient.getInstance(), "Đã nạp lại config."); return 1; }))
                        .then(ClientCommandManager.literal("radius")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(4, 128))
                                        .executes(c -> {
                                            Config.scanRadius = IntegerArgumentType.getInteger(c, "n");
                                            Config.save();
                                            Util.chat(MinecraftClient.getInstance(), "Bán kính quét = " + Config.scanRadius);
                                            return 1; })))));
    }
}
