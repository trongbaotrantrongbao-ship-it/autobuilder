package vn.autobuilder;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Vẽ khung phạm vi quét (và block đang xây) trong thế giới. */
public final class FrameRenderer {

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(FrameRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        boolean menu = mc.currentScreen instanceof MenuScreen;
        if (!Config.showFrame && !menu) return;

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderLayers.lines());
        MatrixStack.Entry e = ctx.matrices().peek();

        int[] b = Region.bounds(mc);
        int color;
        if (AutoBuilderMod.BUILDER.scanner.st == Scanner.St.RUNNING) color = 0xFFFFD60A;   // vàng: đang quét
        else if (Config.lockAnchor) color = 0xFFFF9F0A;                                  // cam: đã khóa tâm
        else color = 0xFF00E5FF;                                                         // xanh: theo người chơi
        box(vc, e, cam, b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, color, 2.5f);

        BlockPos f = AutoBuilderMod.BUILDER.focus;
        if (f != null && AutoBuilderMod.BUILDER.isEnabled()) {
            box(vc, e, cam, f.getX(), f.getY(), f.getZ(), f.getX() + 1, f.getY() + 1, f.getZ() + 1, 0xFF30D158, 3f);
        }
    }

    private static void box(VertexConsumer vc, MatrixStack.Entry e, Vec3d cam,
                            double x0, double y0, double z0, double x1, double y1, double z1, int c, float w) {
        // 4 cạnh dưới, 4 cạnh trên, 4 cạnh đứng
        line(vc, e, cam, x0, y0, z0, x1, y0, z0, c, w); line(vc, e, cam, x1, y0, z0, x1, y0, z1, c, w);
        line(vc, e, cam, x1, y0, z1, x0, y0, z1, c, w); line(vc, e, cam, x0, y0, z1, x0, y0, z0, c, w);
        line(vc, e, cam, x0, y1, z0, x1, y1, z0, c, w); line(vc, e, cam, x1, y1, z0, x1, y1, z1, c, w);
        line(vc, e, cam, x1, y1, z1, x0, y1, z1, c, w); line(vc, e, cam, x0, y1, z1, x0, y1, z0, c, w);
        line(vc, e, cam, x0, y0, z0, x0, y1, z0, c, w); line(vc, e, cam, x1, y0, z0, x1, y1, z0, c, w);
        line(vc, e, cam, x1, y0, z1, x1, y1, z1, c, w); line(vc, e, cam, x0, y0, z1, x0, y1, z1, c, w);
    }

    private static void line(VertexConsumer vc, MatrixStack.Entry e, Vec3d cam,
                             double x1, double y1, double z1, double x2, double y2, double z2, int c, float w) {
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;
        dx /= len; dy /= len; dz /= len;
        vc.vertex(e, (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z)).color(c).normal(e, dx, dy, dz).lineWidth(w);
        vc.vertex(e, (float) (x2 - cam.x), (float) (y2 - cam.y), (float) (z2 - cam.z)).color(c).normal(e, dx, dy, dz).lineWidth(w);
    }

    private FrameRenderer() {}
}
