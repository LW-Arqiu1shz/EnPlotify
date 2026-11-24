package io.github.lwarqiu1shz.enplotify.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.PlotNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A story missed while away: a card slides out at the top left, "[chapter]" over an italic
 * "新剧情待观看", and stays until the player presses Enter, which starts the story.
 * <p>
 * The chapter name is the language key {@code <ns>.plot.<path>.title}, else the {@code "title"}
 * in the story's plot.json (a language key or plain text), else "一段剧情".
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class PlotNotice {
    private static final long SLIDE_MS = 450L;
    private static final int GOLD = 0xFFCC33;

    private static Component title;
    /** When the card started sliding out; -1 until it may show (not over a story or right after joining). */
    private static long shownAt = -1L;
    private static boolean enterWasDown;

    /** What the HUD drew last frame (minimaps and the like), and what it is drawing this frame. */
    private static List<ScreenRectangle> drawnLast = List.of();
    private static final List<ScreenRectangle> drawnNow = new ArrayList<>();
    /** True while the notice draws itself (its own parts don't count). */
    private static boolean drawing;
    /** Where the card sits; it glides there when something on the HUD moves. */
    private static float cardY = -1.0F;

    /** Called for everything put on the screen. */
    public static void noteDrawn(ScreenRectangle bounds) {
        if (!drawing && bounds != null && bounds.width() > 0 && bounds.height() > 0) drawnNow.add(bounds);
    }

    /** A frame is over: what it drew becomes what the card stays clear of (not with a menu open). */
    public static void frameDone() {
        if (Minecraft.getInstance().screen == null) drawnLast = List.copyOf(drawnNow);
        drawnNow.clear();
    }

    /** The highest spot at the left, from 6 down, where a card this size overlaps nothing on the HUD. */
    private static int freeY(int w, int h, int screenW, int screenH) {
        int y = 6;
        for (int guard = 0; guard < 64; guard++) {
            int pushed = y;
            for (ScreenRectangle r : drawnLast) {
                // Whole-screen overlays (vignette, pumpkin…) are not things to dodge.
                if (r.width() > screenW / 2 || r.height() > screenH / 2) continue;
                if (r.left() < w + 2 && r.right() > 0 && r.top() < y + h && r.bottom() > y) pushed = Math.max(pushed, r.bottom() + 4);
            }
            if (pushed == y) break;
            y = pushed;
        }
        return Math.min(y, Math.max(6, screenH - h - 6));
    }

    public static void show(Identifier plot) {
        title = chapter(plot);
        shownAt = -1L;
        cardY = -1.0F;
    }

    private static Component chapter(Identifier plot) {
        String key = plot.getNamespace() + ".plot." + plot.getPath().replace('/', '.') + ".title";
        if (I18n.exists(key)) return Component.translatable(key);
        Identifier file = Identifier.fromNamespaceAndPath(plot.getNamespace(), "plots/" + plot.getPath() + "/plot.json");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
        if (resource.isPresent()) {
            try (Reader reader = resource.get().openAsReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root instanceof JsonObject json && json.get("title") instanceof JsonElement t && t.isJsonPrimitive()) {
                    String value = t.getAsString();
                    return I18n.exists(value) ? Component.translatable(value) : Component.literal(value);
                }
            } catch (Exception ignored) {
                // no title: the plain name below
            }
        }
        return Component.translatable("enplotify.notice.untitled");
    }

    private static boolean ready(Minecraft mc) {
        return title != null && mc.player != null && mc.player.tickCount > 40 && !PlotCutscene.isBusy();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean down = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_ENTER)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_KP_ENTER);
        boolean pressed = down && !enterWasDown;
        enterWasDown = down;
        if (!ready(mc)) return;
        if (shownAt < 0L) shownAt = Util.getMillis();
        // Only in the world itself: Enter in chat or a menu is theirs.
        if (pressed && mc.screen == null && Util.getMillis() - shownAt > SLIDE_MS) {
            AbstractWidget.playButtonClickSound(mc.getSoundManager());
            title = null;
            shownAt = -1L;
            ClientPacketDistributor.sendToServer(PlotNetwork.AcceptPayload.INSTANCE);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        title = null;
        shownAt = -1L;
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!ready(mc) || shownAt < 0L || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        Font font = mc.font;
        Component waiting = Component.translatable("enplotify.notice.waiting").withStyle(ChatFormatting.ITALIC);
        Component key = Component.literal("  [Enter]");
        int textW = Math.max(font.width(title), font.width(waiting) + font.width(key));
        int w = textW + 22, h = 30;
        float k = Mth.clamp((Util.getMillis() - shownAt) / (float) SLIDE_MS, 0.0F, 1.0F);
        k = 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
        int target = freeY(w, h, g.guiWidth(), g.guiHeight());
        cardY = cardY < 0.0F ? target : cardY + (target - cardY) * 0.25F;
        if (Math.abs(target - cardY) < 0.5F) cardY = target;
        int x = Math.round(-w * (1.0F - k)), y = Math.round(cardY);
        drawing = true;
        try {
            draw(g, font, waiting, key, x, y, w, h);
        } finally {
            drawing = false;
        }
    }

    private static void draw(GuiGraphicsExtractor g, Font font, Component waiting, Component key, int x, int y, int w, int h) {
        // A card fading out to the right, with a gold edge that breathes.
        int solid = w - 16;
        g.fill(x, y, x + solid, y + h, 0xB0100C06);
        for (int i = 0; i < 16; i++) {
            int a = Math.round(0xB0 * (1.0F - (i + 1) / 17.0F));
            g.fill(x + solid + i, y, x + solid + i + 1, y + h, (a << 24) | 0x100C06);
        }
        float pulse = 0.65F + 0.35F * (float) Math.sin(Util.getMillis() / 400.0);
        g.fill(x, y, x + 2, y + h, (Math.round(pulse * 255) << 24) | GOLD);
        int tx = x + 9;
        g.text(font, title, tx, y + 5, 0xFF000000 | GOLD, true);
        g.text(font, waiting, tx, y + 17, 0xFFE8E2D6, true);
        g.text(font, key, tx + font.width(waiting), y + 17, 0xFF8A847A, false);
    }

    private PlotNotice() {}
}
