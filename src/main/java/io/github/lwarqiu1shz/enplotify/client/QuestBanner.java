package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.quest.QuestSync;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * "Stage done" banner: a quarter of the way down the screen, the stage's name fades in, a second
 * later "已完成" under it, then both fade out. Roots and single requirements get none.
 * In a {@code branches()} stage each finished requirement gets a smaller banner. Each kind has its own sound.
 * <p>
 * Until the player has opened the quest map once, a blinking "按 [J] 打开流程页" (the key as
 * bound) shows as soon as they have a quest line and see the normal game screen (no story on
 * screen, e.g. once the intro has fully faded out), and stays until they open it.
 */
public final class QuestBanner {
    private static final long TITLE_IN = 500, SUB_AT = 1000, SUB_IN = 500, OUT_AT = 3600, OUT = 800;
    private static final long TOTAL = OUT_AT + OUT;
    /** The small banner: fade in, hold, fade out. */
    private static final long SMALL_IN = 400, SMALL_SUB_AT = 350, SMALL_OUT_AT = 2400, SMALL_OUT = 600;
    private static final long SMALL_TOTAL = SMALL_OUT_AT + SMALL_OUT;

    private record Entry(Component text, boolean small) {}

    private static final Deque<Entry> QUEUE = new ArrayDeque<>();
    private static Entry shown;
    private static long shownAt;
    /** Showing the "press J" hint (since {@link #hintAt}). */
    private static boolean hint;
    private static long hintAt;
    /** Client ticks the game screen has been clear (for the hint). */
    private static int clearTicks;
    /** Ticks the screen must stay clear first, so a story starting just after joining comes first. */
    private static final int CLEAR_TICKS = 20;

    /** Each client tick (only when the quest map is in use): starts the hint once the screen is clear. */
    static void tick(Minecraft mc, boolean received) {
        if (hint || !received || QuestClient.everOpened()) {
            clearTicks = 0;
            return;
        }
        boolean clear = mc.player != null && mc.level != null && mc.screen == null && !PlotCutscene.isBusy()
                && !QuestClient.map().lines().isEmpty();
        clearTicks = clear ? clearTicks + 1 : 0;
        if (clearTicks >= CLEAR_TICKS) {
            hint = true;
            hintAt = System.currentTimeMillis();
        }
    }

    /** Called with each new quest map: a banner for every stage that became done since the last one. */
    static void compare(QuestSync.Payload before, QuestSync.Payload after) {
        if (before == null) return; // the first map after joining: nothing just happened
        Map<String, QuestSync.StageView> old = new HashMap<>();
        for (QuestSync.LineView line : before.lines()) {
            for (QuestSync.StageView s : line.stages()) old.put(line.id() + "#" + s.id(), s);
        }
        for (QuestSync.LineView line : after.lines()) {
            for (QuestSync.StageView s : line.stages()) {
                if (s.root()) continue;
                QuestSync.StageView was = old.get(line.id() + "#" + s.id());
                boolean wasDone = was != null && was.state() == QuestSync.DONE;
                boolean nowDone = s.state() == QuestSync.DONE;
                if (s.branches() && !wasDone) {
                    // Requirements that just became met (by position; the list does not change while playing).
                    java.util.List<Component> met = new java.util.ArrayList<>();
                    for (int i = 0; i < s.requirements().size(); i++) {
                        QuestSync.ReqView r = s.requirements().get(i);
                        boolean before0 = was != null && i < was.requirements().size()
                                && was.requirements().get(i).value() >= was.requirements().get(i).target();
                        if (r.value() >= r.target() && !before0) met.add(r.text());
                    }
                    // Finished all at once (by a command, say): only the stage banner.
                    if (!nowDone || met.size() == 1) for (Component c : met) QUEUE.add(new Entry(c, true));
                }
                if (nowDone && !wasDone) QUEUE.add(new Entry(s.title(), false));
            }
        }
    }

    static void clear() {
        QUEUE.clear();
        shown = null;
        hint = false;
        clearTicks = 0;
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (hint && QuestClient.everOpened()) hint = false;
        if (hint && mc.screen == null && !mc.options.hideGui && !PlotCutscene.isBusy()) renderHint(g, mc.font, now);
        if (shown == null) {
            // Wait until a story on screen is over.
            if (QUEUE.isEmpty() || PlotCutscene.isBusy() || mc.options.hideGui) return;
            shown = QUEUE.poll();
            shownAt = now;
            if (shown.small()) requirementDoneSound(mc);
            else stageDoneSound(mc);
        }
        long t = now - shownAt;
        if (t >= (shown.small() ? SMALL_TOTAL : TOTAL)) {
            shown = null;
            return;
        }
        if (mc.options.hideGui) return;
        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int y = g.guiHeight() / 4;
        if (shown.small()) {
            renderSmall(g, font, shown.text(), cx, y, t);
            return;
        }
        Component title = shown.text();
        float out = t < OUT_AT ? 1.0F : 1.0F - (t - OUT_AT) / (float) OUT;

        float titleIn = ease(Mth.clamp(t / (float) TITLE_IN, 0.0F, 1.0F));
        float titleAlpha = titleIn * out;
        float subIn = ease(Mth.clamp((t - SUB_AT) / (float) SUB_IN, 0.0F, 1.0F));
        float subAlpha = subIn * out;

        Matrix3x2fStack pose = g.pose();
        // Stage name: rises a little as it fades in.
        if (titleAlpha > 0.02F) {
            float scale = 2.0F;
            pose.pushMatrix();
            pose.translate(cx, y - 18 + (1.0F - titleIn) * 8.0F);
            pose.scale(scale, scale);
            g.centeredText(font, title, 0, 0, alpha(0xFFCC33, titleAlpha));
            pose.popMatrix();
            // A thin line that grows out from the middle.
            int half = Math.round((font.width(title) * 1.0F + 30) * titleIn);
            g.fill(cx - half, y + 2, cx + half, y + 3, alpha(0xFFCC33, titleAlpha * 0.7F));
        }
        if (subAlpha > 0.02F) {
            pose.pushMatrix();
            pose.translate(cx, y + 9 + (1.0F - subIn) * 5.0F);
            pose.scale(1.25F, 1.25F);
            g.centeredText(font, Component.translatable("enplotify.quest.done"), 0, 0, alpha(0xFFFFFF, subAlpha));
            pose.popMatrix();
        }
    }

