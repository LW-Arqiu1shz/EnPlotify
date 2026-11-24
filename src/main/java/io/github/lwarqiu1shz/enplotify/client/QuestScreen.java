package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.quest.QuestSync;
import io.github.lwarqiu1shz.enplotify.quest.QuestSync.LineView;
import io.github.lwarqiu1shz.enplotify.quest.QuestSync.ReqView;
import io.github.lwarqiu1shz.enplotify.quest.QuestSync.StageView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quest map (J): the current task in the top left, the line's stages as a map that can be
 * dragged and zoomed, and the chosen stage's requirements on the right. Locked stages carry a lock;
 * stages marked {@code branches()} open their requirements as small branches under them (double-click
 * the stage or click its arrow), and a branch can be picked to show its own description.
 */
public final class QuestScreen extends Screen {
    private static final Identifier LOCK = Identifier.withDefaultNamespace("widget/locked_button");
    private static final int NODE_W = 120, NODE_H = 26, COL_STEP = 156, ROW_STEP = 36;
    private static final int HEADER_H = 46, PANEL_W = 176, MARGIN = 8;
    /** Requirement branches: offset from the stage's left edge, size, step. */
    private static final int REQ_X = 14, REQ_W = NODE_W - 10, REQ_H = 16, REQ_STEP = 20;
    /** The two buttons in the map's top right corner. */
    private static final int BTN = 16;
    private static final float ZOOM_MIN = 0.4F, ZOOM_MAX = 2.0F;

    private static final int GOLD = 0xFFFFCC33, GREEN = 0xFF66CC66, GRAY = 0xFF888888, WHITE = 0xFFFFFFFF, DIM = 0xFFAAAAAA;

    private int lineIndex;
    private String selected;
    /** The picked branch of the selected stage, or -1 for the stage itself. */
    private int selectedReq = -1;
    private boolean lastClickDouble;
    /** Screen position of the map's origin, and its scale. */
    private float panX, panY, zoom = 1.0F;
    private boolean centred;
    private boolean dragging;
    private double dragDistance;
    /** Stage grid cells {column, row} on the map, for the shown line. */
    private final Map<String, int[]> layout = new HashMap<>();
    /** Which columns hold a stage, per row. */
    private final Map<Integer, Set<Integer>> rowCols = new HashMap<>();
    private int rows;
    /** Map y of each row, after making room under opened branches. */
    private int[] rowY = new int[0];
    private int layoutFor = -1;
    private int lastMapHash;

    QuestScreen() {
        super(Component.translatable("key.enplotify.quests"));
        goToCurrent();
    }

    /** Shows the line of the current task and selects that task. */
    private void goToCurrent() {
        selectedReq = -1;
        QuestSync.Payload map = QuestClient.map();
        String current = map.current();
        for (int i = 0; i < map.lines().size(); i++) {
            String prefix = map.lines().get(i).id() + "#";
            if (current.startsWith(prefix)) {
                lineIndex = i;
                selected = current.substring(prefix.length());
            }
        }
    }

    private LineView line() {
        List<LineView> lines = QuestClient.map().lines();
        if (lines.isEmpty()) return null;
        lineIndex = Mth.clamp(lineIndex, 0, lines.size() - 1);
        return lines.get(lineIndex);
    }

    private StageView stage(LineView line, String id) {
        if (line == null || id == null) return null;
        for (StageView s : line.stages()) if (s.id().equals(id)) return s;
        return null;
    }

    // ---- layout ----

    private void layout(LineView line) {
        int hash = System.identityHashCode(QuestClient.map());
        if (layoutFor == lineIndex && lastMapHash == hash) return;
        layoutFor = lineIndex;
        lastMapHash = hash;
        layout.clear();
        rowCols.clear();
        Map<String, Integer> depth = new HashMap<>();
        for (StageView s : line.stages()) depth(line, s, depth, 0);
        Set<Long> taken = new HashSet<>();
        int maxRow = -1;
        for (StageView s : line.stages()) {
            int col = depth.getOrDefault(s.id(), 0);
            int row = -1;
            for (String parent : s.after()) {
                int[] cell = layout.get(parent);
                if (cell != null) {
                    // Stay on the parent's row; a parallel stage goes just below it.
                    row = cell[1];
                    break;
                }
            }
            // A new start (a root): the next row down, no gap.
            if (row < 0) row = maxRow + 1;
            while (taken.contains(cellKey(col, row))) row++;
            taken.add(cellKey(col, row));
            maxRow = Math.max(maxRow, row);
            layout.put(s.id(), new int[]{col, row});
            rowCols.computeIfAbsent(row, k -> new HashSet<>()).add(col);
        }
        rows = maxRow + 1;
        if (stage(line, selected) == null) {
            selected = line.stages().isEmpty() ? null : firstOpen(line);
            selectedReq = -1;
        }
        computeRows(line);
    }

