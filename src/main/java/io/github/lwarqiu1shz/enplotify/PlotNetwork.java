package io.github.lwarqiu1shz.enplotify;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;

public final class PlotNetwork {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, path);
    }

    /** Server to client: play this story; {@code vars} fill its {name} placeholders. */
    public record PlayPayload(Identifier plot, java.util.Map<String, net.minecraft.network.chat.Component> vars) implements CustomPacketPayload {
        public static final Type<PlayPayload> TYPE = new Type<>(id("play"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayPayload> CODEC =
                StreamCodec.composite(Identifier.STREAM_CODEC, PlayPayload::plot,
                        ByteBufCodecs.map(java.util.HashMap::new, ByteBufCodecs.stringUtf8(64),
                                net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC, 64), PlayPayload::vars,
                        PlayPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to client: the story line above the action bar ({@code api.StoryLines}) and how long it stays (ticks); empty = fade it out now. */
    public record LinePayload(java.util.Optional<net.minecraft.network.chat.Component> message, int ticks) implements CustomPacketPayload {
        public static final Type<LinePayload> TYPE = new Type<>(id("line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LinePayload> CODEC =
                StreamCodec.composite(net.minecraft.network.chat.ComponentSerialization.OPTIONAL_STREAM_CODEC, LinePayload::message,
                        ByteBufCodecs.VAR_INT, LinePayload::ticks, LinePayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to client: end the story now (the server already counts it as over). */
    public record StopPayload() implements CustomPacketPayload {
        public static final StopPayload INSTANCE = new StopPayload();
        public static final Type<StopPayload> TYPE = new Type<>(id("stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StopPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: the text is over, a logo / ending may still play: release the player. */
    public record ReleasePayload() implements CustomPacketPayload {
        public static final ReleasePayload INSTANCE = new ReleasePayload();
        public static final Type<ReleasePayload> TYPE = new Type<>(id("release"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReleasePayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: the story is completely over (or was skipped). */
    public record DonePayload(boolean skipped) implements CustomPacketPayload {
        public static final Type<DonePayload> TYPE = new Type<>(id("done"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DonePayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, DonePayload::skipped, DonePayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: this client has no text for the story, nothing was shown. */
    public record MissingPayload() implements CustomPacketPayload {
        public static final MissingPayload INSTANCE = new MissingPayload();
        public static final Type<MissingPayload> TYPE = new Type<>(id("missing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MissingPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: the player opened the quest map. */
    public record QuestOpenedPayload() implements CustomPacketPayload {
        public static final QuestOpenedPayload INSTANCE = new QuestOpenedPayload();
        public static final Type<QuestOpenedPayload> TYPE = new Type<>(id("quests_opened"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuestOpenedPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to every client: this player started / stopped reading a story (for the name tag). */
    public record StatusPayload(UUID player, boolean watching) implements CustomPacketPayload {
        public static final Type<StatusPayload> TYPE = new Type<>(id("status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusPayload> CODEC =
                StreamCodec.composite(UUIDUtil.STREAM_CODEC, StatusPayload::player,
                        ByteBufCodecs.BOOL, StatusPayload::watching, StatusPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A story missed while away waits for Enter: shown as a notice at the left. */
    public record OfferPayload(Identifier plot) implements CustomPacketPayload {
        public static final Type<OfferPayload> TYPE = new Type<>(id("offer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OfferPayload> CODEC =
                StreamCodec.composite(Identifier.STREAM_CODEC, OfferPayload::plot, OfferPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The player pressed Enter on the notice. */
    public record AcceptPayload() implements CustomPacketPayload {
        public static final AcceptPayload INSTANCE = new AcceptPayload();
        public static final Type<AcceptPayload> TYPE = new Type<>(id("accept"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AcceptPayload> CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void register(IEventBus modBus) {
        modBus.addListener(PlotNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(PlayPayload.TYPE, PlayPayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> io.github.lwarqiu1shz.enplotify.client.PlotCutscene.begin(payload.plot(), payload.vars())));
        registrar.playToClient(StopPayload.TYPE, StopPayload.CODEC,
                (payload, context) -> context.enqueueWork(io.github.lwarqiu1shz.enplotify.client.PlotCutscene::stop));
        registrar.playToClient(LinePayload.TYPE, LinePayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> io.github.lwarqiu1shz.enplotify.client.StoryLineBar.set(payload.message().orElse(null), payload.ticks())));
        registrar.playToClient(io.github.lwarqiu1shz.enplotify.quest.QuestSync.Payload.TYPE,
                io.github.lwarqiu1shz.enplotify.quest.QuestSync.Payload.CODEC,
                (payload, context) -> context.enqueueWork(() -> io.github.lwarqiu1shz.enplotify.client.QuestClient.set(payload)));
        registrar.playToClient(StatusPayload.TYPE, StatusPayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> io.github.lwarqiu1shz.enplotify.client.PlotNameTags.set(payload.player(), payload.watching())));
        registrar.playToServer(ReleasePayload.TYPE, ReleasePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) PlotServer.release(player, false);
                }));
        registrar.playToServer(DonePayload.TYPE, DonePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) PlotServer.finish(player, payload.skipped());
                }));
        registrar.playToClient(OfferPayload.TYPE, OfferPayload.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> io.github.lwarqiu1shz.enplotify.client.PlotNotice.show(payload.plot())));
        registrar.playToServer(AcceptPayload.TYPE, AcceptPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) PlotServer.accept(player);
                }));
        registrar.playToServer(MissingPayload.TYPE, MissingPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) PlotServer.fail(player);
                }));
        registrar.playToServer(QuestOpenedPayload.TYPE, QuestOpenedPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) io.github.lwarqiu1shz.enplotify.quest.QuestSync.opened(player);
                }));
    }

    private PlotNetwork() {}
}
