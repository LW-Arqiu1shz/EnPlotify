package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.mixin.client.MultiLineEditBoxAccessor;
import io.github.lwarqiu1shz.enplotify.mixin.client.MultilineTextFieldAccessor;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.awt.Image;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The editor shared by posters, newspapers, documents and boards: the surface in the middle (at
 * most 90 % of the screen wide, in its real proportions), text tools along the left edge, pictures /
 * handwriting / pages / saving along the right edge. Ctrl+Z / Ctrl+Y undo and redo.
 * <p>
 * Papers and posters snap to a grid: a page's printable area (and every half of a newspaper) is
 * cut into 16 x 16 equal rectangles, a poster into 16 x 16 squares per cell. Boards are free.
 */
final class CanvasEditorScreen extends Screen {
    enum Tool { SELECT, TEXT, PEN, ERASE_STROKE, ERASE_PIXEL }

    /** A snapping area: {@code cols x rows} equal rectangles over (x0, y0) .. (x1, y1). */
    record Region(float x0, float y0, float x1, float y1, int cols, int rows) {
        float cw() { return (x1 - x0) / cols; }
        float ch() { return (y1 - y0) / rows; }
        boolean contains(float u, float v) { return u >= x0 && u <= x1 && v >= y0 && v <= y1; }
    }

    /** What is being edited and how it is saved. */
    static final class Config {
        Component title = Component.empty();
        final List<EditCanvas> surfaces = new ArrayList<>();
        /** Names of the surfaces (newspaper sides); null: numbered pages. */
        @Nullable List<Component> surfaceNames;
        boolean pages;
        float aspect = 1.0F;
        /** Height of one surface in px (1 block = 96 px, the scale of sign text); sizes and widths are in px. */
        float heightPx = 68.0F;
        /** Sizes are px of the sheet as shown in the editor (posters, newspapers, documents), not of the world. */
        boolean screenPx;
        int paper = 0xFFFFFFFF;
        boolean board;
        int[] quickColors = PaperContents.MARKER_COLORS;
        int textColor = 0x2A2522;
        float textPx = 3.0F;
        /** Snapping areas; empty = free placement. Rebuilt for posters when their size changes. */
        List<Region> grid = new ArrayList<>();
        /** Lines drawn down the middle (newspaper fold). */
        boolean fold;
        @Nullable String itemTitle;
        /** Hint in the title / name box. */
        String titleHint = "enplotify.paper.title";
        /** Animated pictures (GIF, video) may be added. */
        boolean animations;
        boolean poster;
        /** The poster still needs its size chosen before anything can be placed on it. */
        boolean posterNeedsSize;
        int posterW = 1, posterH = 1;
        int imageMaxSide = 256;
        boolean canFinalize = true;
        Saver saver = (surfaces, title, w, h, lock) -> {};
        /** Closed without saving. */
        @Nullable Runnable onCancelled;
        /**
         * The undo history belongs to the thing (a board), not to this editor: it opens with the
         * versions saved before (oldest first) and after (next first), and on saving tells which
         * of them (numbered: undo versions, the opened state, redo versions) are still reachable.
         */
        boolean sharedHistory;
        List<Saved> undoVersions = List.of();
        List<Saved> redoVersions = List.of();
        int[] undoTags = new int[0];
        int[] redoTags = new int[0];
        /**
         * Drawn under the writing (a SAWMO whiteboard's screen); returns false when there is nothing
         * to show (plain paper then). With one, the writing is painted see-through.
         */
        @Nullable Backdrop backdrop;
    }

    interface Backdrop {
        boolean draw(GuiGraphicsExtractor graphics, int x, int y, int w, int h);
    }

    /** A saved version of a single-surface thing. */
    record Saved(Canvas canvas, String name) {}

    interface Saver {
        void save(List<Canvas> surfaces, String title, int posterW, int posterH, boolean lock);
    }

    /** One block is this many px: the same scale as text on a sign (8 px text = sign text). */
    static final float PX_PER_BLOCK = 96.0F;
    static final float[] TEXT_PX = {1, 1.5F, 2, 2.5F, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32, 48, 64};
    static final float[] PEN_PX = {0.5F, 1, 1.5F, 2, 3, 4, 6, 8, 12, 16};

    private static final int BUTTON = 18;
    private static final int GAP = 1;
    /** Space between the sheet and the button columns: a quarter of a button. */
    private static final int SIDE_GAP = (BUTTON + 2) / 4;
    private static final int[] PALETTE = {
            0x000000, 0x404040, 0x808080, 0xFFFFFF, 0x2A2522, 0xC8322D, 0xE07B2A, 0xF3D33A,
            0x2E8B3A, 0x1B5E20, 0x22A0C8, 0x2D5AC8, 0x1A237E, 0x7B3FA0, 0xD04A9A, 0x8B5A2B};

    private final Config config;
    private int surface;
    private Tool tool = Tool.SELECT;
    private EditCanvas.@Nullable Item selected;
    private int textColor;
    private float textPx;
    private int penColor;
    private float penPx;
    /** Eraser width in px; follows the pen until chosen. */
    private float eraserPx = -1;
    private int typingFlags;
    private int typingColor = -1;

    private enum Drag { NONE, MOVE, RESIZE, PEN, ERASE, SELECT_TEXT }
    private Drag drag = Drag.NONE;
    /** Corner being dragged: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. */
    private int resizeCorner = 3;
    /** Alignment for new text boxes: centred both ways until changed. */
    private int newAlign = 1, newValign = 1;
    private @Nullable Button alignButton, valignButton, textColorButton, penColorButton;
    /** The text being typed, as an element (rebuilt only when it changed). */
    private Canvas.@Nullable Element editingElement;
    private @Nullable IMEPreeditOverlay preedit;
    private long caretMoved;
    private float dragStartU, dragStartV;
    private float[] dragStartBounds = new float[4];
    private final List<Integer> penPoints = new ArrayList<>();
    private double mouseX, mouseY;

    private EditCanvas.@Nullable Item editingText;
    private @Nullable MultiLineEditBox textBox;
    private EditCanvas.@Nullable Item editingFormula;
    private @Nullable EditBox formulaBox;
    private @Nullable EditBox titleBox;
    private String itemTitle;

    private enum PaletteTarget { NONE, TEXT, PEN }
    private PaletteTarget palette = PaletteTarget.NONE;
    private @Nullable EditBox hexBox;

    /**
     * A list of choices popped up next to a button (font size, pen width, poster size), optionally
     * with a box to type any other number.
     */
    private record Choices(Button anchor, boolean leftOfAnchor, List<String> labels, int current, IntConsumer pick,
                           java.util.function.@Nullable Consumer<Float> custom) {}
    private @Nullable Choices choices;
    /** Typing box shown on top of the button (font size, pen width): type a number, Enter. */
    private @Nullable EditBox customBox;
    private String customStart = "";
    /** First list row shown (long lists scroll with the mouse wheel). */
    private int choicesScroll;
    private static final int ROW = 11;

    /** {@code tag}: which saved version this is (-1: a state from this editor only); {@code title}: its name, if saved. */
    private record Snapshot(List<EditCanvas> surfaces, int surface, int posterW, int posterH, boolean needsSize,
                            int tag, @Nullable String title) {}
    /** The saved version shown right now, or -1 once something was changed. */
    private int currentTag = -1;
    private boolean saved;
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();

    private @Nullable Canvas shown;
    private @Nullable Canvas latest;
    private boolean dirty = true;
    private long lastChange;

    private Component status = Component.empty();
    private int statusColor = 0xFFA8A8A8;
    private boolean uploading;
    private PaperGui.Rect area = new PaperGui.Rect(0, 0, 1, 1);

    CanvasEditorScreen(Config config) {
        super(config.title);
        this.config = config;
        this.textColor = config.textColor;
        this.textPx = config.textPx;
        this.penColor = config.quickColors[0];
        this.penPx = 2;
        this.itemTitle = config.itemTitle == null ? "" : config.itemTitle;
        if (config.poster) rebuildPosterGrid();
        if (config.sharedHistory) {
            int u = config.undoVersions.size();
            for (int i = 0; i < u; i++) undo.push(savedSnapshot(config.undoVersions.get(i), i));
            for (int i = config.redoVersions.size() - 1; i >= 0; i--) redo.push(savedSnapshot(config.redoVersions.get(i), u + 1 + i));
            currentTag = u;
        }
    }

    private EditCanvas canvas() {
        return config.surfaces.get(surface);
    }

    private float aspect() {
        return config.poster ? config.posterW / (float) config.posterH : config.aspect;
    }

    private float heightPx() {
        if (config.screenPx) return Math.max(1, area.h());
        return config.poster ? config.posterH * PX_PER_BLOCK : config.heightPx;
    }

    private float pxToSize(float px) {
        return px / heightPx();
    }

    private float sizeToPx(float size) {
        return size * heightPx();
    }

    private float penWidth() {
        return penPx / heightPx();
    }

    private float eraserWidth() {
        return (eraserPx > 0 ? eraserPx : penPx) / heightPx();
    }

