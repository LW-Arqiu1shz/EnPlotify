package io.github.lwarqiu1shz.enplotify.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * How a terminal looks when it starts and on its desktop. Every part is optional; what is left out
 * is the built-in look ("EnPlotify OS" on black, the blue gradient desktop).
 *
 * <p>Other mods can:
 * <ul>
 *   <li>give one terminal its own look: the item component {@code enplotify:terminal_theme} on the
 *   terminal item (kept when the terminal is placed and mined again);</li>
 *   <li>change the look of every terminal without its own: {@link #setDefault} (call it on both
 *   sides, e.g. in common setup).</li>
 * </ul>
 *
 * @param logoText   start-up logo as text (any styles: colours, italic, ...), drawn in the middle,
 *                   with a thin outline in {@code logoOutline}
 * @param logoImage  start-up logo as a picture (a texture path such as
 *                   {@code mymod:textures/gui/os_logo.png}); used instead of the text
 * @param logoWidth  the picture's width as a share of the screen width (0..1)
 * @param logoAspect the picture's width / height
 * @param logoBackground colour behind the logo (ARGB)
 * @param logoOutline outline colour of the text logo (ARGB, 0 = none)
 * @param logoTicks  how long the logo shows (ticks; 20 = 1 second)
 * @param wallpaper  desktop picture (a texture path), stretched over the desktop
 * @param iconText   colour of the desktop icons' names (ARGB; white by default, dark for a light wallpaper)
 */
public record TerminalTheme(Optional<Component> logoText, Optional<Identifier> logoImage, float logoWidth, float logoAspect,
                            int logoBackground, int logoOutline, int logoTicks, Optional<Identifier> wallpaper,
                            int iconText) {
    public static final Codec<TerminalTheme> CODEC = RecordCodecBuilder.create(i -> i.group(
            ComponentSerialization.CODEC.optionalFieldOf("logo_text").forGetter(TerminalTheme::logoText),
            Identifier.CODEC.optionalFieldOf("logo_image").forGetter(TerminalTheme::logoImage),
            Codec.floatRange(0.05F, 1.0F).optionalFieldOf("logo_width", 0.5F).forGetter(TerminalTheme::logoWidth),
            Codec.floatRange(0.1F, 20.0F).optionalFieldOf("logo_aspect", 3.0F).forGetter(TerminalTheme::logoAspect),
            Codec.INT.optionalFieldOf("logo_background", 0xFF000000).forGetter(TerminalTheme::logoBackground),
            Codec.INT.optionalFieldOf("logo_outline", 0xFFFFFFFF).forGetter(TerminalTheme::logoOutline),
            Codec.intRange(1, 400).optionalFieldOf("logo_ticks", 60).forGetter(TerminalTheme::logoTicks),
            Identifier.CODEC.optionalFieldOf("wallpaper").forGetter(TerminalTheme::wallpaper),
            Codec.INT.optionalFieldOf("icon_text", 0xFFFFFFFF).forGetter(TerminalTheme::iconText)
    ).apply(i, TerminalTheme::new));

    /** The built-in look. */
    public static final TerminalTheme BUILT_IN = new TerminalTheme(Optional.empty(), Optional.empty(), 0.5F, 3.0F,
            0xFF000000, 0xFFFFFFFF, 60, Optional.empty(), 0xFFFFFFFF);

    private static TerminalTheme fallback = BUILT_IN;

    /** The look of every terminal that has none of its own. */
    public static void setDefault(@Nullable TerminalTheme theme) {
        fallback = theme == null ? BUILT_IN : theme;
    }

    public static TerminalTheme getDefault() {
        return fallback;
    }

    /** A theme with only a wallpaper. */
    public static TerminalTheme wallpaper(Identifier texture) {
        return new TerminalTheme(Optional.empty(), Optional.empty(), 0.5F, 3.0F, 0xFF000000, 0xFFFFFFFF, 60, Optional.of(texture), 0xFFFFFFFF);
    }

    public TerminalTheme withLogoText(Component text) {
        return new TerminalTheme(Optional.of(text), Optional.empty(), logoWidth, logoAspect, logoBackground, logoOutline, logoTicks, wallpaper, iconText);
    }

    public TerminalTheme withLogoImage(Identifier texture, float width, float aspect) {
        return new TerminalTheme(Optional.empty(), Optional.of(texture), width, aspect, logoBackground, logoOutline, logoTicks, wallpaper, iconText);
    }

    public TerminalTheme withWallpaper(@Nullable Identifier texture) {
        return new TerminalTheme(logoText, logoImage, logoWidth, logoAspect, logoBackground, logoOutline, logoTicks, Optional.ofNullable(texture), iconText);
    }

    public TerminalTheme withLogoTicks(int ticks) {
        return new TerminalTheme(logoText, logoImage, logoWidth, logoAspect, logoBackground, logoOutline, Math.clamp(ticks, 1, 400), wallpaper, iconText);
    }

    public TerminalTheme withLogoColors(int background, int outline) {
        return new TerminalTheme(logoText, logoImage, logoWidth, logoAspect, background, outline, logoTicks, wallpaper, iconText);
    }

    /** The colour of the desktop icons' names (ARGB). */
    public TerminalTheme withIconText(int argb) {
        return new TerminalTheme(logoText, logoImage, logoWidth, logoAspect, logoBackground, logoOutline, logoTicks, wallpaper, argb);
    }
}
