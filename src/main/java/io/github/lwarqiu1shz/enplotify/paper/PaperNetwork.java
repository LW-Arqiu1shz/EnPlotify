package io.github.lwarqiu1shz.enplotify.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PaperNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    /** Chunk size for client-to-server data: stays under the 32 KiB payload limit. */
    public static final int UPLOAD_CHUNK = 30000;
    /** Download chunk size (server to client allows up to 1 MiB per payload). */
    public static final int DOWNLOAD_CHUNK = 262144;
    /** Largest edit a client may send (all pages of a document, a full board…). */
    public static final int MAX_EDIT_BYTES = 1 << 20;

    public static final byte EDIT_POSTER = 0, EDIT_NEWSPAPER = 1, EDIT_DOCUMENT = 2, EDIT_BOARD = 3;

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, path));
    }

    // ------------------------------------------------------------------ client to server

    public record PlaceWall(InteractionHand hand, BlockPos clicked, Direction face, int width, int height) implements CustomPacketPayload {
        public static final Type<PlaceWall> TYPE = typeOf("place_wall");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceWall> CODEC = StreamCodec.composite(
                InteractionHand.STREAM_CODEC, PlaceWall::hand,
                BlockPos.STREAM_CODEC, PlaceWall::clicked,
                Direction.STREAM_CODEC, PlaceWall::face,
                ByteBufCodecs.VAR_INT, PlaceWall::width,
                ByteBufCodecs.VAR_INT, PlaceWall::height,
                PlaceWall::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Something the user of a terminal asks it ({@link TerminalServer} ops). */
    public record TerminalOp(BlockPos pos, int op, int a, int b, BlockPos other, String text) implements CustomPacketPayload {
        public static final Type<TerminalOp> TYPE = typeOf("terminal_op");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalOp> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalOp::pos,
                ByteBufCodecs.VAR_INT, TerminalOp::op,
                ByteBufCodecs.VAR_INT, TerminalOp::a,
                ByteBufCodecs.VAR_INT, TerminalOp::b,
                BlockPos.STREAM_CODEC, TerminalOp::other,
                ByteBufCodecs.stringUtf8(256), TerminalOp::text,
                TerminalOp::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static TerminalOp of(BlockPos pos, int op, int a) {
            return new TerminalOp(pos, op, a, 0, BlockPos.ZERO, "");
        }
    }

    /** Screenshots: one piece of a picture (a picture goes in as many pieces as it needs), and the largest picture. */
    public static final int MAX_SHOT_BYTES = 30000;
    /** Pieces from the server can be far bigger (a client takes up to 1 MB at once): mostly one piece a picture. */
    public static final int MAX_SHOT_DATA_BYTES = 900_000;
    public static final int MAX_PICTURE_BYTES = 1_500_000;

    /** A viewer would like pictures of this terminal's screen, {@code fps} per second (0 = on window changes). */
    public record TerminalWatch(BlockPos pos, float fps, int resolution) implements CustomPacketPayload {
        public static final Type<TerminalWatch> TYPE = typeOf("terminal_watch");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalWatch> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalWatch::pos,
                ByteBufCodecs.FLOAT, TerminalWatch::fps,
                ByteBufCodecs.VAR_INT, TerminalWatch::resolution,
                TerminalWatch::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** To the user: take pictures at this rate (negative = nobody watches, 0 = only on window changes). */
    public record TerminalCaptureRate(BlockPos pos, float fps, int resolution) implements CustomPacketPayload {
        public static final Type<TerminalCaptureRate> TYPE = typeOf("terminal_capture_rate");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalCaptureRate> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalCaptureRate::pos,
                ByteBufCodecs.FLOAT, TerminalCaptureRate::fps,
                ByteBufCodecs.VAR_INT, TerminalCaptureRate::resolution,
                TerminalCaptureRate::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * A piece ({@code index} of {@code total}) of a picture of the user's screen (JPEG);
     * {@code switched}: taken because the window changed.
     */
    public record TerminalShot(BlockPos pos, byte[] jpeg, boolean switched, int index, int total) implements CustomPacketPayload {
        public static final Type<TerminalShot> TYPE = typeOf("terminal_shot");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalShot> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalShot::pos,
                ByteBufCodecs.byteArray(MAX_SHOT_BYTES), TerminalShot::jpeg,
                ByteBufCodecs.BOOL, TerminalShot::switched,
                ByteBufCodecs.VAR_INT, TerminalShot::index,
                ByteBufCodecs.VAR_INT, TerminalShot::total,
                TerminalShot::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Most bytes of sound in one packet (µ-law, 24000 a second: a third of a second). */
    public static final int MAX_AUDIO_BYTES = 8192;

    /** Sound of a page in a terminal (one stream per page), from the player whose browser plays it. */
    public record TerminalAudio(BlockPos pos, int stream, byte[] samples) implements CustomPacketPayload {
        public static final Type<TerminalAudio> TYPE = typeOf("terminal_audio");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalAudio> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalAudio::pos,
                ByteBufCodecs.VAR_INT, TerminalAudio::stream,
                ByteBufCodecs.byteArray(MAX_AUDIO_BYTES), TerminalAudio::samples,
                TerminalAudio::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The same sound, passed on to the players nearby. */
    public record TerminalAudioData(BlockPos pos, int stream, byte[] samples) implements CustomPacketPayload {
        public static final Type<TerminalAudioData> TYPE = typeOf("terminal_audio_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalAudioData> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalAudioData::pos,
                ByteBufCodecs.VAR_INT, TerminalAudioData::stream,
                ByteBufCodecs.byteArray(MAX_AUDIO_BYTES), TerminalAudioData::samples,
                TerminalAudioData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A piece of a picture of a terminal's screen, passed on to a viewer. */
    public record TerminalShotData(BlockPos pos, byte[] jpeg, int index, int total) implements CustomPacketPayload {
        public static final Type<TerminalShotData> TYPE = typeOf("terminal_shot_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalShotData> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalShotData::pos,
                ByteBufCodecs.byteArray(MAX_SHOT_DATA_BYTES), TerminalShotData::jpeg,
                ByteBufCodecs.VAR_INT, TerminalShotData::index,
                ByteBufCodecs.VAR_INT, TerminalShotData::total,
                TerminalShotData::new);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Cuts a picture into pieces of at most {@link #MAX_SHOT_BYTES}, handing each to {@code send(piece, index, total)}. */
    public static void inPieces(byte[] picture, PieceSender send) {
        inPieces(picture, MAX_SHOT_BYTES, send);
    }

    /** The same, in pieces of at most {@code size} bytes. */
    public static void inPieces(byte[] picture, int size, PieceSender send) {
        int total = Math.max(1, (picture.length + size - 1) / size);
        if (total == 1) {
            send.send(picture, 0, 1);
            return;
        }
        for (int i = 0; i < total; i++) {
            send.send(java.util.Arrays.copyOfRange(picture, i * size, Math.min(picture.length, (i + 1) * size)), i, total);
        }
    }

    public interface PieceSender {
        void send(byte[] piece, int index, int total);
    }

    /** Puts pieces back together, per key; returns the whole picture once its last piece came. */
    public static final class Pieces<K> {
        private final java.util.Map<K, java.io.ByteArrayOutputStream> open = new java.util.HashMap<>();

        public byte @org.jspecify.annotations.Nullable [] add(K key, byte[] piece, int index, int total) {
            if (total < 1 || total > MAX_PICTURE_BYTES / MAX_SHOT_BYTES + 1 || index < 0 || index >= total) return null;
            java.io.ByteArrayOutputStream out = index == 0 ? new java.io.ByteArrayOutputStream() : open.get(key);
            if (out == null) return null;
            out.writeBytes(piece);
            if (out.size() > MAX_PICTURE_BYTES) {
                open.remove(key);
                return null;
            }
            if (index < total - 1) {
                open.put(key, out);
                return null;
            }
            open.remove(key);
            return out.toByteArray();
        }

        public void clear() {
            open.clear();
        }

        public void removeIf(java.util.function.Predicate<K> test) {
            open.keySet().removeIf(test);
        }
    }

    /** A file in a terminal's list (kind: {@link PrinterBlockEntity} kinds). */
    public record FileInfo(int id, String name, int kind, boolean editable) {
        public static final StreamCodec<RegistryFriendlyByteBuf, FileInfo> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FileInfo::id,
                ByteBufCodecs.stringUtf8(256), FileInfo::name,
                ByteBufCodecs.VAR_INT, FileInfo::kind,
                ByteBufCodecs.BOOL, FileInfo::editable,
                FileInfo::new);
    }

    /** A printer a terminal can reach (name "" = the default name). */
    /** {@code kindKey}: the printer block's name key, for its default name. */
    public record PrinterInfo(BlockPos pos, String name, String kindKey, int paper, int paperKind, boolean busy, int queued, boolean chosen) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PrinterInfo> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PrinterInfo::pos,
                ByteBufCodecs.stringUtf8(256), PrinterInfo::name,
                ByteBufCodecs.stringUtf8(256), PrinterInfo::kindKey,
                ByteBufCodecs.VAR_INT, PrinterInfo::paper,
                ByteBufCodecs.VAR_INT, PrinterInfo::paperKind,
                ByteBufCodecs.BOOL, PrinterInfo::busy,
                ByteBufCodecs.VAR_INT, PrinterInfo::queued,
                ByteBufCodecs.BOOL, PrinterInfo::chosen,
                PrinterInfo::new);
    }

    /**
     * A display a terminal can reach. name "" = the default name; size = width << 8 | height;
     * state: 0 free, 1 connected to this terminal, 2 taken by the terminal at owner (named ownerName).
     */
    /** {@code kindKey} / {@code ownerKey}: name keys of the display and of its terminal, for default names. */
    public record DisplayInfo(java.util.UUID id, BlockPos pos, String name, String kindKey, int size, int state, BlockPos owner,
                              String ownerName, String ownerKey) {
        public static final int FREE = 0, MINE = 1, TAKEN = 2;
        public static final StreamCodec<RegistryFriendlyByteBuf, DisplayInfo> CODEC = StreamCodec.composite(
                net.minecraft.core.UUIDUtil.STREAM_CODEC, DisplayInfo::id,
                BlockPos.STREAM_CODEC, DisplayInfo::pos,
                ByteBufCodecs.stringUtf8(256), DisplayInfo::name,
                ByteBufCodecs.stringUtf8(256), DisplayInfo::kindKey,
                ByteBufCodecs.VAR_INT, DisplayInfo::size,
                ByteBufCodecs.VAR_INT, DisplayInfo::state,
                BlockPos.STREAM_CODEC, DisplayInfo::owner,
                ByteBufCodecs.stringUtf8(256), DisplayInfo::ownerName,
                ByteBufCodecs.stringUtf8(256), DisplayInfo::ownerKey,
                DisplayInfo::new);
    }

    /** What the user of a terminal sees; open false = they were let go. message: a translation key or "". */
    public record TerminalState(BlockPos pos, boolean open, int app, List<FileInfo> files, List<PrinterInfo> printers,
                                List<DisplayInfo> displays, String message) implements CustomPacketPayload {
        public static final Type<TerminalState> TYPE = typeOf("terminal_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalState> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalState::pos,
                ByteBufCodecs.BOOL, TerminalState::open,
                ByteBufCodecs.VAR_INT, TerminalState::app,
                FileInfo.CODEC.apply(ByteBufCodecs.list(TerminalBlockEntity.MAX_FILES)), TerminalState::files,
                PrinterInfo.CODEC.apply(ByteBufCodecs.list(64)), TerminalState::printers,
                DisplayInfo.CODEC.apply(ByteBufCodecs.list(64)), TerminalState::displays,
                ByteBufCodecs.stringUtf8(256), TerminalState::message,
                TerminalState::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A terminal file opened for reading or editing. */
    public record TerminalFileData(BlockPos pos, int id, ItemStack stack) implements CustomPacketPayload {
        public static final Type<TerminalFileData> TYPE = typeOf("terminal_file");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalFileData> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalFileData::pos,
                ByteBufCodecs.VAR_INT, TerminalFileData::id,
                ItemStack.STREAM_CODEC, TerminalFileData::stack,
                TerminalFileData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A new name for a printer ("" = back to the default). */
    public record TerminalRename(BlockPos pos, String name) implements CustomPacketPayload {
        public static final Type<TerminalRename> TYPE = typeOf("terminal_rename");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalRename> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalRename::pos,
                ByteBufCodecs.stringUtf8(256), TerminalRename::name,
                TerminalRename::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The right click on this terminal that follows is with Alt held (taking over a window left open). */
    public record TerminalAltClick(BlockPos pos) implements CustomPacketPayload {
        public static final Type<TerminalAltClick> TYPE = typeOf("terminal_alt_click");
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalAltClick> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalAltClick::pos,
                TerminalAltClick::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A new name for a display ("" = back to the default). */
    public record DisplayRename(int entity, String name) implements CustomPacketPayload {
        public static final Type<DisplayRename> TYPE = typeOf("display_rename");
        public static final StreamCodec<RegistryFriendlyByteBuf, DisplayRename> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DisplayRename::entity,
                ByteBufCodecs.stringUtf8(256), DisplayRename::name,
                DisplayRename::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PrinterRename(BlockPos pos, String name) implements CustomPacketPayload {
        public static final Type<PrinterRename> TYPE = typeOf("printer_rename");
        public static final StreamCodec<RegistryFriendlyByteBuf, PrinterRename> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PrinterRename::pos,
                ByteBufCodecs.stringUtf8(256), PrinterRename::name,
                PrinterRename::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A click on a printer, already worked out by the client ({@link PrinterServer} actions). */
    public record PrinterAction(BlockPos pos, int action) implements CustomPacketPayload {
        public static final Type<PrinterAction> TYPE = typeOf("printer_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, PrinterAction> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PrinterAction::pos,
                ByteBufCodecs.VAR_INT, PrinterAction::action,
                PrinterAction::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One piece of an edit; the pieces joined are {@code [kind][hand or entity id][content]}. */
    public record EditChunk(int editId, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<EditChunk> TYPE = typeOf("edit_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, EditChunk> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EditChunk::editId,
                ByteBufCodecs.VAR_INT, EditChunk::index,
                ByteBufCodecs.VAR_INT, EditChunk::total,
                ByteBufCodecs.byteArray(UPLOAD_CHUNK), EditChunk::data,
                EditChunk::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UploadChunk(int uploadId, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadChunk> TYPE = typeOf("upload_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunk> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, UploadChunk::uploadId,
                ByteBufCodecs.VAR_INT, UploadChunk::index,
                ByteBufCodecs.VAR_INT, UploadChunk::total,
                ByteBufCodecs.byteArray(UPLOAD_CHUNK), UploadChunk::data,
                UploadChunk::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The board editor was closed without saving. */
    public record BoardClose(int session) implements CustomPacketPayload {
        public static final Type<BoardClose> TYPE = typeOf("board_close");
        public static final StreamCodec<RegistryFriendlyByteBuf, BoardClose> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BoardClose::session, BoardClose::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestImage(String hash) implements CustomPacketPayload {
        public static final Type<RequestImage> TYPE = typeOf("request_image");
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestImage> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), RequestImage::hash, RequestImage::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ------------------------------------------------------------------ server to client

    /** hash is "" and error is a translation key when the upload was refused. */
    public record UploadResult(int uploadId, String hash, String error) implements CustomPacketPayload {
        public static final Type<UploadResult> TYPE = typeOf("upload_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadResult> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, UploadResult::uploadId,
                ByteBufCodecs.STRING_UTF8, UploadResult::hash,
                ByteBufCodecs.STRING_UTF8, UploadResult::error,
                UploadResult::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One piece of a board opened for editing: its drawing and its undo history. */
    public record BoardData(int session, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<BoardData> TYPE = typeOf("board_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, BoardData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BoardData::session,
                ByteBufCodecs.VAR_INT, BoardData::index,
                ByteBufCodecs.VAR_INT, BoardData::total,
                ByteBufCodecs.byteArray(DOWNLOAD_CHUNK), BoardData::data,
                BoardData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** total == 0 means the server does not have this image. */
    public record ImageData(String hash, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<ImageData> TYPE = typeOf("image_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageData> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), ImageData::hash,
                ByteBufCodecs.VAR_INT, ImageData::index,
                ByteBufCodecs.VAR_INT, ImageData::total,
                ByteBufCodecs.byteArray(DOWNLOAD_CHUNK), ImageData::data,
                ImageData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ------------------------------------------------------------------ edit encoding (shared)

    /** Edit targets from this number on are files in the terminal the player controls (number - this = file id). */
    public static final int TERMINAL_TARGET = 2;

    /** Encodes an edit of the item in {@code hand} (poster / newspaper / document). */
    public static byte[] encodeItemEdit(byte kind, InteractionHand hand, Object content) {
        return encodeItemEdit(kind, hand.ordinal(), content);
    }

    /** {@code target}: 0/1 = main / off hand, {@link #TERMINAL_TARGET} + id = a terminal file. */
    public static byte[] encodeItemEdit(byte kind, int target, Object content) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(kind);
        ByteBufCodecs.VAR_INT.encode(buf, target);
        switch (kind) {
            case EDIT_POSTER -> PaperContents.Poster.STREAM_CODEC.encode(buf, (PaperContents.Poster) content);
            case EDIT_NEWSPAPER -> PaperContents.Newspaper.STREAM_CODEC.encode(buf, (PaperContents.Newspaper) content);
            case EDIT_DOCUMENT -> PaperContents.Document.STREAM_CODEC.encode(buf, (PaperContents.Document) content);
            default -> throw new IllegalArgumentException("kind " + kind);
        }
        return toArray(buf);
    }

    public static byte[] encodeBoardEdit(int entityId, Canvas canvas, String name, int session, int[] undoTags, int[] redoTags) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(EDIT_BOARD);
        ByteBufCodecs.VAR_INT.encode(buf, entityId);
        Canvas.STREAM_CODEC.encode(buf, canvas);
        ByteBufCodecs.STRING_UTF8.encode(buf, name);
        ByteBufCodecs.VAR_INT.encode(buf, session);
        writeTags(buf, undoTags);
        writeTags(buf, redoTags);
        return toArray(buf);
    }

    private static void writeTags(ByteBuf buf, int[] tags) {
        ByteBufCodecs.VAR_INT.encode(buf, tags.length);
        for (int tag : tags) ByteBufCodecs.VAR_INT.encode(buf, tag);
    }

    private static int[] readTags(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        if (n < 0 || n > 64) throw new IllegalArgumentException("tags " + n);
        int[] tags = new int[n];
        for (int i = 0; i < n; i++) tags[i] = ByteBufCodecs.VAR_INT.decode(buf);
        return tags;
    }

    private static byte[] toArray(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    private record PendingEdit(int id, byte[][] parts) {}

    private static final Map<UUID, PendingEdit> EDITS = new HashMap<>();

    private static void receiveEdit(ServerPlayer player, EditChunk chunk) {
        int maxChunks = MAX_EDIT_BYTES / UPLOAD_CHUNK + 1;
        if (chunk.total() <= 0 || chunk.total() > maxChunks || chunk.index() < 0 || chunk.index() >= chunk.total()) return;
        PendingEdit pending = EDITS.get(player.getUUID());
        if (pending == null || pending.id() != chunk.editId() || pending.parts().length != chunk.total()) {
            pending = new PendingEdit(chunk.editId(), new byte[chunk.total()][]);
            EDITS.put(player.getUUID(), pending);
        }
        pending.parts()[chunk.index()] = chunk.data();
        for (byte[] part : pending.parts()) {
            if (part == null) return;
        }
        EDITS.remove(player.getUUID());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : pending.parts()) joined.writeBytes(part);
        ByteBuf buf = Unpooled.wrappedBuffer(joined.toByteArray());
        try {
            byte kind = buf.readByte();
            int target = ByteBufCodecs.VAR_INT.decode(buf);
            InteractionHand hand = target == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (kind != EDIT_BOARD && target >= TERMINAL_TARGET) {
                Object content = switch (kind) {
                    case EDIT_POSTER -> PaperContents.Poster.STREAM_CODEC.decode(buf);
                    case EDIT_NEWSPAPER -> PaperContents.Newspaper.STREAM_CODEC.decode(buf);
                    case EDIT_DOCUMENT -> PaperContents.Document.STREAM_CODEC.decode(buf);
                    default -> null;
                };
                if (content != null) TerminalServer.editFile(player, target - TERMINAL_TARGET, content);
                return;
            }
            switch (kind) {
                case EDIT_POSTER -> PaperServer.editPoster(player, hand, PaperContents.Poster.STREAM_CODEC.decode(buf));
                case EDIT_NEWSPAPER -> PaperServer.editNewspaper(player, hand, PaperContents.Newspaper.STREAM_CODEC.decode(buf));
                case EDIT_DOCUMENT -> PaperServer.editDocument(player, hand, PaperContents.Document.STREAM_CODEC.decode(buf));
                case EDIT_BOARD -> {
                    Canvas canvas = Canvas.STREAM_CODEC.decode(buf);
                    String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int session = ByteBufCodecs.VAR_INT.decode(buf);
                    PaperServer.editBoard(player, target, canvas, name, session, readTags(buf), readTags(buf));
                }
                default -> {}
            }
        } catch (RuntimeException e) {
            LOGGER.warn("EnPlotify: ignored a malformed edit from {}", player.getGameProfile().name(), e);
        } finally {
            buf.release();
        }
    }

    public static void forget(UUID player) {
        EDITS.remove(player);
        BoardSessions.forget(player);
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(PaperNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("13");
        registrar.playToServer(TerminalAltClick.TYPE, TerminalAltClick.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) TerminalServer.altClick(player, p.pos());
        }));
        registrar.playToServer(TerminalAudio.TYPE, TerminalAudio.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof net.minecraft.server.level.ServerPlayer player) TerminalServer.audio(player, p);
        }));
        registrar.playToClient(TerminalAudioData.TYPE, TerminalAudioData.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.TerminalAudio.onData(p.pos(), p.stream(), p.samples())));
        registrar.playToServer(TerminalWatch.TYPE, TerminalWatch.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) TerminalServer.watch(player, p.pos(), p.fps(), p.resolution());
        }));
        registrar.playToServer(TerminalShot.TYPE, TerminalShot.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) TerminalServer.shotPiece(player, p);
        }));
        registrar.playToClient(TerminalCaptureRate.TYPE, TerminalCaptureRate.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.TerminalShots.onCaptureRate(p.pos(), p.fps(), p.resolution())));
        registrar.playToClient(TerminalShotData.TYPE, TerminalShotData.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.TerminalShots.onShotPiece(p)));
        registrar.playToServer(TerminalOp.TYPE, TerminalOp.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) TerminalServer.handle(player, p);
        }));
        registrar.playToClient(TerminalState.TYPE, TerminalState.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.TerminalView.onState(p)));
        registrar.playToClient(TerminalFileData.TYPE, TerminalFileData.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.TerminalView.onFile(p)));
        registrar.playToServer(PrinterAction.TYPE, PrinterAction.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) PrinterServer.handle(player, p.pos(), p.action());
        }));
        registrar.playToServer(TerminalRename.TYPE, TerminalRename.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player && !player.isSpectator() && player.isWithinBlockInteractionRange(p.pos(), 4.0)
                    && player.level().getBlockEntity(p.pos()) instanceof TerminalBlockEntity terminal) {
                terminal.rename(p.name());
            }
        }));
        registrar.playToServer(DisplayRename.TYPE, DisplayRename.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player && !player.isSpectator()
                    && player.level().getEntity(p.entity()) instanceof ScreenLink display
                    && player.distanceToSqr(((net.minecraft.world.entity.Entity) display).position()) < 12.0 * 12.0) {
                display.rename(p.name());
            }
        }));
        registrar.playToServer(PrinterRename.TYPE, PrinterRename.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) PrinterServer.rename(player, p.pos(), p.name());
        }));
        registrar.playToServer(PlaceWall.TYPE, PlaceWall.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) WallPlacement.place(player, p.hand(), p.clicked(), p.face(), p.width(), p.height());
        }));
        registrar.playToServer(EditChunk.TYPE, EditChunk.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) receiveEdit(player, p);
        }));
        registrar.playToServer(UploadChunk.TYPE, UploadChunk.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) ImageStore.receiveChunk(player, p);
        }));
        registrar.playToServer(BoardClose.TYPE, BoardClose.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) BoardSessions.close(player, p.session());
        }));
        registrar.playToClient(BoardData.TYPE, BoardData.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.onBoardData(p.session(), p.index(), p.total(), p.data())));
        registrar.playToServer(RequestImage.TYPE, RequestImage.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) ImageStore.sendImage(player, p.hash());
        }));
        registrar.playToClient(UploadResult.TYPE, UploadResult.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.PaperImages.onUploadResult(p.uploadId(), p.hash(), p.error())));
        registrar.playToClient(ImageData.TYPE, ImageData.CODEC, (p, c) -> c.enqueueWork(
                () -> io.github.lwarqiu1shz.enplotify.client.paper.PaperImages.onImageData(p.hash(), p.index(), p.total(), p.data())));
    }

    private PaperNetwork() {}
}