    private static String number(float value) {
        float rounded = Math.round(value * 10.0F) / 10.0F;
        return rounded == Math.rint(rounded) ? String.valueOf((int) rounded) : String.format(java.util.Locale.ROOT, "%.1f", rounded);
    }

    private void rebuildPosterGrid() {
        config.grid = new ArrayList<>(List.of(new Region(0, 0, 1, 1, 16 * config.posterW, 16 * config.posterH)));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int top = config.itemTitle != null ? 34 : 18;
        area = PaperGui.fit(this.width, top, this.height - 16, aspect(), BUTTON + SIDE_GAP + 3 + (config.board ? 4 : 0));
        textColorButton = penColorButton = null;
        closeChoices();

        if (config.itemTitle != null) {
            titleBox = new EditBox(this.font, this.width / 2 - 100, 14, 200, 16, Component.translatable(config.titleHint));
            titleBox.setMaxLength(PaperContents.MAX_TITLE);
            titleBox.setHint(Component.translatable(config.titleHint));
            titleBox.setValue(itemTitle);
            titleBox.setResponder(v -> itemTitle = v);
            addRenderableWidget(titleBox);
        }

        if (config.posterNeedsSize) {
            initPosterSizeStep();
            return;
        }

        // Left: text and formatting.
        List<@Nullable Button> left = new ArrayList<>();
        left.add(tool("T", "enplotify.editor.text", () -> setTool(Tool.TEXT)));
        left.add(tool("∑", "enplotify.editor.formula", this::addFormula));
        left.add(tool("B", "enplotify.editor.bold", () -> applyStyle(Canvas.BOLD)));
        left.add(tool("I", "enplotify.editor.italic", () -> applyStyle(Canvas.ITALIC)));
        left.add(tool("x²", "enplotify.editor.superscript", () -> applyStyle(Canvas.SUPER)));
        left.add(tool("x₂", "enplotify.editor.subscript", () -> applyStyle(Canvas.SUB)));
        Button sizeButton = Button.builder(Component.literal(number(currentPx())), b -> openSizeChoices(b))
                .tooltip(Tooltip.create(Component.translatable(config.screenPx ? "enplotify.editor.font_size_screen" : "enplotify.editor.font_size"))).size(BUTTON, BUTTON).build();
        left.add(sizeButton);
        alignButton = tool("↔", "enplotify.editor.align", () -> cycleAlign(false));
        valignButton = tool("↕", "enplotify.editor.valign", () -> cycleAlign(true));
        left.add(alignButton);
        left.add(valignButton);
        refreshAlignButtons();
        textColorButton = tool("■", "enplotify.editor.text_color", () -> openPalette(PaletteTarget.TEXT));
        left.add(textColorButton);
        left.add(tool("✕", "enplotify.editor.delete", this::deleteSelected));
        layoutColumn(left, false);

        // Right: pictures, handwriting, surfaces, then (well apart) saving.
        List<@Nullable Button> right = new ArrayList<>();
        right.add(tool("↖", "enplotify.editor.select", () -> setTool(Tool.SELECT)));
        if (config.board) {
            right.add(tool("✎", "enplotify.editor.pen", () -> setTool(Tool.PEN)));
            penColorButton = tool("■", "enplotify.editor.pen_color", () -> openPalette(PaletteTarget.PEN));
            right.add(penColorButton);
            right.add(Button.builder(Component.literal(number(tool == Tool.ERASE_STROKE || tool == Tool.ERASE_PIXEL
                            ? (eraserPx > 0 ? eraserPx : penPx) : penPx)), this::openWidthChoices)
                    .tooltip(Tooltip.create(Component.translatable("enplotify.editor.pen_width"))).size(BUTTON, BUTTON).build());
            right.add(tool("⌫", "enplotify.editor.erase_stroke", () -> setTool(Tool.ERASE_STROKE)));
            right.add(tool("░", "enplotify.editor.erase_pixel", () -> setTool(Tool.ERASE_PIXEL)));
        }
        right.add(tool("▣", config.animations ? "enplotify.editor.image_animated" : "enplotify.editor.image", this::chooseImage));
        if (config.board) right.add(tool("⟲", "enplotify.editor.clear", this::clearAll));
        if (config.surfaces.size() > 1 || config.pages) {
            right.add(tool("◀", "enplotify.editor.previous", () -> turn(-1)));
            right.add(tool("▶", "enplotify.editor.next", () -> turn(1)));
        }
        if (config.pages) {
            right.add(tool("+P", "enplotify.editor.add_page", this::addPage));
            right.add(tool("-P", "enplotify.editor.remove_page", this::removePage));
        }
        if (config.poster) {
            right.add(Button.builder(Component.literal("W" + config.posterW), b -> openPosterSizeChoices(b, true))
                    .tooltip(Tooltip.create(Component.translatable("enplotify.editor.poster_width"))).size(BUTTON, BUTTON).build());
            right.add(Button.builder(Component.literal("H" + config.posterH), b -> openPosterSizeChoices(b, false))
                    .tooltip(Tooltip.create(Component.translatable("enplotify.editor.poster_height"))).size(BUTTON, BUTTON).build());
        }
        right.add(null);
        right.add(Button.builder(Component.literal("✔").withColor(0x55FF55), b -> save(false))
                .tooltip(Tooltip.create(Component.translatable("enplotify.paper.save"))).size(BUTTON, BUTTON).build());
        if (config.canFinalize) {
            right.add(Button.builder(Component.literal("\uD83D\uDD12"), b -> save(true))
                    .tooltip(Tooltip.create(Component.translatable("enplotify.paper.finalize").append("\n")
                            .append(Component.translatable("enplotify.paper.finalize.tooltip"))))
                    .size(BUTTON, BUTTON).build());
        }
        right.add(Button.builder(Component.literal("❌").withColor(0xFF5555), b -> onClose())
                .tooltip(Tooltip.create(Component.translatable("gui.cancel"))).size(BUTTON, BUTTON).build());
        layoutColumn(right, true);
        refreshColorButtons();

        if (palette != PaletteTarget.NONE) openPalette(palette);
        if (editingText != null) startTextEdit(editingText, textBox == null ? editingText.text.length() : field().cursor(), false);
        if (editingFormula != null) startFormulaEdit(editingFormula);
        dirty = true;
    }

    /** First step for a new poster: choose its size in cells, then start. */
    private void initPosterSizeStep() {
        List<@Nullable Button> right = new ArrayList<>();
        right.add(Button.builder(Component.literal("W" + config.posterW), b -> openPosterSizeChoices(b, true))
                .tooltip(Tooltip.create(Component.translatable("enplotify.editor.poster_width"))).size(BUTTON, BUTTON).build());
        right.add(Button.builder(Component.literal("H" + config.posterH), b -> openPosterSizeChoices(b, false))
                .tooltip(Tooltip.create(Component.translatable("enplotify.editor.poster_height"))).size(BUTTON, BUTTON).build());
        right.add(null);
        right.add(Button.builder(Component.literal("✔").withColor(0x55FF55), b -> {
            pushUndo();
            config.posterNeedsSize = false;
            rebuildWidgets();
        }).tooltip(Tooltip.create(Component.translatable("enplotify.editor.poster_start"))).size(BUTTON, BUTTON).build());
        right.add(Button.builder(Component.literal("❌").withColor(0xFF5555), b -> onClose())
                .tooltip(Tooltip.create(Component.translatable("gui.cancel"))).size(BUTTON, BUTTON).build());
        layoutColumn(right, true);
    }

    /** The colour buttons show their colour. */
    private void refreshColorButtons() {
        if (textColorButton != null) textColorButton.setMessage(Component.literal("■").withColor(textColor & 0xFFFFFF));
        if (penColorButton != null) penColorButton.setMessage(Component.literal("■").withColor(penColor & 0xFFFFFF));
    }

    private Button tool(String label, String tooltipKey, @Nullable Runnable action) {
        return Button.builder(Component.literal(label), b -> {
                    if (action != null) action.run();
                })
                .tooltip(Tooltip.create(Component.translatable(tooltipKey))).size(BUTTON, BUTTON).build();
    }

    /**
     * Puts buttons in one column right beside the sheet (a quarter of a button away), the whole
     * column centred on the sheet's height. A null is a gap two buttons high. On a short screen
     * the gaps shrink first, then the buttons.
     */
    private void layoutColumn(List<@Nullable Button> buttons, boolean rightSide) {
        int count = 0, gaps = 0;
        for (Button b : buttons) {
            if (b == null) gaps++;
            else count++;
        }
        int room = this.height - (config.itemTitle != null ? 34 : 18) - 14;
        int size = BUTTON, gap = 2 * BUTTON;
        while (count * (size + GAP) + gaps * gap > room) {
            if (gap > size / 2) gap = Math.max(size / 2, gap - 2);
            else if (size > 12) {
                size--;
                gap = Math.min(gap, size / 2);
            } else break;
        }
        int total = count * (size + GAP) - GAP + gaps * gap;
        int y = area.y() + area.h() / 2 - total / 2;
        y = Math.max(2, Math.min(y, this.height - 14 - total));
        int x = rightSide ? area.x() + area.w() + SIDE_GAP : area.x() - SIDE_GAP - size;
        if (config.board) x += rightSide ? 4 : -4; // the board's frame
        for (Button b : buttons) {
            if (b == null) {
                y += gap - GAP;
                continue;
            }
            b.setWidth(size);
            b.setHeight(size);
            b.setX(x);
            b.setY(y);
            addRenderableWidget(b);
            y += size + GAP;
        }
    }