    private static long cellKey(int col, int row) {
        return ((long) col << 32) | (row & 0xFFFFFFFFL);
    }

    /** Row heights: a row only moves down when an opened branch above would run into one of its stages. */
    private void computeRows(LineView line) {
        rowY = new int[rows];
        List<int[]> open = new ArrayList<>(); // {column, row, branch count}
        for (StageView s : line.stages()) {
            int[] cell = layout.get(s.id());
            if (cell != null && isExpanded(line, s)) open.add(new int[]{cell[0], cell[1], s.requirements().size()});
        }
        for (int row = 0; row < rows; row++) {
            int y = row == 0 ? 0 : rowY[row - 1] + ROW_STEP;
            Set<Integer> cols = rowCols.getOrDefault(row, Set.of());
            for (int[] o : open) {
                if (o[1] < row && cols.contains(o[0])) {
                    y = Math.max(y, rowY[o[1]] + NODE_H + 4 + o[2] * REQ_STEP + (ROW_STEP - NODE_H));
                }
            }
            rowY[row] = y;
        }
    }

    private boolean canExpand(StageView s) {
        return s.branches() && !s.root() && !s.requirements().isEmpty() && !(s.secret() && s.state() == QuestSync.LOCKED);
    }

    /** Opened branches are remembered on this computer; until then only the current task is open. */
    private boolean isExpanded(LineView line, StageView s) {
        if (!canExpand(s)) return false;
        String key = line.id() + "#" + s.id();
        Boolean saved = Memory.get(key);
        return saved != null ? saved : key.equals(QuestClient.map().current());
    }

    /** Where a stage sits on the map (unscaled map units). */
    private int[] pos(String id) {
        int[] cell = layout.get(id);
        if (cell == null || cell[1] >= rowY.length) return null;
        return new int[]{cell[0] * COL_STEP, rowY[cell[1]]};
    }

    private String firstOpen(LineView line) {
        String last = null;
        for (StageView s : line.stages()) {
            if (s.state() == QuestSync.ACTIVE) return s.id();
            if (s.state() == QuestSync.DONE) last = s.id();
        }
        return last != null ? last : line.stages().get(0).id();
    }

    private int depth(LineView line, StageView s, Map<String, Integer> memo, int guard) {
        Integer known = memo.get(s.id());
        if (known != null) return known;
        int d = 0;
        if (guard < 64) {
            for (String parent : s.after()) {
                StageView p = stage(line, parent);
                if (p != null) d = Math.max(d, depth(line, p, memo, guard + 1) + 1);
            }
        }
        memo.put(s.id(), d);
        return d;
    }

    private int mapLeft() { return MARGIN; }
    private int mapTop() { return HEADER_H; }
    private int mapRight() { return width - PANEL_W - MARGIN * 2; }
    private int mapBottom() { return height - MARGIN; }

    private void centreOn(String id) {
        int[] pos = pos(id);
        if (pos == null) return;
        panX = (mapLeft() + mapRight()) / 2.0F - (pos[0] + NODE_W / 2.0F) * zoom;
        panY = (mapTop() + mapBottom()) / 2.0F - (pos[1] + NODE_H / 2.0F) * zoom;
    }

    /** Zooms to {@code to}, keeping the map point under (sx, sy) where it is. */
    private void zoomAt(float to, double sx, double sy) {
        to = Mth.clamp(to, ZOOM_MIN, ZOOM_MAX);
        panX = (float) (sx - (sx - panX) * to / zoom);
        panY = (float) (sy - (sy - panY) * to / zoom);
        zoom = to;
    }

