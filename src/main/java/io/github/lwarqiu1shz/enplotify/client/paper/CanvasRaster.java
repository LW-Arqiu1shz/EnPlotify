package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * Paints a {@link Canvas} into an ARGB pixel array with Java2D (runs off the render thread):
 * LaTeX formulas (JLaTeXMath), handwriting strokes and pictures. Text elements are not painted
 * here: they are drawn on top with Minecraft's own font by {@link CanvasText}.
 */
final class CanvasRaster {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static boolean latexBroken;

    /** Pixels of the painted canvas, row by row, 0xAARRGGBB. */
    static int[] paint(Canvas canvas, int width, int height, int background, Map<String, BufferedImage> images) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(new Color(background, true));
            g.fillRect(0, 0, width, height);
            for (Canvas.Element e : canvas.elements()) {
                try {
                    switch (e.type()) {
                        // Text is drawn live with Minecraft's font (CanvasText), not painted here.
                        case Canvas.IMAGE -> picture(g, e, width, height, images.get(e.text()));
                        case Canvas.FORMULA -> formula(g, e, width, height);
                        case Canvas.STROKE -> stroke(g, e, width, height);
                        default -> {}
                    }
                } catch (RuntimeException ex) {
                    LOGGER.debug("EnPlotify: could not paint an element", ex);
                }
            }
        } finally {
            g.dispose();
        }
        return out.getRGB(0, 0, width, height, null, 0, width);
    }

    static Color color(int rgb) {
        return new Color(rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ pictures, formulas, strokes

    private static void picture(Graphics2D g, Canvas.Element e, int width, int height, BufferedImage image) {
        int x = Math.round(e.x() * width), y = Math.round(e.y() * height);
        int w = Math.max(1, Math.round(e.w() * width)), h = Math.max(1, Math.round(e.h() * height));
        if (image == null) {
            g.setColor(new Color(0x60B9B4AA, true));
            g.fillRect(x, y, w, h);
            return;
        }
        drawFitted(g, image, image.getWidth(), image.getHeight(), x, y, w, h);
    }

    private static void drawFitted(Graphics2D g, Image image, int iw, int ih, int x, int y, int w, int h) {
        float scale = Math.min(w / (float) iw, h / (float) ih);
        int dw = Math.max(1, Math.round(iw * scale));
        int dh = Math.max(1, Math.round(ih * scale));
        g.drawImage(image, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
    }

    private static void formula(Graphics2D g, Canvas.Element e, int width, int height) {
        int x = Math.round(e.x() * width), y = Math.round(e.y() * height);
        int w = Math.max(1, Math.round(e.w() * width)), h = Math.max(1, Math.round(e.h() * height));
        Image image = renderLatex(e.text(), Math.max(4.0F, e.size() * height), e.color() & 0xFFFFFF);
        if (image == null) {
            g.setColor(new Color(0xC8322D));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(6, Math.round(e.size() * height * 0.8F))));
            g.drawString(e.text(), x, y + g.getFontMetrics().getAscent());
            return;
        }
        int iw = image.getWidth(null), ih = image.getHeight(null);
        if (iw <= w && ih <= h) {
            g.drawImage(image, x, y + (h - ih) / 2, null);
        } else {
            drawFitted(g, image, iw, ih, x, y, w, h);
        }
    }

    /** A LaTeX formula as an image (transparent background), or null if it cannot be parsed. */
    static Image renderLatex(String latex, float sizePx, int rgb) {
        if (latexBroken || latex == null || latex.isBlank()) return null;
        try {
            return org.scilab.forge.jlatexmath.TeXFormula.createBufferedImage(latex,
                    org.scilab.forge.jlatexmath.TeXConstants.STYLE_DISPLAY, sizePx, color(rgb), null);
        } catch (LinkageError err) {
            latexBroken = true;
            LOGGER.warn("EnPlotify: LaTeX formulas are unavailable (this Java has no desktop graphics)", err);
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void stroke(Graphics2D g, Canvas.Element e, int width, int height) {
        List<Integer> points = e.points();
        if (points.size() < 2) return;
        Path2D.Float path = new Path2D.Float();
        path.moveTo(Canvas.pointX(points.get(0)) * width, Canvas.pointY(points.get(0)) * height);
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(Canvas.pointX(points.get(i)) * width, Canvas.pointY(points.get(i)) * height);
        }
        g.setColor(color(e.color()));
        g.setStroke(new BasicStroke(Math.max(1.0F, e.size() * height), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
    }

    private CanvasRaster() {}
}