    // ------------------------------------------------------------------ undo / redo

    private Snapshot snapshot() {
        List<EditCanvas> copy = new ArrayList<>();
        for (EditCanvas c : config.surfaces) {
            EditCanvas cc = new EditCanvas();
            for (EditCanvas.Item item : c.items) cc.items.add(item.copy());
            copy.add(cc);
        }
        return new Snapshot(copy, surface, config.posterW, config.posterH, config.posterNeedsSize,
                currentTag, currentTag >= 0 ? itemTitle : null);
    }

    private Snapshot savedSnapshot(Saved version, int tag) {
        return new Snapshot(new ArrayList<>(List.of(EditCanvas.of(version.canvas()))), 0, config.posterW, config.posterH, false, tag, version.name());
    }

    /** Call before every change, so Ctrl+Z can go back to this state. */
    private void pushUndo() {
        undo.push(snapshot());
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
        currentTag = -1;
    }

    private void restore(Snapshot s) {
        cancelInlineEditors();
        config.surfaces.clear();
        config.surfaces.addAll(s.surfaces());
        surface = Mth.clamp(s.surface(), 0, config.surfaces.size() - 1);
        boolean sizeChanged = config.posterW != s.posterW() || config.posterH != s.posterH() || config.posterNeedsSize != s.needsSize();
        config.posterW = s.posterW();
        config.posterH = s.posterH();
        config.posterNeedsSize = s.needsSize();
        currentTag = s.tag();
        if (s.title() != null) itemTitle = s.title();
        if (config.poster) rebuildPosterGrid();
        selected = null;
        changed();
        rebuildWidgets();
        if (sizeChanged) dirty = true;
    }

    private void undo() {
        if (editingText != null || editingFormula != null) finishEditing();
        if (undo.isEmpty()) return;
        redo.push(snapshot());
        restore(undo.pop());
    }

    private void redo() {
        if (editingText != null || editingFormula != null) finishEditing();
        if (redo.isEmpty()) return;
        undo.push(snapshot());
        restore(redo.pop());
    }

    // ------------------------------------------------------------------ snapping

