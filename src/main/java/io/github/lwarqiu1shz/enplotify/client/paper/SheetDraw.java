package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Drawing helpers for paper in the world. Everything is drawn in a local plane: x to the right,
 * y up, the visible side facing +z, units in blocks.
 */
final class SheetDraw {
    static final Identifier WHITE = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "textures/entity/white.png");

    /** An axis-aligned box in local space, tinted. */
    record Box(float x0, float y0, float z0, float x1, float y1, float z1, int argb) {}

    /** A flat rectangle of colour. */
    static void fill(PoseStack poseStack, SubmitNodeCollector collector, float x0, float y0, float x1, float y1,
                     float z, int argb, int light) {
        quad(poseStack, collector, WHITE, x0, y0, x1, y1, z, argb, light);
    }

    /** The four thin edges of a flat slab from {@code zBack} to {@code zFront} (front and back are drawn by the caller). */
    static void edges(PoseStack poseStack, SubmitNodeCollector collector, float x0, float y0, float x1, float y1,
                      float zBack, float zFront, int argb, int light) {
        int c = darken(argb, 0.82F);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE), (pose, b) -> {
            vertex(b, pose, x0, y1, zFront, 0, 0, c, light, 0, 1, 0);
            vertex(b, pose, x1, y1, zFront, 0, 0, c, light, 0, 1, 0);
            vertex(b, pose, x1, y1, zBack, 0, 0, c, light, 0, 1, 0);
            vertex(b, pose, x0, y1, zBack, 0, 0, c, light, 0, 1, 0);
            vertex(b, pose, x0, y0, zBack, 0, 0, c, light, 0, -1, 0);
            vertex(b, pose, x1, y0, zBack, 0, 0, c, light, 0, -1, 0);
            vertex(b, pose, x1, y0, zFront, 0, 0, c, light, 0, -1, 0);
            vertex(b, pose, x0, y0, zFront, 0, 0, c, light, 0, -1, 0);
            vertex(b, pose, x1, y0, zFront, 0, 0, c, light, 1, 0, 0);
            vertex(b, pose, x1, y0, zBack, 0, 0, c, light, 1, 0, 0);
            vertex(b, pose, x1, y1, zBack, 0, 0, c, light, 1, 0, 0);
            vertex(b, pose, x1, y1, zFront, 0, 0, c, light, 1, 0, 0);
            vertex(b, pose, x0, y0, zBack, 0, 0, c, light, -1, 0, 0);
            vertex(b, pose, x0, y0, zFront, 0, 0, c, light, -1, 0, 0);
            vertex(b, pose, x0, y1, zFront, 0, 0, c, light, -1, 0, 0);
            vertex(b, pose, x0, y1, zBack, 0, 0, c, light, -1, 0, 0);
        });
    }

    /** A textured rectangle, the whole texture stretched over it (top of the texture at y1). */
    static void quad(PoseStack poseStack, SubmitNodeCollector collector, Identifier texture,
                     float x0, float y0, float x1, float y1, float z, int argb, int light) {
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (pose, buffer) -> {
            vertex(buffer, pose, x0, y0, z, 0.0F, 1.0F, argb, light, 0, 0, 1);
            vertex(buffer, pose, x1, y0, z, 1.0F, 1.0F, argb, light, 0, 0, 1);
            vertex(buffer, pose, x1, y1, z, 1.0F, 0.0F, argb, light, 0, 0, 1);
            vertex(buffer, pose, x0, y1, z, 0.0F, 0.0F, argb, light, 0, 0, 1);
        });
    }

    /** {@link #quad}, but see-through where the texture is (soft edges kept). */
    static void quadTranslucent(PoseStack poseStack, SubmitNodeCollector collector, Identifier texture,
                                float x0, float y0, float x1, float y1, float z, int argb, int light) {
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(texture), (pose, buffer) -> {
            vertex(buffer, pose, x0, y0, z, 0.0F, 1.0F, argb, light, 0, 0, 1);
            vertex(buffer, pose, x1, y0, z, 1.0F, 1.0F, argb, light, 0, 0, 1);
            vertex(buffer, pose, x1, y1, z, 1.0F, 0.0F, argb, light, 0, 0, 1);
            vertex(buffer, pose, x0, y1, z, 0.0F, 0.0F, argb, light, 0, 0, 1);
        });
    }

    /** A flat coloured rectangle at depth z, facing +z. */
    record Rect(float x0, float y0, float x1, float y1, float z, int argb) {}

    /** Many flat rectangles in one batch. */
    static void rects(PoseStack poseStack, SubmitNodeCollector collector, List<Rect> rects, int light) {
        if (rects.isEmpty()) return;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE), (pose, b) -> {
            for (Rect r : rects) {
                vertex(b, pose, r.x0(), r.y0(), r.z(), 0, 0, r.argb(), light, 0, 0, 1);
                vertex(b, pose, r.x1(), r.y0(), r.z(), 0, 0, r.argb(), light, 0, 0, 1);
                vertex(b, pose, r.x1(), r.y1(), r.z(), 0, 0, r.argb(), light, 0, 0, 1);
                vertex(b, pose, r.x0(), r.y1(), r.z(), 0, 0, r.argb(), light, 0, 0, 1);
            }
        });
    }

    /** Many tinted boxes (top and four sides each) in one batch. Local axes: y is up. */
    static void boxes(PoseStack poseStack, SubmitNodeCollector collector, List<Box> boxes, int light) {
        if (boxes.isEmpty()) return;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(WHITE), (pose, b) -> {
            for (Box box : boxes) {
                int c = box.argb();
                int side = darken(c, 0.82F);
                float x0 = box.x0(), y0 = box.y0(), z0 = box.z0(), x1 = box.x1(), y1 = box.y1(), z1 = box.z1();
                // top
                vertex(b, pose, x0, y1, z1, 0, 0, c, light, 0, 1, 0);
                vertex(b, pose, x1, y1, z1, 0, 0, c, light, 0, 1, 0);
                vertex(b, pose, x1, y1, z0, 0, 0, c, light, 0, 1, 0);
                vertex(b, pose, x0, y1, z0, 0, 0, c, light, 0, 1, 0);
                // +z / -z
                vertex(b, pose, x0, y0, z1, 0, 0, side, light, 0, 0, 1);
                vertex(b, pose, x1, y0, z1, 0, 0, side, light, 0, 0, 1);
                vertex(b, pose, x1, y1, z1, 0, 0, side, light, 0, 0, 1);
                vertex(b, pose, x0, y1, z1, 0, 0, side, light, 0, 0, 1);
                vertex(b, pose, x1, y0, z0, 0, 0, side, light, 0, 0, -1);
                vertex(b, pose, x0, y0, z0, 0, 0, side, light, 0, 0, -1);
                vertex(b, pose, x0, y1, z0, 0, 0, side, light, 0, 0, -1);
                vertex(b, pose, x1, y1, z0, 0, 0, side, light, 0, 0, -1);
                // +x / -x
                vertex(b, pose, x1, y0, z1, 0, 0, side, light, 1, 0, 0);
                vertex(b, pose, x1, y0, z0, 0, 0, side, light, 1, 0, 0);
                vertex(b, pose, x1, y1, z0, 0, 0, side, light, 1, 0, 0);
                vertex(b, pose, x1, y1, z1, 0, 0, side, light, 1, 0, 0);
                vertex(b, pose, x0, y0, z0, 0, 0, side, light, -1, 0, 0);
                vertex(b, pose, x0, y0, z1, 0, 0, side, light, -1, 0, 0);
                vertex(b, pose, x0, y1, z1, 0, 0, side, light, -1, 0, 0);
                vertex(b, pose, x0, y1, z0, 0, 0, side, light, -1, 0, 0);
            }
        });
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                               int argb, int light, int nx, int ny, int nz) {
        buffer.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    static int darken(int argb, float factor) {
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Texture size for a surface {@code aspect} wide per 1 high: {@code longSide} pixels on the longer side. */
    static int[] pixels(float aspect, int longSide) {
        return aspect >= 1.0F
                ? new int[] {longSide, Math.max(1, Math.round(longSide / aspect))}
                : new int[] {Math.max(1, Math.round(longSide * aspect)), longSide};
    }

    private SheetDraw() {}
}
