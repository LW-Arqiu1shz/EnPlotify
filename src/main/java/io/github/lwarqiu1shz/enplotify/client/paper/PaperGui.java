package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Drawing helpers shared by the paper screens. */
final class PaperGui {
    /** Integer rectangle on screen. */
    record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    /**
     * The largest rectangle of the given proportions (width / height) that is at most 90 % of the
     * screen wide and fits between {@code top} and {@code bottom}, centred horizontally.
     */
    static Rect fit(int screenW, int top, int bottom, float aspect, int sideReserve) {
        int maxW = Math.min(Math.round(screenW * 0.9F), screenW - 2 * sideReserve);
        int maxH = Math.max(20, bottom - top);
        int w = maxW;
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        return new Rect((screenW - w) / 2, top + (maxH - h) / 2, w, h);
    }

    /** Real screen pixels for a GUI length, rounded up to a multiple of 64 (so resizing rarely repaints). */
    static int realPixels(int guiLength) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        int px = (int) Math.ceil(guiLength * scale / 64.0) * 64;
        return Math.max(64, Math.min(2048, px));
    }

    /** Draws a painted canvas into the rectangle (plain paper while it is still being painted). */
    static void canvas(GuiGraphicsExtractor graphics, Canvas canvas, Rect r, int pxW, int pxH, int paper) {
        graphics.fill(r.x() - 1, r.y() - 1, r.x() + r.w() + 1, r.y() + r.h() + 1, 0xFF8A8377);
        graphics.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), paper);
        if (canvas.isEmpty()) return;
        if (CanvasTextures.needsPaint(canvas)) {
            Identifier texture = CanvasTextures.get(canvas, pxW, pxH, paper);
            if (texture != null) graphics.blit(texture, r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), 0.0F, 1.0F, 0.0F, 1.0F);
        }
        if (PaperAnimations.hasImages(canvas)) PaperAnimations.drawGui(graphics, canvas, r);
        CanvasText.drawGui(graphics, canvas, r, null);
    }

    private PaperGui() {}
}
