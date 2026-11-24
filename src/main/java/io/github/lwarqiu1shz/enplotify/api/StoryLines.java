package io.github.lwarqiu1shz.enplotify.api;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.PlotNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story lines while playing (live story, no cutscene), shown one after another in the line above
 * the action bar:
 * <ul>
 *   <li>"[speaker]: text", only "[speaker]: " in yellow bold, the text white; the player's own name shows as 你 / You;</li>
 *   <li>{@code |text|} in the text is shown light blue (without the bars);</li>
 *   <li>each line stays (letters / 5 + 1) s in Chinese, (letters / 20 + 1) s in other languages;
 *   only letters and digits count, not punctuation;</li>
 *   <li>each line fades in over 0.25 s and out over its last 0.5 s.</li>
 * </ul>
 * Lines are measured in the player's own language, read from the mods' language files on the
 * server ({@link ServerLang}). Skipping a story ({@link EnPlotify#skip}, or the reader skipping a
 * cutscene) also takes the lines down.
 * <p>
 * Server side only; nothing to register.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class StoryLines {
    /** One line: who says it, what they say, and how long it stays (ticks; 0 = by text length). */
    public record Line(Component speaker, Component text, int ticks) {
        public Line(Component speaker, Component text) { this(speaker, text, 0); }
    }

    private static final class Talk {
        final Deque<Line> queue = new ArrayDeque<>();
        long until = -1;
    }

    private static final Map<UUID, Talk> TALKS = new HashMap<>();

    /** Shows one line now, cutting off whatever this player was being told. */
    public static void say(ServerPlayer player, Component speaker, Component text) {
        play(player, List.of(new Line(speaker, text)));
    }

    /** Starts a conversation, replacing the current one. */
    public static void play(ServerPlayer player, List<Line> lines) {
        Talk talk = new Talk();
        talk.queue.addAll(lines);
        TALKS.put(player.getUUID(), talk);
        next(player, talk);
    }

    /** Adds lines after the current conversation. */
    public static void queue(ServerPlayer player, List<Line> lines) {
        Talk talk = TALKS.get(player.getUUID());
        if (talk == null) {
            play(player, lines);
        } else {
            talk.queue.addAll(lines);
        }
    }

    /** Takes the line down (fading) and drops the rest of the conversation. */
    public static void clear(ServerPlayer player) {
        if (TALKS.remove(player.getUUID()) != null) send(player, null, 0);
    }

    public static boolean isTalking(ServerPlayer player) {
        return TALKS.containsKey(player.getUUID());
    }

    /** Ticks a line of this text stays for this player. */
    public static int ticksFor(ServerPlayer player, String text) {
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) letters++;
            i += Character.charCount(cp);
        }
        float perSecond = ServerLang.chinese(player) ? 5.0F : 20.0F;
        return Math.round((letters / perSecond + 1.0F) * 20.0F);
    }

    /** Shows the next line; false when there is none. */
    private static boolean next(ServerPlayer player, Talk talk) {
        Line line = talk.queue.poll();
        if (line == null) return false;
        String speaker = ServerLang.resolve(player, line.speaker());
        // The player's own name shows as 你 / You.
        if (speaker.equals(player.getGameProfile().name())) speaker = ServerLang.resolve(player, Component.translatable("enplotify.speaker.you"));
        String text = ServerLang.resolve(player, line.text());
        int ticks = line.ticks() > 0 ? line.ticks() : ticksFor(player, text);
        talk.until = player.level().getServer().getTickCount() + ticks;
        MutableComponent shown = Component.empty();
        shown.append(Component.literal("[" + speaker + "]: ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        shown.append(styled(text).withStyle(ChatFormatting.WHITE));
        send(player, shown, ticks);
        return true;
    }

    private static void send(ServerPlayer player, Component message, int ticks) {
        PacketDistributor.sendToPlayer(player, new PlotNetwork.LinePayload(Optional.ofNullable(message), ticks));
    }

    /** {@code |text|} becomes light blue; a lone bar is kept as it is. */
    static MutableComponent styled(String text) {
        MutableComponent out = Component.empty();
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('|', at);
            int close = open < 0 ? -1 : text.indexOf('|', open + 1);
            if (close < 0) {
                out.append(Component.literal(text.substring(at)));
                break;
            }
            if (open > at) out.append(Component.literal(text.substring(at, open)));
            out.append(Component.literal(text.substring(open + 1, close)).withStyle(ChatFormatting.AQUA));
            at = close + 1;
        }
        return out;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (TALKS.isEmpty()) return;
        long now = event.getServer().getTickCount();
        Iterator<Map.Entry<UUID, Talk>> it = TALKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Talk> entry = it.next();
            Talk talk = entry.getValue();
            if (now < talk.until) continue;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !next(player, talk)) it.remove();
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TALKS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TALKS.clear();
    }

    private StoryLines() {}
}
