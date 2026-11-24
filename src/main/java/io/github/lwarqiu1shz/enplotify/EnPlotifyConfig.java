package io.github.lwarqiu1shz.enplotify;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player client preferences (config/enplotify-client.toml). */
public final class EnPlotifyConfig {
    public static final double TEXT_SIZE_MIN = 6.0;
    public static final double TEXT_SIZE_MAX = 96.0;
    public static final double TEXT_SIZE_DEFAULT = 12.0;
    /** Vanilla Minecraft text counts as this size; size 24 = three times vanilla. */
    public static final double VANILLA_TEXT_SIZE = 8.0;

    public static final double TEXT_SPEED_OFF = -1.0;
    public static final double TEXT_SPEED_MIN = 0.1;
    public static final double TEXT_SPEED_MAX = 10.0;
    public static final double TEXT_SPEED_DEFAULT = 1.0;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue TEXT_SIZE;
    public static final ModConfigSpec.DoubleValue TEXT_SPEED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        TEXT_SIZE = builder
                .comment("Text size of story mode. Vanilla Minecraft text is size 8.",
                        "Any multiple of 0.5 from 6 to 96. Too large a size is shrunk automatically so a page still fits on screen.")
                .translation("config.enplotify.text_size")
                .defineInRange("text_size", TEXT_SIZE_DEFAULT, TEXT_SIZE_MIN, TEXT_SIZE_MAX);
        TEXT_SPEED = builder
                .comment("Typing speed multiplier of story mode (1 = the default speed).",
                        "Any multiple of 0.1 from 0.1 to 10, or -1 to show each page at once without the typing animation.")
                .translation("config.enplotify.text_speed")
                .defineInRange("text_speed", TEXT_SPEED_DEFAULT, TEXT_SPEED_OFF, TEXT_SPEED_MAX);
        builder.translation("config.enplotify.terminal").push("terminal");
        TERMINAL_DISPLAY = builder
                .comment("How other players' terminal screens look: CURRENT_WINDOW = black with the name of the open window,",
                        "SCREENSHOT = a picture of what they see.")
                .translation("config.enplotify.terminal.display")
                .defineEnum("display", TerminalDisplay.SCREENSHOT);
        TERMINAL_FPS = builder
                .comment("SCREENSHOT only: new pictures per second (0 to 60, decimals allowed). 0 = only when the window changes.")
                .translation("config.enplotify.terminal.fps")
                .defineInRange("screenshot_fps", 5.0, 0.0, 60.0);
        TERMINAL_RESOLUTION = builder
                .comment("SCREENSHOT only: how sharp other players' screens (and displays) look: the picture size you ask for (16:10).",
                        "The player at the terminal sends the largest size anyone near asks for. Larger pictures use more network.")
                .translation("config.enplotify.terminal.resolution")
                .defineEnum("screenshot_resolution", ScreenshotResolution.R320X200);
        TERMINAL_UI_SCALE = builder
                .comment("Size of the terminal system while you use it, 0.1 to 10 (1 = as large as Minecraft's own interface).")
                .translation("config.enplotify.terminal.ui_scale")
                .defineInRange("ui_scale", 1.0, 0.1, 10.0);
        builder.pop();
        SPEC = builder.build();
    }

    // ------------------------------------------------------------------ terminal screens

    public static final double TERMINAL_FPS_DEFAULT = 5.0;
    public static final ModConfigSpec.EnumValue<TerminalDisplay> TERMINAL_DISPLAY;
    public static final ModConfigSpec.DoubleValue TERMINAL_FPS;
    public static final ModConfigSpec.DoubleValue TERMINAL_UI_SCALE;
    public static final ModConfigSpec.EnumValue<ScreenshotResolution> TERMINAL_RESOLUTION;

    /** Picture sizes for terminal screens, all 16:10. */
    public enum ScreenshotResolution implements net.neoforged.neoforge.common.TranslatableEnum {
        R320X200(320), R480X300(480), R640X400(640), R800X500(800), R960X600(960), R1280X800(1280),
        R1440X900(1440), R1680X1050(1680), R1920X1200(1920), R2560X1600(2560);

        public final int width, height;

        ScreenshotResolution(int width) {
            this.width = width;
            this.height = width * 10 / 16;
        }

        public static ScreenshotResolution byIndex(int index) {
            ScreenshotResolution[] all = values();
            return all[Math.clamp(index, 0, all.length - 1)];
        }

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.literal(width + "×" + height);
        }
    }

    public static ScreenshotResolution terminalResolution() {
        try {
            return TERMINAL_RESOLUTION.get();
        } catch (IllegalStateException e) {
            return ScreenshotResolution.R320X200;
        }
    }

    /** How other players' terminal screens are drawn. */
    public enum TerminalDisplay implements net.neoforged.neoforge.common.TranslatableEnum {
        CURRENT_WINDOW, SCREENSHOT;

        @Override
        public net.minecraft.network.chat.Component getTranslatedName() {
            return net.minecraft.network.chat.Component.translatable("config.enplotify.terminal.display."
                    + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static TerminalDisplay terminalDisplay() {
        try {
            return TERMINAL_DISPLAY.get();
        } catch (IllegalStateException e) {
            return TerminalDisplay.SCREENSHOT;
        }
    }

    public static double terminalFps() {
        try {
            return Math.max(0.0, Math.min(60.0, TERMINAL_FPS.get()));
        } catch (IllegalStateException e) {
            return TERMINAL_FPS_DEFAULT;
        }
    }

    public static double terminalUiScale() {
        try {
            return Math.max(0.1, Math.min(10.0, TERMINAL_UI_SCALE.get()));
        } catch (IllegalStateException e) {
            return 1.0;
        }
    }

    /** True for a size the option accepts: within range and a multiple of 0.5. */
    public static boolean isValidTextSize(double value) {
        return value >= TEXT_SIZE_MIN && value <= TEXT_SIZE_MAX && value * 2.0 == Math.rint(value * 2.0);
    }

    /** -1 (no typing animation), or a multiple of 0.1 between 0.1 and 10. */
    public static boolean isValidTextSpeed(double value) {
        if (value == TEXT_SPEED_OFF) return true;
        return value >= TEXT_SPEED_MIN - 1.0E-9 && value <= TEXT_SPEED_MAX + 1.0E-9
                && Math.abs(value * 10.0 - Math.rint(value * 10.0)) < 1.0E-6;
    }

    /** The configured size, snapped to 0.5 steps in case the file was edited by hand. */
    public static double textSize() {
        double raw;
        try {
            raw = TEXT_SIZE.get();
        } catch (IllegalStateException e) {
            raw = TEXT_SIZE_DEFAULT; // config not loaded yet
        }
        double snapped = Math.rint(raw * 2.0) / 2.0;
        return Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX, snapped));
    }

    /** Speed multiplier, or a negative number when the typing animation is off. */
    public static double textSpeed() {
        double raw;
        try {
            raw = TEXT_SPEED.get();
        } catch (IllegalStateException e) {
            raw = TEXT_SPEED_DEFAULT;
        }
        if (raw < 0.0) return TEXT_SPEED_OFF;
        double snapped = Math.rint(raw * 10.0) / 10.0;
        return Math.max(TEXT_SPEED_MIN, Math.min(TEXT_SPEED_MAX, snapped));
    }

    private EnPlotifyConfig() {}
}
