package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity;
import io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;

/**
 * What a terminal screen looks like. In the world it is drawn on a fixed 240 x 150 grid (the
 * screen's 16:10; the taskbar takes the bottom, so what is above it is 16:9); while a player uses it, on a grid of the screen's own size in GUI pixels, so
 * text is exactly as large as the rest of Minecraft's interface. {@link #size} picks the grid.
 */
final class TerminalUi {
    /** The grid in the world. */
    static final int GRID_W = 240, GRID_H = 150;
    static final int TITLE = 12;
    /** The taskbar is never lower than this. */
    static final int MIN_TASKBAR = 12;
    /** The grid being drawn on now (render thread only). */
    static int W = GRID_W, H = GRID_H, TASKBAR = taskbar(GRID_W, GRID_H), WINDOW_BOTTOM = GRID_H - TASKBAR;

    static void size(int w, int h) {
        W = w;
        H = h;
        TASKBAR = taskbar(w, h);
        WINDOW_BOTTOM = h - TASKBAR;
    }

    /** The taskbar's height on a {@code w x h} grid: what is left under a 16:9 area (web pages fit it). */
    static int taskbar(int w, int h) {
        return Math.max(MIN_TASKBAR, h - Math.round(w * 9.0F / 16.0F));
    }

    static final int TEXT = 0xFF20242A, TEXT_LIGHT = 0xFFFFFFFF, TEXT_DIM = 0xFF70767E;
    static final int BODY = 0xFFF4F4F2, BAR = 0xFF2A3440, SELECTED = 0xFFCFE0F5, BUTTON = 0xFFDDE1E6, BUTTON_HOVER = 0xFFC6CCD4;

    /** Where something is drawn; coordinates on the grid, y down. */
    interface Painter {
        void fill(float x0, float y0, float x1, float y1, int argb);

        void text(String text, float x, float y, int argb);

        /** A picture stretched over x0..x1, y0..y1. */
        default void image(net.minecraft.resources.Identifier texture, float x0, float y0, float x1, float y1) {}
    }

    static Font font() {
        return Minecraft.getInstance().font;
    }

    /** Desktop icons: app, x, y (icon 16 x 16, label under it). The browser only with FerricOxide. */
    private static final int[][] ICONS = {{TerminalBlockEntity.APP_FILES, 10, 8}, {TerminalBlockEntity.APP_PRINTERS, 10, 44}};
    private static final int[][] ICONS_WITH_BROWSER = {{TerminalBlockEntity.APP_FILES, 10, 8},
            {TerminalBlockEntity.APP_PRINTERS, 10, 44}, {TerminalBlockEntity.APP_BROWSER, 10, 80}};

    static int[][] icons() {
        return WebBrowser.available() ? ICONS_WITH_BROWSER : ICONS;
    }

    static String appName(int app) {
        return I18n.get(switch (app) {
            case TerminalBlockEntity.APP_FILES -> "terminal.enplotify.app.files";
            case TerminalBlockEntity.APP_PRINTERS -> "terminal.enplotify.app.printers";
            case TerminalBlockEntity.APP_BROWSER -> "terminal.enplotify.app.browser";
            default -> "terminal.enplotify.app.editing";
        });
    }

    /** The window's name as others see it: desktop, files, printers, browser or editor. */
    static String windowName(int app) {
        return switch (app) {
            case TerminalBlockEntity.APP_DESKTOP -> I18n.get("terminal.enplotify.app.desktop");
            case TerminalBlockEntity.APP_EDITING -> I18n.get("terminal.enplotify.app.editor");
            default -> appName(app);
        };
    }

    /** The built-in start-up logo: "EnPlotify OS", slanted; En pink, Plot light blue, ify grey. */
    static net.minecraft.network.chat.Component builtInLogo() {
        net.minecraft.network.chat.Style italic = net.minecraft.network.chat.Style.EMPTY.withItalic(true);
        return net.minecraft.network.chat.Component.empty()
                .append(net.minecraft.network.chat.Component.literal("En").withStyle(italic.withColor(0xFF8CC8)))
                .append(net.minecraft.network.chat.Component.literal("Plot").withStyle(italic.withColor(0x9AD8FF)))
                .append(net.minecraft.network.chat.Component.literal("ify").withStyle(italic.withColor(0xA6AAB0)))
                .append(net.minecraft.network.chat.Component.literal(" OS").withStyle(italic.withColor(0xFFFFFF)));
    }