    private @Nullable Region regionAt(float u, float v) {
        Region best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Region r : config.grid) {
            if (r.contains(u, v)) return r;
            float du = Math.max(0, Math.max(r.x0() - u, u - r.x1()));
            float dv = Math.max(0, Math.max(r.y0() - v, v - r.y1()));
            float d = du * du + dv * dv;
            if (d < bestDistance) {
                bestDistance = d;
                best = r;
            }
        }
        return best;
    }

    /** The top-left corner of the grid rectangle the point is in (unchanged on boards). */
    private float[] snapCell(float u, float v) {
        Region r = regionAt(u, v);
        if (r == null) return new float[] {u, v};
        int col = Mth.clamp((int) Math.floor((u - r.x0()) / r.cw()), 0, r.cols() - 1);
        int row = Mth.clamp((int) Math.floor((v - r.y0()) / r.ch()), 0, r.rows() - 1);
        return new float[] {r.x0() + col * r.cw(), r.y0() + row * r.ch()};
    }

    /** The nearest grid corner. */
    private float[] snapCorner(float u, float v) {
        Region r = regionAt(u, v);
        if (r == null) return new float[] {u, v};
        int col = Mth.clamp(Math.round((u - r.x0()) / r.cw()), 0, r.cols());
        int row = Mth.clamp(Math.round((v - r.y0()) / r.ch()), 0, r.rows());
        return new float[] {r.x0() + col * r.cw(), r.y0() + row * r.ch()};
    }

    /** Snaps a box: its top-left to the nearest corner, its size to whole rectangles. */
    private float[] snapBox(float x, float y, float w, float h, boolean keepSize) {
        Region r = regionAt(x + Math.min(w, 0.01F), y + Math.min(h, 0.01F));
        if (r == null) return new float[] {x, y, w, h};
        float[] corner = snapCorner(x, y);
        if (keepSize) return new float[] {corner[0], corner[1], w, h};
        float sw = Math.max(1, Math.round(w / r.cw())) * r.cw();
        float sh = Math.max(1, Math.round(h / r.ch())) * r.ch();
        return new float[] {corner[0], corner[1], sw, sh};
    }

    // ------------------------------------------------------------------ actions

    private void setTool(Tool next) {
        finishEditing();
        tool = next;
        rebuildWidgets();
    }

    private void changed() {
        dirty = true;
        lastChange = Util.getMillis();
    }

    private void setStatus(Component text, int color) {
        status = text;
        statusColor = color;
    }

    private float currentPx() {
        EditCanvas.Item item = editingText != null ? editingText : editingFormula != null ? editingFormula : selected;
        if (item != null && (item.type == Canvas.TEXT || item.type == Canvas.FORMULA)) return sizeToPx(item.size);
        return textPx;
    }

    private void setTextPx(float px, Button anchor) {
        textPx = px;
        EditCanvas.Item item = editingText != null ? editingText : editingFormula != null ? editingFormula : selected;
        if (item != null && (item.type == Canvas.TEXT || item.type == Canvas.FORMULA)) {
            pushUndo();
            item.size = Mth.clamp(pxToSize(px), 0.001F, 1.0F);
            if (item.type == Canvas.FORMULA) fitFormula(item);
            changed();
        }
        anchor.setMessage(Component.literal(number(px)));
    }

    private void openSizeChoices(Button anchor) {
        List<String> labels = new ArrayList<>();
        for (float px : TEXT_PX) labels.add(number(px) + " px");
        openChoices(new Choices(anchor, false, labels, indexOf(TEXT_PX, currentPx()),
                i -> setTextPx(TEXT_PX[i], anchor),
                px -> setTextPx(Mth.clamp(px, 0.5F, 768.0F), anchor)));
    }

    private void setWidthPx(float px, boolean eraser, Button anchor) {
        if (eraser) eraserPx = px;
        else penPx = px;
        anchor.setMessage(Component.literal(number(px)));
    }

    private void openWidthChoices(Button anchor) {
        List<String> labels = new ArrayList<>();
        for (float px : PEN_PX) labels.add(number(px) + " px");
        boolean eraser = tool == Tool.ERASE_STROKE || tool == Tool.ERASE_PIXEL;
        float now = eraser ? (eraserPx > 0 ? eraserPx : penPx) : penPx;
        openChoices(new Choices(anchor, true, labels, indexOf(PEN_PX, now),
                i -> setWidthPx(PEN_PX[i], eraser, anchor),
                px -> setWidthPx(Mth.clamp(px, 0.25F, 192.0F), eraser, anchor)));
    }

    private static int indexOf(float[] values, float value) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - value) < 0.01F) return i;
        }
        return -1;
    }

    /**
     * Shows a list of choices beside its button. When any number may be entered, the button
     * itself turns into a typing box holding the current number (selected, so typing replaces it).
     */
    private void openChoices(Choices next) {
        closeChoices();
        choices = next;
        int visible = visibleRows();
        int total = next.labels().size();
        choicesScroll = next.current() < 0 ? 0 : Mth.clamp(next.current() - visible / 2, 0, Math.max(0, total - visible));
        if (next.custom() != null) {
            Button anchor = next.anchor();
            String now = anchor.getMessage().getString();
            int w = Math.max(anchor.getWidth(), this.font.width("888.88") + 10);
            int x = next.leftOfAnchor() ? anchor.getX() + anchor.getWidth() - w : anchor.getX();
            x = Mth.clamp(x, 2, this.width - w - 2);
            customBox = new EditBox(this.font, x, anchor.getY(), w, anchor.getHeight(), anchor.getMessage());
            customBox.setMaxLength(6);
            customBox.setFilter(v -> v.isEmpty() || v.matches("[0-9]*\\.?[0-9]*"));
            customBox.setValue(now);
            customBox.moveCursorToEnd(false);
            customBox.setHighlightPos(0);
            customStart = now;
            anchor.visible = false;
            addRenderableWidget(customBox);
            setFocused(customBox);
        }
    }

    private void closeChoices() {
        if (customBox != null) removeWidget(customBox);
        if (choices != null) choices.anchor().visible = true;
        customBox = null;
        choices = null;
    }

    /** Enter in the typing box (or a click elsewhere after typing): use the typed number. */
    private boolean applyCustom() {
        if (choices == null || choices.custom() == null || customBox == null) return false;
        try {
            float value = Float.parseFloat(customBox.getValue());
            if (value > 0) choices.custom().accept(value);
        } catch (NumberFormatException ignored) {
            // nothing typed
        }
        closeChoices();
        return true;
    }

    private int visibleRows() {
        return Math.max(3, (this.height - 6) / ROW);
    }

    private void openPosterSizeChoices(Button anchor, boolean width) {
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= PaperContents.MAX_CELLS; i++) labels.add(String.valueOf(i));
        openChoices(new Choices(anchor, true, labels, (width ? config.posterW : config.posterH) - 1, i -> {
            int oldW = config.posterW, oldH = config.posterH;
            int newW = width ? i + 1 : oldW, newH = width ? oldH : i + 1;
            if (newW == oldW && newH == oldH) return;
            pushUndo();
            // What is already on the poster keeps its real size and place (from the top-left corner).
            float sx = oldW / (float) newW, sy = oldH / (float) newH;
            for (EditCanvas c : config.surfaces) {
                for (EditCanvas.Item item : c.items) item.rescale(sx, sy);
            }
            config.posterW = newW;
            config.posterH = newH;
            rebuildPosterGrid();
            changed();
            rebuildWidgets();
        }, null));
    }

    private void addFormula() {
        finishEditing();
        pushUndo();
        EditCanvas.Item item = new EditCanvas.Item();
        item.type = Canvas.FORMULA;
        item.text = "E = mc^2";
        item.color = textColor;
        item.size = pxToSize(textPx) * 1.4F;
        float[] at = snapCell(0.3F, 0.4F);
        item.x = at[0];
        item.y = at[1];
        fitFormula(item);
        canvas().items.add(item);
        selected = item;
        tool = Tool.SELECT;
        startFormulaEdit(item);
        changed();
    }

    /** Sizes a formula's box to its painted size. */
    private void fitFormula(EditCanvas.Item item) {
        float reference = 1000.0F;
        Image image = CanvasRaster.renderLatex(item.text, item.size * reference, item.color);
        if (image == null) {
            item.w = Math.max(item.w, 0.2F);
            item.h = Math.max(item.h, item.size * 1.5F);
            return;
        }
        item.w = image.getWidth(null) / (reference * aspect());
        item.h = image.getHeight(null) / reference;
    }

    /** Bold / italic / … on the selected characters, on what is typed next, or on the whole box. */
    private void applyStyle(int flag) {
        if (editingText != null && textBox != null) {
            int[] sel = selection();
            if (sel[1] > sel[0]) {
                pushUndo();
                editingText.toggle(sel[0], sel[1], flag);
            } else {
                typingFlags ^= flag;
            }
        } else if (selected != null && selected.type == Canvas.TEXT) {
            pushUndo();
            selected.toggle(0, selected.text.length(), flag);
        }
        changed();
    }

    private int[] selection() {
        if (textBox == null) return new int[] {0, 0};
        MultilineTextField field = ((MultiLineEditBoxAccessor) textBox).enplotify$textField();
        int a = field.cursor();
        int b = ((MultilineTextFieldAccessor) field).enplotify$selectCursor();
        return new int[] {Math.min(a, b), Math.max(a, b)};
    }

    private EditCanvas.@Nullable Item alignTarget() {
        EditCanvas.Item item = editingText != null ? editingText : selected;
        return item != null && item.type == Canvas.TEXT ? item : null;
    }

    /** Left / centre / right (or top / middle / bottom) of the text being edited, or of new boxes. */
    private void cycleAlign(boolean vertical) {
        EditCanvas.Item item = alignTarget();
        if (item == null) {
            if (vertical) newValign = (newValign + 1) % 3;
            else newAlign = (newAlign + 1) % 3;
        } else {
            pushUndo();
            int h = CanvasText.align(item.color), v = CanvasText.valign(item.color);
            if (vertical) v = (v + 1) % 3;
            else h = (h + 1) % 3;
            item.color = (item.color & 0xFFFFFF) | (h << PaperContents.ALIGN_SHIFT) | (v << PaperContents.VALIGN_SHIFT);
            changed();
        }
        refreshAlignButtons();
    }

    private static final String[] H_LABELS = {"⇤", "↔", "⇥"}, V_LABELS = {"⤒", "↕", "⤓"};

    private void refreshAlignButtons() {
        EditCanvas.Item item = alignTarget();
        int h = item == null ? newAlign : CanvasText.align(item.color);
        int v = item == null ? newValign : CanvasText.valign(item.color);
        h = Math.min(h, 2);
        v = Math.min(v, 2);
        if (alignButton != null) {
            alignButton.setMessage(Component.literal(H_LABELS[h]));
            alignButton.setTooltip(Tooltip.create(Component.translatable("enplotify.editor.align", Component.translatable("enplotify.editor.align." + h))));
        }
        if (valignButton != null) {
            valignButton.setMessage(Component.literal(V_LABELS[v]));
            valignButton.setTooltip(Tooltip.create(Component.translatable("enplotify.editor.valign", Component.translatable("enplotify.editor.valign." + v))));
        }
    }

    private void applyColor(int rgb) {
        if (palette == PaletteTarget.PEN) {
            penColor = rgb;
            return;
        }
        textColor = rgb;
        if (editingText != null) {
            int[] sel = selection();
            if (sel[1] > sel[0]) {
                pushUndo();
                editingText.recolor(sel[0], sel[1], rgb);
            } else {
                typingColor = rgb;
            }
        } else if (selected != null && (selected.type == Canvas.TEXT || selected.type == Canvas.FORMULA || selected.type == Canvas.STROKE)) {
            pushUndo();
            selected.color = (selected.color & ~0xFFFFFF) | rgb;
            if (selected.type == Canvas.TEXT) selected.recolor(0, selected.text.length(), -1);
            if (selected.type == Canvas.FORMULA) fitFormula(selected);
        }
        changed();
    }

    private void deleteSelected() {
        if (selected == null) return;
        pushUndo();
        if (selected == editingText || selected == editingFormula) cancelInlineEditors();
        canvas().items.remove(selected);
        selected = null;
        changed();
    }

    private void clearAll() {
        pushUndo();
        cancelInlineEditors();
        canvas().items.clear();
        selected = null;
        changed();
    }

    private void turn(int step) {
        finishEditing();
        int next = Mth.clamp(surface + step, 0, config.surfaces.size() - 1);
        if (next == surface) return;
        surface = next;
        selected = null;
        changed();
    }

    private void addPage() {
        finishEditing();
        if (config.surfaces.size() >= PaperContents.MAX_PAGES) {
            setStatus(Component.translatable("enplotify.paper.max_pages", PaperContents.MAX_PAGES), 0xFFE06A5A);
            return;
        }
        pushUndo();
        config.surfaces.add(surface + 1, new EditCanvas());
        surface++;
        selected = null;
        changed();
    }

    private void removePage() {
        finishEditing();
        pushUndo();
        if (config.surfaces.size() <= 1) {
            canvas().items.clear();
        } else {
            config.surfaces.remove(surface);
            surface = Math.min(surface, config.surfaces.size() - 1);
        }
        selected = null;
        changed();
    }

    private void chooseImage() {
        if (uploading) return;
        uploading = true;
        setStatus(Component.translatable("enplotify.paper.choosing"), 0xFFA8A8A8);
        PaperImages.chooseAndUpload(uploadSpec());
    }

    private PaperImages.UploadSpec uploadSpec() {
        return new PaperImages.UploadSpec(config.imageMaxSide, config.board ? penColor : -1, config.animations,
                config.paper & 0xFFFFFF, this::imageUploaded, this::uploadFailed, text -> setStatus(text, 0xFFA8A8A8));
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (files.isEmpty() || uploading || config.posterNeedsSize) return;
        uploading = true;
        setStatus(Component.translatable("enplotify.paper.uploading"), 0xFFA8A8A8);
        PaperImages.uploadFile(files.get(0), uploadSpec());
    }

    private void imageUploaded(String hash) {
        uploading = false;
        setStatus(PaperImages.wasCut(hash)
                ? Component.translatable("enplotify.paper.uploaded_cut", io.github.lwarqiu1shz.enplotify.EnPlotifyServerConfig.maxAnimationSeconds())
                : Component.translatable("enplotify.paper.uploaded"), 0xFF7FD36B);
        pushUndo();
        EditCanvas.Item item = new EditCanvas.Item();
        item.type = Canvas.IMAGE;
        item.text = hash;
        java.awt.image.BufferedImage image = PaperImages.awt(hash);
        float imageAspect = image == null ? 1.0F : image.getWidth() / (float) image.getHeight();
        float w = 0.5F;
        float h = w * aspect() / imageAspect;
        if (h > 0.6F) {
            h = 0.6F;
            w = h * imageAspect / aspect();
        }
        float[] box = snapBox((1 - w) / 2, (1 - h) / 2, w, h, false);
        item.x = box[0];
        item.y = box[1];
        item.w = box[2];
        item.h = box[3];
        canvas().items.add(item);
        selected = item;
        tool = Tool.SELECT;
        changed();
        rebuildWidgets();
    }

    private void uploadFailed(Component reason) {
        uploading = false;
        setStatus(reason, 0xFFE06A5A);
    }

    private void save(boolean lock) {
        finishEditing();
        List<Canvas> canvases = new ArrayList<>();
        for (EditCanvas c : config.surfaces) canvases.add(c.toCanvas());
        if (config.sharedHistory) {
            List<Integer> back = new ArrayList<>(), forward = new ArrayList<>();
            for (java.util.Iterator<Snapshot> it = undo.descendingIterator(); it.hasNext(); ) {
                int tag = it.next().tag();
                if (tag >= 0) back.add(tag);
            }
            for (Snapshot s : redo) {
                if (s.tag() >= 0) forward.add(s.tag());
            }
            config.undoTags = back.stream().mapToInt(Integer::intValue).toArray();
            config.redoTags = forward.stream().mapToInt(Integer::intValue).toArray();
        }
        saved = true;
        config.saver.save(canvases, itemTitle, config.posterW, config.posterH, lock);
        onClose();
    }

    private EditCanvas.Item newText(float u, float v) {
        float[] at = snapCell(u, v);
        EditCanvas.Item item = new EditCanvas.Item();
        item.type = Canvas.TEXT;
        item.x = at[0];
        item.y = at[1];
        float w = Math.min(0.5F, 1.0F - at[0]);
        float h = pxToSize(textPx) * 3.0F;
        float[] box = config.grid.isEmpty() ? new float[] {at[0], at[1], w, h} : snapBox(at[0], at[1], w, h, false);
        item.w = box[2];
        item.h = box[3];
        item.color = textColor | (newAlign << PaperContents.ALIGN_SHIFT) | (newValign << PaperContents.VALIGN_SHIFT);
        item.size = pxToSize(textPx);
        return item;
    }

    // ------------------------------------------------------------------ erasers

    /** Removes whole strokes (continuous eraser) or the parts of strokes (pixel eraser) under the cursor. */
    private void erase(float u, float v) {
        float radius = Math.max(eraserWidth() / 2.0F, 1.5F / area.h());
        float ru = radius / aspect();
        List<EditCanvas.Item> items = canvas().items;
        List<EditCanvas.Item> result = new ArrayList<>(items.size());
        boolean changedAny = false;
        for (EditCanvas.Item item : items) {
            if (item.type != Canvas.STROKE) {
                result.add(item);
                continue;
            }
            float reach = radius + item.size / 2.0F;
            float reachU = ru + item.size / 2.0F / aspect();
            boolean[] inside = new boolean[item.points.size()];
            boolean any = false;
            for (int i = 0; i < item.points.size(); i++) {
                float du = (Canvas.pointX(item.points.get(i)) - u) / reachU;
                float dv = (Canvas.pointY(item.points.get(i)) - v) / reach;
                inside[i] = du * du + dv * dv <= 1.0F;
                any |= inside[i];
            }
            if (!any) {
                result.add(item);
                continue;
            }
            changedAny = true;
            if (tool == Tool.ERASE_STROKE) continue;
            // Pixel eraser: keep the runs of points outside the eraser as separate strokes.
            List<Integer> run = new ArrayList<>();
            for (int i = 0; i <= item.points.size(); i++) {
                if (i < item.points.size() && !inside[i]) {
                    run.add(item.points.get(i));
                } else {
                    if (run.size() >= 2) {
                        EditCanvas.Item piece = item.copy();
                        piece.points = new ArrayList<>(run);
                        result.add(piece);
                    }
                    run = new ArrayList<>();
                }
            }
        }
        if (changedAny) {
            items.clear();
            items.addAll(result);
            if (selected != null && !items.contains(selected)) selected = null;
            changed();
        }
    }

    // ------------------------------------------------------------------ inline editors

    /**
     * Types straight into the text box on the sheet, like in a word processor. The keys go to a
     * hidden text field (which knows typing, deleting, the clipboard and selections); the box itself
     * shows the text as it will look, with the caret and the selection drawn on top.
     */
    private void startTextEdit(EditCanvas.Item item, int caret, boolean undoable) {
        if (undoable) pushUndo();
        cancelInlineEditors();
        editingText = item;
        selected = item;
        typingFlags = 0;
        typingColor = -1;
        // Far above the screen and wide enough never to wrap: it only holds the text and the caret.
        textBox = MultiLineEditBox.builder().setX(0).setY(-100000)
                .build(this.font, 1000000, 20, Component.translatable("enplotify.editor.text"));
        textBox.setCharacterLimit(Canvas.MAX_TEXT);
        textBox.setValue(item.text);
        field().seekCursor(Whence.ABSOLUTE, Mth.clamp(caret, 0, item.text.length()));
        textBox.setValueListener(value -> {
            if (editingText != null) {
                editingText.textChanged(value, typingFlags, typingColor);
                editingElement = null;
                growToFit(editingText);
                changed();
            }
        });
        addWidget(textBox);
        setFocused(textBox);
        editingElement = null;
        caretMoved = Util.getMillis();
        changed();
        refreshAlignButtons();
    }

    private MultilineTextField field() {
        return ((MultiLineEditBoxAccessor) textBox).enplotify$textField();
    }

    /** The text being typed as an element (cached until it changes). */
    private Canvas.Element editingElement() {
        if (editingElement == null && editingText != null) editingElement = editingText.toElement();
        return editingElement;
    }

    /** Makes the box taller while the text grows past its bottom (up to the edge of the sheet). */
    private void growToFit(EditCanvas.Item item) {
        Canvas.Element element = item == editingText ? editingElement() : item.toElement();
        CanvasText.Placement p = CanvasText.placeGui(element, area);
        float needed = p.layout().height() * p.unit() / area.h();
        if (needed > item.h) {
            item.h = Math.min(needed, Math.max(item.h, 1.0F - item.y));
            editingElement = null;
        }
    }

    private void moveCaret(int index, boolean extend) {
        if (textBox == null) return;
        MultilineTextField f = field();
        f.setSelecting(extend);
        f.seekCursor(Whence.ABSOLUTE, Mth.clamp(index, 0, f.value().length()));
        f.setSelecting(false);
        caretMoved = Util.getMillis();
    }

    /** Arrow keys up / down and Home / End move by the lines as they are shown in the box. */
    private boolean editingKey(KeyEvent event) {
        if (editingText == null || textBox == null || getFocused() != textBox) return false;
        int key = event.key();
        if (key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN && key != GLFW.GLFW_KEY_HOME && key != GLFW.GLFW_KEY_END) {
            caretMoved = Util.getMillis();
            return false;
        }
        CanvasText.Placement p = CanvasText.placeGui(editingElement(), area);
        List<CanvasText.Line> lines = p.layout().lines();
        int cursor = field().cursor();
        int li = CanvasText.lineOf(p.layout(), cursor);
        CanvasText.Line line = lines.get(li);
        int target;
        if (key == GLFW.GLFW_KEY_HOME) {
            target = event.hasControlDown() ? 0 : line.start();
        } else if (key == GLFW.GLFW_KEY_END) {
            target = event.hasControlDown() ? field().value().length() : line.end();
        } else {
            int next = li + (key == GLFW.GLFW_KEY_UP ? -1 : 1);
            if (next < 0) {
                target = 0;
            } else if (next >= lines.size()) {
                target = field().value().length();
            } else {
                float x = line.xs()[Mth.clamp(cursor - line.start(), 0, line.xs().length - 1)];
                target = CanvasText.indexInLine(lines.get(next), x);
            }
        }
        moveCaret(target, event.hasShiftDown());
        return true;
    }

    /** Selects the word (or the single CJK character) around a caret position. */
    private void selectWord(int index) {
        String text = field().value();
        int a = Mth.clamp(index, 0, text.length()), b = a;
        if (a < text.length() && isWordChar(text.charAt(a))) {
            while (a > 0 && isWordChar(text.charAt(a - 1))) a--;
            while (b < text.length() && isWordChar(text.charAt(b))) b++;
        } else if (a < text.length()) {
            b = a + 1;
        }
        moveCaret(a, false);
        moveCaret(b, true);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) && c < 0x2E80;
    }

    @Override
    public boolean preeditUpdated(@Nullable PreeditEvent event) {
        if (editingText != null && getFocused() == textBox) {
            preedit = event != null ? new IMEPreeditOverlay(event, this.font, 10) : null;
            return true;
        }
        return super.preeditUpdated(event);
    }

    private void startFormulaEdit(EditCanvas.Item item) {
        cancelInlineEditors();
        editingFormula = item;
        PaperGui.Rect r = screenRect(item);
        int w = Mth.clamp(Math.max(r.w(), 160), 80, this.width - 8);
        int x = Mth.clamp(r.x(), 2, this.width - w - 2);
        int y = Mth.clamp(r.y() + r.h() + 2, 2, this.height - 18);
        formulaBox = new EditBox(this.font, x, y, w, 16, Component.translatable("enplotify.editor.formula"));
        formulaBox.setMaxLength(Canvas.MAX_LATEX);
        formulaBox.setValue(item.text);
        formulaBox.setResponder(value -> {
            if (editingFormula != null) {
                editingFormula.text = value;
                fitFormula(editingFormula);
                changed();
            }
        });
        addRenderableWidget(formulaBox);
        setFocused(formulaBox);
    }

    /** Ends inline editing, keeping what was typed (empty text boxes disappear). */
    private void finishEditing() {
        if (editingText != null && editingText.text.isBlank()) {
            canvas().items.remove(editingText);
            if (selected == editingText) selected = null;
        }
        cancelInlineEditors();
        changed();
    }

    private void cancelInlineEditors() {
        preedit = null;
        editingElement = null;
        if (textBox != null) removeWidget(textBox);
        if (formulaBox != null) removeWidget(formulaBox);
        textBox = null;
        formulaBox = null;
        editingText = null;
        editingFormula = null;
    }

    private void openPalette(PaletteTarget target) {
        if (hexBox != null) removeWidget(hexBox);
        palette = target;
        PaperGui.Rect p = paletteRect();
        hexBox = new EditBox(this.font, p.x() + 2, p.y() + p.h() - 18, p.w() - 4, 16, Component.literal("#RRGGBB"));
        hexBox.setMaxLength(7);
        hexBox.setHint(Component.literal("#RRGGBB"));
        hexBox.setResponder(value -> {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            if (hex.length() == 6) {
                try {
                    applyColor(Integer.parseInt(hex, 16));
                } catch (NumberFormatException ignored) {
                    // not a colour yet
                }
            }
        });
        addRenderableWidget(hexBox);
    }

    private void closePalette() {
        if (hexBox != null) removeWidget(hexBox);
        hexBox = null;
        palette = PaletteTarget.NONE;
    }

    private PaperGui.Rect paletteRect() {
        int w = 4 * 16 + 6;
        int h = 4 * 16 + 24;
        int x = palette == PaletteTarget.PEN ? area.x() + area.w() - w - 2 : area.x() + 2;
        return new PaperGui.Rect(x, area.y() + 2, w, h);
    }

    private PaperGui.Rect choicesRect() {
        Choices c = choices;
        if (c == null) return new PaperGui.Rect(0, 0, 0, 0);
        int w = 8;
        for (String label : c.labels()) w = Math.max(w, this.font.width(label) + 8);
        int rows = Math.min(c.labels().size(), visibleRows());
        if (rows < c.labels().size()) w += 4; // scroll bar
        int h = rows * ROW + 2;
        PaperGui.Rect beside = customBox != null
                ? new PaperGui.Rect(customBox.getX(), customBox.getY(), customBox.getWidth(), customBox.getHeight())
                : new PaperGui.Rect(c.anchor().getX(), c.anchor().getY(), c.anchor().getWidth(), c.anchor().getHeight());
        int x = c.leftOfAnchor() ? beside.x() - w - 2 : beside.x() + beside.w() + 2;
        x = Mth.clamp(x, 2, this.width - w - 2);
        // Centred on the button, kept on screen.
        int y = beside.y() + beside.h() / 2 - h / 2;
        y = Mth.clamp(y, 2, Math.max(2, this.height - h - 2));
        return new PaperGui.Rect(x, y, w, h);
    }

    // ------------------------------------------------------------------ geometry

    private PaperGui.Rect screenRect(EditCanvas.Item item) {
        float[] b = item.bounds();
        return new PaperGui.Rect(area.x() + Math.round(b[0] * area.w()), area.y() + Math.round(b[1] * area.h()),
                Math.max(2, Math.round(b[2] * area.w())), Math.max(2, Math.round(b[3] * area.h())));
    }

    private float toU(double mouseX) {
        return (float) ((mouseX - area.x()) / area.w());
    }

    private float toV(double mouseY) {
        return (float) ((mouseY - area.y()) / area.h());
    }

    private EditCanvas.@Nullable Item hit(double mx, double my) {
        List<EditCanvas.Item> items = canvas().items;
        for (int i = items.size() - 1; i >= 0; i--) {
            PaperGui.Rect r = screenRect(items.get(i));
            if (mx >= r.x() - 2 && my >= r.y() - 2 && mx <= r.x() + r.w() + 2 && my <= r.y() + r.h() + 2) return items.get(i);
        }
        return null;
    }

    /** The resize handle under the mouse: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right, or -1. */
    private int handleAt(EditCanvas.Item item, double mx, double my) {
        PaperGui.Rect r = screenRect(item);
        for (int corner = 0; corner < 4; corner++) {
            int cx = (corner & 1) == 0 ? r.x() : r.x() + r.w();
            int cy = corner < 2 ? r.y() : r.y() + r.h();
            if (Math.abs(mx - cx) <= 4 && Math.abs(my - cy) <= 4) return corner;
        }
        return -1;
    }

    /** True if the point is inside the text box being typed in (a little margin included). */
    private boolean inEditingText(double mx, double my) {
        if (editingText == null) return false;
        PaperGui.Rect r = screenRect(editingText);
        return mx >= r.x() - 3 && my >= r.y() - 3 && mx <= r.x() + r.w() + 3 && my <= r.y() + r.h() + 3;
    }

    /** Keeps the keys going to the text being typed after a button was clicked. */
    private void keepTypingFocus() {
        if (editingText != null && textBox != null && !(getFocused() instanceof EditBox)) setFocused(textBox);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (choices != null && choicesRect().contains(mx, my)) {
            int total = choices.labels().size();
            choicesScroll = Mth.clamp(choicesScroll - (int) Math.signum(scrollY), 0, Math.max(0, total - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (choices != null) {
            if (customBox != null && customBox.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
            PaperGui.Rect c = choicesRect();
            Choices open = choices;
            if (c.contains(mx, my)) {
                closeChoices();
                int index = (int) ((my - c.y() - 1) / ROW) + choicesScroll;
                if (index >= 0 && index < open.labels().size()) open.pick().accept(index);
                return true;
            }
            if (customBox != null && !customBox.getValue().equals(customStart)) applyCustom();
            else closeChoices();
            if (open.anchor().isMouseOver(mx, my)) return true; // clicking the button again just closes it
        }
        if (palette != PaletteTarget.NONE) {
            PaperGui.Rect p = paletteRect();
            if (p.contains(mx, my)) {
                int col = (int) ((mx - p.x() - 3) / 16), row = (int) ((my - p.y() - 3) / 16);
                if (col >= 0 && col < 4 && row >= 0 && row < 4) {
                    applyColor(PALETTE[row * 4 + col]);
                    return true;
                }
                return super.mouseClicked(event, doubleClick);
            }
            closePalette();
        }
        if (formulaBox != null && formulaBox.isMouseOver(mx, my)) return super.mouseClicked(event, doubleClick);
        if (!area.contains(mx, my) || config.posterNeedsSize) {
            // Outside the sheet: buttons first, then a resize corner or a part of the selected
            // thing that sticks out over the edge.
            boolean handled = super.mouseClicked(event, doubleClick);
            boolean onItem = !handled && !config.posterNeedsSize && tool == Tool.SELECT
                    && selected != null && canvas().items.contains(selected)
                    && (handleAt(selected, mx, my) >= 0 || hit(mx, my) == selected);
            if (!onItem) {
                keepTypingFocus();
                return handled;
            }
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        // Clicking inside the text being typed moves the caret (double click: a word, drag: a selection).
        if (editingText != null && textBox != null && inEditingText(mx, my) && handleAt(editingText, mx, my) < 0) {
            setFocused(textBox);
            int index = CanvasText.indexAt(CanvasText.placeGui(editingElement(), area), mx, my);
            if (doubleClick) {
                selectWord(index);
            } else {
                moveCaret(index, event.hasShiftDown());
                drag = Drag.SELECT_TEXT;
            }
            return true;
        }
        if (editingText != null || editingFormula != null) finishEditing();
        float u = toU(mx), v = toV(my);
        switch (tool) {
            case PEN -> {
                drag = Drag.PEN;
                penPoints.clear();
                penPoints.add(Canvas.packPoint(u, v));
            }
            case ERASE_STROKE, ERASE_PIXEL -> {
                pushUndo();
                drag = Drag.ERASE;
                erase(u, v);
            }
            case TEXT -> {
                pushUndo();
                EditCanvas.Item item = newText(u, v);
                canvas().items.add(item);
                selected = item;
                tool = Tool.SELECT;
                startTextEdit(item, 0, false);
            }
            case SELECT -> {
                int corner = selected != null ? handleAt(selected, mx, my) : -1;
                if (corner >= 0) {
                    pushUndo();
                    drag = Drag.RESIZE;
                    resizeCorner = corner;
                } else {
                    selected = hit(mx, my);
                    drag = selected == null ? Drag.NONE : Drag.MOVE;
                    if (selected != null && doubleClick) {
                        if (selected.type == Canvas.TEXT) {
                            startTextEdit(selected, CanvasText.indexAt(CanvasText.placeGui(selected.toElement(), area), mx, my), false);
                        }
                        else if (selected.type == Canvas.FORMULA) startFormulaEdit(selected);
                        drag = Drag.NONE;
                    } else if (selected == null && doubleClick && config.board) {
                        // Boards: double click empty space to type right there.
                        pushUndo();
                        EditCanvas.Item item = newText(u, v);
                        canvas().items.add(item);
                        selected = item;
                        startTextEdit(item, 0, false);
                    } else if (selected != null) {
                        pushUndo();
                    }
                }
                if (selected != null) {
                    dragStartU = u;
                    dragStartV = v;
                    dragStartBounds = selected.bounds();
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        float u = toU(event.x()), v = toV(event.y());
        switch (drag) {
            case PEN -> {
                int last = penPoints.get(penPoints.size() - 1);
                float lu = Canvas.pointX(last), lv = Canvas.pointY(last);
                float du = (u - lu) * area.w(), dv = (v - lv) * area.h();
                float dist = (float) Math.sqrt(du * du + dv * dv);
                if (dist >= 2.0F) {
                    // Keep points close together, so the pixel eraser can cut anywhere.
                    int steps = (int) Math.ceil(dist / 2.0F);
                    for (int s = 1; s <= steps && penPoints.size() < Canvas.MAX_STROKE_POINTS; s++) {
                        float t = s / (float) steps;
                        penPoints.add(Canvas.packPoint(Mth.clamp(lu + (u - lu) * t, 0, 1), Mth.clamp(lv + (v - lv) * t, 0, 1)));
                    }
                }
                return true;
            }
            case ERASE -> {
                erase(u, v);
                return true;
            }
            case MOVE -> {
                if (selected == null) return true;
                float[] b = dragStartBounds;
                float nx = b[0] + u - dragStartU, ny = b[1] + v - dragStartV;
                float[] snapped = selected.type == Canvas.STROKE ? new float[] {nx, ny, b[2], b[3]} : snapBox(nx, ny, b[2], b[3], true);
                if (keepInside(event)) {
                    // Ctrl: the picture stays on the sheet.
                    snapped[0] = Mth.clamp(snapped[0], 0, Math.max(0, 1 - b[2]));
                    snapped[1] = Mth.clamp(snapped[1], 0, Math.max(0, 1 - b[3]));
                }
                selected.setBounds(snapped[0], snapped[1], b[2], b[3]);
                changed();
                return true;
            }
            case RESIZE -> {
                if (selected == null) return true;
                float[] b = dragStartBounds;
                boolean left = (resizeCorner & 1) == 0, top = resizeCorner < 2;
                // The opposite corner stays where it is.
                float ax = left ? b[0] + b[2] : b[0], ay = top ? b[1] + b[3] : b[1];
                float cx = (left ? b[0] : b[0] + b[2]) + u - dragStartU;
                float cy = (top ? b[1] : b[1] + b[3]) + v - dragStartV;
                if (!config.grid.isEmpty() && selected.type != Canvas.STROKE && !event.hasShiftDown()) {
                    float[] snapped = snapCorner(cx, cy);
                    cx = snapped[0];
                    cy = snapped[1];
                }
                boolean inside = keepInside(event);
                if (inside) {
                    cx = Mth.clamp(cx, 0, 1);
                    cy = Mth.clamp(cy, 0, 1);
                }
                float w = Math.max(0.01F, left ? ax - cx : cx - ax);
                float h = Math.max(0.01F, top ? ay - cy : cy - ay);
                if (event.hasShiftDown()) {
                    float scale = Math.max(w / b[2], h / b[3]);
                    if (inside) {
                        float roomW = left ? ax : 1 - ax, roomH = top ? ay : 1 - ay;
                        scale = Math.min(scale, Math.min(roomW / b[2], roomH / b[3]));
                    }
                    w = Math.max(0.01F, b[2] * scale);
                    h = Math.max(0.01F, b[3] * scale);
                }
                selected.setBounds(left ? ax - w : ax, top ? ay - h : ay, w, h);
                if (selected == editingText) editingElement = null;
                changed();
                return true;
            }
            case SELECT_TEXT -> {
                if (editingText != null && textBox != null) {
                    MultilineTextField f = field();
                    f.setSelecting(true);
                    f.seekCursor(Whence.ABSOLUTE, CanvasText.indexAt(CanvasText.placeGui(editingElement(), area), event.x(), event.y()));
                    f.setSelecting(false);
                    caretMoved = Util.getMillis();
                }
                return true;
            }
            default -> {
                return super.mouseDragged(event, dx, dy);
            }
        }
    }

    /** Ctrl held while moving or resizing a picture: it may not leave the sheet. */
    private boolean keepInside(MouseButtonEvent event) {
        return event.hasControlDown() && selected != null && selected.type == Canvas.IMAGE;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (drag == Drag.PEN && penPoints.size() >= 2) {
            pushUndo();
            EditCanvas.Item stroke = new EditCanvas.Item();
            stroke.type = Canvas.STROKE;
            stroke.color = penColor;
            stroke.size = penWidth();
            stroke.points = new ArrayList<>(penPoints);
            canvas().items.add(stroke);
            changed();
        }
        if (drag == Drag.RESIZE && selected != null && selected.type == Canvas.FORMULA) {
            float[] b = selected.bounds();
            float ratio = b[3] / Math.max(0.001F, dragStartBounds[3]);
            selected.size = Mth.clamp(selected.size * ratio, 0.002F, 0.5F);
            float right = b[0] + b[2], bottom = b[1] + b[3];
            fitFormula(selected);
            if ((resizeCorner & 1) == 0) selected.x = right - selected.w;
            if (resizeCorner < 2) selected.y = bottom - selected.h;
            changed();
        }
        penPoints.clear();
        drag = Drag.NONE;
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double x, double y) {
        mouseX = x;
        mouseY = y;
        super.mouseMoved(x, y);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.hasControlDown() && key == GLFW.GLFW_KEY_Z && editingFormula == null) {
            if (event.hasShiftDown()) redo();
            else undo();
            return true;
        }
        if (event.hasControlDown() && key == GLFW.GLFW_KEY_Y && editingFormula == null) {
            redo();
            return true;
        }
        if (customBox != null && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            return applyCustom();
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (choices != null) {
                closeChoices();
                return true;
            }
            if (palette != PaletteTarget.NONE) {
                closePalette();
                return true;
            }
            if (editingText != null || editingFormula != null) {
                finishEditing();
                return true;
            }
            if (selected != null) {
                selected = null;
                return true;
            }
        }
        if (editingKey(event)) return true;
        if (editingText != null && getFocused() == textBox && key == GLFW.GLFW_KEY_TAB) return true;
        if (editingFormula != null && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            finishEditing();
            return true;
        }
        boolean typing = getFocused() instanceof EditBox box && box.isFocused();
        if (editingText == null && editingFormula == null && !typing && selected != null
                && (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE)) {
            deleteSelected();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void tick() {
        refreshAlignButtons();
        refreshColorButtons();
        // Keys go back to the text being typed once a button or a closed box had them.
        if (editingText != null && textBox != null && !(getFocused() instanceof EditBox box && children().contains(box))) {
            setFocused(textBox);
        }
        // Rebuild the preview a few times a second while something changes.
        if (dirty && Util.getMillis() - lastChange > 60L) {
            dirty = false;
            List<Canvas.Element> elements = new ArrayList<>();
            for (EditCanvas.Item item : canvas().items) {
                if (item != editingText) elements.add(item.toElement());
            }
            if (latest != null && latest != shown) CanvasTextures.forget(latest);
            latest = new Canvas(elements);
        }
    }

    private int[] previewPixels() {
        int pxW = PaperGui.realPixels(area.w());
        return new int[] {pxW, Math.max(1, Math.round(pxW / aspect()))};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.centeredText(this.font, this.title, this.width / 2, 3, 0xFFFFFFFF);
        Component where = config.posterNeedsSize ? Component.translatable("enplotify.editor.poster_choose", config.posterW, config.posterH)
                : config.surfaceNames != null ? config.surfaceNames.get(surface)
                : config.surfaces.size() > 1 || config.pages ? Component.translatable("enplotify.paper.page_of", surface + 1, config.surfaces.size())
                : Component.empty();
        graphics.centeredText(this.font, where, this.width / 2, area.y() + area.h() + 3, 0xFFD0D0D0);

        if (config.board) graphics.fill(area.x() - 4, area.y() - 4, area.x() + area.w() + 4, area.y() + area.h() + 4, 0xFF6B4A2B);
        graphics.fill(area.x(), area.y(), area.x() + area.w(), area.y() + area.h(), config.paper);
        if (config.backdrop != null) config.backdrop.draw(graphics, area.x(), area.y(), area.w(), area.h());
        int inkBackground = config.backdrop != null ? 0x00FFFFFF : config.paper;

        // Picture layer (formulas, pictures, handwriting), swapped in once painted.
        int[] px = previewPixels();
        Canvas current = latest;
        if (current != null && CanvasTextures.needsPaint(current)) {
            CanvasTextures.get(current, px[0], px[1], inkBackground);
            if (CanvasTextures.isReady(current, px[0], px[1], inkBackground)) {
                if (shown != null && shown != current) CanvasTextures.forget(shown);
                shown = current;
            }
        } else if (current != null) {
            if (shown != null && shown != current) CanvasTextures.forget(shown);
            shown = current;
        }
        if (shown != null && CanvasTextures.needsPaint(shown)) {
            net.minecraft.resources.Identifier texture = CanvasTextures.get(shown, px[0], px[1], inkBackground);
            if (texture != null) graphics.blit(texture, area.x(), area.y(), area.x() + area.w(), area.y() + area.h(), 0.0F, 1.0F, 0.0F, 1.0F);
        }
        // Animated pictures play over their first frame.
        if (current != null && PaperAnimations.hasImages(current)) PaperAnimations.drawGui(graphics, current, area);
        // Text layer (always the newest), then the text being typed with its selection and caret.
        if (current != null) CanvasText.drawGui(graphics, current, area, null);
        if (editingText != null && textBox != null) drawTyping(graphics);

        drawGrid(graphics);
        if (config.fold) graphics.fill(area.x() + area.w() / 2, area.y(), area.x() + area.w() / 2 + 1, area.y() + area.h(), 0x66000000);

        // The stroke being drawn.
        if (drag == Drag.PEN) {
            int r = Math.max(1, Math.round(penWidth() * area.h() / 2));
            int color = 0xFF000000 | penColor;
            for (int pt : penPoints) {
                int x = Math.round(area.x() + Canvas.pointX(pt) * area.w());
                int y = Math.round(area.y() + Canvas.pointY(pt) * area.h());
                graphics.fill(x - r, y - r, x + r, y + r, color);
            }
        }
        // Eraser outline.
        if ((tool == Tool.ERASE_STROKE || tool == Tool.ERASE_PIXEL) && area.contains(mouseX, mouseY)) {
            int r = Math.max(2, Math.round(eraserWidth() * area.h() / 2));
            graphics.outline(mouseX - r, mouseY - r, 2 * r, 2 * r, tool == Tool.ERASE_STROKE ? 0xFFE06A5A : 0xFFFFFFFF);
        }

        if (selected != null && canvas().items.contains(selected)) {
            PaperGui.Rect r = screenRect(selected);
            int color = selected == editingText ? 0x993A8DFF : 0xFF3A8DFF;
            graphics.outline(r.x() - 1, r.y() - 1, r.w() + 2, r.h() + 2, color);
            for (int corner = 0; corner < 4; corner++) {
                int cx = (corner & 1) == 0 ? r.x() : r.x() + r.w();
                int cy = corner < 2 ? r.y() : r.y() + r.h();
                graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF3A8DFF);
            }
        }

        Component toolName = Component.translatable(switch (tool) {
            case SELECT -> "enplotify.editor.select_short";
            case TEXT -> "enplotify.editor.text_short";
            case PEN -> "enplotify.editor.pen_short";
            case ERASE_STROKE -> "enplotify.editor.erase_stroke_short";
            case ERASE_PIXEL -> "enplotify.editor.erase_pixel_short";
        });
        graphics.text(this.font, toolName, 3, this.height - 12, 0xFFD0D0D0, false);
        graphics.centeredText(this.font, status, this.width / 2, this.height - 12, statusColor);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        if (palette != PaletteTarget.NONE) {
            PaperGui.Rect p = paletteRect();
            graphics.fill(p.x(), p.y(), p.x() + p.w(), p.y() + p.h(), 0xEE202020);
            for (int i = 0; i < PALETTE.length; i++) {
                int x = p.x() + 3 + (i % 4) * 16, y = p.y() + 3 + (i / 4) * 16;
                graphics.fill(x, y, x + 14, y + 14, 0xFF000000 | PALETTE[i]);
            }
            if (hexBox != null) hexBox.extractRenderState(graphics, mouseX, mouseY, a);
        }
        if (choices != null) {
            PaperGui.Rect c = choicesRect();
            graphics.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), 0xEE202020);
            graphics.outline(c.x(), c.y(), c.w(), c.h(), 0xFF808080);
            int total = choices.labels().size();
            int rows = Math.min(total, visibleRows());
            for (int r = 0; r < rows; r++) {
                int i = r + choicesScroll;
                if (i >= total) break;
                int y = c.y() + 1 + r * ROW;
                if (mouseY >= y && mouseY < y + ROW && mouseX >= c.x() && mouseX < c.x() + c.w()) {
                    graphics.fill(c.x() + 1, y, c.x() + c.w() - 1, y + ROW, 0xFF3A5A8A);
                } else if (i == choices.current()) {
                    graphics.fill(c.x() + 1, y, c.x() + c.w() - 1, y + ROW, 0xFF34404F);
                }
                graphics.text(this.font, choices.labels().get(i), c.x() + 4, y + 2, 0xFFFFFFFF, false);
            }
            if (rows < total) {
                // Scroll bar.
                int barH = Math.max(6, (c.h() - 2) * rows / total);
                int barY = c.y() + 1 + (c.h() - 2 - barH) * choicesScroll / Math.max(1, total - rows);
                graphics.fill(c.x() + c.w() - 4, c.y() + 1, c.x() + c.w() - 1, c.y() + c.h() - 1, 0xFF303030);
                graphics.fill(c.x() + c.w() - 4, barY, c.x() + c.w() - 1, barY + barH, 0xFF9A9A9A);
            }
        }
    }

    /** The text being typed, drawn in place: selection, text, caret (and the input method's preview). */
    private void drawTyping(GuiGraphicsExtractor graphics) {
        Canvas.Element element = editingElement();
        CanvasText.Placement p = CanvasText.placeGui(element, area);
        MultilineTextField f = field();
        int cursor = f.cursor();
        graphics.enableScissor(area.x(), area.y(), area.x() + area.w(), area.y() + area.h());
        if (f.hasSelection()) {
            int[] sel = selection();
            List<CanvasText.Line> lines = p.layout().lines();
            for (int i = 0; i < lines.size(); i++) {
                CanvasText.Line line = lines.get(i);
                int a = Math.max(sel[0], line.start()), b = Math.min(sel[1], line.end());
                boolean wholeBreak = sel[1] > line.end() && sel[0] <= line.end();
                if (b < a || (a == b && !wholeBreak)) continue;
                float x0 = p.x() + line.xs()[a - line.start()] * p.unit();
                float x1 = p.x() + line.xs()[b - line.start()] * p.unit() + (wholeBreak ? 2 : 0);
                float top = p.y() + i * CanvasText.LINE * p.unit();
                graphics.fill(Math.round(x0), Math.round(top), Math.max(Math.round(x0) + 1, Math.round(x1)),
                        Math.round(top + CanvasText.LINE * p.unit()), 0x663A8DFF);
            }
        }
        if (!element.text().isEmpty()) CanvasText.drawElementGui(graphics, p, area);
        float[] c = CanvasText.caret(p, cursor);
        boolean blinkOn = ((Util.getMillis() - caretMoved) / 500L) % 2L == 0L;
        if (blinkOn && getFocused() == textBox) {
            int x = Math.round(c[0]);
            graphics.fill(x, Math.round(c[1]), x + 1, Math.max(Math.round(c[1]) + 2, Math.round(c[2])), 0xFF000000 | (element.color() & 0xFFFFFF));
        }
        graphics.disableScissor();
        if (preedit != null) {
            preedit.updateInputPosition(Math.round(c[0]), Math.round(c[1]));
            graphics.setPreeditOverlay(preedit);
        }
    }

    /** Faint snapping grid (only the lines that are at least 4 pixels apart). */
    private void drawGrid(GuiGraphicsExtractor graphics) {
        for (Region r : config.grid) {
            int x0 = area.x() + Math.round(r.x0() * area.w()), x1 = area.x() + Math.round(r.x1() * area.w());
            int y0 = area.y() + Math.round(r.y0() * area.h()), y1 = area.y() + Math.round(r.y1() * area.h());
            float cw = r.cw() * area.w(), ch = r.ch() * area.h();
            int stepX = Math.max(1, (int) Math.ceil(4 / Math.max(0.01F, cw)));
            int stepY = Math.max(1, (int) Math.ceil(4 / Math.max(0.01F, ch)));
            for (int c = 0; c <= r.cols(); c += stepX) {
                int x = x0 + Math.round(c * cw);
                graphics.fill(x, y0, x + 1, y1, c % 16 == 0 ? 0x30000000 : 0x14000000);
            }
            for (int row = 0; row <= r.rows(); row += stepY) {
                int y = y0 + Math.round(row * ch);
                graphics.fill(x0, y, x1, y + 1, row % 16 == 0 ? 0x30000000 : 0x14000000);
            }
        }
    }

    @Override
    public void removed() {
        if (!saved && config.onCancelled != null) config.onCancelled.run();
        saved = true;
        if (shown != null) CanvasTextures.forget(shown);
        if (latest != null) CanvasTextures.forget(latest);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
