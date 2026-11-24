package io.github.lwarqiu1shz.enplotify.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One player's story quest progress, saved with the player (kept through death).
 * Keys: {@code line#stage} for stages, {@code line#stage#requirement} for requirement counts.
 */
public final class QuestProgress {
    public final Set<String> unlocked;
    public final Set<String> done;
    public final Map<String, Integer> counts;
    /** The stage shown as the current task, or "". */
    public String current;

    private QuestProgress(List<String> unlocked, List<String> done, Map<String, Integer> counts, String current) {
        this.unlocked = new LinkedHashSet<>(unlocked);
        this.done = new LinkedHashSet<>(done);
        this.counts = new HashMap<>(counts);
        this.current = current;
    }

    static QuestProgress empty() {
        return new QuestProgress(List.of(), List.of(), Map.of(), "");
    }

    private static final MapCodec<QuestProgress> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("unlocked", List.of()).forGetter(p -> List.copyOf(p.unlocked)),
            Codec.STRING.listOf().optionalFieldOf("done", List.of()).forGetter(p -> List.copyOf(p.done)),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("counts", Map.of()).forGetter(p -> p.counts),
            Codec.STRING.optionalFieldOf("current", "").forGetter(p -> p.current)
    ).apply(i, QuestProgress::new));

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, EnPlotify.MOD_ID);

    private static final Supplier<AttachmentType<QuestProgress>> TYPE = ATTACHMENTS.register("quest_progress",
            () -> AttachmentType.builder(QuestProgress::empty).serialize(CODEC).copyOnDeath().build());

    /** Stories the player has watched to the end (or skipped). */
    private static final Supplier<AttachmentType<List<String>>> SEEN = ATTACHMENTS.register("seen_plots",
            () -> AttachmentType.<List<String>>builder(() -> List.of()).serialize(Codec.STRING.listOf().fieldOf("plots"))
                    .copyOnDeath().build());

    public static boolean hasSeen(ServerPlayer player, Identifier plot) {
        return player.getData(SEEN).contains(plot.toString());
    }

    public static void markSeen(ServerPlayer player, Identifier plot) {
        List<String> seen = player.getData(SEEN);
        if (seen.contains(plot.toString())) return;
        List<String> more = new java.util.ArrayList<>(seen);
        more.add(plot.toString());
        player.setData(SEEN, List.copyOf(more));
    }

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    public static QuestProgress of(ServerPlayer player) {
        return player.getData(TYPE);
    }

    /** Marks the (changed in place) progress to be saved. */
    public static void save(ServerPlayer player) {
        player.setData(TYPE, player.getData(TYPE));
    }

    public static String key(Identifier line, String stage) {
        return line + "#" + stage;
    }

    public static String key(Identifier line, String stage, String requirement) {
        return line + "#" + stage + "#" + requirement;
    }
}