    private double mapX(double sx) { return (sx - panX) / zoom; }
    private double mapY(double sy) { return (sy - panY) / zoom; }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        LineView line = line();
        drawHeader(g);
        if (line == null) {
            g.centeredText(font, Component.translatable("enplotify.quest.none"), width / 2, height / 2, DIM);
            return;
        }
        layout(line);
        computeRows(line);
        if (!centred) {
            centreOn(selected);
            centred = true;
        }
        drawMap(g, line, mouseX, mouseY);
        drawPanel(g, line, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        QuestSync.Payload map = QuestClient.map();
        LineView currentLine = null;
        StageView current = null;
        for (LineView l : map.lines()) {
            String prefix = l.id() + "#";
            if (map.current().startsWith(prefix)) {
                currentLine = l;
                current = stage(l, map.current().substring(prefix.length()));
            }
        }
        Component label = Component.translatable("enplotify.quest.current");
        if (currentLine != null) label = label.copy().append(" · ").append(currentLine.title());
        g.text(font, label, MARGIN + 2, 8, DIM);
        Component title = current == null ? Component.translatable("enplotify.quest.none") : current.title();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(MARGIN + 2, 21);
        pose.scale(1.6F, 1.6F);
        g.text(font, title, 0, 0, current != null && current.state() == QuestSync.DONE ? GREEN : GOLD);
        pose.popMatrix();
    }

    private void drawMap(GuiGraphicsExtractor g, LineView line, int mouseX, int mouseY) {
        int l = mapLeft(), t = mapTop(), r = mapRight(), b = mapBottom();
        g.fill(l, t, r, b, 0x90000000);
        g.outline(l, t, r - l, b - t, 0xFF3A3A3A);
        g.enableScissor(l + 1, t + 1, r - 1, b - 1);
        boolean overMap = inMap(mouseX, mouseY) && button(mouseX, mouseY) == 0;
        double mx = mapX(mouseX), my = mapY(mouseY);
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(panX, panY);
        pose.scale(zoom, zoom);
        // Paths first, under the stages.
        for (StageView s : line.stages()) {
            int[] to = pos(s.id());
            for (String parentId : s.after()) {
                int[] from = pos(parentId);
                StageView parent = stage(line, parentId);
                if (from == null || to == null || parent == null) continue;
                int color = parent.state() == QuestSync.DONE ? 0xFFB0B0B0 : 0xFF4A4A4A;
                int x1 = from[0] + NODE_W, y1 = from[1] + NODE_H / 2;
                int x2 = to[0], y2 = to[1] + NODE_H / 2;
                int mid = (x1 + x2) / 2;
                g.fill(Math.min(x1, mid), y1 - 1, Math.max(x1, mid) + 1, y1 + 1, color);
                g.fill(mid - 1, Math.min(y1, y2) - 1, mid + 1, Math.max(y1, y2) + 1, color);
                g.fill(Math.min(mid, x2), y2 - 1, Math.max(mid, x2), y2 + 1, color);
            }
        }
        Component tip = null;
        for (StageView s : line.stages()) {
            int[] pos = pos(s.id());
            if (pos == null) continue;
            int x = pos[0], y = pos[1];
            boolean hover = overMap && mx >= x && mx < x + NODE_W && my >= y && my < y + NODE_H;
            boolean mine = s.id().equals(selected);
            drawNode(g, line, s, x, y, hover, mine && selectedReq < 0, overMap && onArrow(s, x, y, mx, my));
            if (isExpanded(line, s)) {
                Component branchTip = drawBranches(g, s, x, y, overMap, mx, my, mine ? selectedReq : -1);
                if (branchTip != null) tip = branchTip;
            }
        }
        pose.popMatrix();
        g.disableScissor();
        g.text(font, Component.translatable("enplotify.quest.hint"), l + 4, b - 11, 0xFF666666, false);
        drawButtons(g, mouseX, mouseY);
        if (tip != null) g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
    }

    /** 1 = back to the current task, 2 = default size, 0 = neither. */
    private int button(double mx, double my) {
        int r = mapRight(), t = mapTop();
        if (my < t + 4 || my >= t + 4 + BTN) return 0;
        if (mx >= r - 4 - BTN && mx < r - 4) return 2;
        if (mx >= r - 8 - BTN * 2 && mx < r - 8 - BTN) return 1;
        return 0;
    }