    /** "按 [J] 打开流程页" where "已完成" goes under a stage banner, blinking (fades in first). */
    private static void renderHint(GuiGraphicsExtractor g, Font font, long now) {
        long t = now - hintAt;
        if (t < 0) return;
        float in = ease(Mth.clamp(t / (float) SUB_IN, 0.0F, 1.0F));
        float blink = 0.6F + 0.4F * (float) Math.cos(t / 1000.0 * Math.PI * 2.0);
        if (QuestClient.KEY == null) return;
        Component text = Component.translatable("enplotify.quest.open_hint", QuestClient.KEY.getTranslatedKeyMessage());
        g.centeredText(font, text, g.guiWidth() / 2, g.guiHeight() / 4 + 26, alpha(0xFFFFFF, in * blink));
    }

    /** A smaller version of the stage banner, in green: "✔ requirement", a growing line, "要求达成". */
    private static void renderSmall(GuiGraphicsExtractor g, Font font, Component text, int cx, int y, long t) {
        float out = t < SMALL_OUT_AT ? 1.0F : 1.0F - (t - SMALL_OUT_AT) / (float) SMALL_OUT;
        float in = ease(Mth.clamp(t / (float) SMALL_IN, 0.0F, 1.0F));
        float a = in * out;
        float subIn = ease(Mth.clamp((t - SMALL_SUB_AT) / (float) SMALL_IN, 0.0F, 1.0F));
        float subA = subIn * out;
        Matrix3x2fStack pose = g.pose();
        Component line = Component.literal("✔ ").append(text);
        if (a > 0.02F) {
            // A little pop: starts slightly larger and settles.
            float scale = 1.5F * (1.0F + (1.0F - in) * 0.25F);
            pose.pushMatrix();
            pose.translate(cx, y - 14 + (1.0F - in) * 6.0F);
            pose.scale(scale, scale);
            g.centeredText(font, line, 0, 0, alpha(0x7CFC7C, a));
            pose.popMatrix();
            int half = Math.round((font.width(line) * 0.75F + 20) * in);
            g.fill(cx - half, y, cx + half, y + 1, alpha(0x7CFC7C, a * 0.7F));
        }
        if (subA > 0.02F) {
            pose.pushMatrix();
            pose.translate(cx, y + 5 + (1.0F - subIn) * 4.0F);
            g.centeredText(font, Component.translatable("enplotify.quest.req_done"), 0, 0, alpha(0xFFFFFF, subA));
            pose.popMatrix();
        }
    }

    /** Our own copy of the amethyst shimmer: the game's plays it at a fifth of the volume. */
    private static final SoundEvent SHIMMER = SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath("enplotify", "quest_shimmer"));

    /** A stage done: chimes on fa and la, a bell on la, the beacon hum and a shimmer, all at once. */
    private static void stageDoneSound(Minecraft mc) {
        float fa = (float) Math.pow(2, 5 / 12.0), la = (float) Math.pow(2, 9 / 12.0);
        play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), fa, 0.7F);
        play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), la, 0.7F);
        play(mc, SoundEvents.NOTE_BLOCK_BELL.value(), la, 0.8F);
        play(mc, SoundEvents.BEACON_ACTIVATE, 1.0F, 0.8F);
        play(mc, SHIMMER, 1.0F, 0.6F);
    }

    /** Always the second enchanting table sound (the game's picks one of three). */
    private static final SoundEvent ENCHANT = SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath("enplotify", "quest_enchant"));

    /** A requirement done: the stage chord a fourth lower, with a soft enchanting table under it. */
    private static void requirementDoneSound(Minecraft mc) {
        float fa = (float) Math.pow(2, -1 / 12.0), la = (float) Math.pow(2, 3 / 12.0);
        play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), fa, 0.7F);
        play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), la, 0.7F);
        play(mc, SoundEvents.NOTE_BLOCK_BELL.value(), la, 0.8F);
        play(mc, ENCHANT, 1.0F, 0.2F);
        play(mc, SHIMMER, 1.0F, 0.6F);
    }

    private static void play(Minecraft mc, SoundEvent sound, float pitch, float volume) {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private static float ease(float k) {
        return 1.0F - (1.0F - k) * (1.0F - k);
    }

    private static int alpha(int rgb, float a) {
        return (Math.max(5, Math.round(Mth.clamp(a, 0.0F, 1.0F) * 255)) << 24) | rgb;
    }

    private QuestBanner() {}
}
