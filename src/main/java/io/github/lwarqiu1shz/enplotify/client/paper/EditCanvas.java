package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.Canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A canvas being edited: mutable elements, with per-character text styles. */
final class EditCanvas {
    final List<Item> items = new ArrayList<>();

    /** One element being edited. Text styles are kept per character until saved as runs. */
    static final class Item {
        int type;
        float x, y, w, h;
        String text = "";
        /** Per character: style flags, and colour (-1 = the element's colour). */
        int[] flags = new int[0];
        int[] colors = new int[0];
        int color;
        float size;
        List<Integer> points = new ArrayList<>();

        Item copy() {
            Item c = new Item();
            c.type = type;
            c.x = x; c.y = y; c.w = w; c.h = h;
            c.text = text;
            c.flags = flags.clone();
            c.colors = colors.clone();
            c.color = color;
            c.size = size;
            c.points = new ArrayList<>(points);
            return c;
        }

        static Item of(Canvas.Element e) {
            Item item = new Item();
            item.type = e.type();
            item.x = e.x(); item.y = e.y(); item.w = e.w(); item.h = e.h();
            item.text = e.text();
            item.color = e.color();
            item.size = e.size();
            item.points = new ArrayList<>(e.points());
            item.flags = new int[item.text.length()];
            item.colors = new int[item.text.length()];
            Arrays.fill(item.colors, -1);
            for (Canvas.Run run : e.runs()) {
                for (int i = Math.max(0, run.start()); i < Math.min(item.text.length(), run.end()); i++) {
                    item.flags[i] = run.flags();
                    item.colors[i] = run.color();
                }
            }
            return item;
        }

        /** Keeps the per-character style arrays as long as the text (formulas set {@code text} directly). */
        void fitStyles() {
            if (flags.length == text.length() && colors.length == text.length()) return;
            int[] nf = new int[text.length()];
            int[] nc = new int[text.length()];
            Arrays.fill(nc, -1);
            System.arraycopy(flags, 0, nf, 0, Math.min(flags.length, nf.length));
            System.arraycopy(colors, 0, nc, 0, Math.min(colors.length, nc.length));
            flags = nf;
            colors = nc;
        }

        Canvas.Element toElement() {
            List<Canvas.Run> runs = new ArrayList<>();
            if (type != Canvas.TEXT) return new Canvas.Element(type, x, y, w, h, text, runs, color, size, List.copyOf(points));
            fitStyles();
            int start = 0;
            for (int i = 1; i <= text.length(); i++) {
                if (i == text.length() || flags[i] != flags[start] || colors[i] != colors[start]) {
                    if (flags[start] != 0 || colors[start] >= 0) runs.add(new Canvas.Run(start, i, flags[start], colors[start]));
                    start = i;
                }
            }
            return new Canvas.Element(type, x, y, w, h, text, runs, color, size, List.copyOf(points));
        }

        /**
         * The text changed from {@code text} to {@code next} (typing, pasting, deleting): the styles of
         * untouched characters move with them; inserted characters take {@code typingFlags / typingColor}.
         */
        void textChanged(String next, int typingFlags, int typingColor) {
            fitStyles();
            String old = text;
            int prefix = 0;
            int max = Math.min(old.length(), next.length());
            while (prefix < max && old.charAt(prefix) == next.charAt(prefix)) prefix++;
            int suffix = 0;
            while (suffix < max - prefix && old.charAt(old.length() - 1 - suffix) == next.charAt(next.length() - 1 - suffix)) suffix++;
            int inserted = next.length() - prefix - suffix;
            int[] nf = new int[next.length()];
            int[] nc = new int[next.length()];
            System.arraycopy(flags, 0, nf, 0, prefix);
            System.arraycopy(colors, 0, nc, 0, prefix);
            for (int i = 0; i < inserted; i++) {
                nf[prefix + i] = typingFlags;
                nc[prefix + i] = typingColor;
            }
            System.arraycopy(flags, old.length() - suffix, nf, prefix + inserted, suffix);
            System.arraycopy(colors, old.length() - suffix, nc, prefix + inserted, suffix);
            text = next;
            flags = nf;
            colors = nc;
        }

        /** Toggles a style flag on [start, end): on for all if any lacks it, else off for all. */
        void toggle(int start, int end, int flag) {
            fitStyles();
            start = Math.max(0, start);
            end = Math.min(text.length(), end);
            boolean allOn = true;
            for (int i = start; i < end; i++) allOn &= (flags[i] & flag) != 0;
            for (int i = start; i < end; i++) {
                int f = allOn ? flags[i] & ~flag : flags[i] | flag;
                // Superscript and subscript exclude each other.
                if (!allOn && flag == Canvas.SUPER) f &= ~Canvas.SUB;
                if (!allOn && flag == Canvas.SUB) f &= ~Canvas.SUPER;
                flags[i] = f;
            }
        }

        void recolor(int start, int end, int rgb) {
            fitStyles();
            for (int i = Math.max(0, start); i < Math.min(text.length(), end); i++) colors[i] = rgb;
        }

        /** Bounding box of a stroke, from its points. */
        float[] strokeBounds() {
            float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
            for (int p : points) {
                float px = Canvas.pointX(p), py = Canvas.pointY(p);
                x0 = Math.min(x0, px); y0 = Math.min(y0, py);
                x1 = Math.max(x1, px); y1 = Math.max(y1, py);
            }
            return new float[] {x0, y0, Math.max(0.005F, x1 - x0), Math.max(0.005F, y1 - y0)};
        }

        /** {x, y, w, h} of the element (strokes: their points' bounds). */
        float[] bounds() {
            return type == Canvas.STROKE ? strokeBounds() : new float[] {x, y, w, h};
        }

        /** Scales position and size (posters changing size: {@code sx = old width / new width}). */
        void rescale(float sx, float sy) {
            if (type == Canvas.STROKE) {
                List<Integer> moved = new ArrayList<>(points.size());
                for (int p : points) moved.add(Canvas.packPoint(Canvas.pointX(p) * sx, Canvas.pointY(p) * sy));
                points = moved;
                size *= sy;
                return;
            }
            x *= sx; w *= sx;
            y *= sy; h *= sy;
            if (type == Canvas.TEXT || type == Canvas.FORMULA) size *= sy;
        }

        /** Moves / scales the element to a new box. */
        void setBounds(float nx, float ny, float nw, float nh) {
            if (type == Canvas.STROKE) {
                float[] b = strokeBounds();
                List<Integer> moved = new ArrayList<>(points.size());
                for (int p : points) {
                    float px = nx + (Canvas.pointX(p) - b[0]) / b[2] * nw;
                    float py = ny + (Canvas.pointY(p) - b[1]) / b[3] * nh;
                    moved.add(Canvas.packPoint(px, py));
                }
                points = moved;
            } else {
                x = nx; y = ny; w = nw; h = nh;
            }
        }
    }

    static EditCanvas of(Canvas canvas) {
        EditCanvas edit = new EditCanvas();
        for (Canvas.Element e : canvas.elements()) edit.items.add(Item.of(e));
        return edit;
    }

    Canvas toCanvas() {
        List<Canvas.Element> elements = new ArrayList<>(items.size());
        for (Item item : items) elements.add(item.toElement());
        return new Canvas(elements);
    }
}
