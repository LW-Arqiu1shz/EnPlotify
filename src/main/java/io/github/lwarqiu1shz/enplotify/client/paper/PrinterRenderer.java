package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.lwarqiu1shz.enplotify.paper.PosterItem;
import io.github.lwarqiu1shz.enplotify.paper.PrinterBlock;
import io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The paper in a printer: blank paper standing at the back, the original lying on the glass as
 * large as fits, the sheet coming out of the slot and the copies in the tray (only the part outside
 * the printer shows; the rest is hidden in the body). Originals and copies show their content.
 * <p>
 * Works in the block model's frame: front (tray) to the north, in 1/16 blocks.
 */
public final class PrinterRenderer implements BlockEntityRenderer<PrinterBlockEntity, PrinterRenderer.State> {
    private static final float PX = 1.0F / 16.0F;
    private static final float GLASS_W = (float) (PrinterBlock.GLASS_X1 - PrinterBlock.GLASS_X0);
    private static final float GLASS_D = (float) (PrinterBlock.GLASS_Z1 - PrinterBlock.GLASS_Z0);
    private static final float GLASS_X = (float) (PrinterBlock.GLASS_X0 + PrinterBlock.GLASS_X1) / 2.0F;
    private static final float GLASS_Z = (float) (PrinterBlock.GLASS_Z0 + PrinterBlock.GLASS_Z1) / 2.0F;
    /** Sheet thickness, in 1/16 blocks. */
    private static final float SHEET = 0.08F;
    private static final double CONTENT_DISTANCE_SQ = 24.0 * 24.0;

    public static final class State extends BlockEntityRenderState {
        float yRot;
        int paperCount, paperKind, copyCount, printKind;
        float progress;
        boolean tailLowX, copyTailLowX, near;
        ItemStack original = ItemStack.EMPTY, copy = ItemStack.EMPTY, print = ItemStack.EMPTY;
    }

