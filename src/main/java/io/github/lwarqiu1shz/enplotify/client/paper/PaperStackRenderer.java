package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import io.github.lwarqiu1shz.enplotify.paper.DocumentItem;
import io.github.lwarqiu1shz.enplotify.paper.NewspaperItem;
import io.github.lwarqiu1shz.enplotify.paper.PaperStackEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a pile of newspapers / documents: every run of same-sized sheets below the visible ones is
 * one box, and the visible sheets on top (until a newspaper covers the rest) show their content.
 */
public final class PaperStackRenderer extends EntityRenderer<PaperStackEntity, PaperStackRenderer.State> {
    static final float[] NEWSPAPER = {1.0F, 420.0F / 594.0F};
    static final float[] DOCUMENT = {420.0F / 594.0F * 210.0F / 297.0F, 420.0F / 594.0F};
    private static final float SHEET = PaperStackEntity.SHEET;
    /** Paper surfaces are painted this many pixels tall (also what the reading screen uses). */
    static final int SURFACE_PX = 1024;
    /** Beyond this distance only blank sheets are drawn. */
    private static final double CONTENT_DISTANCE_SQ = 24.0 * 24.0;
    /** One layer step: well under a sheet's thickness (5 steps stay below it). */
    private static final float LAYER_LIFT = 0.003F;

    public static class State extends EntityRenderState {
        float yaw;
        float tilt;
        float slopeYaw;
        int layer;
        String layers = "";
        final ItemStack[] visible = new ItemStack[PaperStackEntity.VISIBLE];
    }

    public PaperStackRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PaperStackEntity entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.yaw = entity.getYRot();
        state.tilt = entity.tilt();
        state.slopeYaw = entity.slopeYaw();
        state.layer = entity.layer();
        state.layers = entity.layers();
        for (int i = 0; i < PaperStackEntity.VISIBLE; i++) state.visible[i] = entity.visible(i);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        String layers = state.layers;
        int count = layers.length();
        if (count == 0) return;
        int light = state.lightCoords;
        int shown = 0;
        while (shown < PaperStackEntity.VISIBLE && shown < count && state.visible[shown] != null && !state.visible[shown].isEmpty()) shown++;
        int firstVisible = count - shown;

        poseStack.pushPose();
        if (state.tilt != 0.0F) {
            // Lying on a slope (a lectern): first lean like the slope, then turn on it.
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.slopeYaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(state.tilt));
            poseStack.mulPose(Axis.YP.rotationDegrees(state.slopeYaw - state.yaw));
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yaw));
        }
        // Piles put down later over another one sit a hair higher, so they never flicker through each other.
        if (state.layer != 0) poseStack.translate(0.0F, state.layer * LAYER_LIFT, 0.0F);

        // Hidden sheets: one box per run of the same kind.
        List<SheetDraw.Box> boxes = new ArrayList<>();
        int runStart = 0;
        for (int i = 1; i <= firstVisible; i++) {
            if (i == firstVisible || layers.charAt(i) != layers.charAt(runStart)) {
                float[] size = layers.charAt(runStart) == 'N' ? NEWSPAPER : DOCUMENT;
                boxes.add(new SheetDraw.Box(-size[0] / 2, runStart * SHEET, -size[1] / 2, size[0] / 2, i * SHEET, size[1] / 2,
                        layers.charAt(runStart) == 'N' ? PaperViews.NEWSPRINT : PaperViews.DOCUMENT_PAPER));
                runStart = i;
            }
        }
        SheetDraw.boxes(poseStack, collector, boxes, light);

        boolean near = state.distanceToCameraSq < CONTENT_DISTANCE_SQ;
        // Visible sheets, bottom-most first, each with a small fixed twist.
        for (int slot = shown - 1; slot >= 0; slot--) {
            int layer = count - 1 - slot;
            ItemStack sheet = state.visible[slot];
            boolean newspaper = sheet.getItem() instanceof NewspaperItem;
            float[] size = newspaper ? NEWSPAPER : DOCUMENT;
            int paper = newspaper ? PaperViews.NEWSPRINT : PaperViews.DOCUMENT_PAPER;
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(((layer * 37) % 9 - 4) * 0.8F));
            float y0 = layer * SHEET;
            SheetDraw.boxes(poseStack, collector, List.of(new SheetDraw.Box(-size[0] / 2, y0, -size[1] / 2, size[0] / 2, y0 + SHEET, size[1] / 2, paper)), light);
            if (near) {
                poseStack.translate(0.0F, y0 + SHEET + 0.0006F, 0.0F);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                if (newspaper) drawNewspaper(poseStack, collector, sheet, size, light);
                else if (sheet.getItem() instanceof DocumentItem) drawDocument(poseStack, collector, sheet, size, light);
            }
            poseStack.popPose();
        }
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /** The front of the unfolded sheet: front-left and front-right halves side by side. */
    private static void drawNewspaper(PoseStack poseStack, SubmitNodeCollector collector, ItemStack sheet, float[] size, int light) {
        PaperViews.NewspaperView view = PaperViews.newspaper(sheet);
        float hw = size[0] / 2, hh = size[1] / 2;
        int[] px = SheetDraw.pixels(PaperViews.HALF_ASPECT, SURFACE_PX);
        surface(poseStack, collector, view.halves().get(0), px, PaperViews.NEWSPRINT, -hw, -hh, 0.0F, hh, light);
        surface(poseStack, collector, view.halves().get(1), px, PaperViews.NEWSPRINT, 0.0F, -hh, hw, hh, light);
        // The fold down the middle.
        SheetDraw.fill(poseStack, collector, -0.002F, -hh, 0.002F, hh, 0.0002F, 0xFFC4C0B6, light);
    }

    private static void drawDocument(PoseStack poseStack, SubmitNodeCollector collector, ItemStack sheet, float[] size, int light) {
        PaperViews.DocumentView view = PaperViews.document(sheet);
        int[] px = SheetDraw.pixels(PaperViews.PAGE_ASPECT, SURFACE_PX);
        surface(poseStack, collector, view.pages().get(0), px, PaperViews.DOCUMENT_PAPER, -size[0] / 2, -size[1] / 2, size[0] / 2, size[1] / 2, light);
    }

    static void surface(PoseStack poseStack, SubmitNodeCollector collector, Canvas canvas, int[] px, int paper,
                                float x0, float y0, float x1, float y1, int light) {
        if (canvas.isEmpty()) return;
        if (CanvasTextures.needsPaint(canvas)) {
            Identifier texture = CanvasTextures.get(canvas, px[0], px[1], paper);
            if (texture != null) SheetDraw.quad(poseStack, collector, texture, x0, y0, x1, y1, 0.0F, 0xFFFFFFFF, light);
        }
        CanvasText.drawWorld(poseStack, collector, canvas, x0, y0, x1, y1, 0.0006F, light, net.minecraft.client.gui.Font.DisplayMode.NORMAL);
    }
}
