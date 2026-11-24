package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity;
import io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity;
import io.github.lwarqiu1shz.enplotify.paper.TerminalFile;
import io.github.lwarqiu1shz.enplotify.paper.TerminalServer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The little system on a terminal, drawn exactly over the screen once the camera is there. Laid
 * out in GUI pixels (text as large as everywhere else in Minecraft); only when the screen is
 * narrower than 240 GUI pixels is a 240-wide grid shrunk to fit. Clicks are turned into grid points.
 * Win+Esc closes the open dialog / program (plain Esc goes to web pages); Alt+Q leaves the terminal.
 */
final class TerminalScreen extends Screen {
    private static final int MODAL_NONE = 0, MODAL_PRINT = 1, MODAL_DELETE = 2, MODAL_RENAME = 3;
    private static final int ROW = 10, LIST_TOP = 27;
    private static final int PRINTER_ROW = 18, PRINTER_TOP = 29;
    /** The display list's rows, held and dragged like the others (not an app of its own). */
    private static final int LIST_DISPLAYS = 100;
    /** What the search boxes hold (kept while the terminal is used). */
    private static String fileSearch = "", printerSearch = "", displaySearch = "";
    /** The pressed printer was the chosen one: let go without dragging, it is chosen no more. */
    private boolean pressedChosen;
    private @Nullable EditBox searchBox;
    private int searchApp = -1;
    /** A row held down in the file or printer list: which app, which row (file id or printer), where. */
    private int pressApp = -1;
    private @Nullable Object pressKey;
    private double pressY;
    private boolean dragging;
    /** The web page has the keyboard (the system's input method is on for it). */
    private boolean pageIme, composing;
    private static final int PRINT_ROWS = 6;

    private float rx, ry, rw, rh, scale;
    /** This screen's grid, in grid points. */
    private int gridW = TerminalUi.GRID_W, gridH = TerminalUi.GRID_H;
    private int modal = MODAL_NONE;
    private int modalFile = -1;
    private @Nullable BlockPos printTo;
    private int copies = 1;
    private int printScroll;
    private @Nullable EditBox nameBox;

    /** Clickable areas of the last frame (grid coordinates). */
    private record Hit(float x0, float y0, float x1, float y1, Runnable action) {}
    private final List<Hit> hits = new ArrayList<>();
    private double gridMouseX = -1, gridMouseY = -1;

    TerminalScreen() {
        super(Component.translatable("block.enplotify.terminal"));
    }

    boolean showsPrinters() {
        return TerminalView.app == TerminalBlockEntity.APP_PRINTERS || modal == MODAL_PRINT;
    }

    @Override
    protected void init() {
        float[] r = TerminalView.guiRect(this.width, this.height);
        // Whole GUI pixels, so text stays sharp.
        rx = Math.round(r[0]);
        ry = Math.round(r[1]);
        rw = Math.round(r[2]);
        rh = Math.round(r[3]);
        // GUI pixels per grid point = the player's UI size factor (1 = Minecraft's own size).
        double ui = io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.terminalUiScale();
        gridW = Math.max(40, (int) Math.round(rw / ui));
        scale = rw / gridW;
        gridH = Math.max(30, Math.round(rh / scale));
        useGrid();
        String keep = nameBox != null ? nameBox.getValue() : null;
        nameBox = null;
        addressBox = null;
        searchBox = null;
        searchApp = -1;
        if (modal == MODAL_RENAME) openNameBox(keep);
    }

    /** Lays out on this screen's grid (the world renderer uses its own between frames). */
    private void useGrid() {
        TerminalUi.size(gridW, gridH);
        lastGridW = gridW;
        lastGridH = gridH;
    }

    /** The grid of the system as last shown (the size pictures of it are laid out in). */
    static int lastGridW = TerminalUi.GRID_W, lastGridH = TerminalUi.GRID_H;

    /**
     * The grid this player's system would have on a terminal now (as {@link #init} works it out),
     * so a desktop drawn in the world looks exactly like the one on the screen.
     */
    static int[] guiGrid() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        float[] r = TerminalView.guiRect(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        float rw = Math.round(r[2]), rh = Math.round(r[3]);
        double ui = io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.terminalUiScale();
        int w = Math.max(40, (int) Math.round(rw / ui));
        float scale = rw / w;
        int h = Math.max(30, Math.round(rh / scale));
        return new int[] {w, h};
    }

    private int listRows() {
        return Math.max(1, (TerminalUi.WINDOW_BOTTOM - 15 - LIST_TOP) / ROW);
    }

    private int printerRows() {
        return Math.max(1, (TerminalUi.WINDOW_BOTTOM - 14 - PRINTER_TOP) / PRINTER_ROW);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** No dimming or blur: the screen in the world is the background. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        hits.clear();
        if (!TerminalView.arrived()) return;
        useGrid();
        gridMouseX = (mouseX - rx) / scale;
        gridMouseY = (mouseY - ry) / scale;
        Font font = this.font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(rx, ry);
        graphics.pose().scale(scale, scale);
        // The scissor goes through the pose too: given on the grid.
        graphics.enableScissor(0, 0, TerminalUi.W, TerminalUi.H);
        TerminalUi.Painter p = new TerminalUi.Painter() {
            @Override
            public void fill(float x0, float y0, float x1, float y1, int argb) {
                if (x1 <= x0 || y1 <= y0) return;
                graphics.pose().pushMatrix();
                graphics.pose().translate(x0, y0);
                graphics.pose().scale(x1 - x0, y1 - y0);
                graphics.fill(0, 0, 1, 1, argb);
                graphics.pose().popMatrix();
            }

            @Override
            public void text(String text, float x, float y, int argb) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x, y);
                graphics.text(font, text, 0, 0, argb, false);
                graphics.pose().popMatrix();
            }