    public PrinterRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PrinterBlockEntity printer, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(printer, state, partialTicks, cameraPosition, breakProgress);
        // Same turn as the block model's "y" for this facing (north = 0).
        state.yRot = printer.getBlockState().getValue(PrinterBlock.FACING).toYRot() + 180.0F;
        state.paperCount = printer.paperCount();
        state.paperKind = printer.paperKind();
        state.copyCount = printer.copyCount();
        state.printKind = printer.printKind();
        state.progress = printer.printProgress(partialTicks);
        state.tailLowX = printer.tailLowX();
        state.copyTailLowX = printer.copyTailLowX();
        state.original = printer.originalKind() == PrinterBlockEntity.NONE ? ItemStack.EMPTY : printer.shownOriginal();
        state.copy = printer.copyCount() == 0 ? ItemStack.EMPTY : printer.shownCopy();
        state.print = printer.printKind() == PrinterBlockEntity.NONE ? ItemStack.EMPTY : printer.shownPrint();
        state.near = Vec3.atCenterOf(printer.getBlockPos()).distanceToSqr(cameraPosition) < CONTENT_DISTANCE_SQ;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        int light = state.lightCoords;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        // Blank paper standing against the rest at the back.
        if (state.paperCount > 0) {
            float thick = 0.15F + Math.min(state.paperCount, 48) * 0.022F;
            SheetDraw.boxes(poseStack, collector, List.of(box(3.0F, 9.3F, 14.3F - thick, 13.0F, 14.8F, 14.3F, color(state.paperKind))), light);
        }
        // The original on the glass.
        if (!state.original.isEmpty()) {
            drawSheet(poseStack, collector, state.original, GLASS_X, (float) PrinterBlock.GLASS_TOP, GLASS_Z, state.tailLowX, state.near, light);
        }
        // Copies in the tray: the same size and way round as the original, their front edge at the tray's.
        float trayTop = (float) PrinterBlock.TRAY_TOP;
        if (state.copyCount > 0 && !state.copy.isEmpty()) {
            float[] size = layout(state.copy, state.copyTailLowX);
            float under = Math.min(state.copyCount - 1, 31) * 0.05F;
            if (under > 0) {
                SheetDraw.boxes(poseStack, collector, List.of(box(GLASS_X - size[2] / 2, trayTop, 0.6F,
                        GLASS_X + size[2] / 2, trayTop + under, 0.6F + size[3], color(PrinterBlockEntity.kindOf(state.copy)))), light);
            }
            trayTop += under;
            drawSheet(poseStack, collector, state.copy, GLASS_X, trayTop, 0.6F + size[3] / 2, state.copyTailLowX, state.near, light);
            trayTop += SHEET;
        }
        // The copy being printed: slides out of the slot and sinks onto the tray.
        if (state.printKind != PrinterBlockEntity.NONE && !state.print.isEmpty() && state.progress > 0.0F) {
            float p = state.progress;
            float depth = layout(state.print, state.tailLowX)[3];
            float startZ = (float) PrinterBlock.BODY_FRONT + depth / 2;
            float endZ = 0.6F + depth / 2;
            float y = 3.3F + (trayTop + 0.02F - 3.3F) * p;
            drawSheet(poseStack, collector, state.print, GLASS_X, y, startZ + (endZ - startZ) * p, state.tailLowX, state.near, light);
        }
        poseStack.popPose();
    }

    /**
     * How a sheet lies in the glass area: {turned (1/0), its shown face's width, its x extent, its z
     * extent}, all in 1/16 blocks. Documents always lie sideways; others the way that is largest.
     */
    private static float[] layout(ItemStack sheet, boolean tailLowX) {
        float aspect = aspect(sheet);
        float flatH = Math.min(GLASS_W / aspect, GLASS_D);
        float turnedH = Math.min(GLASS_W, GLASS_D / aspect);
        boolean turned = PrinterBlockEntity.kindOf(sheet) == PrinterBlockEntity.DOCUMENT || turnedH > flatH + 1.0E-4F;
        float h = turned ? turnedH : flatH;
        float w = h * aspect;
        return turned ? new float[] {1, w, h, w} : new float[] {0, w, w, h};
    }

    /** Width / height of the face that shows. */
    private static float aspect(ItemStack sheet) {
        return switch (PrinterBlockEntity.kindOf(sheet)) {
            case PrinterBlockEntity.NEWSPAPER -> 2.0F * PaperViews.HALF_ASPECT;
            case PrinterBlockEntity.POSTER -> {
                int[] cells = sheet.getItem() instanceof PosterItem poster ? poster.size(sheet) : new int[] {1, 1};
                yield cells[0] > 0 && cells[1] > 0 ? cells[0] / (float) cells[1] : 1.0F;
            }
            default -> PaperViews.PAGE_ASPECT;
        };
    }

    /** One sheet lying face up, centred at (x, z) on height y (1/16 blocks), with its content when near. */
    private static void drawSheet(PoseStack poseStack, SubmitNodeCollector collector, ItemStack sheet, float x, float y, float z,
                                  boolean tailLowX, boolean near, int light) {
        int kind = PrinterBlockEntity.kindOf(sheet);
        float[] size = layout(sheet, tailLowX);
        SheetDraw.boxes(poseStack, collector, List.of(box(x - size[2] / 2, y, z - size[3] / 2, x + size[2] / 2, y + SHEET, z + size[3] / 2, color(kind))), light);
        if (!near) return;
        poseStack.pushPose();
        poseStack.translate(x * PX, (y + SHEET) * PX + 0.0004F, z * PX);
        // Page top: towards the back when lying straight (readable from the front), else away from the tail.
        poseStack.mulPose(Axis.YP.rotationDegrees(size[0] == 0 ? 180.0F : tailLowX ? -90.0F : 90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        float hw = size[1] * PX / 2, hh = (size[0] == 0 ? size[3] : size[2]) * PX / 2;
        switch (kind) {
            case PrinterBlockEntity.NEWSPAPER -> {
                PaperViews.NewspaperView view = PaperViews.newspaper(sheet);
                int[] px = SheetDraw.pixels(PaperViews.HALF_ASPECT, PaperStackRenderer.SURFACE_PX);
                PaperStackRenderer.surface(poseStack, collector, view.halves().get(0), px, PaperViews.NEWSPRINT, -hw, -hh, 0.0F, hh, light);
                PaperStackRenderer.surface(poseStack, collector, view.halves().get(1), px, PaperViews.NEWSPRINT, 0.0F, -hh, hw, hh, light);
            }
            case PrinterBlockEntity.POSTER -> {
                int[] px = SheetDraw.pixels(hw / hh, PaperStackRenderer.SURFACE_PX);
                PaperStackRenderer.surface(poseStack, collector, PaperViews.poster(sheet), px, PaperViews.POSTER_PAPER, -hw, -hh, hw, hh, light);
            }
            case PrinterBlockEntity.DOCUMENT -> {
                int[] px = SheetDraw.pixels(PaperViews.PAGE_ASPECT, PaperStackRenderer.SURFACE_PX);
                PaperStackRenderer.surface(poseStack, collector, PaperViews.document(sheet).pages().get(0), px, PaperViews.DOCUMENT_PAPER, -hw, -hh, hw, hh, light);
            }
            default -> {}
        }
        poseStack.popPose();
    }

    /** A box given in 1/16 blocks. */
    private static SheetDraw.Box box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        return new SheetDraw.Box(x0 * PX, y0 * PX, z0 * PX, x1 * PX, y1 * PX, z1 * PX, color);
    }

    private static int color(int kind) {
        return switch (kind) {
            case PrinterBlockEntity.NEWSPAPER -> PaperViews.NEWSPRINT;
            case PrinterBlockEntity.POSTER -> PaperViews.POSTER_PAPER;
            default -> PaperViews.DOCUMENT_PAPER;
        };
    }
}
