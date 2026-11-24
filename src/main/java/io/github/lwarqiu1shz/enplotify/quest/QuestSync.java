package io.github.lwarqiu1shz.enplotify.quest;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.api.StoryQuests;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Sends each player their quest map (lines, stages, progress) whenever it changes, at most once a tick. */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class QuestSync {
    private static final Set<UUID> DIRTY = new HashSet<>();

    public static void markDirty(ServerPlayer player) {
        DIRTY.add(player.getUUID());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            StoryQuests.refresh(player);
            markDirty(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DIRTY.isEmpty()) return;
        for (UUID id : List.copyOf(DIRTY)) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);
            if (player != null) PacketDistributor.sendToPlayer(player, snapshot(player));
        }
        DIRTY.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DIRTY.clear();
    }

    /** Saved with the player once they have opened the quest map (the "press J" hint is gone for good). */
    public static final String OPENED_KEY = "enplotify_quests_opened";

    /** The client says the player opened the quest map. */
    public static void opened(ServerPlayer player) {
        if (player.getPersistentData().getBooleanOr(OPENED_KEY, false)) return;
        player.getPersistentData().putBoolean(OPENED_KEY, true);
        markDirty(player);
    }

    /** Stage states on the map. */
    public static final int LOCKED = 0, ACTIVE = 1, DONE = 2;

    static Payload snapshot(ServerPlayer player) {
        QuestProgress p = QuestProgress.of(player);
        List<LineView> lines = new ArrayList<>();
        for (StoryQuests.Line line : StoryQuests.started(player)) {
            List<StageView> stages = new ArrayList<>();
            for (StoryQuests.Stage stage : line.stages()) {
                String key = QuestProgress.key(line.id, stage.id);
                int state = p.done.contains(key) ? DONE : p.unlocked.contains(key) ? ACTIVE : LOCKED;
                List<ReqView> reqs = new ArrayList<>();
                for (StoryQuests.Requirement r : stage.requirements()) {
                    int value = state == DONE ? r.target() : p.counts.getOrDefault(QuestProgress.key(line.id, stage.id, r.id()), 0);
                    reqs.add(new ReqView(r.text(), value, r.target(), Optional.ofNullable(r.description())));
                }
                stages.add(new StageView(stage.id, stage.title, Optional.ofNullable(stage.description()),
                        line.parents(stage), stage.isSecret(), stage.isRoot(), stage.hasBranches(), state, reqs));
            }
            lines.add(new LineView(line.id, line.title, stages));
        }
        return new Payload(lines, p.current, player.getPersistentData().getBooleanOr(OPENED_KEY, false));
    }

    public record ReqView(Component text, int value, int target, Optional<Component> description) {}

    public record StageView(String id, Component title, Optional<Component> description, List<String> after,
                            boolean secret, boolean root, boolean branches, int state, List<ReqView> requirements) {}

    public record LineView(Identifier id, Component title, List<StageView> stages) {}

    /**
     * Server to client: the player's whole quest map; {@code current} is {@code line#stage} or "";
     * {@code opened}: the player has opened the map before.
     */
    public record Payload(List<LineView> lines, String current, boolean opened) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "quests"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(Payload::write, Payload::read);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        private static void write(RegistryFriendlyByteBuf buf, Payload payload) {
            buf.writeVarInt(payload.lines.size());
            for (LineView line : payload.lines) {
                buf.writeIdentifier(line.id);
                ComponentSerialization.STREAM_CODEC.encode(buf, line.title);
                buf.writeVarInt(line.stages.size());
                for (StageView s : line.stages) {
                    buf.writeUtf(s.id);
                    ComponentSerialization.STREAM_CODEC.encode(buf, s.title);
                    ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, s.description);
                    buf.writeVarInt(s.after.size());
                    for (String a : s.after) buf.writeUtf(a);
                    buf.writeBoolean(s.secret);
                    buf.writeBoolean(s.root);
                    buf.writeBoolean(s.branches);
                    buf.writeVarInt(s.state);
                    buf.writeVarInt(s.requirements.size());
                    for (ReqView r : s.requirements) {
                        ComponentSerialization.STREAM_CODEC.encode(buf, r.text);
                        buf.writeVarInt(r.value);
                        buf.writeVarInt(r.target);
                        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, r.description);
                    }
                }
            }
            buf.writeUtf(payload.current);
            buf.writeBoolean(payload.opened);
        }

        private static Payload read(RegistryFriendlyByteBuf buf) {
            int lineCount = buf.readVarInt();
            List<LineView> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                Identifier id = buf.readIdentifier();
                Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
                int stageCount = buf.readVarInt();
                List<StageView> stages = new ArrayList<>(stageCount);
                for (int j = 0; j < stageCount; j++) {
                    String sid = buf.readUtf();
                    Component stitle = ComponentSerialization.STREAM_CODEC.decode(buf);
                    Optional<Component> desc = ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
                    int afterCount = buf.readVarInt();
                    List<String> after = new ArrayList<>(afterCount);
                    for (int k = 0; k < afterCount; k++) after.add(buf.readUtf());
                    boolean secret = buf.readBoolean();
                    boolean root = buf.readBoolean();
                    boolean branches = buf.readBoolean();
                    int state = buf.readVarInt();
                    int reqCount = buf.readVarInt();
                    List<ReqView> reqs = new ArrayList<>(reqCount);
                    for (int k = 0; k < reqCount; k++) {
                        reqs.add(new ReqView(ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt(),
                                ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf)));
                    }
                    stages.add(new StageView(sid, stitle, desc, after, secret, root, branches, state, reqs));
                }
                lines.add(new LineView(id, title, stages));
            }
            return new Payload(lines, buf.readUtf(), buf.readBoolean());
        }
    }

    private QuestSync() {}
}