    private void drawButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int r = mapRight(), t = mapTop() + 4;
        int hover = inMap(mouseX, mouseY) ? button(mouseX, mouseY) : 0;
        drawButton(g, r - 8 - BTN * 2, t, "◎", hover == 1);
        drawButton(g, r - 4 - BTN, t, "1:1", hover == 2);
        if (hover == 1) g.setTooltipForNextFrame(font, Component.translatable("enplotify.quest.locate"), mouseX, mouseY);
        if (hover == 2) g.setTooltipForNextFrame(font, Component.translatable("enplotify.quest.zoom_reset"), mouseX, mouseY);
    }

    private void drawButton(GuiGraphicsExtractor g, int x, int y, String icon, boolean hover) {
        g.fill(x, y, x + BTN, y + BTN, hover ? 0xE0404040 : 0xE0202020);
        g.outline(x, y, BTN, BTN, hover ? WHITE : 0xFF666666);
        int w = font.width(icon);
        if (w > BTN - 4) {
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + BTN / 2.0F, y + BTN / 2.0F - 3.0F);
            pose.scale(0.75F, 0.75F);
            g.centeredText(font, icon, 0, 0, hover ? WHITE : DIM);
            pose.popMatrix();
        } else {
            g.centeredText(font, icon, x + BTN / 2, y + 4, hover ? WHITE : DIM);
        }
    }

    /** The open/fold arrow at a branch stage's right end. */
    private boolean onArrow(StageView s, int x, int y, double mx, double my) {
        return canExpand(s) && mx >= x + NODE_W - 14 && mx < x + NODE_W && my >= y && my < y + NODE_H;
    }

    private void drawNode(GuiGraphicsExtractor g, LineView line, StageView s, int x, int y, boolean hover, boolean chosen, boolean arrowHover) {
        int state = s.state();
        int border = s.root() ? 0xFFDDDDDD : state == QuestSync.DONE ? 0xFF4F9F4F : state == QuestSync.ACTIVE ? GOLD : 0xFF555555;
        int fill = state == QuestSync.DONE ? 0xF0182818 : state == QuestSync.ACTIVE ? 0xF02E2610 : 0xF0141414;
        if (chosen) g.outline(x - 2, y - 2, NODE_W + 4, NODE_H + 4, WHITE);
        g.fill(x, y, x + NODE_W, y + NODE_H, hover ? brighter(fill) : fill);
        g.outline(x, y, NODE_W, NODE_H, border);
        int textX;
        if (state == QuestSync.LOCKED) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, LOCK, x + 3, y + 3, 20, 20);
            textX = x + 27;
        } else if (s.root()) {
            g.text(font, "◆", x + 6, y + 9, WHITE);
            textX = x + 17;
        } else {
            g.text(font, state == QuestSync.DONE ? "✔" : "▶", x + 6, y + 9, state == QuestSync.DONE ? GREEN : GOLD);
            textX = x + 17;
        }
        String plain = title(s).getString();
        boolean marker = canExpand(s);
        int room = x + NODE_W - 4 - textX - (marker ? 8 : 0);
        if (s.root()) {
            String cut = plain;
            while (!cut.isEmpty() && font.width(Component.literal(cut).withStyle(ChatFormatting.BOLD)) > room) cut = cut.substring(0, cut.length() - 1);
            g.text(font, Component.literal(cut).withStyle(ChatFormatting.BOLD), textX, y + 9, WHITE);
        } else {
            g.text(font, font.plainSubstrByWidth(plain, room), textX, y + 9, state == QuestSync.LOCKED ? GRAY : WHITE);
        }
        if (marker) {
            if (arrowHover) g.fill(x + NODE_W - 13, y + 3, x + NODE_W - 2, y + NODE_H - 5, 0x40FFFFFF);
            g.text(font, isExpanded(line, s) ? "▾" : "▸", x + NODE_W - 9, y + 9, arrowHover ? WHITE : DIM, false);
        }
        if (state == QuestSync.ACTIVE && !s.requirements().isEmpty()) {
            int barW = NODE_W - 4;
            g.fill(x + 2, y + NODE_H - 4, x + 2 + barW, y + NODE_H - 2, 0xFF3A3020);
            g.fill(x + 2, y + NODE_H - 4, x + 2 + Math.round(barW * fraction(s)), y + NODE_H - 2, GOLD);
        }
    }

    /** The stage's requirements as small nodes forking off a trunk under it; returns the hovered one's full text. */
    private Component drawBranches(GuiGraphicsExtractor g, StageView s, int x, int y, boolean overMap, double mx, double my, int chosen) {
        List<ReqView> reqs = s.requirements();
        int trunkX = x + REQ_X / 2;
        int firstMid = y + NODE_H + 4 + REQ_H / 2;
        int lastMid = firstMid + (reqs.size() - 1) * REQ_STEP;
        int lineColor = s.state() == QuestSync.LOCKED ? 0xFF4A4A4A : 0xFF8A8A8A;
        g.fill(trunkX - 1, y + NODE_H, trunkX + 1, lastMid + 1, lineColor);
        Component tip = null;
        for (int i = 0; i < reqs.size(); i++) {
            ReqView r = reqs.get(i);
            boolean met = r.value() >= r.target();
            int ry = y + NODE_H + 4 + i * REQ_STEP, rx = x + REQ_X;
            int mid = ry + REQ_H / 2;
            g.fill(trunkX, mid - 1, rx, mid + 1, met ? 0xFF4F9F4F : lineColor);
            boolean hover = overMap && mx >= rx && mx < rx + REQ_W && my >= ry && my < ry + REQ_H;
            if (i == chosen) g.outline(rx - 2, ry - 2, REQ_W + 4, REQ_H + 4, WHITE);
            int fill = met ? 0xF0183018 : 0xF0141414;
            g.fill(rx, ry, rx + REQ_W, ry + REQ_H, hover ? brighter(fill) : fill);
            g.outline(rx, ry, REQ_W, REQ_H, met ? 0xFF4F9F4F : 0xFF555555);
            g.text(font, met ? "✔" : "□", rx + 4, ry + 4, met ? GREEN : GRAY, false);
            String count = r.target() > 1 ? r.value() + "/" + r.target() : "";
            int countW = count.isEmpty() ? 0 : font.width(count) + 4;
            String text = font.plainSubstrByWidth(r.text().getString(), REQ_W - 16 - countW - 3);
            g.text(font, text, rx + 14, ry + 4, met ? 0xFFAADDAA : 0xFFCCCCCC, false);
            if (!count.isEmpty()) g.text(font, count, rx + REQ_W - countW, ry + 4, met ? GREEN : DIM, false);
            if (hover) tip = r.text();
        }
        return tip;
    }

    private static int brighter(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 24), gr = Math.min(255, ((argb >> 8) & 0xFF) + 24), b = Math.min(255, (argb & 0xFF) + 24);
        return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }

    private static float fraction(StageView s) {
        if (s.requirements().isEmpty()) return s.state() == QuestSync.DONE ? 1.0F : 0.0F;
        float sum = 0;
        for (ReqView r : s.requirements()) sum += Math.min(1.0F, r.value() / (float) r.target());
        return sum / s.requirements().size();
    }

    private Component title(StageView s) {
        return s.secret() && s.state() == QuestSync.LOCKED ? Component.translatable("enplotify.quest.secret") : s.title();
    }

    private void drawPanel(GuiGraphicsExtractor g, LineView line, int mouseX, int mouseY) {
        int l = width - PANEL_W - MARGIN, t = HEADER_H, r = width - MARGIN, b = height - MARGIN;
        int inner = PANEL_W - 16;
        g.fill(l, t, r, b, 0xC0000000);
        g.outline(l, t, r - l, b - t, 0xFF3A3A3A);
        int y = t + 7;
        // Which line (arrows when there are several).
        boolean several = QuestClient.map().lines().size() > 1;
        String name = font.plainSubstrByWidth(line.title().getString(), inner - (several ? 24 : 0));
        g.centeredText(font, name, (l + r) / 2, y, WHITE);
        if (several) {
            g.text(font, "◀", l + 6, y, hovering(mouseX, mouseY, l + 2, y - 3, 14, 15) ? WHITE : GRAY);
            g.text(font, "▶", r - 12, y, hovering(mouseX, mouseY, r - 16, y - 3, 14, 15) ? WHITE : GRAY);
        }
        y += 14;
        g.fill(l + 6, y, r - 6, y + 1, 0xFF3A3A3A);
        y += 7;
        StageView s = stage(line, selected);
        if (s == null) return;
        int x = l + 8;
        if (selectedReq >= 0 && selectedReq < s.requirements().size() && isExpanded(line, s)) {
            drawReqPanel(g, s, s.requirements().get(selectedReq), x, y, r, inner);
            return;
        }
        int state = s.state();
        Component panelTitle = s.root() ? title(s).copy().withStyle(ChatFormatting.BOLD) : title(s);
        for (FormattedCharSequence part : font.split(panelTitle, inner)) {
            g.text(font, part, x, y, state == QuestSync.DONE ? GREEN : state == QuestSync.ACTIVE ? GOLD : GRAY);
            y += 10;
        }
        String stateKey = state == QuestSync.DONE ? "enplotify.quest.done" : state == QuestSync.ACTIVE ? "enplotify.quest.active" : "enplotify.quest.locked";
        g.text(font, Component.translatable(stateKey), x, y, DIM, false);
        y += 14;
        boolean hidden = s.secret() && state == QuestSync.LOCKED;
        if (!hidden && s.description().isPresent()) {
            for (FormattedCharSequence part : font.split(s.description().get(), inner)) {
                g.text(font, part, x, y, 0xFFCCCCCC, false);
                y += 10;
            }
            y += 6;
        }
        if (state == QuestSync.LOCKED) {
            List<String> names = new ArrayList<>();
            for (String parent : s.after()) {
                StageView p = stage(line, parent);
                if (p != null && p.state() != QuestSync.DONE) names.add(title(p).getString());
            }
            if (!names.isEmpty()) {
                String or = Component.translatable("enplotify.quest.or").getString();
                for (FormattedCharSequence part : font.split(Component.translatable("enplotify.quest.needs", String.join(or, names)), inner)) {
                    g.text(font, part, x, y, DIM, false);
                    y += 10;
                }
                y += 6;
            }
        }
        if (!hidden && !s.requirements().isEmpty()) {
            g.text(font, Component.translatable(state == QuestSync.ACTIVE ? "enplotify.quest.requirements" : "enplotify.quest.requirements_plain"), x, y, WHITE, false);
            y += 13;
            for (ReqView req : s.requirements()) {
                boolean met = req.value() >= req.target();
                g.text(font, met ? "✔" : "□", x, y, met ? GREEN : GRAY, false);
                List<FormattedCharSequence> parts = font.split(req.text(), inner - 12);
                for (FormattedCharSequence part : parts) {
                    g.text(font, part, x + 11, y, met ? 0xFF99BB99 : 0xFFDDDDDD, false);
                    y += 10;
                }
                if (req.target() > 1) {
                    String count = req.value() + "/" + req.target();
                    int barR = r - 10 - font.width(count) - 4;
                    g.fill(x + 11, y + 2, barR, y + 5, 0xFF333333);
                    g.fill(x + 11, y + 2, x + 11 + Math.round((barR - x - 11) * Math.min(1.0F, req.value() / (float) req.target())), y + 5, met ? GREEN : GOLD);
                    g.text(font, count, r - 10 - font.width(count), y, DIM, false);
                    y += 10;
                }
                y += 3;
            }
        }
        // What finishing it opens.
        if (state != QuestSync.DONE && !hidden) {
            List<String> next = new ArrayList<>();
            for (StageView other : line.stages()) {
                if (other.after().contains(s.id())) next.add(title(other).getString());
            }
            if (!next.isEmpty()) {
                y += 4;
                for (FormattedCharSequence part : font.split(Component.translatable("enplotify.quest.unlocks", String.join("、", next)), inner)) {
                    g.text(font, part, x, y, DIM, false);
                    y += 10;
                }
            }
        }
    }

    /** One picked branch: its text, whether it is done, its count and its description. */
    private void drawReqPanel(GuiGraphicsExtractor g, StageView s, ReqView req, int x, int y, int r, int inner) {
        boolean met = req.value() >= req.target();
        for (FormattedCharSequence part : font.split(req.text(), inner)) {
            g.text(font, part, x, y, met ? GREEN : GOLD);
            y += 10;
        }
        g.text(font, Component.translatable(met ? "enplotify.quest.done" : "enplotify.quest.req_todo"), x, y, DIM, false);
        y += 12;
        if (req.target() > 1) {
            String count = req.value() + "/" + req.target();
            int barR = r - 10 - font.width(count) - 4;
            g.fill(x, y + 2, barR, y + 5, 0xFF333333);
            g.fill(x, y + 2, x + Math.round((barR - x) * Math.min(1.0F, req.value() / (float) req.target())), y + 5, met ? GREEN : GOLD);
            g.text(font, count, r - 10 - font.width(count), y, DIM, false);
            y += 12;
        }
        y += 4;
        if (req.description().isPresent()) {
            for (FormattedCharSequence part : font.split(req.description().get(), inner)) {
                g.text(font, part, x, y, 0xFFCCCCCC, false);
                y += 10;
            }
            y += 6;
        }
        for (FormattedCharSequence part : font.split(Component.translatable("enplotify.quest.req_of", title(s)), inner)) {
            g.text(font, part, x, y, 0xFF777777, false);
            y += 10;
        }
    }

    private static boolean hovering(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean inMap(double mx, double my) {
        return mx >= mapLeft() && mx < mapRight() && my >= mapTop() && my < mapBottom();
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        if (inMap(mx, my)) {
            int button = button(mx, my);
            if (button != 0) {
                AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
                if (button == 1) {
                    goToCurrent();
                    layoutFor = -1;
                    LineView line = line();
                    if (line != null) {
                        layout(line);
                        centreOn(selected);
                    }
                } else {
                    zoomAt(1.0F, (mapLeft() + mapRight()) / 2.0, (mapTop() + mapBottom()) / 2.0);
                }
                return true;
            }
            dragging = true;
            dragDistance = 0;
            lastClickDouble = doubleClick;
            return true;
        }
        if (QuestClient.map().lines().size() > 1) {
            int l = width - PANEL_W - MARGIN, r = width - MARGIN, y = HEADER_H + 7;
            int step = hovering((int) mx, (int) my, l + 2, y - 3, 14, 15) ? -1 : hovering((int) mx, (int) my, r - 16, y - 3, 14, 15) ? 1 : 0;
            if (step != 0) {
                int count = QuestClient.map().lines().size();
                lineIndex = (lineIndex + step + count) % count;
                selected = null;
                selectedReq = -1;
                layoutFor = -1;
                layout(line());
                centreOn(selected);
                AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            panX += (float) dx;
            panY += (float) dy;
            dragDistance += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
            if (dragDistance < 3) pick(event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }

    private void pick(double sx, double sy) {
        LineView line = line();
        if (line == null) return;
        double mx = mapX(sx), my = mapY(sy);
        for (StageView s : line.stages()) {
            int[] pos = pos(s.id());
            if (pos == null) continue;
            if (mx >= pos[0] && mx < pos[0] + NODE_W && my >= pos[1] && my < pos[1] + NODE_H) {
                AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
                boolean toggle = onArrow(s, pos[0], pos[1], mx, my) || (lastClickDouble && canExpand(s));
                if (!toggle || !s.id().equals(selected)) selectedReq = -1;
                selected = s.id();
                if (toggle) {
                    boolean open = !isExpanded(line, s);
                    Memory.set(line.id() + "#" + s.id(), open);
                    if (!open) selectedReq = -1;
                    computeRows(line);
                }
                return;
            }
            // A branch node: pick it to read about it.
            if (isExpanded(line, s)) {
                for (int i = 0; i < s.requirements().size(); i++) {
                    int ry = pos[1] + NODE_H + 4 + i * REQ_STEP, rx = pos[0] + REQ_X;
                    if (mx >= rx && mx < rx + REQ_W && my >= ry && my < ry + REQ_H) {
                        AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
                        selected = s.id();
                        selectedReq = i;
                        return;
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (inMap(mx, my)) {
            if (scrollY != 0) zoomAt(zoom * (float) Math.pow(1.15, scrollY), mx, my);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (QuestClient.KEY != null && QuestClient.KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Which branch stages the player opened or folded, kept in config/enplotify-quest-map.txt. */
    private static final class Memory {
        private static Map<String, Boolean> values;

        private static Path file() {
            return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("enplotify-quest-map.txt");
        }

        private static Map<String, Boolean> values() {
            if (values == null) {
                values = new LinkedHashMap<>();
                try {
                    Path p = file();
                    if (Files.exists(p)) {
                        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                            int eq = line.lastIndexOf('=');
                            if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1).trim().equals("1"));
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A broken file just means nothing is remembered.
                }
            }
            return values;
        }

        static Boolean get(String key) {
            return values().get(key);
        }

        static void set(String key, boolean open) {
            values().put(key, open);
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Boolean> e : values.entrySet()) lines.add(e.getKey() + "=" + (e.getValue() ? "1" : "0"));
            try {
                Path p = file();
                Files.createDirectories(p.getParent());
                Files.write(p, lines, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Not saved this time; it still holds until the game closes.
            }
        }
    }
}
