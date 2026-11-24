package io.github.lwarqiu1shz.enplotify.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.api.PlotEvent;
import io.github.lwarqiu1shz.enplotify.api.StoryQuests;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lines marked {@link StoryQuests.Line#shared()}: the whole server is as far as its furthest player,
 * players who join later included. Saved with the world, so it holds while that player is offline.
 * <p>
 * What a player reached is only passed on once nobody online is still inside a story that player
 * watched, and players who are in a story get nothing until it is over: a fast reader never cuts
 * the others' story short or pops a "done" banner in the middle of it.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class SharedQuests extends SavedData {
    private static final Codec<LineData> LINE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("started", false).forGetter(d -> d.started),
            Codec.STRING.listOf().optionalFieldOf("met", List.of()).forGetter(d -> List.copyOf(d.met)),
            Codec.STRING.listOf().optionalFieldOf("done", List.of()).forGetter(d -> List.copyOf(d.done))
    ).apply(i, LineData::new));

    public static final SavedDataType<SharedQuests> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "shared_quests"),
            SharedQuests::new,
            Codec.unboundedMap(Codec.STRING, LINE_CODEC).xmap(SharedQuests::new, d -> d.lines),
            null);

    private static final class LineData {
        boolean started;
        /** "stage/requirement" met by someone. */
        final Set<String> met = new LinkedHashSet<>();
        /** Stages someone finished. */
        final Set<String> done = new LinkedHashSet<>();

        LineData() {}

        LineData(boolean started, List<String> met, List<String> done) {
            this.started = started;
            this.met.addAll(met);
            this.done.addAll(done);
        }
    }

    private final Map<String, LineData> lines = new LinkedHashMap<>();

    private SharedQuests() {}

    private SharedQuests(Map<String, LineData> lines) {
        this.lines.putAll(lines);
    }

    private LineData line(Identifier id) {
        return lines.computeIfAbsent(id.toString(), k -> new LineData());
    }

    /** Stories each player has started that someone online may still be watching. */
    private static final Map<UUID, Set<Identifier>> WATCHED = new HashMap<>();

    /** Marks a requirement of a shared line as met for the whole server (offline players included). */
    public static void shareMet(MinecraftServer server, Identifier line, String stage, String requirement) {
        SharedQuests data = server.getDataStorage().computeIfAbsent(TYPE);
        LineData d = data.line(line);
        d.started = true;
        d.met.add(stage + "/" + requirement);
        data.setDirty();
        apply(server, data, line);
    }

    /** Brings the shared lines up to date now (also runs by itself every second and on login). */
    public static void sync(MinecraftServer server) {
        SharedQuests data = server.getDataStorage().computeIfAbsent(TYPE);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        // Stories nobody online is in any more stop holding back what their readers reached.
        Set<Identifier> running = new HashSet<>();
        for (ServerPlayer p : players) {
            Identifier plot = EnPlotify.currentPlot(p);
            if (plot != null) running.add(plot);
        }
        for (Set<Identifier> watched : WATCHED.values()) watched.retainAll(running);
        WATCHED.values().removeIf(Set::isEmpty);

        for (StoryQuests.Line line : StoryQuests.lines()) {
            if (!line.isShared()) continue;
            LineData d = data.line(line.id);
            boolean changed = false;
            for (ServerPlayer p : players) {
                if (WATCHED.containsKey(p.getUUID())) continue;
                if (!d.started && StoryQuests.started(p).contains(line)) { d.started = true; changed = true; }
                for (StoryQuests.Stage stage : line.stages()) {
                    boolean done = StoryQuests.isDone(p, line.id, stage.id);
                    if (done && d.done.add(stage.id)) changed = true;
                    for (StoryQuests.Requirement r : stage.requirements()) {
                        String key = stage.id + "/" + r.id();
                        if (d.met.contains(key)) continue;
                        if (done || StoryQuests.progress(p, line.id, stage.id, r.id()) >= r.target()) {
                            d.met.add(key);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) data.setDirty();
            apply(server, data, line.id);
        }
    }

    /** Gives every online player who is not in a story what the server has reached. */
    private static void apply(MinecraftServer server, SharedQuests data, Identifier lineId) {
        StoryQuests.Line line = StoryQuests.get(lineId);
        LineData d = data.lines.get(lineId.toString());
        if (line == null || d == null || !d.started) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (EnPlotify.currentPlot(p) != null) continue;
            if (!StoryQuests.started(p).contains(line)) StoryQuests.start(p, lineId);
            for (StoryQuests.Stage stage : line.stages()) {
                if (StoryQuests.isDone(p, lineId, stage.id)) continue;
                for (StoryQuests.Requirement r : stage.requirements()) {
                    if (StoryQuests.isDone(p, lineId, stage.id)) break;
                    if (d.met.contains(stage.id + "/" + r.id())
                            && StoryQuests.progress(p, lineId, stage.id, r.id()) < r.target()) {
                        StoryQuests.meet(p, lineId, stage.id, r.id());
                    }
                }
                // Finished without its requirements (or it has none): finish it once it is open.
                if (d.done.contains(stage.id) && StoryQuests.isUnlocked(p, lineId, stage.id)) {
                    StoryQuests.complete(p, lineId, stage.id);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlotStarted(PlotEvent.Started event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            WATCHED.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(event.getPlot());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 0) sync(event.getServer());
    }

    /** After other mods' own login catch-up. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p.level().getServer());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WATCHED.clear();
    }
}