    /** The "loading" sign: eight dots in a ring, fading round (still). */
    static void loading(Painter p, float cx, float cy, float r, float dot) {
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 - Math.PI / 2;
            float x = cx + (float) (Math.cos(a) * r), y = cy + (float) (Math.sin(a) * r);
            int shade = 0x50 + i * 0x16;
            int argb = 0xFF000000 | shade << 16 | shade << 8 | shade;
            p.fill(x - dot / 2, y - dot / 2, x + dot / 2, y + dot / 2, argb);
        }
    }

    /** The very dark grey behind the loading sign. */
    static final int LOADING_BACKGROUND = 0xFF15171A;

    static String clock() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return "";
        long time = minecraft.level.getOverworldClockTime() % 24000L;
        int hours = (int) ((time / 1000 + 6) % 24);
        int minutes = (int) (time % 1000 * 60 / 1000);
        // Day 1 is the first; a day turns over at 6:00 like the game's own day count.
        long day = minecraft.level.getOverworldClockTime() / 24000L + 1;
        // Night from 19:00 to 5:00 (when monsters can spawn in the open); it keeps the day's number.
        boolean night = time >= 13000 && time < 23000;
        return I18n.get(night ? "terminal.enplotify.clock_night" : "terminal.enplotify.clock", day,
                String.format("%02d:%02d", hours, minutes));
    }

    // ------------------------------------------------------------------ desktop

    static void desktop(Painter p, String user) {
        desktop(p, user, -1, null, TEXT_LIGHT);
    }

    /** The desktop; the icon of {@code hovered} (an app, or -1) is lit. */
    static void desktop(Painter p, String user, int hovered, net.minecraft.resources.@org.jspecify.annotations.Nullable Identifier wallpaper,
                        int iconText) {
        if (wallpaper != null) {
            // The terminal's own wallpaper (see TerminalTheme).
            p.image(wallpaper, 0, 0, W, WINDOW_BOTTOM);
        } else {
            // Wallpaper: a soft vertical gradient in bands.
            int bands = 12;
            for (int i = 0; i < bands; i++) {
                float y0 = WINDOW_BOTTOM * i / (float) bands, y1 = WINDOW_BOTTOM * (i + 1) / (float) bands;
                p.fill(0, y0, W, y1, mix(0xFF3A6EA5, 0xFF16324F, i / (float) (bands - 1)));
            }
        }
        Font font = font();
        for (int[] icon : icons()) {
            int x = icon[1], y = icon[2];
            if (icon[0] == hovered) {
                // A light glow behind light names, a dark one behind dark names (light wallpaper).
                int lum = ((iconText >> 16 & 0xFF) * 3 + (iconText >> 8 & 0xFF) * 6 + (iconText & 0xFF)) / 10;
                p.fill(x - 6, y - 2, x + 22, y + 30, lum < 128 ? 0x30000000 : 0x40FFFFFF);
            }
            drawIcon(p, icon[0], x, y);
            String name = appName(icon[0]);
            p.text(name, x + 8 - font.width(name) / 2.0F, y + 19, iconText);
        }
        taskbar(p, user);
    }

    static void taskbar(Painter p, String user) {
        Font font = font();
        p.fill(0, WINDOW_BOTTOM, W, H, 0xFF101820);
        // A start mark: four small squares, in the middle of the taskbar's height.
        float m = WINDOW_BOTTOM + (H - WINDOW_BOTTOM - MIN_TASKBAR) / 2.0F;
        p.fill(3, m + 3, 6, m + 6, 0xFF5BA4E6);
        p.fill(7, m + 3, 10, m + 6, 0xFF5BA4E6);
        p.fill(3, m + 7, 6, m + 10, 0xFF5BA4E6);
        p.fill(7, m + 7, 10, m + 10, 0xFF5BA4E6);
        if (!user.isEmpty()) textMiddle(p, I18n.get("terminal.enplotify.in_use", user), 14, WINDOW_BOTTOM, H, 0xFFB8C4D0);
        String clock = clock();
        textMiddle(p, clock, W - 3 - font.width(clock), WINDOW_BOTTOM, H, TEXT_LIGHT);
    }

    static void drawIcon(Painter p, int app, float x, float y) {
        if (app == TerminalBlockEntity.APP_BROWSER) {
            p.fill(x + 4, y + 1, x + 12, y + 15, 0xFF3C8DDE);      // a globe
            p.fill(x + 1, y + 4, x + 15, y + 12, 0xFF3C8DDE);
            p.fill(x + 2, y + 2, x + 14, y + 14, 0xFF3C8DDE);
            p.fill(x + 7, y + 1, x + 9, y + 15, 0xFFBFE0FF);
            p.fill(x + 1, y + 7, x + 15, y + 9, 0xFFBFE0FF);
            p.fill(x + 4, y + 4, x + 6, y + 6, 0xFF8EE08E);
            p.fill(x + 10, y + 10, x + 13, y + 12, 0xFF8EE08E);
        } else if (app == TerminalBlockEntity.APP_FILES) {
            p.fill(x + 1, y + 2, x + 7, y + 4, 0xFFD9A93A);        // tab
            p.fill(x + 1, y + 4, x + 15, y + 15, 0xFFE8C04A);      // folder
            p.fill(x + 1, y + 7, x + 15, y + 8, 0xFFD9A93A);
        } else {
            // Devices: a display with a small printer in front.
            p.fill(x + 1, y + 1, x + 13, y + 10, 0xFF3A4048);
            p.fill(x + 2, y + 2, x + 12, y + 9, 0xFF5BA4E6);
            p.fill(x + 6, y + 10, x + 8, y + 12, 0xFF3A4048);
            p.fill(x + 8, y + 9, x + 12, y + 11, 0xFFF8F8F8);
            p.fill(x + 6, y + 11, x + 16, y + 15, 0xFFB8BCC4);
            p.fill(x + 13, y + 12, x + 14, y + 13, 0xFF6BE06B);
        }
    }

    static void printerIcon(Painter p, float x, float y) {
        p.fill(x + 4, y + 1, x + 12, y + 6, 0xFFF8F8F8);       // paper in
        p.fill(x + 1, y + 6, x + 15, y + 12, 0xFFB8BCC4);      // body
        p.fill(x + 3, y + 12, x + 13, y + 15, 0xFF8E939B);     // tray
        p.fill(x + 12, y + 8, x + 14, y + 9, 0xFF6BE06B);      // light
    }

    static void displayIcon(Painter p, float x, float y) {
        p.fill(x + 1, y + 2, x + 15, y + 12, 0xFF3A4048);      // frame
        p.fill(x + 2, y + 3, x + 14, y + 11, 0xFF5BA4E6);      // screen
        p.fill(x + 3, y + 4, x + 7, y + 6, 0xFF8EC6F2);
        p.fill(x + 7, y + 12, x + 9, y + 14, 0xFF3A4048);      // stand
        p.fill(x + 4, y + 14, x + 12, y + 15, 0xFF3A4048);
    }

    /** Icon under a point on the grid, or -1. */
    static int iconAt(double mx, double my) {
        for (int[] icon : icons()) {
            if (mx >= icon[1] - 6 && mx < icon[1] + 22 && my >= icon[2] && my < icon[2] + 30) return icon[0];
        }
        return -1;
    }

    // ------------------------------------------------------------------ windows

    /** A window filling the desktop, with its title and a close cross. */
    /** Text with its letters' middle at the middle of y0..y1. */
    static void textMiddle(Painter p, String text, float x, float y0, float y1, int argb) {
        // Letters stand on row 7 (their middle is 3.5 down); Chinese characters fill rows 0..8.
        boolean wide = false;
        for (int i = 0; i < text.length() && !wide; i++) wide = text.charAt(i) >= 0x2E80;
        p.text(text, x, (y0 + y1) / 2.0F - (wide ? 4.0F : 3.5F), argb);
    }

    /** An "x" drawn as two strokes of small squares around (cx, cy): centred exactly, whatever the font. */
    static void cross(Painter p, float cx, float cy, float r, int argb) {
        int steps = 5;
        float d = 2.0F * r / (steps - 1), size = Math.max(0.9F, d * 1.1F);
        for (int i = 0; i < steps; i++) {
            float o = -r + i * d;
            p.fill(cx + o - size / 2, cy + o - size / 2, cx + o + size / 2, cy + o + size / 2, argb);
            p.fill(cx + o - size / 2, cy - o - size / 2, cx + o + size / 2, cy - o + size / 2, argb);
        }
    }

    /** A "+" drawn as two bars around (cx, cy). */
    static void plus(Painter p, float cx, float cy, float r, int argb) {
        p.fill(cx - r, cy - 0.5F, cx + r, cy + 0.5F, argb);
        p.fill(cx - 0.5F, cy - r, cx + 0.5F, cy + r, argb);
    }

    static void window(Painter p, String title) {
        p.fill(0, 0, W, TITLE, BAR);
        textMiddle(p, title, 4, 0, TITLE, TEXT_LIGHT);
        p.fill(W - TITLE, 0, W, TITLE, 0xFFC84A3A);
        cross(p, W - TITLE / 2.0F, TITLE / 2.0F, 2.5F, TEXT_LIGHT);
        p.fill(0, TITLE, W, WINDOW_BOTTOM, BODY);
    }

    static boolean overClose(double mx, double my) {
        return mx >= W - TITLE && mx < W && my >= 0 && my < TITLE;
    }

    /** A placeholder look of the open program (what others see in the world). */
    static void windowSketch(Painter p, int app) {
        window(p, appName(app));
        if (app == TerminalBlockEntity.APP_BROWSER) {
            p.fill(4, 14, 236, 24, 0xFFFFFFFF);
            p.fill(0, 26, TerminalUi.W, WINDOW_BOTTOM, 0xFFFFFFFF);
            p.fill(10, 32, 90, 40, 0xFF3C8DDE);
            for (int i = 0; i < 6; i++) p.fill(10, 48 + i * 12, 200 - (i % 3) * 40, 52 + i * 12, 0xFFC4C8CE);
        } else if (app == TerminalBlockEntity.APP_EDITING) {
            p.fill(70, 18, 170, 128, 0xFFFFFFFF);
            for (int i = 0; i < 8; i++) p.fill(78, 26 + i * 11, 162 - (i % 3) * 18, 29 + i * 11, 0xFFB8BCC4);
        } else {
            for (int i = 0; i < 7; i++) {
                p.fill(6, 18 + i * 14, 14, 26 + i * 14, i % 2 == 0 ? 0xFF9AB8D8 : 0xFFE0C070);
                p.fill(18, 20 + i * 14, 120 - (i % 3) * 20, 24 + i * 14, 0xFFC4C8CE);
            }
        }
    }

    static void button(Painter p, String label, float x, float y, float w, boolean hover, boolean enabled) {
        p.fill(x, y, x + w, y + 11, !enabled ? 0xFFEBEDEF : hover ? BUTTON_HOVER : BUTTON);
        Font font = font();
        textMiddle(p, label, x + (w - font.width(label)) / 2.0F, y, y + 11, enabled ? TEXT : 0xFFA8ACB2);
    }

    static int kindColor(int kind) {
        return switch (kind) {
            case PrinterBlockEntity.NEWSPAPER -> 0xFF9E9B95;
            case PrinterBlockEntity.POSTER -> 0xFFE08A5A;
            default -> 0xFF8FB4DC;
        };
    }

    static String kindName(int kind) {
        return I18n.get(switch (kind) {
            case PrinterBlockEntity.NEWSPAPER -> "terminal.enplotify.kind.newspaper";
            case PrinterBlockEntity.POSTER -> "terminal.enplotify.kind.poster";
            default -> "terminal.enplotify.kind.document";
        });
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private TerminalUi() {}
}
