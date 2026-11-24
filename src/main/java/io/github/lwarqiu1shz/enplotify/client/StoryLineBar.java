package io.github.lwarqiu1shz.enplotify.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * The story line above the action bar ({@code api.StoryLines}): one text row above the action bar
 * ({@code guiHeight - 68 - 4 - 9}). Each line fades in over 0.25 s and out over the last 0.5 s of
 * the time the server gave it; taking it down early fades it out from where it is.
 */
public final class StoryLineBar {
    private static final int ACTION_BAR_OFFSET = 68 + 4;
    private static final int LINE_GAP = 9;
    private static final long FADE_IN_MS = 250L;
    private static final long FADE_MS = 500L;

    private static Component message;
    private static long shownAt;
    private static long lengthMs;
    /** When it was taken down early (-1 = not). */
    private static long downAt = -1;
    private static float alphaAtDown;

    /** null fades the line out now. */
    public static void set(Component value, int ticks) {
        long now = Util.getMillis();
        if (value == null) {
            if (message != null && downAt < 0) {
                alphaAtDown = alpha(now);
                downAt = now;
            }
            return;
        }
        message = value;
        shownAt = now;
        lengthMs = Math.max(FADE_IN_MS + FADE_MS, ticks * 50L);
        downAt = -1;
    }

    private static float alpha(long now) {
        long t = now - shownAt;
        float a = Math.min(1.0F, Math.min(t / (float) FADE_IN_MS, (lengthMs - t) / (float) FADE_MS));
        if (downAt >= 0) a = Math.min(a, alphaAtDown * (1.0F - (now - downAt) / (float) FADE_MS));
        return Mth.clamp(a, 0.0F, 1.0F);
    }

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Component current = message;
        if (current == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { message = null; return; }
        long now = Util.getMillis();
        if (now - shownAt >= lengthMs || (downAt >= 0 && now - downAt >= FADE_MS)) { message = null; return; }
        if (mc.options.hideGui) return;
        float a = alpha(now);
        int alpha = Math.round(a * 255.0F);
        if (alpha < 8) return; // the font draws nearly transparent text fully opaque
        int y = graphics.guiHeight() - ACTION_BAR_OFFSET - LINE_GAP;
        graphics.centeredText(mc.font, current, graphics.guiWidth() / 2, y, (alpha << 24) | 0xFFFFFF);
    }

    private StoryLineBar() {}
}
