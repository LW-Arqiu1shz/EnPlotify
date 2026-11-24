package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Text elements, drawn with Minecraft's own font (bold, italic, underline and strikethrough are the
 * font's own; superscript and subscript are smaller characters raised / lowered). Lines are laid out
 * once per element and width and cached; drawing then only submits ready-made pieces.
 * <p>
 * Layout units are font units. An element's {@code size} is the glyph height as a fraction of the
 * surface height, i.e. {@link #GLYPH} font units; lines are {@link #LINE} units apart. On screen the
 * scale is rounded so one font pixel covers a whole number of screen pixels (as sharp as the game's
 * own text), and the text is wrapped at the width it really has there.
 */
final class CanvasText {
    static final float LINE = 10.0F;
    /** Font units in the height of the glyphs (what a size of N px makes N pixels tall). */
    static final float GLYPH = 8.0F;
    private static final float SCRIPT_SCALE = 0.62F;

    /** One piece of a line in one style, starting at {@code x} font units. */
    record Piece(FormattedCharSequence text, float x, int script) {}

    /**
     * One line: characters {@code [start, end)} of the text; {@code xs[i]} is where the caret before
     * character {@code start + i} stands (length {@code end - start + 1}).
     */
    record Line(List<Piece> pieces, float width, int start, int end, float[] xs) {}

    record Layout(List<Line> lines) {
        float height() {
            return lines.size() * LINE;
        }
    }

    /**
     * Where a text element sits on screen: font unit {@code (0, 0)} of its first line is at GUI
     * {@code (x, y)}, one font unit is {@code unit} GUI pixels.
     */
    record Placement(Layout layout, float unit, float x, float y) {}

    private record Key(Canvas.Element element, float maxWidth) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.element == element && k.maxWidth == maxWidth;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(element) * 31 + Float.hashCode(maxWidth);
        }
    }

    private static final Map<Key, Layout> CACHE = new HashMap<>();
    private static final Map<Key, Long> USED = new HashMap<>();
    private static long lastSweep;
    /** Advance of one character in one style (colour does not change it). */
    private static final Map<Integer, Float> ADVANCE = new HashMap<>();

    // ------------------------------------------------------------------ layout

    /** Lines of a text element, wrapped at {@code maxWidth} font units. */
    static Layout layout(Canvas.Element e, float maxWidth) {
        Key key = new Key(e, Math.max(LINE, maxWidth));
        long now = Util.getMillis();
        USED.put(key, now);
        if (now - lastSweep > 5000L) sweep(now);
        return CACHE.computeIfAbsent(key, k -> build(e, k.maxWidth()));
    }

    private static void sweep(long now) {
        lastSweep = now;
        Iterator<Map.Entry<Key, Long>> it = USED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Long> next = it.next();
            if (now - next.getValue() > 10000L) {
                CACHE.remove(next.getKey());
                it.remove();
            }
        }
        if (ADVANCE.size() > 8192) ADVANCE.clear();
    }

    static int align(int color) {
        return (color >>> PaperContents.ALIGN_SHIFT) & 3;
    }

    static int valign(int color) {
        return (color >>> PaperContents.VALIGN_SHIFT) & 3;
    }

    private static Style style(int flags, int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb & 0xFFFFFF))
                .withBold((flags & Canvas.BOLD) != 0)
                .withItalic((flags & Canvas.ITALIC) != 0)
                .withUnderlined((flags & Canvas.UNDERLINE) != 0)
                .withStrikethrough((flags & Canvas.STRIKE) != 0);
    }

    private static int script(int flags) {
        return (flags & Canvas.SUPER) != 0 ? 1 : (flags & Canvas.SUB) != 0 ? -1 : 0;
    }

    private static float advance(Font font, char c, int flags) {
        int key = (c << 6) | (flags & 63);
        Float known = ADVANCE.get(key);
        if (known != null) return known;
        float w = font.width(Component.literal(String.valueOf(c)).withStyle(style(flags, 0)));
        if (script(flags) != 0) w *= SCRIPT_SCALE;
        ADVANCE.put(key, w);
        return w;
    }

    private static Layout build(Canvas.Element e, float maxWidth) {
        Font font = Minecraft.getInstance().font;
        String text = e.text();
        int n = text.length();
        int[] flags = new int[n];
        int[] colors = new int[n];
        Arrays.fill(colors, e.color() & 0xFFFFFF);
        for (Canvas.Run run : e.runs()) {
            for (int i = Math.max(0, run.start()); i < Math.min(n, run.end()); i++) {
                flags[i] = run.flags();
                if (run.color() >= 0) colors[i] = run.color();
            }
        }
        float[] widths = new float[n];
        for (int i = 0; i < n; i++) widths[i] = text.charAt(i) == '\n' ? 0 : advance(font, text.charAt(i), flags[i]);
        int align = align(e.color());

        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start <= n) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = n;
            breakParagraph(text, flags, colors, widths, start, end, maxWidth, align, lines);
            start = end + 1;
        }
        return new Layout(lines);
    }

    /** Greedy line breaking: Latin text breaks at spaces, CJK between any two characters. */
    private static void breakParagraph(String text, int[] flags, int[] colors, float[] widths, int start, int end,
                                       float maxWidth, int align, List<Line> lines) {
        if (start == end) {
            lines.add(makeLine(text, flags, colors, widths, start, end, maxWidth, align));
            return;
        }
        int lineStart = start;
        float width = 0;
        int lastSpace = -1;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            float w = widths[i];
            if (width + w > maxWidth && i > lineStart) {
                int breakAt = (c != ' ' && lastSpace > lineStart && c < 0x2E80) ? lastSpace : i;
                lines.add(makeLine(text, flags, colors, widths, lineStart, breakAt, maxWidth, align));
                lineStart = breakAt;
                while (lineStart < end && text.charAt(lineStart) == ' ') lineStart++;
                width = 0;
                for (int j = lineStart; j < i; j++) width += widths[j];
                lastSpace = -1;
                if (lineStart > i) {
                    i = lineStart - 1;
                    continue;
                }
            }
            if (c == ' ') lastSpace = i;
            width += w;
        }
        if (lineStart < end) lines.add(makeLine(text, flags, colors, widths, lineStart, end, maxWidth, align));
    }

    private static Line makeLine(String text, int[] flags, int[] colors, float[] widths, int start, int end,
                                 float maxWidth, int align) {
        float width = 0;
        for (int i = start; i < end; i++) width += widths[i];
        float shift = align == 1 ? (maxWidth - width) / 2 : align == 2 ? maxWidth - width : 0;
        List<Piece> pieces = new ArrayList<>();
        float[] xs = new float[end - start + 1];
        float x = shift;
        int pieceStart = start;
        for (int i = start + 1; i <= end; i++) {
            if (i == end || flags[i] != flags[pieceStart] || colors[i] != colors[pieceStart]) {
                int script = script(flags[pieceStart]);
                // Normal pieces are drawn at whole font units.
                float px = script == 0 ? Math.round(x) : x;
                Component part = Component.literal(text.substring(pieceStart, i)).withStyle(style(flags[pieceStart], colors[pieceStart]));
                pieces.add(new Piece(part.getVisualOrderText(), px, script));
                float cx = px;
                for (int j = pieceStart; j < i; j++) {
                    xs[j - start] = cx;
                    cx += widths[j];
                }
                x = cx;
                pieceStart = i;
            }
        }
        xs[end - start] = start == end ? shift : x;
        return new Line(pieces, width, start, end, xs);
    }

    // ------------------------------------------------------------------ placement and caret

    /** Where the element's text sits in GUI rectangle {@code r}. */
    static Placement placeGui(Canvas.Element e, PaperGui.Rect r) {
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        // Lines break exactly as in the world (same width in font units); the text is drawn at a
        // whole number of real pixels per font pixel only when that is almost the true size.
        float exact = Math.max(1.0E-4F, e.size()) / GLYPH * r.h();
        float sharp = crisp(exact, guiScale);
        float unit = Math.abs(sharp - exact) <= exact * 0.03F ? sharp : exact;
        Layout layout = layout(e, e.w() * r.w() / exact);
        float boxH = e.h() * r.h() / unit;
        float top = verticalOffset(e, layout, boxH);
        float x = snap(r.x() + e.x() * r.w(), guiScale);
        float y = snap(r.y() + e.y() * r.h() + top * unit, guiScale);
        return new Placement(layout, unit, x, y);
    }

    private static float verticalOffset(Canvas.Element e, Layout layout, float boxH) {
        int v = valign(e.color());
        if (v == 0) return 0;
        float free = Math.max(0, boxH - layout.height());
        return v == 1 ? free / 2 : free;
    }

    /** The line holding caret position {@code index}. */
    static int lineOf(Layout layout, int index) {
        List<Line> lines = layout.lines();
        int best = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).start() <= index) best = i;
            else break;
        }
        return best;
    }

    /** GUI {x, top, bottom} of the caret before character {@code index}. */
    static float[] caret(Placement p, int index) {
        int li = lineOf(p.layout(), index);
        Line line = p.layout().lines().get(li);
        int k = Math.max(0, Math.min(index, line.end()) - line.start());
        float x = p.x() + line.xs()[k] * p.unit();
        float top = p.y() + (li * LINE) * p.unit();
        return new float[] {x, top, top + (GLYPH + 2) * p.unit()};
    }

    /** Caret position nearest to GUI point (gx, gy). */
    static int indexAt(Placement p, double gx, double gy) {
        List<Line> lines = p.layout().lines();
        int li = (int) Math.floor((gy - p.y()) / (LINE * p.unit()));
        li = Math.max(0, Math.min(lines.size() - 1, li));
        return indexInLine(lines.get(li), (float) ((gx - p.x()) / p.unit()));
    }

    /** Caret position in line {@code li} nearest to {@code x} font units. */
    static int indexInLine(Line line, float x) {
        float[] xs = line.xs();
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int k = 0; k < xs.length; k++) {
            float d = Math.abs(xs[k] - x);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return line.start() + best;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the canvas's text elements in a GUI rectangle (on top of its painted picture), clipped to
     * it. {@code skip} is left out.
     */
    static void drawGui(GuiGraphicsExtractor graphics, Canvas canvas, PaperGui.Rect r, Canvas.Element skip) {
        graphics.enableScissor(r.x(), r.y(), r.x() + r.w(), r.y() + r.h());
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() != Canvas.TEXT || e == skip || e.text().isEmpty()) continue;
            drawElementGui(graphics, placeGui(e, r), r);
        }
        graphics.disableScissor();
    }

    /** Draws one placed text element (callers clip). */
    static void drawElementGui(GuiGraphicsExtractor graphics, Placement p, PaperGui.Rect r) {
        Font font = Minecraft.getInstance().font;
        Matrix3x2fStack pose = graphics.pose();
        float unit = p.unit();
        pose.pushMatrix();
        pose.translate(p.x(), p.y());
        pose.scale(unit, unit);
        List<Line> lines = p.layout().lines();
        for (int i = 0; i < lines.size(); i++) {
            float y = i * LINE + 1.0F;
            float top = p.y() + y * unit;
            if (top > r.y() + r.h()) break;
            if (top + LINE * unit < r.y()) continue;
            for (Piece piece : lines.get(i).pieces()) {
                if (piece.script() == 0) {
                    graphics.text(font, piece.text(), Math.round(piece.x()), Math.round(y), 0xFF000000, false);
                } else {
                    pose.pushMatrix();
                    pose.translate(piece.x(), y + (piece.script() > 0 ? -1.5F : 4.0F));
                    pose.scale(SCRIPT_SCALE, SCRIPT_SCALE);
                    graphics.text(font, piece.text(), 0, 0, 0xFF000000, false);
                    pose.popMatrix();
                }
            }
        }
        pose.popMatrix();
    }

    /**
     * Draws the canvas's text elements in the world, on a surface spanning (x0, y0) .. (x1, y1) in
     * the local plane (y up, facing +z), slightly in front of {@code z}.
     */
    static void drawWorld(PoseStack poseStack, SubmitNodeCollector collector, Canvas canvas,
                          float x0, float y0, float x1, float y1, float z, int light) {
        drawWorld(poseStack, collector, canvas, x0, y0, x1, y1, z, light, Font.DisplayMode.POLYGON_OFFSET);
    }

    /**
     * Like above; {@code NORMAL} keeps the text exactly at {@code z} (for sheets that other sheets may
     * lie on: polygon offset would pull the text through them).
     */
    static void drawWorld(PoseStack poseStack, SubmitNodeCollector collector, Canvas canvas,
                          float x0, float y0, float x1, float y1, float z, int light, Font.DisplayMode mode) {
        float w = x1 - x0, h = y1 - y0;
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() != Canvas.TEXT || e.text().isEmpty()) continue;
            float unit = Math.max(1.0E-4F, e.size()) / GLYPH * h;
            Layout layout = layout(e, e.w() * w / unit);
            float top = verticalOffset(e, layout, e.h() * h / unit);
            poseStack.pushPose();
            poseStack.translate(x0 + e.x() * w, y1 - e.y() * h - top * unit, z);
            poseStack.scale(unit, -unit, unit);
            float room = (1.0F - e.y()) * h / unit - top;
            for (int i = 0; i < layout.lines().size(); i++) {
                float y = i * LINE + 1.0F;
                if (y + GLYPH > room + 0.5F) break;
                for (Piece p : layout.lines().get(i).pieces()) {
                    if (p.script() == 0) {
                        collector.submitText(poseStack, p.x(), y, p.text(), false, mode, light, 0xFF000000, 0, 0);
                    } else {
                        poseStack.pushPose();
                        poseStack.translate(p.x(), y + (p.script() > 0 ? -1.5F : 4.0F), 0.0F);
                        poseStack.scale(SCRIPT_SCALE, SCRIPT_SCALE, SCRIPT_SCALE);
                        collector.submitText(poseStack, 0.0F, 0.0F, p.text(), false, mode, light, 0xFF000000, 0, 0);
                        poseStack.popPose();
                    }
                }
            }
            poseStack.popPose();
        }
    }

    /** GUI pixels per font unit, rounded so a font pixel is a whole number of real pixels. */
    private static float crisp(float unitGui, float guiScale) {
        float real = unitGui * guiScale;
        return (real < 1.0F ? Math.max(0.25F, Math.round(real * 4.0F) / 4.0F) : Math.round(real)) / guiScale;
    }

    /** A GUI coordinate moved onto the real pixel grid. */
    private static float snap(float gui, float guiScale) {
        return Math.round(gui * guiScale) / guiScale;
    }

    static boolean hasText(Canvas canvas) {
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() == Canvas.TEXT && !e.text().isEmpty()) return true;
        }
        return false;
    }

    private CanvasText() {}
}
