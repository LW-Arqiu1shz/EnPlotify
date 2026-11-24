package io.github.lwarqiu1shz.enplotify;

import io.github.lwarqiu1shz.enplotify.api.PlotEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server half of the story mode. While a player is "watching" (the text is on their screen)
 * they are held where they stood and, unless the story was started unprotected, take no damage
 * and mobs drop them as a target.
 * <p>
 * In multiplayer, a story started within {@link #JOIN_WINDOW} ticks of the player joining (one they
 * missed while away) is not played at once: the player gets a notice at the screen's left and the
 * story starts when they press Enter. Several such stories wait in order, one notice at a time.
 * A player's very first time on the server never waits: their first story plays at once.
 * <p>
 * With others online a story is watched together: it plays for the player it was started for and
 * for every other online player who has not seen it yet and is not in a story. While anyone is in
 * a story, a new one waits until everybody is done, then plays (several in the order they came).
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class PlotServer {
    /** Players whose story text is on screen, and where they are held. */
    private static final Map<UUID, Vec3> WATCHING = new HashMap<>();
    /** Players with a story running (text or ending), and which story. */
    private static final Map<UUID, Identifier> ACTIVE = new HashMap<>();
    /** Players watching a story started with {@code protect = false}: damage and targeting as usual. */
    private static final java.util.Set<UUID> UNPROTECTED = new java.util.HashSet<>();
    /** Stories started this soon after joining (ticks) wait for the player's Enter. */
    private static final int JOIN_WINDOW = 200;
    /** When each player joined (server tick). */
    private static final Map<UUID, Integer> JOINED = new HashMap<>();
    private record Offer(Identifier plot, Map<String, net.minecraft.network.chat.Component> vars, boolean protect) {}
    /** Stories waiting for the player's Enter; the first one is on their notice. */
    private static final Map<UUID, java.util.ArrayDeque<Offer>> OFFERS = new HashMap<>();
    /** On the server for the first time, until their first story starts: it never waits. */
    private static final java.util.Set<UUID> FIRST_VISIT = new java.util.HashSet<>();
    private record Pending(Identifier plot, Map<String, net.minecraft.network.chat.Component> vars, boolean protect,
                           java.util.Set<UUID> starters) {}
    /** Stories started while someone was in one: they play once everybody is done. */
    private static final java.util.ArrayDeque<Pending> WAITING = new java.util.ArrayDeque<>();

    static void start(ServerPlayer player, Identifier plot, Map<String, net.minecraft.network.chat.Component> vars,
                      boolean protect) {
        var server = player.level().getServer();
        boolean together = server.getPlayerList().getPlayerCount() > 1;
        // Already watching this very story (pulled in when someone else started it).
        if (together && plot.equals(ACTIVE.get(player.getUUID()))) return;
        if (shouldOffer(player)) {
            java.util.ArrayDeque<Offer> queue = OFFERS.computeIfAbsent(player.getUUID(), k -> new java.util.ArrayDeque<>());
            queue.removeIf(o -> o.plot().equals(plot));
            queue.add(new Offer(plot, Map.copyOf(vars), protect));
            if (queue.size() == 1 && !ACTIVE.containsKey(player.getUUID())) sendOffer(player);
            return;
        }
        if (!together) {
            begin(player, plot, vars, protect);
            return;
        }
        if (!ACTIVE.isEmpty() && !FIRST_VISIT.contains(player.getUUID())) {
            for (Pending p : WAITING) {
                if (p.plot().equals(plot)) {
                    p.starters().add(player.getUUID());
                    return;
                }
            }
            java.util.Set<UUID> starters = new java.util.LinkedHashSet<>();
            starters.add(player.getUUID());
            WAITING.add(new Pending(plot, Map.copyOf(vars), protect, starters));
            return;
        }
        beginTogether(server, java.util.Set.of(player.getUUID()), plot, vars, protect);
    }

    /** Plays the story for {@code starters} and every other free online player who hasn't seen it. */
    private static void beginTogether(net.minecraft.server.MinecraftServer server, java.util.Set<UUID> starters, Identifier plot,
                                      Map<String, net.minecraft.network.chat.Component> vars, boolean protect) {
        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID id = p.getUUID();
            if (starters.contains(id)) {
                if (!plot.equals(ACTIVE.get(id))) begin(p, plot, vars, protect);
            } else if (!ACTIVE.containsKey(id) && !io.github.lwarqiu1shz.enplotify.quest.QuestProgress.hasSeen(p, plot)) {
                begin(p, plot, vars, protect);
            }
        }
    }

    /** Everybody is done: the next waiting story plays. */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (WAITING.isEmpty() || !ACTIVE.isEmpty()) return;
        Pending next = WAITING.poll();
        java.util.Set<UUID> online = new java.util.LinkedHashSet<>();
        for (UUID id : next.starters()) if (event.getServer().getPlayerList().getPlayer(id) != null) online.add(id);
        beginTogether(event.getServer(), online, next.plot(), next.vars(), next.protect());
    }

    /** Joined a multiplayer game a moment ago (the host of a single player world never waits). */
    private static boolean shouldOffer(ServerPlayer player) {
        var server = player.level().getServer();
        Integer joined = JOINED.get(player.getUUID());
        return joined != null && server.getTickCount() - joined < JOIN_WINDOW
                && !FIRST_VISIT.contains(player.getUUID())
                && !server.isSingleplayerOwner(player.nameAndId());
    }

    private static void sendOffer(ServerPlayer player) {
        java.util.ArrayDeque<Offer> queue = OFFERS.get(player.getUUID());
        if (queue == null || queue.isEmpty()) return;
        PacketDistributor.sendToPlayer(player, new PlotNetwork.OfferPayload(queue.peek().plot()));
    }

    /** The player pressed Enter on the notice: play the story it named. */
    static void accept(ServerPlayer player) {
        java.util.ArrayDeque<Offer> queue = OFFERS.get(player.getUUID());
        Offer offer = queue == null ? null : queue.poll();
        if (offer == null) return;
        if (queue.isEmpty()) OFFERS.remove(player.getUUID());
        begin(player, offer.plot(), offer.vars(), offer.protect());
    }

    /** A story is over: the next waiting one gets its notice. */
    private static void offerNext(ServerPlayer player) {
        if (!ACTIVE.containsKey(player.getUUID())) sendOffer(player);
    }

    private static void begin(ServerPlayer player, Identifier plot, Map<String, net.minecraft.network.chat.Component> vars,
                              boolean protect) {
        UUID id = player.getUUID();
        FIRST_VISIT.remove(id);
        ACTIVE.put(id, plot);
        if (protect) UNPROTECTED.remove(id);
        else UNPROTECTED.add(id);
        boolean wasWatching = WATCHING.put(id, player.position()) != null;
        PacketDistributor.sendToPlayer(player, new PlotNetwork.PlayPayload(plot, Map.copyOf(vars)));
        if (!wasWatching) PacketDistributor.sendToAllPlayers(new PlotNetwork.StatusPayload(id, true));
        NeoForge.EVENT_BUS.post(new PlotEvent.Started(player, plot));
    }

    /** The text is over: control, damage and targeting come back. */
    static void release(ServerPlayer player, boolean skipped) {
        UUID id = player.getUUID();
        if (WATCHING.remove(id) == null) return;
        PacketDistributor.sendToAllPlayers(new PlotNetwork.StatusPayload(id, false));
        Identifier plot = ACTIVE.get(id);
        if (plot != null) NeoForge.EVENT_BUS.post(new PlotEvent.Released(player, plot, skipped));
    }

    /** The whole story, ending included, is over (or was skipped). */
    static void finish(ServerPlayer player, boolean skipped) {
        // Skipping a story also takes the live story lines down (before the events, which may start new ones).
        if (skipped && ACTIVE.containsKey(player.getUUID())) io.github.lwarqiu1shz.enplotify.api.StoryLines.clear(player);
        release(player, skipped);
        UNPROTECTED.remove(player.getUUID());
        Identifier plot = ACTIVE.remove(player.getUUID());
        if (plot != null) {
            io.github.lwarqiu1shz.enplotify.quest.QuestProgress.markSeen(player, plot);
            // Seen now: a waiting copy of it doesn't play again for them.
            for (Pending p : WAITING) if (p.plot().equals(plot)) p.starters().remove(player.getUUID());
            NeoForge.EVENT_BUS.post(new PlotEvent.Finished(player, plot, skipped));
        }
        offerNext(player);
    }

    /** Ends the story at once (the client fades back to the world); reported like a skip. */
    static void stop(ServerPlayer player) {
        if (!ACTIVE.containsKey(player.getUUID())) return;
        PacketDistributor.sendToPlayer(player, PlotNetwork.StopPayload.INSTANCE);
        finish(player, true);
    }

    /** The client had no text for the story: free the player, but don't report it as seen. */
    static void fail(ServerPlayer player) {
        UUID id = player.getUUID();
        UNPROTECTED.remove(id);
        if (WATCHING.remove(id) != null) PacketDistributor.sendToAllPlayers(new PlotNetwork.StatusPayload(id, false));
        Identifier plot = ACTIVE.remove(id);
        if (plot != null) NeoForge.EVENT_BUS.post(new PlotEvent.Failed(player, plot));
        offerNext(player);
    }

    static boolean isWatching(ServerPlayer player) {
        return WATCHING.containsKey(player.getUUID());
    }

    /** Watching a story that keeps them safe (the default). */
    private static boolean isProtected(ServerPlayer player) {
        return isWatching(player) && !UNPROTECTED.contains(player.getUUID());
    }

    static Identifier currentPlot(ServerPlayer player) {
        return ACTIVE.get(player.getUUID());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isProtected(player)) event.setCanceled(true);
    }

    /** Mobs never pick a player who is reading a story as their target. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && isProtected(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Vec3 hold = WATCHING.get(player.getUUID());
        if (hold == null) return;
        if (isProtected(player)) {
            if (player.tickCount % 10 == 0) {
                for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(24.0D))) {
                    if (mob.getTarget() == player) mob.setTarget(null);
                }
            }
            player.clearFire();
        }
        player.setDeltaMovement(Vec3.ZERO);
        if (player.position().distanceToSqr(hold) > 0.01D) {
            player.teleportTo(hold.x, hold.y, hold.z);
        }
    }

    /** Noted first, so stories other mods start on joining become notices. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            JOINED.put(player.getUUID(), player.level().getServer().getTickCount());
            OFFERS.remove(player.getUUID());
            // Never left this world before: the first time here.
            if (player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM, net.minecraft.stats.Stats.LEAVE_GAME) == 0) {
                FIRST_VISIT.add(player.getUUID());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        JOINED.clear();
        OFFERS.clear();
        FIRST_VISIT.clear();
        WAITING.clear();
    }

    /** A player who joins learns who is currently reading a story (for the name tags). */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joined)) return;
        for (UUID watcher : WATCHING.keySet()) {
            if (!watcher.equals(joined.getUUID())) {
                PacketDistributor.sendToPlayer(joined, new PlotNetwork.StatusPayload(watcher, true));
            }
        }
    }

    /** Respawned or moved to another dimension mid-story: held where they are now, not dragged back. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        WATCHING.replace(event.getEntity().getUUID(), event.getEntity().position());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        WATCHING.replace(event.getEntity().getUUID(), event.getEntity().position());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        JOINED.remove(id);
        OFFERS.remove(id);
        FIRST_VISIT.remove(id);
        ACTIVE.remove(id);
        UNPROTECTED.remove(id);
        if (WATCHING.remove(id) != null) {
            PacketDistributor.sendToAllPlayers(new PlotNetwork.StatusPayload(id, false));
        }
    }

    private PlotServer() {}
}