            @Override
            public void image(net.minecraft.resources.Identifier texture, float x0, float y0, float x1, float y1) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x0, y0);
                graphics.pose().scale((x1 - x0) / 64.0F, (y1 - y0) / 64.0F);
                graphics.blit(texture, 0, 0, 64, 64, 0.0F, 1.0F, 0.0F, 1.0F);
                graphics.pose().popMatrix();
            }
        };
        int hoveredIcon = -1;
        if (TerminalView.app == TerminalBlockEntity.APP_DESKTOP && modal == MODAL_NONE) {
            for (int[] icon : TerminalUi.icons()) {
                if (hover(icon[1] - 6, icon[2] - 2, icon[1] + 22, icon[2] + 30)) hoveredIcon = icon[0];
            }
        }
        TerminalUi.desktop(p, "", hoveredIcon, TerminalView.wallpaper(), TerminalView.iconText());
        for (int[] icon : TerminalUi.icons()) {
            int app = icon[0];
            hits.add(new Hit(icon[1] - 6, icon[2] - 2, icon[1] + 22, icon[2] + 30, () -> TerminalView.setApp(app)));
        }
        switch (TerminalView.app) {
            case TerminalBlockEntity.APP_FILES -> files(p, font);
            case TerminalBlockEntity.APP_PRINTERS -> devices(p, font);
            case TerminalBlockEntity.APP_BROWSER -> browser(p, font, graphics);
            default -> {}
        }
        syncAddressBox();
        syncSearchBox();
        updatePageIme();
        switch (modal) {
            case MODAL_PRINT -> printDialog(p, font);
            case MODAL_DELETE -> deleteDialog(p, font);
            case MODAL_RENAME -> renameDialog(p);
            default -> {}
        }
        status(p, font);
        updateCursor(mouseX, mouseY);
        graphics.disableScissor();
        graphics.pose().popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private boolean hover(float x0, float y0, float x1, float y1) {
        return gridMouseX >= x0 && gridMouseX < x1 && gridMouseY >= y0 && gridMouseY < y1;
    }

    /** A button; returns its right edge. */
    private float button(TerminalUi.Painter p, String key, float x, float y, boolean enabled, Runnable action) {
        String label = I18n.get(key);
        float w = this.font.width(label) + 8;
        TerminalUi.button(p, label, x, y, w, enabled && modalFree() && hover(x, y, x + w, y + 11), enabled);
        if (enabled) hits.add(new Hit(x, y, x + w, y + 11, action));
        return x + w;
    }

    private boolean modalFree() {
        return true;
    }

    private void windowFrame(TerminalUi.Painter p, int app) {
        TerminalUi.window(p, TerminalUi.appName(app));
        // The window covers the desktop icons.
        hits.clear();
        hits.add(new Hit(TerminalUi.W - TerminalUi.TITLE, 0, TerminalUi.W, TerminalUi.TITLE, () -> {
            // The window's x closes it: a browser's pages end (Win+Esc only minimises).
            if (app == TerminalBlockEntity.APP_BROWSER) TerminalView.closeBrowser();
            TerminalView.setApp(TerminalBlockEntity.APP_DESKTOP);
        }));
    }

    private void files(TerminalUi.Painter p, Font font) {
        windowFrame(p, TerminalBlockEntity.APP_FILES);
        List<PaperNetwork.FileInfo> files = shownFiles();
        float x = 4;
        x = button(p, "terminal.enplotify.new_document", x, 14, true, () -> newFile(PrinterBlockEntity.DOCUMENT)) + 3;
        x = button(p, "terminal.enplotify.new_newspaper", x, 14, true, () -> newFile(PrinterBlockEntity.NEWSPAPER)) + 3;
        x = button(p, "terminal.enplotify.new_poster", x, 14, true, () -> newFile(PrinterBlockEntity.POSTER)) + 3;
        x = button(p, "terminal.enplotify.import", x, 14, true, this::importHeld) + 6;
        searchField(p, x, 14, TerminalUi.W - 4);
        p.fill(0, LIST_TOP - 1, TerminalUi.W, LIST_TOP, 0xFFD8DCE0);
        if (files.isEmpty()) {
            String empty = I18n.get("terminal.enplotify.no_files");
            p.text(empty, (TerminalUi.W - font.width(empty)) / 2.0F, TerminalUi.WINDOW_BOTTOM / 2.0F, TerminalUi.TEXT_DIM);
        }
        int rows = listRows();
        int maxScroll = Math.max(0, files.size() - rows);
        TerminalView.scroll = Math.clamp(TerminalView.scroll, 0, maxScroll);
        for (int i = 0; i < rows && i + TerminalView.scroll < files.size(); i++) {
            PaperNetwork.FileInfo f = files.get(i + TerminalView.scroll);
            float y = LIST_TOP + i * ROW;
            boolean sel = f.id() == TerminalView.selected;
            boolean held = dragging && Integer.valueOf(f.id()).equals(pressKey);
            if (sel) p.fill(0, y, TerminalUi.W, y + ROW, held ? 0xFFB6CDEA : TerminalUi.SELECTED);
            else if (hover(0, y, TerminalUi.W, y + ROW) && !dragging) p.fill(0, y, TerminalUi.W, y + ROW, 0xFFE8ECF0);
            p.fill(5, y + 2, 11, y + 8, TerminalUi.kindColor(f.kind()));
            String kind = TerminalUi.kindName(f.kind()) + (f.editable() ? "" : " · " + I18n.get("terminal.enplotify.read_only"));
            int kindW = font.width(kind);
            TerminalUi.textMiddle(p, font.plainSubstrByWidth(shownName(f.name()), TerminalUi.W - 30 - kindW), 15, y, y + ROW, TerminalUi.TEXT);
            TerminalUi.textMiddle(p, kind, TerminalUi.W - 6 - kindW, y, y + ROW, TerminalUi.TEXT_DIM);
            int id = f.id();
            hits.add(new Hit(0, y, TerminalUi.W, y + ROW, () -> {
                TerminalView.selected = id;
                press(TerminalBlockEntity.APP_FILES, id);
            }));
        }
        dropLine(p, TerminalBlockEntity.APP_FILES, LIST_TOP, ROW, rows, files.size());
        float ay = TerminalUi.WINDOW_BOTTOM - 13;
        p.fill(0, ay - 2, TerminalUi.W, ay - 1, 0xFFD8DCE0);
        boolean any = TerminalView.selected >= 0;
        x = 4;
        x = button(p, "terminal.enplotify.open", x, ay, any, this::openSelected) + 3;
        x = button(p, "terminal.enplotify.print", x, ay, any, this::openPrint) + 3;
        x = button(p, "terminal.enplotify.rename", x, ay, any, () -> {
            modal = MODAL_RENAME;
            modalFile = TerminalView.selected;
            openNameBox(null);
        }) + 3;
        button(p, "terminal.enplotify.delete", x, ay, any, () -> {
            modal = MODAL_DELETE;
            modalFile = TerminalView.selected;
        });
    }

    /** The devices program: first a choice of printers or displays, then that list. */
    private void devices(TerminalUi.Painter p, Font font) {
        windowFrame(p, TerminalBlockEntity.APP_PRINTERS);
        switch (TerminalView.devicePage) {
            case 1 -> printers(p, font);
            case 2 -> displays(p, font);
            default -> deviceChoice(p, font);
        }
    }

    private void deviceChoice(TerminalUi.Painter p, Font font) {
        String[] keys = {"terminal.enplotify.devices.printers", "terminal.enplotify.devices.displays"};
        int[] counts = {TerminalView.printers.size(), TerminalView.displays.size()};
        float rowH = 22;
        for (int i = 0; i < keys.length; i++) {
            float y0 = 16 + i * (rowH + 2), y1 = y0 + rowH;
            if (hover(0, y0, TerminalUi.W, y1)) p.fill(0, y0, TerminalUi.W, y1, 0xFFE8ECF0);
            if (i == 0) TerminalUi.printerIcon(p, 6, y0 + 3);
            else TerminalUi.displayIcon(p, 6, y0 + 3);
            TerminalUi.textMiddle(p, I18n.get(keys[i]), 28, y0, y1, TerminalUi.TEXT);
            String count = I18n.get("terminal.enplotify.devices.count", counts[i]);
            TerminalUi.textMiddle(p, count, TerminalUi.W - 8 - font.width(count), y0, y1, TerminalUi.TEXT_DIM);
            final int page = i + 1;
            hits.add(new Hit(0, y0, TerminalUi.W, y1, () -> {
                TerminalView.devicePage = page;
                TerminalView.scroll = 0;
                TerminalView.send(TerminalServer.OP_REFRESH, 0);
            }));
        }
    }

    /** The top of a device list: back to the choice, and the search field. */
    private void deviceListTop(TerminalUi.Painter p) {
        float x = button(p, "terminal.enplotify.devices.back", 4, 14, true, () -> {
            TerminalView.devicePage = 0;
            TerminalView.scroll = 0;
        }) + 4;
        searchField(p, x, 14, TerminalUi.W - 4);
        p.fill(0, PRINTER_TOP - 2, TerminalUi.W, PRINTER_TOP - 1, 0xFFD8DCE0);
    }

    /** A small blue label after a name (like "默认"), centred on mid; returns nothing. */
    private void tag(TerminalUi.Painter p, String tag, float x, float mid) {
        float w = this.font.width(tag);
        p.fill(x - 3, mid - 6, x + w + 3, mid + 6, 0xFF3C8DDE);
        TerminalUi.textMiddle(p, tag, x, mid - 6, mid + 6, TerminalUi.TEXT_LIGHT);
    }

    private void printers(TerminalUi.Painter p, Font font) {
        deviceListTop(p);
        List<PaperNetwork.PrinterInfo> printers = shownPrinters();
        if (printers.isEmpty()) {
            String none = I18n.get("terminal.enplotify.no_printers");
            p.text(none, (TerminalUi.W - font.width(none)) / 2.0F, TerminalUi.WINDOW_BOTTOM / 2.0F, TerminalUi.TEXT_DIM);
            return;
        }
        int rows = printerRows();
        int maxScroll = Math.max(0, printers.size() - rows);
        TerminalView.scroll = Math.clamp(TerminalView.scroll, 0, maxScroll);
        for (int i = 0; i < rows && i + TerminalView.scroll < printers.size(); i++) {
            PaperNetwork.PrinterInfo info = printers.get(i + TerminalView.scroll);
            float y0 = PRINTER_TOP - 1 + i * PRINTER_ROW, y1 = y0 + PRINTER_ROW, mid = (y0 + y1) / 2.0F;
            boolean held = dragging && info.pos().equals(pressKey);
            if (info.chosen()) p.fill(0, y0, TerminalUi.W, y1, held ? 0xFFB6CDEA : TerminalUi.SELECTED);
            else if (held) p.fill(0, y0, TerminalUi.W, y1, 0xFFDCE3EA);
            else if (hover(0, y0, TerminalUi.W, y1) && !dragging) p.fill(0, y0, TerminalUi.W, y1, 0xFFE8ECF0);
            TerminalUi.printerIcon(p, 4, mid - 8);
            String status = printerStatus(info);
            int statusW = font.width(status);
            String tag = info.chosen() ? I18n.get("terminal.enplotify.printer_chosen") : "";
            int tagW = tag.isEmpty() ? 0 : font.width(tag) + 10;
            String name = font.plainSubstrByWidth(printerName(info), TerminalUi.W - 32 - statusW - tagW);
            TerminalUi.textMiddle(p, name, 24, y0, y1, TerminalUi.TEXT);
            if (!tag.isEmpty()) tag(p, tag, 24 + font.width(name) + 6, mid);
            TerminalUi.textMiddle(p, status, TerminalUi.W - 6 - statusW, y0, y1, info.busy() ? 0xFF2E8B3A : TerminalUi.TEXT_DIM);
            BlockPos at = info.pos();
            boolean chosen = info.chosen();
            hits.add(new Hit(0, y0, TerminalUi.W, y1, () -> {
                // The chosen one clicked again: no longer chosen (when let go without dragging).
                if (!chosen) choosePrinter(at);
                press(TerminalBlockEntity.APP_PRINTERS, at);
                pressedChosen = chosen;
            }));
        }
        dropLine(p, TerminalBlockEntity.APP_PRINTERS, PRINTER_TOP - 1, PRINTER_ROW, rows, printers.size());
        String hint = I18n.get("terminal.enplotify.printers_hint");
        p.text(font.plainSubstrByWidth(hint, TerminalUi.W - 8), 4, TerminalUi.WINDOW_BOTTOM - 11, TerminalUi.TEXT_DIM);
    }

    private void displays(TerminalUi.Painter p, Font font) {
        deviceListTop(p);
        List<PaperNetwork.DisplayInfo> displays = shownDisplays();
        if (displays.isEmpty()) {
            String none = I18n.get("terminal.enplotify.no_displays");
            p.text(none, (TerminalUi.W - font.width(none)) / 2.0F, TerminalUi.WINDOW_BOTTOM / 2.0F, TerminalUi.TEXT_DIM);
            return;
        }
        int rows = printerRows();
        int maxScroll = Math.max(0, displays.size() - rows);
        TerminalView.scroll = Math.clamp(TerminalView.scroll, 0, maxScroll);
        for (int i = 0; i < rows && i + TerminalView.scroll < displays.size(); i++) {
            PaperNetwork.DisplayInfo info = displays.get(i + TerminalView.scroll);
            float y0 = PRINTER_TOP - 1 + i * PRINTER_ROW, y1 = y0 + PRINTER_ROW, mid = (y0 + y1) / 2.0F;
            boolean mine = info.state() == PaperNetwork.DisplayInfo.MINE, taken = info.state() == PaperNetwork.DisplayInfo.TAKEN;
            boolean held = dragging && info.id().equals(pressKey);
            if (mine) p.fill(0, y0, TerminalUi.W, y1, held ? 0xFFB6CDEA : TerminalUi.SELECTED);
            else if (held) p.fill(0, y0, TerminalUi.W, y1, 0xFFDCE3EA);
            else if (hover(0, y0, TerminalUi.W, y1) && !dragging && !taken) p.fill(0, y0, TerminalUi.W, y1, 0xFFE8ECF0);
            TerminalUi.displayIcon(p, 4, mid - 8);
            String status = taken ? I18n.get("terminal.enplotify.display_taken", ownerName(info))
                    : I18n.get("terminal.enplotify.display_size", (info.size() >> 8) & 0xFF, info.size() & 0xFF);
            int statusW = Math.min(font.width(status), TerminalUi.W / 2);
            status = font.plainSubstrByWidth(status, statusW);
            String tag = mine ? I18n.get("terminal.enplotify.display_connected") : "";
            int tagW = tag.isEmpty() ? 0 : font.width(tag) + 10;
            String name = font.plainSubstrByWidth(displayName(info), TerminalUi.W - 32 - statusW - tagW);
            TerminalUi.textMiddle(p, name, 24, y0, y1, taken ? 0xFFA8ACB2 : TerminalUi.TEXT);
            if (!tag.isEmpty()) tag(p, tag, 24 + font.width(name) + 6, mid);
            TerminalUi.textMiddle(p, status, TerminalUi.W - 6 - statusW, y0, y1, taken ? 0xFFA8ACB2 : TerminalUi.TEXT_DIM);
            java.util.UUID id = info.id();
            hits.add(new Hit(0, y0, TerminalUi.W, y1, () -> press(LIST_DISPLAYS, id)));
        }
        dropLine(p, LIST_DISPLAYS, PRINTER_TOP - 1, PRINTER_ROW, rows, displays.size());
        String hint = I18n.get("terminal.enplotify.displays_hint");
        p.text(font.plainSubstrByWidth(hint, TerminalUi.W - 8), 4, TerminalUi.WINDOW_BOTTOM - 11, TerminalUi.TEXT_DIM);
    }

    /** A device's default name, e.g. "终端[1,2,3]" (see ScreenLink.defaultName). */
    static String defaultName(String kindKey, net.minecraft.core.BlockPos at) {
        return io.github.lwarqiu1shz.enplotify.paper.ScreenLink.defaultName(kindKey, at).getString();
    }

    static String displayName(PaperNetwork.DisplayInfo info) {
        return info.name().isEmpty()
                ? defaultName(info.kindKey(), info.pos())
                : info.name();
    }

    private static String ownerName(PaperNetwork.DisplayInfo info) {
        return info.ownerName().isEmpty()
                ? defaultName(info.ownerKey(), info.owner())
                : info.ownerName();
    }

    /** A display row clicked (not dragged): connect it here, or let it go; one taken elsewhere stays. */
    private void toggleDisplay(java.util.UUID id) {
        List<PaperNetwork.DisplayInfo> next = new ArrayList<>();
        for (PaperNetwork.DisplayInfo info : TerminalView.displays) {
            if (info.id().equals(id)) {
                if (info.state() == PaperNetwork.DisplayInfo.TAKEN) {
                    TerminalView.message = "message.enplotify.terminal.display_taken";
                    TerminalView.messageTime = System.currentTimeMillis();
                    return;
                }
                int state = info.state() == PaperNetwork.DisplayInfo.MINE ? PaperNetwork.DisplayInfo.FREE : PaperNetwork.DisplayInfo.MINE;
                info = new PaperNetwork.DisplayInfo(info.id(), info.pos(), info.name(), info.kindKey(), info.size(), state, info.owner(),
                        info.ownerName(), info.ownerKey());
            }
            next.add(info);
        }
        TerminalView.displays = next;
        TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_TOGGLE_DISPLAY, 0, 0, BlockPos.ZERO, id.toString()));
    }

    // ------------------------------------------------------------------ browser (with FerricOxide)

    static final int TAB_TOP = 12, TAB_H = 12, TOOL_TOP = 24, PAGE_TOP = 37;
    /** Browser colours: tab row, the shown tab and toolbar (one surface), lines, the address field. */
    private static final int TAB_ROW = 0xFFDDE1E6, TOOLBAR = 0xFFF7F8FA, LINE = 0xFFC9CED5, FIELD = 0xFFFFFFFF;

    /**
     * Page px per grid point. At UI size 1 a page px is a window pixel (sharp); at size 2 the page
     * is drawn half as wide and shown twice as large, like zooming a browser.
     */
    private double pagePixel() {
        return scale * this.minecraft.getWindow().getGuiScale() / io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.terminalUiScale();
    }

    /** Tells the browser the page size it should have now. */
    private void sizePage() {
        double px = pagePixel();
        WebBrowser.want((int) Math.round(TerminalUi.W * px), (int) Math.round((TerminalUi.WINDOW_BOTTOM - PAGE_TOP) * px));
    }
    private @Nullable EditBox addressBox;

    private void browser(TerminalUi.Painter p, Font font, GuiGraphicsExtractor graphics) {
        windowFrame(p, TerminalBlockEntity.APP_BROWSER);
        sizePage();
        // Bands edge to edge, so nothing of the window body shows between them.
        p.fill(0, TAB_TOP, TerminalUi.W, TOOL_TOP, TAB_ROW);
        p.fill(0, TOOL_TOP, TerminalUi.W, PAGE_TOP - 1, TOOLBAR);
        p.fill(0, PAGE_TOP - 1, TerminalUi.W, PAGE_TOP, LINE);
        tabStrip(p, font);
        WebBrowser browser = WebBrowser.get();
        float ax = addressX();
        p.fill(ax - 1, TOOL_TOP + 1, TerminalUi.W - 2, TOOL_TOP + 11, LINE);
        p.fill(ax, TOOL_TOP + 2, TerminalUi.W - 3, TOOL_TOP + 10, FIELD);
        float x = button(p, "terminal.enplotify.browser_back", 3, TOOL_TOP + 0.5F, browser != null && browser.canBack(), () -> {
            WebBrowser b = WebBrowser.get();
            if (b != null) b.back();
        }) + 2;
        x = button(p, "terminal.enplotify.browser_forward", x, TOOL_TOP + 0.5F, browser != null && browser.canForward(), () -> {
            WebBrowser b = WebBrowser.get();
            if (b != null) b.forward();
        }) + 2;
        button(p, "terminal.enplotify.browser_reload", x, TOOL_TOP + 0.5F, browser != null, () -> {
            WebBrowser b = WebBrowser.get();
            if (b != null) b.reload();
        });
        TerminalShots.pageArea = new float[] {0.0F, PAGE_TOP / (float) TerminalUi.H, 1.0F, TerminalUi.WINDOW_BOTTOM / (float) TerminalUi.H};
        if (browser != null && browser.hasFrame()) {
            graphics.blit(browser.texture(), 0, PAGE_TOP, TerminalUi.W, TerminalUi.WINDOW_BOTTOM, 0.0F, 1.0F, 0.0F, browser.pageV());
        } else {
            p.fill(0, PAGE_TOP, TerminalUi.W, TerminalUi.WINDOW_BOTTOM, 0xFFFFFFFF);
            String text = I18n.get(browser == null ? "terminal.enplotify.browser_failed" : "terminal.enplotify.browser_loading");
            p.text(text, (TerminalUi.W - font.width(text)) / 2.0F, (PAGE_TOP + TerminalUi.WINDOW_BOTTOM) / 2.0F, TerminalUi.TEXT_DIM);
        }
    }

    /** The tab strip: each tab shows its site and an "x" that closes it (and ends its page process). */
    private void tabStrip(TerminalUi.Painter p, Font font) {
        int count = WebBrowser.tabCount();
        if (count == 0) return;
        float plus = 12;
        float tabWidth = Math.max(34, Math.min(86, (TerminalUi.W - 8 - plus) / count - 1));
        float top = TAB_TOP + 1;
        for (int i = 0; i < count; i++) {
            float x0 = 3 + i * (tabWidth + 1);
            if (x0 + tabWidth > TerminalUi.W - 4) break;
            boolean shown = i == WebBrowser.activeIndex();
            boolean over = hover(x0, top, x0 + tabWidth, TOOL_TOP);
            // The shown tab is the toolbar's colour and runs into it; the others sit on the row.
            if (shown) p.fill(x0, top, x0 + tabWidth, TOOL_TOP, TOOLBAR);
            else if (over) p.fill(x0, top, x0 + tabWidth, TOOL_TOP, 0xFFE9ECEF);
            else if (i + 1 != WebBrowser.activeIndex() && i + 1 < count) p.fill(x0 + tabWidth, top + 2, x0 + tabWidth + 1, TOOL_TOP - 2, LINE);
            String label = WebBrowser.titleAt(i);
            TerminalUi.textMiddle(p, font.plainSubstrByWidth(label, (int) Math.max(10, tabWidth - 15)), x0 + 4, top, TOOL_TOP,
                    shown ? TerminalUi.TEXT : TerminalUi.TEXT_DIM);
            boolean overX = hover(x0 + tabWidth - 10, top, x0 + tabWidth - 1, TOOL_TOP);
            float midY = (top + TOOL_TOP) / 2.0F;
            if (overX) p.fill(x0 + tabWidth - 10, midY - 4, x0 + tabWidth - 2, midY + 4, 0xFFD6DAE0);
            TerminalUi.cross(p, x0 + tabWidth - 6, midY, 2.0F, overX ? 0xFFB04A3A : TerminalUi.TEXT_DIM);
            final int index = i;
            hits.add(new Hit(x0, top, x0 + tabWidth, TOOL_TOP, () -> selectTab(index)));
            hits.add(new Hit(x0 + tabWidth - 10, top, x0 + tabWidth, TOOL_TOP, () -> closeBrowserTab(index)));
        }
        float px = 3 + count * (tabWidth + 1) + 1;
        if (px + plus <= TerminalUi.W - 3) {
            float midY = (top + TOOL_TOP) / 2.0F;
            if (hover(px, top, px + plus, TOOL_TOP)) p.fill(px + 1, midY - 4.5F, px + plus - 1, midY + 4.5F, 0xFFE9ECEF);
            TerminalUi.plus(p, px + plus / 2.0F, midY, 3.0F, TerminalUi.TEXT);
            hits.add(new Hit(px, top, px + plus, TOOL_TOP, this::openNewTab));
        }
    }

    /** The search field's look; the box itself is {@link #syncSearchBox}. */
    private void searchField(TerminalUi.Painter p, float x0, float y, float x1) {
        if (x1 - x0 < 30) return;
        p.fill(x0 - 1, y, x1 + 1, y + 11, LINE);
        p.fill(x0, y + 1, x1, y + 10, FIELD);
        searchArea = new float[] {x0, y, x1, y + 11};
    }

    private float @Nullable [] searchArea;

    /** The search box is there exactly while the files or printers window shows (no dialog over it). */
    private void syncSearchBox() {
        int app = TerminalView.app;
        int key = searchKey();
        boolean want = modal == MODAL_NONE && searchArea != null && key >= 0;
        if (!want || searchApp != key) {
            if (searchBox != null) removeWidget(searchBox);
            searchBox = null;
            searchApp = -1;
        }
        if (!want) {
            searchArea = null;
            return;
        }
        if (searchBox != null) return;
        float[] a = searchArea;
        searchBox = new EditBox(this.font, Math.round(rx + (a[0] + 3) * scale), Math.round(ry + (a[1] + a[3]) / 2.0F * scale - 4),
                Math.max(10, Math.round((a[2] - a[0] - 6) * scale)), 9, Component.empty());
        searchBox.setBordered(false);
        searchBox.setTextColor(TerminalUi.TEXT);
        searchBox.setTextShadow(false);
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("terminal.enplotify.search").withStyle(s -> s.withColor(0xFF9AA0A8)));
        searchBox.setValue(key == TerminalBlockEntity.APP_FILES ? fileSearch : key == LIST_DISPLAYS ? displaySearch : printerSearch);
        searchBox.setResponder(text -> {
            if (searchApp == TerminalBlockEntity.APP_FILES) fileSearch = text;
            else if (searchApp == LIST_DISPLAYS) displaySearch = text;
            else printerSearch = text;
            TerminalView.scroll = 0;
        });
        searchApp = key;
        addRenderableWidget(searchBox);
    }

    /** Which list's search box shows now (-1: none): files, printers or displays. */
    private static int searchKey() {
        if (TerminalView.app == TerminalBlockEntity.APP_FILES) return TerminalBlockEntity.APP_FILES;
        if (TerminalView.app != TerminalBlockEntity.APP_PRINTERS) return -1;
        return TerminalView.devicePage == 1 ? TerminalBlockEntity.APP_PRINTERS : TerminalView.devicePage == 2 ? LIST_DISPLAYS : -1;
    }

    /** The displays as listed (search applied). */
    private static List<PaperNetwork.DisplayInfo> shownDisplays() {
        if (displaySearch.isBlank()) return TerminalView.displays;
        List<PaperNetwork.DisplayInfo> out = new ArrayList<>();
        for (PaperNetwork.DisplayInfo info : TerminalView.displays) if (matches(displayName(info), displaySearch)) out.add(info);
        return out;
    }

    private static boolean matches(String name, String search) {
        String q = search.strip().toLowerCase(java.util.Locale.ROOT);
        return q.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    /** The files as listed (search applied). */
    private static List<PaperNetwork.FileInfo> shownFiles() {
        if (fileSearch.isBlank()) return TerminalView.files;
        List<PaperNetwork.FileInfo> out = new ArrayList<>();
        for (PaperNetwork.FileInfo f : TerminalView.files) if (matches(shownName(f.name()), fileSearch)) out.add(f);
        return out;
    }

    /** The printers as listed (search applied). */
    private static List<PaperNetwork.PrinterInfo> shownPrinters() {
        if (printerSearch.isBlank()) return TerminalView.printers;
        List<PaperNetwork.PrinterInfo> out = new ArrayList<>();
        for (PaperNetwork.PrinterInfo info : TerminalView.printers) if (matches(printerName(info), printerSearch)) out.add(info);
        return out;
    }

    /** Makes {@code at} the chosen printer (null: none chosen). */
    private void choosePrinter(@Nullable BlockPos at) {
        List<PaperNetwork.PrinterInfo> next = new ArrayList<>();
        for (PaperNetwork.PrinterInfo info : TerminalView.printers) {
            next.add(new PaperNetwork.PrinterInfo(info.pos(), info.name(), info.kindKey(), info.paper(), info.paperKind(), info.busy(), info.queued(),
                    info.pos().equals(at)));
        }
        TerminalView.printers = next;
        TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_CHOOSE_PRINTER, at == null ? 1 : 0, 0,
                at == null ? BlockPos.ZERO : at, ""));
    }

    /** A list row was pressed: it may be dragged to another place. */
    private void press(int app, Object key) {
        pressApp = app;
        pressKey = key;
        pressY = gridMouseY;
        dragging = false;
    }

    /** Where a dragged row would go: a slot 0..count of the listed rows. */
    private int dropSlot(float top, float rowH, int count) {
        int slot = (int) Math.round((gridMouseY - top) / rowH) + TerminalView.scroll;
        return Math.clamp(slot, 0, count);
    }

    /** The line showing where the dragged row goes. */
    private void dropLine(TerminalUi.Painter p, int app, float top, float rowH, int rows, int count) {
        if (!dragging || pressApp != app) return;
        int slot = dropSlot(top, rowH, count);
        float y = top + (slot - TerminalView.scroll) * rowH;
        if (slot < TerminalView.scroll || slot > TerminalView.scroll + rows) return;
        p.fill(2, y - 0.5F, TerminalUi.W - 2, y + 0.5F, 0xFF3C8DDE);
        p.fill(2, y - 1.5F, 3, y + 1.5F, 0xFF3C8DDE);
        p.fill(TerminalUi.W - 3, y - 1.5F, TerminalUi.W - 2, y + 1.5F, 0xFF3C8DDE);
    }

    /** The held row was let go: if it was dragged, it moves (in the whole list, also when searching). */
    private void drop() {
        if (!dragging || pressKey == null) {
            if (!dragging && pressApp == TerminalBlockEntity.APP_PRINTERS && pressedChosen) choosePrinter(null);
            if (!dragging && pressApp == LIST_DISPLAYS && pressKey instanceof java.util.UUID id) toggleDisplay(id);
            pressApp = -1;
            pressKey = null;
            pressedChosen = false;
            dragging = false;
            return;
        }
        if (pressApp == TerminalBlockEntity.APP_FILES && pressKey instanceof Integer id) {
            List<PaperNetwork.FileInfo> shown = shownFiles();
            int slot = dropSlot(LIST_TOP, ROW, shown.size());
            List<PaperNetwork.FileInfo> all = new ArrayList<>(TerminalView.files);
            int from = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id() == id) from = i;
            int to = slot >= shown.size() ? (shown.isEmpty() ? all.size() : all.indexOf(shown.getLast()) + 1) : all.indexOf(shown.get(slot));
            if (from >= 0 && to >= 0) {
                if (to > from) to--;
                if (to != from) {
                    all.add(to, all.remove(from));
                    TerminalView.files = all;
                    TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_MOVE_FILE, id, to, BlockPos.ZERO, ""));
                }
            }
        } else if (pressApp == TerminalBlockEntity.APP_PRINTERS && pressKey instanceof BlockPos at) {
            List<PaperNetwork.PrinterInfo> shown = shownPrinters();
            int slot = dropSlot(PRINTER_TOP - 1, PRINTER_ROW, shown.size());
            List<PaperNetwork.PrinterInfo> all = new ArrayList<>(TerminalView.printers);
            int from = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).pos().equals(at)) from = i;
            int to = slot >= shown.size() ? (shown.isEmpty() ? all.size() : all.indexOf(shown.getLast()) + 1) : all.indexOf(shown.get(slot));
            if (from >= 0 && to >= 0) {
                if (to > from) to--;
                if (to != from) {
                    all.add(to, all.remove(from));
                    TerminalView.printers = all;
                    TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_MOVE_PRINTER, 0, to, at, ""));
                }
            }
        } else if (pressApp == LIST_DISPLAYS && pressKey instanceof java.util.UUID id) {
            List<PaperNetwork.DisplayInfo> shown = shownDisplays();
            int slot = dropSlot(PRINTER_TOP - 1, PRINTER_ROW, shown.size());
            List<PaperNetwork.DisplayInfo> all = new ArrayList<>(TerminalView.displays);
            int from = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(id)) from = i;
            int to = slot >= shown.size() ? (shown.isEmpty() ? all.size() : all.indexOf(shown.getLast()) + 1) : all.indexOf(shown.get(slot));
            if (from >= 0 && to >= 0) {
                if (to > from) to--;
                if (to != from) {
                    all.add(to, all.remove(from));
                    TerminalView.displays = all;
                    TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_MOVE_DISPLAY, 0, to, BlockPos.ZERO, id.toString()));
                }
            }
        }
        pressApp = -1;
        pressKey = null;
        pressedChosen = false;
        dragging = false;
    }

    private void selectTab(int index) {
        if (addressBox != null) {
            addressBox.setValue(WebBrowser.urlAt(index));
            addressBox.setFocused(false);
            setFocused(null);
        }
        WebBrowser.select(index);
    }

    private void closeBrowserTab(int index) {
        WebBrowser.closeTab(index);
        if (WebBrowser.tabCount() == 0) {
            // The last tab: the whole window goes, and its pages are already gone.
            TerminalView.closeBrowser();
            TerminalView.setApp(TerminalBlockEntity.APP_DESKTOP);
            return;
        }
        if (addressBox != null) {
            addressBox.setValue(TerminalView.browserUrl);
            addressBox.moveCursorToStart(false);
        }
    }

    private void openNewTab() {
        if (!WebBrowser.newTab()) {
            TerminalView.message = "message.enplotify.terminal.too_many_tabs";
            TerminalView.messageTime = System.currentTimeMillis();
            return;
        }
        if (addressBox != null) {
            addressBox.setValue(TerminalView.browserUrl);
            addressBox.moveCursorToStart(false);
        }
    }

    /** Address x on the grid (after the three buttons). */
    private float addressX() {
        return 3 + buttonWidth("terminal.enplotify.browser_back") + 2 + buttonWidth("terminal.enplotify.browser_forward") + 2
                + buttonWidth("terminal.enplotify.browser_reload") + 3;
    }

    /** The address box is there exactly while the browser window is shown. */
    private void syncAddressBox() {
        boolean want = TerminalView.app == TerminalBlockEntity.APP_BROWSER && modal == MODAL_NONE && WebBrowser.get() != null;
        if (!want) {
            if (addressBox != null) removeWidget(addressBox);
            addressBox = null;
            return;
        }
        if (addressBox != null) {
            // Follow the page (links, back / forward) unless the player is typing an address.
            if (!addressBox.isFocused() && !addressBox.getValue().equals(TerminalView.browserUrl)) {
                addressBox.setValue(TerminalView.browserUrl);
                addressBox.moveCursorToStart(false);
            }
            return;
        }
        float ax = addressX();
        float fieldMid = TOOL_TOP + 6.0F;
        addressBox = new EditBox(this.font, Math.round(rx + (ax + 3) * scale), Math.round(ry + fieldMid * scale - 4),
                Math.max(10, Math.round((TerminalUi.W - 6 - ax - 3) * scale)), 9, Component.empty());
        addressBox.setBordered(false);
        addressBox.setTextColor(TerminalUi.TEXT);
        addressBox.setTextShadow(false);
        addressBox.setMaxLength(2048);
        addressBox.setValue(TerminalView.browserUrl);
        addressBox.moveCursorToStart(false);
        addRenderableWidget(addressBox);
    }

    /** A point on the page (page px), or null when (x, y) is not over the page. */
    private int @Nullable [] pagePoint(double x, double y) {
        if (TerminalView.app != TerminalBlockEntity.APP_BROWSER || modal != MODAL_NONE || !TerminalView.arrived()) return null;
        useGrid();
        double gx = (x - rx) / scale, gy = (y - ry) / scale;
        if (gx < 0 || gx >= TerminalUi.W || gy < PAGE_TOP || gy >= TerminalUi.WINDOW_BOTTOM) return null;
        double px = pagePixel();
        return new int[] {(int) (gx * px), (int) ((gy - PAGE_TOP) * px)};
    }

    /** The page has the keyboard: browser shown and the address box not focused. */
    private @Nullable WebBrowser typingIntoPage() {
        if (TerminalView.app != TerminalBlockEntity.APP_BROWSER || modal != MODAL_NONE) return null;
        if (addressBox != null && addressBox.isFocused()) return null;
        return WebBrowser.get();
    }

    /** Minecraft mouse button → page button (0 left, 1 middle, 2 right). */
    private static int pageButton(int button) {
        return button == 1 ? 2 : button == 2 ? 1 : button;
    }

    /** The page's name for a key: {windows key code, key, code}, or null for keys pages don't get. */
    private static Object @Nullable [] pageKey(int key, boolean shift) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            char lower = (char) ('a' + key - GLFW.GLFW_KEY_A);
            String upper = String.valueOf(Character.toUpperCase(lower));
            return new Object[] {65 + key - GLFW.GLFW_KEY_A, shift ? upper : String.valueOf(lower), "Key" + upper};
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            int d = key - GLFW.GLFW_KEY_0;
            return new Object[] {48 + d, shift ? String.valueOf(")!@#$%^&*(".charAt(d)) : String.valueOf(d), "Digit" + d};
        }
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) {
            int d = key - GLFW.GLFW_KEY_KP_0;
            return new Object[] {96 + d, String.valueOf(d), "Numpad" + d};
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) {
            int n = key - GLFW.GLFW_KEY_F1 + 1;
            return new Object[] {111 + n, "F" + n, "F" + n};
        }
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> new Object[] {32, " ", "Space"};
            case GLFW.GLFW_KEY_ENTER -> new Object[] {13, "Enter", "Enter"};
            case GLFW.GLFW_KEY_KP_ENTER -> new Object[] {13, "Enter", "NumpadEnter"};
            case GLFW.GLFW_KEY_TAB -> new Object[] {9, "Tab", "Tab"};
            case GLFW.GLFW_KEY_BACKSPACE -> new Object[] {8, "Backspace", "Backspace"};
            case GLFW.GLFW_KEY_ESCAPE -> new Object[] {27, "Escape", "Escape"};
            case GLFW.GLFW_KEY_DELETE -> new Object[] {46, "Delete", "Delete"};
            case GLFW.GLFW_KEY_INSERT -> new Object[] {45, "Insert", "Insert"};
            case GLFW.GLFW_KEY_LEFT -> new Object[] {37, "ArrowLeft", "ArrowLeft"};
            case GLFW.GLFW_KEY_UP -> new Object[] {38, "ArrowUp", "ArrowUp"};
            case GLFW.GLFW_KEY_RIGHT -> new Object[] {39, "ArrowRight", "ArrowRight"};
            case GLFW.GLFW_KEY_DOWN -> new Object[] {40, "ArrowDown", "ArrowDown"};
            case GLFW.GLFW_KEY_HOME -> new Object[] {36, "Home", "Home"};
            case GLFW.GLFW_KEY_END -> new Object[] {35, "End", "End"};
            case GLFW.GLFW_KEY_PAGE_UP -> new Object[] {33, "PageUp", "PageUp"};
            case GLFW.GLFW_KEY_PAGE_DOWN -> new Object[] {34, "PageDown", "PageDown"};
            case GLFW.GLFW_KEY_LEFT_SHIFT -> new Object[] {16, "Shift", "ShiftLeft"};
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> new Object[] {16, "Shift", "ShiftRight"};
            case GLFW.GLFW_KEY_LEFT_CONTROL -> new Object[] {17, "Control", "ControlLeft"};
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> new Object[] {17, "Control", "ControlRight"};
            case GLFW.GLFW_KEY_LEFT_ALT -> new Object[] {18, "Alt", "AltLeft"};
            case GLFW.GLFW_KEY_RIGHT_ALT -> new Object[] {18, "Alt", "AltRight"};
            case GLFW.GLFW_KEY_CAPS_LOCK -> new Object[] {20, "CapsLock", "CapsLock"};
            case GLFW.GLFW_KEY_MINUS -> new Object[] {189, shift ? "_" : "-", "Minus"};
            case GLFW.GLFW_KEY_EQUAL -> new Object[] {187, shift ? "+" : "=", "Equal"};
            case GLFW.GLFW_KEY_LEFT_BRACKET -> new Object[] {219, shift ? "{" : "[", "BracketLeft"};
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> new Object[] {221, shift ? "}" : "]", "BracketRight"};
            case GLFW.GLFW_KEY_BACKSLASH -> new Object[] {220, shift ? "|" : "\\", "Backslash"};
            case GLFW.GLFW_KEY_SEMICOLON -> new Object[] {186, shift ? ":" : ";", "Semicolon"};
            case GLFW.GLFW_KEY_APOSTROPHE -> new Object[] {222, shift ? "\"" : "'", "Quote"};
            case GLFW.GLFW_KEY_COMMA -> new Object[] {188, shift ? "<" : ",", "Comma"};
            case GLFW.GLFW_KEY_PERIOD -> new Object[] {190, shift ? ">" : ".", "Period"};
            case GLFW.GLFW_KEY_SLASH -> new Object[] {191, shift ? "?" : "/", "Slash"};
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> new Object[] {192, shift ? "~" : "`", "Backquote"};
            default -> null;
        };
    }

    /** Page modifier bits: Alt 1, Ctrl 2, Meta 4, Shift 8. */
    private static int pageModifiers(KeyEvent event) {
        int m = event.modifiers();
        return ((m & GLFW.GLFW_MOD_ALT) != 0 ? 1 : 0) | ((m & GLFW.GLFW_MOD_CONTROL) != 0 ? 2 : 0)
                | ((m & GLFW.GLFW_MOD_SUPER) != 0 ? 4 : 0) | ((m & GLFW.GLFW_MOD_SHIFT) != 0 ? 8 : 0);
    }

    // ------------------------------------------------------------------ mouse on the page

    /** The cursor mode set last (GLFW), so it is only changed when needed. */
    private int cursorMode = GLFW.GLFW_CURSOR_NORMAL;
    private double lastMouseX = Double.NaN, lastMouseY = Double.NaN;

    /** Hides the cursor where the page hides it, and holds it while the page has locked it. */
    private void updateCursor(int mouseX, int mouseY) {
        WebBrowser browser = TerminalView.app == TerminalBlockEntity.APP_BROWSER && modal == MODAL_NONE ? WebBrowser.get() : null;
        int mode = GLFW.GLFW_CURSOR_NORMAL;
        if (browser != null && browser.locked()) mode = GLFW.GLFW_CURSOR_DISABLED;
        else if (browser != null && browser.cursorHidden() && pagePoint(mouseX, mouseY) != null) mode = GLFW.GLFW_CURSOR_HIDDEN;
        setCursor(mode);
    }

    private void setCursor(int mode) {
        if (mode == cursorMode) return;
        cursorMode = mode;
        GLFW.glfwSetInputMode(this.minecraft.getWindow().handle(), GLFW.GLFW_CURSOR, mode);
    }

    @Override
    public void removed() {
        setCursor(GLFW.GLFW_CURSOR_NORMAL);
        if (pageIme) {
            pageIme = false;
            this.minecraft.onTextInputFocusChange(this, false);
        }
        ImBlockerBridge.pageFocus(false, 0, 0, 0, 0, 0, 0);
        WebBrowser.hoverOffAll();
        super.removed();
    }

    /** The open browser while the page holds the mouse, else null. */
    private @Nullable WebBrowser lockedPage() {
        if (TerminalView.app != TerminalBlockEntity.APP_BROWSER || modal != MODAL_NONE) return null;
        WebBrowser browser = WebBrowser.get();
        return browser != null && browser.locked() ? browser : null;
    }

    @Override
    public void mouseMoved(double x, double y) {
        double dx = Double.isNaN(lastMouseX) ? 0 : x - lastMouseX, dy = Double.isNaN(lastMouseY) ? 0 : y - lastMouseY;
        lastMouseX = x;
        lastMouseY = y;
        WebBrowser locked = lockedPage();
        if (locked != null) {
            double px = pagePixel() / scale;
            locked.lockedMove(dx * px, dy * px);
            return;
        }
        int[] at = pagePoint(x, y);
        WebBrowser browser = at == null ? null : WebBrowser.get();
        if (browser != null) browser.mouseMove(at[0], at[1]);
        else WebBrowser.hoverOffAll(); // left the page (or the browser window)
        super.mouseMoved(x, y);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressApp >= 0) {
            boolean was = dragging;
            drop();
            if (was) return true;
        }
        WebBrowser locked = lockedPage();
        if (locked != null) {
            locked.mouseButton(locked.mouseX(), locked.mouseY(), pageButton(event.button()), false, 1);
            return true;
        }
        int[] at = pagePoint(event.x(), event.y());
        WebBrowser browser = at == null ? null : WebBrowser.get();
        if (browser != null) browser.mouseButton(at[0], at[1], pageButton(event.button()), false, 1);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (pressApp >= 0) {
            gridMouseX = (event.x() - rx) / scale;
            gridMouseY = (event.y() - ry) / scale;
            if (!dragging && Math.abs(gridMouseY - pressY) > 3) dragging = true;
            if (dragging) {
                // Near the list's ends the list scrolls along.
                int app = pressApp;
                float top = app == TerminalBlockEntity.APP_FILES ? LIST_TOP : PRINTER_TOP - 1;
                int rows = app == TerminalBlockEntity.APP_FILES ? listRows() : printerRows();
                float rowH = app == TerminalBlockEntity.APP_FILES ? ROW : PRINTER_ROW;
                if (gridMouseY < top) TerminalView.scroll = Math.max(0, TerminalView.scroll - 1);
                else if (gridMouseY > top + rows * rowH) TerminalView.scroll++;
                return true;
            }
        }
        if (lockedPage() != null) return true;
        int[] at = pagePoint(event.x(), event.y());
        WebBrowser browser = at == null ? null : WebBrowser.get();
        if (browser != null) {
            // mouseMoved already sent the position.
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (composing && typingIntoPage() != null) return true;
        WebBrowser browser = typingIntoPage();
        Object[] key = browser == null ? null : pageKey(event.key(), event.hasShiftDown());
        if (key != null) {
            browser.key("keyUp", (int) key[0], (String) key[1], (String) key[2], null, pageModifiers(event));
            return true;
        }
        return super.keyReleased(event);
    }

    /** Key presses for the browser; true when used. */
    private boolean browserKey(KeyEvent event) {
        if (addressBox != null && addressBox.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                WebBrowser b = WebBrowser.get();
                if (b != null) b.load(addressBox.getValue());
                addressBox.setValue(TerminalView.browserUrl);
                addressBox.setFocused(false);
                setFocused(null);
                return true;
            }
            return false;
        }
        WebBrowser browser = typingIntoPage();
        if (browser == null) return false;
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_V) {
            browser.type(this.minecraft.keyboardHandler.getClipboard());
            return true;
        }
        // Esc also lets go of a locked mouse (a real browser does that itself).
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && browser.locked()) browser.unlock();
        Object[] key = pageKey(event.key(), event.hasShiftDown());
        if (key != null) {
            // Like a real keyboard: the key goes down, and the text it types comes separately (charTyped).
            browser.key("rawKeyDown", (int) key[0], (String) key[1], (String) key[2], null, pageModifiers(event));
            if ((int) key[0] == 13) browser.key("char", 13, "Enter", (String) key[2], "\r", pageModifiers(event));
        }
        // Every other key belongs to the page too (no focus moving around the terminal).
        return true;
    }

    // ------------------------------------------------------------------ dialogs

    private void dialogBox(TerminalUi.Painter p, float x0, float y0, float x1, float y1, String title) {
        hits.clear();
        p.fill(0, 0, TerminalUi.W, TerminalUi.WINDOW_BOTTOM, 0x60000000);
        p.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF505A66);
        p.fill(x0, y0, x1, y1, TerminalUi.BODY);
        p.fill(x0, y0, x1, y0 + 11, TerminalUi.BAR);
        p.text(this.font.plainSubstrByWidth(title, (int) (x1 - x0 - 8)), x0 + 4, y0 + 1.5F, TerminalUi.TEXT_LIGHT);
    }

    private void printDialog(TerminalUi.Painter p, Font font) {
        PaperNetwork.FileInfo file = fileInfo(modalFile);
        if (file == null) {
            modal = MODAL_NONE;
            return;
        }
        float x0 = TerminalUi.W / 2.0F - 100, y0 = Math.max(2, TerminalUi.WINDOW_BOTTOM / 2.0F - 56), x1 = x0 + 200, y1 = y0 + 112;
        dialogBox(p, x0, y0, x1, y1, I18n.get("terminal.enplotify.print_title", shownName(file.name())));
        List<PaperNetwork.PrinterInfo> printers = TerminalView.printers;
        float listTop = y0 + 14;
        if (printers.isEmpty()) {
            p.text(I18n.get("terminal.enplotify.no_printers"), x0 + 6, listTop + 4, TerminalUi.TEXT_DIM);
        }
        printScroll = Math.clamp(printScroll, 0, Math.max(0, printers.size() - PRINT_ROWS));
        for (int i = 0; i < PRINT_ROWS && i + printScroll < printers.size(); i++) {
            PaperNetwork.PrinterInfo info = printers.get(i + printScroll);
            float y = listTop + i * ROW;
            boolean sel = info.pos().equals(printTo);
            if (sel) p.fill(x0 + 2, y, x1 - 2, y + ROW, TerminalUi.SELECTED);
            else if (hover(x0 + 2, y, x1 - 2, y + ROW)) p.fill(x0 + 2, y, x1 - 2, y + ROW, 0xFFE8ECF0);
            String status = printerStatus(info);
            int statusW = font.width(status);
            p.text(font.plainSubstrByWidth(printerName(info), (int) (x1 - x0 - 16 - statusW)), x0 + 5, y + 1, TerminalUi.TEXT);
            p.text(status, x1 - 5 - statusW, y + 1, TerminalUi.TEXT_DIM);
            BlockPos at = info.pos();
            hits.add(new Hit(x0 + 2, y, x1 - 2, y + ROW, () -> printTo = at));
        }
        float cy = listTop + PRINT_ROWS * ROW + 6;
        p.text(I18n.get("terminal.enplotify.copies"), x0 + 6, cy + 1.5F, TerminalUi.TEXT);
        float cx = x0 + 10 + font.width(I18n.get("terminal.enplotify.copies"));
        TerminalUi.button(p, "-", cx, cy, 12, hover(cx, cy, cx + 12, cy + 11), copies > 1);
        hits.add(new Hit(cx, cy, cx + 12, cy + 11, () -> copies = Math.max(1, copies - 1)));
        String n = Integer.toString(copies);
        p.text(n, cx + 20 - font.width(n) / 2.0F, cy + 1.5F, TerminalUi.TEXT);
        TerminalUi.button(p, "+", cx + 28, cy, 12, hover(cx + 28, cy, cx + 40, cy + 11), copies < TerminalServer.MAX_COPIES);
        hits.add(new Hit(cx + 28, cy, cx + 40, cy + 11, () -> copies = Math.min(TerminalServer.MAX_COPIES, copies + 1)));
        // Warn when the chosen printer has the wrong paper (it still queues).
        PaperNetwork.PrinterInfo chosen = printerInfo(printTo);
        if (chosen != null && chosen.paperKind() != file.kind()) {
            String warn = I18n.get("terminal.enplotify.need_paper", TerminalUi.kindName(file.kind()));
            p.text(font.plainSubstrByWidth(warn, 120), cx + 46, cy + 1.5F, 0xFFC0502A);
        }
        float by = y1 - 14;
        float bx = button(p, "terminal.enplotify.cancel", x1 - 4 - buttonWidth("terminal.enplotify.cancel"), by, true,
                () -> modal = MODAL_NONE);
        String go = "terminal.enplotify.print";
        button(p, go, x1 - 4 - buttonWidth("terminal.enplotify.cancel") - 3 - buttonWidth(go), by, chosen != null, () -> {
            TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_PRINT, modalFile, copies, printTo, ""));
            modal = MODAL_NONE;
        });
    }

    private float buttonWidth(String key) {
        return this.font.width(I18n.get(key)) + 8;
    }

    private void deleteDialog(TerminalUi.Painter p, Font font) {
        PaperNetwork.FileInfo file = fileInfo(modalFile);
        if (file == null) {
            modal = MODAL_NONE;
            return;
        }
        float x0 = TerminalUi.W / 2.0F - 75, y0 = TerminalUi.WINDOW_BOTTOM / 2.0F - 28, x1 = x0 + 150, y1 = y0 + 56;
        dialogBox(p, x0, y0, x1, y1, I18n.get("terminal.enplotify.delete"));
        String q = font.plainSubstrByWidth(I18n.get("terminal.enplotify.delete_question", shownName(file.name())), (int) (x1 - x0 - 10));
        p.text(q, (x0 + x1 - font.width(q)) / 2.0F, y0 + 20, TerminalUi.TEXT);
        float by = y1 - 15;
        float w1 = buttonWidth("terminal.enplotify.delete"), w2 = buttonWidth("terminal.enplotify.cancel");
        float bx = (x0 + x1 - w1 - w2 - 4) / 2.0F;
        button(p, "terminal.enplotify.delete", bx, by, true, () -> {
            TerminalView.send(TerminalServer.OP_DELETE, modalFile);
            if (TerminalView.selected == modalFile) TerminalView.selected = -1;
            modal = MODAL_NONE;
        });
        button(p, "terminal.enplotify.cancel", bx + w1 + 4, by, true, () -> modal = MODAL_NONE);
    }

    private void renameDialog(TerminalUi.Painter p) {
        if (fileInfo(modalFile) == null) {
            closeNameBox();
            modal = MODAL_NONE;
            return;
        }
        float x0 = renameX0(), y0 = renameY0(), x1 = x0 + 160, y1 = y0 + 62;
        dialogBox(p, x0, y0, x1, y1, I18n.get("terminal.enplotify.rename"));
        float by = y1 - 15;
        float w1 = buttonWidth("terminal.enplotify.ok"), w2 = buttonWidth("terminal.enplotify.cancel");
        float bx = (x0 + x1 - w1 - w2 - 4) / 2.0F;
        button(p, "terminal.enplotify.ok", bx, by, true, this::confirmRename);
        button(p, "terminal.enplotify.cancel", bx + w1 + 4, by, true, () -> {
            closeNameBox();
            modal = MODAL_NONE;
        });
    }

    private float renameX0() {
        return TerminalUi.W / 2.0F - 80;
    }

    private float renameY0() {
        return TerminalUi.WINDOW_BOTTOM / 2.0F - 31;
    }

    private void openNameBox(@Nullable String text) {
        closeNameBox();
        useGrid();
        PaperNetwork.FileInfo file = fileInfo(modalFile);
        float gx = rx + (renameX0() + 6) * scale, gy = ry + (renameY0() + 18) * scale;
        int w = Math.round(148 * scale), h = Math.max(12, Math.round(14 * scale));
        nameBox = new EditBox(this.font, Math.round(gx), Math.round(gy), w, h, Component.empty());
        nameBox.setMaxLength(TerminalFile.MAX_NAME);
        nameBox.setValue(text != null ? text : file != null ? shownName(file.name()) : "");
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
    }

    private void closeNameBox() {
        if (nameBox != null) removeWidget(nameBox);
        nameBox = null;
    }

    private void confirmRename() {
        if (nameBox != null && !nameBox.getValue().isBlank()) {
            TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_RENAME, modalFile, 0,
                    BlockPos.ZERO, nameBox.getValue().strip()));
        }
        closeNameBox();
        modal = MODAL_NONE;
    }

    /** The status line's top: in the middle of the taskbar's height. */
    private static float statusY() {
        return TerminalUi.WINDOW_BOTTOM + (TerminalUi.H - TerminalUi.WINDOW_BOTTOM - 8) / 2.0F;
    }

    private void status(TerminalUi.Painter p, Font font) {
        if (TerminalView.message.isEmpty() || System.currentTimeMillis() - TerminalView.messageTime > 4000) {
            String hint = I18n.get("terminal.enplotify.exit_hint");
            p.text(hint, 14, statusY(), 0xFF8C98A6);
            return;
        }
        String text = font.plainSubstrByWidth(I18n.get(TerminalView.message), TerminalUi.W - 60);
        p.text(text, 14, statusY(), 0xFFFFE38A);
    }

    // ------------------------------------------------------------------ actions

    private void newFile(int kind) {
        String name = TerminalUi.kindName(kind) + " " + (TerminalView.files.size() + 1);
        TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_NEW, kind, 0, BlockPos.ZERO, name));
    }

    private void importHeld() {
        String name = this.minecraft.player == null ? "" : this.minecraft.player.getMainHandItem().getHoverName().getString();
        TerminalView.send(new PaperNetwork.TerminalOp(TerminalView.pos(), TerminalServer.OP_IMPORT, 0, 0, BlockPos.ZERO, name));
    }

    private void openSelected() {
        if (TerminalView.selected >= 0) TerminalView.send(TerminalServer.OP_OPEN_FILE, TerminalView.selected);
    }

    private void openPrint() {
        if (TerminalView.selected < 0) return;
        modal = MODAL_PRINT;
        modalFile = TerminalView.selected;
        copies = 1;
        printScroll = 0;
        // The printer chosen in the printers window, else the first in its order.
        printTo = null;
        for (PaperNetwork.PrinterInfo info : TerminalView.printers) if (info.chosen()) printTo = info.pos();
        if (printTo == null && !TerminalView.printers.isEmpty()) printTo = TerminalView.printers.getFirst().pos();
        TerminalView.send(TerminalServer.OP_REFRESH, 0);
    }

    private static PaperNetwork.@Nullable FileInfo fileInfo(int id) {
        for (PaperNetwork.FileInfo f : TerminalView.files) if (f.id() == id) return f;
        return null;
    }

    private static PaperNetwork.@Nullable PrinterInfo printerInfo(@Nullable BlockPos at) {
        if (at == null) return null;
        for (PaperNetwork.PrinterInfo p : TerminalView.printers) if (p.pos().equals(at)) return p;
        return null;
    }

    static String printerName(PaperNetwork.PrinterInfo info) {
        return info.name().isEmpty()
                ? defaultName(info.kindKey(), info.pos())
                : info.name();
    }

    static String printerStatus(PaperNetwork.PrinterInfo info) {
        String s = info.busy() ? I18n.get("terminal.enplotify.printer_busy")
                : info.paper() == 0 ? I18n.get("terminal.enplotify.printer_no_paper")
                : I18n.get("terminal.enplotify.printer_paper", info.paper(), TerminalUi.kindName(info.paperKind()));
        if (info.queued() > 0) s += " · " + I18n.get("terminal.enplotify.printer_queued", info.queued());
        return s;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        useGrid();
        WebBrowser locked = lockedPage();
        if (locked != null) {
            locked.mouseButton(locked.mouseX(), locked.mouseY(), pageButton(event.button()), true, doubleClick ? 2 : 1);
            return true;
        }
        int[] at = pagePoint(event.x(), event.y());
        WebBrowser page = at == null ? null : WebBrowser.get();
        if (page != null) {
            // Clicking the page takes the keyboard away from the address box.
            if (addressBox != null) addressBox.setFocused(false);
            if (searchBox != null) searchBox.setFocused(false);
            setFocused(null);
            lastPageClick = new int[] {(int) ((event.x() - rx) / scale), (int) ((event.y() - ry) / scale)};
            page.mouseButton(at[0], at[1], pageButton(event.button()), true, doubleClick ? 2 : 1);
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        if (!TerminalView.arrived() || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        double gx = (event.x() - rx) / scale, gy = (event.y() - ry) / scale;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (gx >= hit.x0() && gx < hit.x1() && gy >= hit.y0() && gy < hit.y1()) {
                hit.action().run();
                // Double click on a file opens it.
                if (doubleClick && modal == MODAL_NONE && TerminalView.app == TerminalBlockEntity.APP_FILES
                        && gy >= LIST_TOP && gy < LIST_TOP + listRows() * ROW) {
                    openSelected();
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        WebBrowser locked = lockedPage();
        if (locked != null) {
            locked.wheel(locked.mouseX(), locked.mouseY(), scrollY);
            return true;
        }
        int[] at = pagePoint(x, y);
        WebBrowser page = at == null ? null : WebBrowser.get();
        if (page != null) {
            page.wheel(at[0], at[1], scrollY);
            return true;
        }
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (modal == MODAL_PRINT) printScroll += step;
        else TerminalView.scroll += step;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        useGrid();
        if (event.key() == GLFW.GLFW_KEY_Q && event.hasAltDown()) {
            TerminalView.exit();
            return true;
        }
        if (TerminalView.app == TerminalBlockEntity.APP_BROWSER && modal == MODAL_NONE && event.hasControlDown()) {
            if (event.key() == GLFW.GLFW_KEY_W) {
                closeBrowserTab(WebBrowser.activeIndex());
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_T) {
                openNewTab();
                return true;
            }
        }
        if (event.isEscape() && (event.modifiers() & GLFW.GLFW_MOD_SUPER) != 0) {
            if (modal != MODAL_NONE) {
                closeNameBox();
                modal = MODAL_NONE;
            } else if (TerminalView.app != TerminalBlockEntity.APP_DESKTOP) {
                TerminalView.setApp(TerminalBlockEntity.APP_DESKTOP);
            }
            return true;
        }
        // While the input method is composing, its keys are its own.
        if (composing && typingIntoPage() != null) return true;
        if (TerminalView.app == TerminalBlockEntity.APP_BROWSER && modal == MODAL_NONE && browserKey(event)) return true;
        // The Windows key itself, and plain Esc outside the browser: nothing.
        if (event.isEscape() || event.key() == GLFW.GLFW_KEY_LEFT_SUPER || event.key() == GLFW.GLFW_KEY_RIGHT_SUPER) return true;
        if (modal == MODAL_RENAME && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            confirmRename();
            return true;
        }
        if (modal == MODAL_NONE && TerminalView.app == TerminalBlockEntity.APP_FILES && TerminalView.selected >= 0
                && (searchBox == null || !searchBox.isFocused())) {
            if (event.key() == GLFW.GLFW_KEY_DELETE) {
                modal = MODAL_DELETE;
                modalFile = TerminalView.selected;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                openSelected();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (pageIme && ImBlockerBridge.probe(event.codepoint())) return true; // IMBlocker looking for the text box
        WebBrowser browser = typingIntoPage();
        if (browser != null) {
            String text = event.codepointAsString();
            if (event.codepoint() < 128 && !composing) browser.key("char", event.codepoint(), text, "", text, 0);
            else browser.type(text); // also ends a composition: the input method's text goes in
            composing = false;
            return true;
        }
        return super.charTyped(event);
    }

    /**
     * Turns the system's input method on while the page has the keyboard (Minecraft only turns it
     * on for its own text boxes), so Chinese and other input methods work on pages too.
     */
    private void updatePageIme() {
        WebBrowser browser = typingIntoPage();
        boolean want = browser != null && !browser.locked() && TerminalView.arrived() && getFocused() == null;
        if (want != pageIme) {
            pageIme = want;
            composing = false;
            // When a text box took the keyboard it has turned the input method on for itself.
            if (want || getFocused() == null) this.minecraft.onTextInputFocusChange(this, want);
        }
        if (want) {
            // The candidate list shows near where the player last clicked on the page.
            int[] at = lastPageClick;
            float gx = at == null ? 8 : at[0], gy = at == null ? PAGE_TOP + 8 : at[1];
            int x = Math.round(rx + gx * scale), y = Math.round(ry + gy * scale);
            this.minecraft.textInputManager().setTextInputArea(x, y, x + 1, y + 10);
        }
        // IMBlocker (if installed) blocks the input method unless told a text box has the keyboard.
        ImBlockerBridge.pageFocus(want, Math.round(rx), Math.round(ry + PAGE_TOP * scale), Math.round(rw),
                Math.round((TerminalUi.WINDOW_BOTTOM - PAGE_TOP) * scale),
                lastPageClick == null ? Math.round(rx + 8 * scale) : Math.round(rx + lastPageClick[0] * scale),
                lastPageClick == null ? Math.round(ry + (PAGE_TOP + 8) * scale) : Math.round(ry + lastPageClick[1] * scale));
    }

    private int @Nullable [] lastPageClick;

    @Override
    public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent event) {
        WebBrowser browser = pageIme ? typingIntoPage() : null;
        if (browser != null) {
            String text = event == null ? "" : event.fullText();
            composing = !text.isEmpty();
            browser.compose(text, event == null ? 0 : event.caretPosition());
            return true;
        }
        return super.preeditUpdated(event);
    }

    /** A file's name as shown: a language key (a mod's ready-made file) in the player's language. */
    static String shownName(String name) {
        return !name.isEmpty() && I18n.exists(name) ? I18n.get(name) : name;
    }
}
