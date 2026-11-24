package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.EnPlotifyConfig;
import io.github.lwarqiu1shz.enplotify.PlotNetwork;
import io.github.lwarqiu1shz.enplotify.PlotSounds;
import io.github.lwarqiu1shz.enplotify.mixin.ClientInputAccessor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the story mode.
 * <pre>
 *   BLACK_IN    started while the world shows: the screen fades to black over 1 s first
 *               (not when a story was already on the black screen)
 *   TEXT        black screen, one page at a time, typed out at the language's speed
 *               (lang key enplotify.cps: 20 characters/s in Chinese, 80 in English, times the
 *               configured speed), a soft tick per revealed character.
 *               Space / left click: finish the page at once; on a finished page (after 0.5 s): next page.
 *               Tab + Space: skip the rest.
 *               With more than one player online, a finished page turns by itself after 3 s.
 *   after the last page, depending on the story's plot.json:
 *     zoom_last_page  ENDING_ZOOM: the last page is pushed towards the screen over 2 s
 *                     (100 % -> 700 % while fading 100 % -> 50 %, then 700 % -> 800 % fading 50 % -> 0 %)
 *     logo            LOGO_IN 1 s (fade in), LOGO_HOLD 2 s, LOGO_OUT 2 s (logo and black backdrop
 *                     fade out together); it grows slowly from 75 % to 82.5 % of the screen width
 *                     over all three, reaching full size as it has faded out
 *     neither         TEXT_FADE: the text fades out over 1 s
 *   BLACK_FADE  the black fades out over 2 s
 * </pre>
 * The player stays held (invulnerable, in place, untargeted) for as long as the screen is fully
 * black, and gets control back the moment the world starts to show again: at the start of
 * BLACK_FADE, or of LOGO_OUT when the story has a logo.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class PlotCutscene {
    enum Phase { NONE, BLACK_IN, TEXT, TEXT_FADE, BLACK_FADE, ENDING_ZOOM, LOGO_IN, LOGO_HOLD, LOGO_OUT }

    private static final long PAGE_DWELL_MS = 500L;
    /** With others online, a finished page turns by itself after this long. */
    private static final long AUTO_TURN_MS = 3000L;
    private static final long BLACK_IN_MS = 1000L;
    private static final long TEXT_FADE_MS = 1000L;
    private static final long BLACK_FADE_MS = 2000L;
    private static final long ZOOM_MS = 2000L;
    private static final long LOGO_IN_MS = 1000L;
    private static final long LOGO_HOLD_MS = 2000L;
    private static final long LOGO_OUT_MS = 2000L;
    private static final int TEXT_COLOR = 0xE8E2D6;
    private static final int HINT_COLOR = 0x6E6A64;
    /** Visible logo width as a fraction of the GUI width: when it starts fading in and when it has faded out. */
    private static final float LOGO_WIDTH_START = 0.75F;
    private static final float LOGO_WIDTH_END = 0.825F;
    /** Final magnification of the last page, and the point where the opacity is down to 50 %. */
    private static final float ZOOM_MAX = 8.0F;
    private static final float ZOOM_HALF_ALPHA_AT = 7.0F;
    /** Characters that must not start a line (they stay at the end of the previous one). */
    private static final String NO_LINE_START = "，。、；：？！…—」』）》,.;:!?)'\"";

    private static Phase phase = Phase.NONE;
    private static PlotScript script;
    private static List<String> pages = List.of();
    private static float charsPerSecond = 20.0F;
    private static int page;
    /** When the current page started typing; -1 until the story is actually on screen. */
    private static long pageStart = -1L;
    /** When the current page became complete; -1 while it is still typing. */
    private static long fullSince = -1L;
    private static boolean forcedFull;
    private static long phaseStart;
    /** Characters left visible while the text fades out. */
    private static int frozenReveal;
    /** Characters already revealed on this page, so each new one gets a tick sound. */
    private static int lastVisible;
    private static boolean skipped;
    private static long skippedAt;

    // ------------------------------------------------------------------ public API

    /** True from the first page until the last fade is over (other HUD effects may wait for it). */
    public static boolean isBusy() {
        return phase != Phase.NONE;
    }

    /** True while the story text itself is on screen (the player is held in place). */
    public static boolean isShowingText() {
        return showsText();
    }

    /** True if the player skipped a story within the last {@code millis} milliseconds. */
    public static boolean skippedWithin(long millis) {
        return skipped && Util.getMillis() - skippedAt < millis;
    }

    // ------------------------------------------------------------------ start

    public static void begin(Identifier plot) {
        begin(plot, java.util.Map.of());
    }

    public static void begin(Identifier plot, java.util.Map<String, net.minecraft.network.chat.Component> vars) {
        PlotScript loaded = PlotScript.load(plot, vars);
        if (loaded == null) {
            ClientPacketDistributor.sendToServer(PlotNetwork.MissingPayload.INSTANCE);
            return;
        }
        script = loaded;
        pages = loaded.pages();
        try {
            charsPerSecond = Math.max(1.0F, Float.parseFloat(I18n.get("enplotify.cps").trim()));
        } catch (NumberFormatException e) {
            charsPerSecond = 20.0F;
        }
        skipped = false;
        page = 0;
        pageStart = -1L;
        fullSince = -1L;
        forcedFull = false;
        lastVisible = 0;
        // Pulled into a story while playing: fade to black first instead of cutting to it.
        // Not when the screen is black already, or right after joining (the world isn't showing yet).
        boolean alreadyBlack = holdsPlayer() && phase != Phase.BLACK_IN;
        Minecraft mc = Minecraft.getInstance();
        boolean justJoined = mc.player == null || mc.player.tickCount < 40;
        if (alreadyBlack || justJoined) {
            phase = Phase.TEXT;
        } else {
            float black = currentBlack();
            phase = Phase.BLACK_IN;
            // A fade already under way carries on from where it was.
            phaseStart = Util.getMillis() - (long) (black * BLACK_IN_MS);
        }
        openScreenIfPossible();
    }

    /** How black the screen is right now, while fading in or back out (else 0). */
    private static float currentBlack() {
        if (phase == Phase.BLACK_FADE) return Mth.clamp(1.0F - (Util.getMillis() - phaseStart) / (float) BLACK_FADE_MS, 0.0F, 1.0F);
        return blackInLevel();
    }

    /** How black the screen is right now while fading in (0 when not fading in). */
    private static float blackInLevel() {
        if (phase != Phase.BLACK_IN) return 0.0F;
        return Mth.clamp((Util.getMillis() - phaseStart) / (float) BLACK_IN_MS, 0.0F, 1.0F);
    }

    static boolean showsText() {
        return phase == Phase.TEXT || phase == Phase.TEXT_FADE;
    }

    /** The screen is still fully black: the story screen stays open and the player is held. */
    static boolean holdsPlayer() {
        return phase == Phase.BLACK_IN || phase == Phase.TEXT || phase == Phase.TEXT_FADE || phase == Phase.ENDING_ZOOM
                || phase == Phase.LOGO_IN || phase == Phase.LOGO_HOLD;
    }

    /**
     * The story screen paints its own black backdrop only behind the text; during the ending it
     * stays open just to keep the player's input, and the GUI layer underneath draws the ending.
     */
    static boolean usesOpaqueBackdrop() {
        return showsText();
    }

    // ------------------------------------------------------------------ input (from PlotScreen)

    /** Space or left click. */
    static void advance() {
        if (phase != Phase.TEXT || pageStart < 0L) return;
        long now = Util.getMillis();
        if (fullSince < 0L) {
            forcedFull = true;
            fullSince = now;
            return;
        }
        if (now - fullSince < PAGE_DWELL_MS) return;
        if (page + 1 < pages.size()) {
            page++;
            pageStart = now;
            fullSince = -1L;
            forcedFull = false;
            lastVisible = 0;
        } else {
            endText(now);
        }
    }

    /** Tab + Space: end the story at once and give control back immediately. */
    static void skip() {
        if (phase != Phase.TEXT) return;
        skipped = true;
        skippedAt = Util.getMillis();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.DISPENSER_FAIL, 1.0F, 0.6F));
        closeStoryScreen();
        ClientPacketDistributor.sendToServer(new PlotNetwork.DonePayload(true));
        phase = Phase.BLACK_FADE;
        phaseStart = Util.getMillis();
    }

    /** The server ended the story: fade back to the world without reporting anything. */
    public static void stop() {
        if (phase == Phase.NONE || phase == Phase.BLACK_FADE) return;
        if (phase == Phase.LOGO_OUT) { // the world already shows: just drop the logo
            phase = Phase.NONE;
            return;
        }
        skipped = true;
        skippedAt = Util.getMillis();
        closeStoryScreen();
        phase = Phase.BLACK_FADE;
        phaseStart = Util.getMillis();
    }

    /** The last page was confirmed. */
    private static void endText(long now) {
        if (script.zoomLastPage() || script.logo() != null) {
            // Still fully black: the player stays held until the world shows again.
            phase = script.zoomLastPage() ? Phase.ENDING_ZOOM : Phase.LOGO_IN;
            phaseStart = now;
        } else {
            frozenReveal = pageStart < 0L ? 0 : reveal(now, totalChars(currentLines()));
            phase = Phase.TEXT_FADE;
            phaseStart = now;
        }
    }

    private static void startBlackFade(long now) {
        phase = Phase.BLACK_FADE;
        phaseStart = now;
        finish();
    }

    private static void finish() {
        closeStoryScreen();
        ClientPacketDistributor.sendToServer(new PlotNetwork.DonePayload(false));
    }

    private static void closeStoryScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PlotScreen) minecraft.setScreen(null);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (phase == Phase.NONE) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        long now = Util.getMillis();
        switch (phase) {
            case BLACK_IN -> {
                openScreenIfPossible();
                if (now - phaseStart >= BLACK_IN_MS) {
                    phase = Phase.TEXT;
                    // The story screen is already open: the first page starts typing now.
                    if (minecraft.screen instanceof PlotScreen) pageStart = now;
                }
            }
            case TEXT -> {
                openScreenIfPossible();
                // Several players online: nobody keeps the others waiting on a page.
                var connection = minecraft.getConnection();
                if (fullSince >= 0L && now - fullSince >= AUTO_TURN_MS
                        && connection != null && connection.getOnlinePlayers().size() > 1) advance();
            }
            case TEXT_FADE -> {
                if (now - phaseStart >= TEXT_FADE_MS) startBlackFade(now);
                else openScreenIfPossible();
            }
            case ENDING_ZOOM -> {
                openScreenIfPossible();
                if (now - phaseStart >= ZOOM_MS) {
                    if (script.logo() != null) {
                        phase = Phase.LOGO_IN;
                        phaseStart = now;
                    } else {
                        startBlackFade(now);
                    }
                }
            }
            case LOGO_IN -> {
                openScreenIfPossible();
                if (now - phaseStart >= LOGO_IN_MS) {
                    phase = Phase.LOGO_HOLD;
                    phaseStart = now;
                }
            }
            case LOGO_HOLD -> {
                openScreenIfPossible();
                if (now - phaseStart >= LOGO_HOLD_MS) {
                    // The black starts fading with the logo: the world shows, control comes back.
                    phase = Phase.LOGO_OUT;
                    phaseStart = now;
                    closeStoryScreen();
                    ClientPacketDistributor.sendToServer(PlotNetwork.ReleasePayload.INSTANCE);
                }
            }
            case LOGO_OUT -> {
                if (now - phaseStart >= LOGO_OUT_MS) {
                    phase = Phase.NONE;
                    finish();
                }
            }
            case BLACK_FADE -> {
                if (now - phaseStart >= BLACK_FADE_MS) phase = Phase.NONE;
            }
            default -> {}
        }
    }

    /** (Re)opens the story screen once nothing else (world loading, pause menu…) is on screen. */
    private static void openScreenIfPossible() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        if (minecraft.screen == null) minecraft.setScreen(new PlotScreen());
    }

    static void onScreenShown() {
        if (phase == Phase.TEXT && pageStart < 0L) pageStart = Util.getMillis();
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        // The player stays put while the screen is fully black.
        if (!holdsPlayer()) return;
        event.getInput().keyPresses = Input.EMPTY;
        ((ClientInputAccessor) event.getInput()).enplotify$setMoveVector(Vec2.ZERO);
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!holdsPlayer()) return;
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        phase = Phase.NONE;
        script = null;
        pages = List.of();
    }

    // ------------------------------------------------------------------ drawing

    /** GUI layer: black behind the text screen, the 2-second fade, and the ending. */
    public static void renderOverlay(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        switch (phase) {
            case BLACK_IN -> {
                int a = Mth.clamp((int) (blackInLevel() * 255.0F), 0, 255);
                if (a > 0) graphics.fill(0, 0, width, height, a << 24);
            }
            case TEXT, TEXT_FADE -> graphics.fill(0, 0, width, height, 0xFF000000);
            case BLACK_FADE -> {
                float black = 1.0F - (Util.getMillis() - phaseStart) / (float) BLACK_FADE_MS;
                int a = Mth.clamp((int) (black * 255.0F), 0, 255);
                if (a > 0) graphics.fill(0, 0, width, height, a << 24);
            }
            case ENDING_ZOOM, LOGO_IN, LOGO_HOLD, LOGO_OUT -> renderEnding(graphics, width, height);
            default -> {}
        }
    }

    /** Called by {@link PlotScreen} every frame; the ending itself is drawn by the GUI layer. */
    static void renderText(GuiGraphicsExtractor graphics, int width, int height) {
        if (showsText() && pageStart >= 0L) renderStory(graphics, width, height);
    }

    private static void renderStory(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        long now = Util.getMillis();
        Layout layout = layout(font, pages.get(page), width, height);
        List<String> lines = layout.lines();
        float scale = layout.scale();
        int total = totalChars(lines);

        float alpha = 1.0F;
        int visible;
        if (phase == Phase.TEXT_FADE) {
            alpha = 1.0F - (now - phaseStart) / (float) TEXT_FADE_MS;
            visible = frozenReveal;
        } else {
            visible = reveal(now, total);
            if (visible >= total && fullSince < 0L) fullSince = now;
        }
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        if (a < 8) return;

        // One soft tick for each newly typed character (at most three per frame).
        if (phase == Phase.TEXT) {
            int newly = visible - lastVisible;
            lastVisible = Math.max(lastVisible, visible);
            if (newly > 0) playTypeTicks(Math.min(newly, 3));
        }

        // Laid out in unscaled font units, then drawn through a scaled pose. The block is centred
        // together with the row reserved for the "▼" hint; the last page has no hint, so its text
        // sits exactly on the screen centre (where the ending zoom starts from).
        boolean hasNext = page + 1 < pages.size();
        float blockHeight = lines.size() * lineHeightUnits(font) * scale;
        float reserved = blockHeight + (hasNext ? lineHeightUnits(font) * scale : 0.0F);
        float top = height / 2.0F - reserved / 2.0F;
        int color = (a << 24) | TEXT_COLOR;
        int remaining = visible;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(width / 2.0F, top);
        pose.scale(scale, scale);
        for (int i = 0; i < lines.size() && remaining > 0; i++) {
            String line = lines.get(i);
            String shown = line.substring(0, Math.min(line.length(), remaining));
            remaining -= shown.length();
            // Left edge of the full line, so the text never shifts while it is being typed.
            int x = -font.width(line) / 2;
            graphics.text(font, shown, x, i * lineHeightUnits(font), color, false);
        }
        if (phase == Phase.TEXT && hasNext && fullSince >= 0L && now - fullSince >= PAGE_DWELL_MS
                && (now - fullSince - PAGE_DWELL_MS) % 1000L < 650L) {
            graphics.centeredText(font, "▼", 0, lines.size() * lineHeightUnits(font) + 3, (a << 24) | TEXT_COLOR);
        }
        pose.popMatrix();

        int hintA = Math.max(8, (int) (a * 0.85F));
        graphics.centeredText(font, I18n.get("enplotify.hint"), width / 2, height - 22, (hintA << 24) | HINT_COLOR);
        String counter = (page + 1) + " / " + pages.size();
        graphics.text(font, counter, width - 12 - font.width(counter), height - 22, (hintA << 24) | HINT_COLOR, false);
    }

    /** Push the final page away, then fade the logo in and out. */
    private static void renderEnding(GuiGraphicsExtractor graphics, int width, int height) {
        if (phase != Phase.LOGO_OUT) graphics.fill(0, 0, width, height, 0xFF000000);
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        long now = Util.getMillis();
        if (phase == Phase.ENDING_ZOOM) {
            float t = Mth.clamp((now - phaseStart) / (float) ZOOM_MS, 0.0F, 1.0F);
            float zoom = 1.0F + (ZOOM_MAX - 1.0F) * t * t;
            float alpha = zoom <= ZOOM_HALF_ALPHA_AT
                    ? 1.0F - 0.5F * (zoom - 1.0F) / (ZOOM_HALF_ALPHA_AT - 1.0F)
                    : 0.5F * (ZOOM_MAX - zoom) / (ZOOM_MAX - ZOOM_HALF_ALPHA_AT);
            int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
            if (a <= 3 || pages.isEmpty()) return;
            Layout layout = layout(font, pages.get(page), width, height);
            List<String> lines = layout.lines();
            float scale = layout.scale() * zoom;
            int color = (a << 24) | TEXT_COLOR;
            float blockHeight = lines.size() * lineHeightUnits(font) * scale;
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(width / 2.0F, height / 2.0F - blockHeight / 2.0F);
            pose.scale(scale, scale);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                graphics.text(font, line, -font.width(line) / 2, i * lineHeightUnits(font), color, false);
            }
            pose.popMatrix();
            return;
        }
        float alpha;
        if (phase == Phase.LOGO_IN) alpha = Mth.clamp((now - phaseStart) / (float) LOGO_IN_MS, 0.0F, 1.0F);
        else if (phase == Phase.LOGO_HOLD) alpha = 1.0F;
        else alpha = 1.0F - Mth.clamp((now - phaseStart) / (float) LOGO_OUT_MS, 0.0F, 1.0F);
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        if (phase == Phase.LOGO_OUT) {
            // The last phase reveals the world again, so the black backdrop fades with the logo.
            graphics.fill(0, 0, width, height, a << 24);
        }
        if (a <= 0 || script == null || script.logo() == null) return;
        int texWidth = script.logoWidth();
        int texHeight = script.logoHeight();
        // The logo grows slowly the whole time it shows, reaching full size as it fades out.
        long shown = now - phaseStart;
        if (phase == Phase.LOGO_HOLD) shown += LOGO_IN_MS;
        else if (phase == Phase.LOGO_OUT) shown += LOGO_IN_MS + LOGO_HOLD_MS;
        float k = Mth.clamp(shown / (float) (LOGO_IN_MS + LOGO_HOLD_MS + LOGO_OUT_MS), 0.0F, 1.0F);
        float widthFraction = LOGO_WIDTH_START + (LOGO_WIDTH_END - LOGO_WIDTH_START) * k;
        float scale = width * widthFraction / texWidth;
        if (texHeight * scale > height * 0.8F) scale = height * 0.8F / texHeight;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(width / 2.0F, height / 2.0F);
        pose.scale(scale, scale);
        graphics.blit(RenderPipelines.GUI_TEXTURED, script.logo(),
                -texWidth / 2, -texHeight / 2,
                0.0F, 0.0F,
                texWidth, texHeight,
                texWidth, texHeight,
                (a << 24) | 0xFFFFFF);
        pose.popMatrix();
    }

    private static void playTypeTicks(int count) {
        Minecraft minecraft = Minecraft.getInstance();
        for (int i = 0; i < count; i++) {
            // One fixed, light tick: tiny pitch jitter only.
            float pitch = 1.68F + (float) Math.random() * 0.06F;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(PlotSounds.TYPE_TICK.get(), pitch, 0.4F));
        }
    }

    private static List<String> currentLines() {
        Minecraft minecraft = Minecraft.getInstance();
        return layout(minecraft.font, pages.get(page),
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight()).lines();
    }

    private record Layout(List<String> lines, float scale) {}

    /** Line pitch in unscaled font units (font height plus spacing). */
    private static int lineHeightUnits(Font font) {
        return font.lineHeight + 5;
    }

    /**
     * Wraps the page at the configured text size (vanilla text = size 8). If the page would not fit
     * between the top edge and the hint line, the size is lowered step by step until it does.
     */
    private static Layout layout(Font font, String text, int width, int height) {
        float scale = (float) (EnPlotifyConfig.textSize() / EnPlotifyConfig.VANILLA_TEXT_SIZE);
        float maxBlockHeight = Math.max(20.0F, height - 70.0F);
        float minScale = 0.5F;
        List<String> lines;
        while (true) {
            // Column width in GUI pixels: 300 at vanilla size, growing with the text, never wider than the screen.
            float columnGui = Math.min(width - 40.0F, 300.0F * Math.max(1.0F, scale));
            lines = wrap(font, text, Math.max(20, (int) (columnGui / scale)));
            float blockHeight = lines.size() * lineHeightUnits(font) * scale;
            if (blockHeight <= maxBlockHeight || scale <= minScale) break;
            scale = Math.max(minScale, scale * 0.92F);
        }
        return new Layout(lines, scale);
    }

    private static int reveal(long now, int total) {
        if (forcedFull) return total;
        double speed = EnPlotifyConfig.textSpeed();
        if (speed < 0.0) return total; // -1: typing animation off
        int typed = (int) ((now - pageStart) * charsPerSecond * speed / 1000.0);
        return Mth.clamp(typed, 0, total);
    }

    private static int totalChars(List<String> lines) {
        int n = 0;
        for (String line : lines) n += line.length();
        return n;
    }

    /** Manual line breaks are kept; each line is then wrapped to the column width. */
    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            List<String> wrapped = wrapParagraph(font, paragraph.strip(), maxWidth);
            if (wrapped.isEmpty()) lines.add("");
            else lines.addAll(wrapped);
        }
        return lines;
    }

    /** Greedy wrap: breaks English at spaces, Chinese between any two characters. */
    private static List<String> wrapParagraph(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (line.isEmpty() && c == ' ') continue;
            if (font.width(line.toString() + c) <= maxWidth || line.isEmpty() || NO_LINE_START.indexOf(c) >= 0) {
                line.append(c);
                continue;
            }
            int space = line.lastIndexOf(" ");
            if (c != ' ' && space > 0 && isLatin(c)) {
                lines.add(line.substring(0, space));
                String rest = line.substring(space + 1);
                line.setLength(0);
                line.append(rest).append(c);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                if (c != ' ') line.append(c);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static boolean isLatin(char c) {
        return c < 0x2E80;
    }

    private PlotCutscene() {}
}
