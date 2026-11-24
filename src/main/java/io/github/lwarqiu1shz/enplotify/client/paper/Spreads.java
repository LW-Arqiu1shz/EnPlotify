package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Newspaper sides are edited as one spread (left half + right half side by side) and saved as two
 * halves again. Also the snapping grids of papers.
 */
final class Spreads {
    /** Margin around the printable area of a page / half, as a fraction of its width and height. */
    static final float MARGIN = 0.05F;
    static final int CELLS = 16;

    static List<CanvasEditorScreen.Region> pageGrid() {
        return new ArrayList<>(List.of(new CanvasEditorScreen.Region(MARGIN, MARGIN, 1 - MARGIN, 1 - MARGIN, CELLS, CELLS)));
    }

    /** One 16 x 16 grid per half of the spread. */
    static List<CanvasEditorScreen.Region> spreadGrid() {
        float m = MARGIN / 2.0F;
        return new ArrayList<>(List.of(
                new CanvasEditorScreen.Region(m, MARGIN, 0.5F - m, 1 - MARGIN, CELLS, CELLS),
                new CanvasEditorScreen.Region(0.5F + m, MARGIN, 1 - m, 1 - MARGIN, CELLS, CELLS)));
    }

    static EditCanvas merge(Canvas left, Canvas right) {
        EditCanvas spread = new EditCanvas();
        add(spread, left, 0.0F);
        add(spread, right, 0.5F);
        return spread;
    }

    private static void add(EditCanvas spread, Canvas half, float offset) {
        for (Canvas.Element e : half.elements()) {
            EditCanvas.Item item = EditCanvas.Item.of(e);
            if (item.type == Canvas.STROKE) {
                List<Integer> moved = new ArrayList<>(item.points.size());
                for (int p : item.points) moved.add(Canvas.packPoint(offset + Canvas.pointX(p) / 2, Canvas.pointY(p)));
                item.points = moved;
            } else {
                item.x = offset + item.x / 2;
                item.w = item.w / 2;
            }
            spread.items.add(item);
        }
    }

    /** Splits a spread into its left and right halves; each element goes to the half its centre is on. */
    static List<Canvas> split(Canvas spread) {
        List<Canvas.Element> left = new ArrayList<>();
        List<Canvas.Element> right = new ArrayList<>();
        for (Canvas.Element e : spread.elements()) {
            boolean isRight = e.x() + e.w() / 2 >= 0.5F;
            float offset = isRight ? 0.5F : 0.0F;
            float x = (e.x() - offset) * 2;
            float w = Math.min(e.w() * 2, 1.0F - Math.max(0, x));
            Canvas.Element moved = new Canvas.Element(e.type(), Math.max(0, x), e.y(), Math.max(0.01F, w), e.h(),
                    e.text(), e.runs(), e.color(), e.size(), e.points());
            (isRight ? right : left).add(moved);
        }
        return List.of(new Canvas(left), new Canvas(right));
    }

    private Spreads() {}
}
