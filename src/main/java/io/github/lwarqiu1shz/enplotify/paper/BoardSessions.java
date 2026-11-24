package io.github.lwarqiu1shz.enplotify.paper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is editing which board, and each board's undo history. The history belongs to the board,
 * not to a player: anyone who opens it can undo what others saved. Only one player edits a board
 * at a time. Kept in memory (lost when the server stops); the newest boards keep theirs.
 */
final class BoardSessions {
    /** Saved versions kept per board, each way. */
    private static final int MAX_VERSIONS = 16;
    /** Bytes of saved versions kept per board, so the editor opens quickly. */
    private static final int MAX_BYTES = 4 << 20;
    private static final int MAX_BOARDS = 32;

    /** One saved state: the encoded canvas and name. */
    private record Version(byte[] data) {}

    private static final class History {
        /** Oldest first. */
        final List<Version> undo = new ArrayList<>();
        /** Next first. */
        final List<Version> redo = new ArrayList<>();
    }

    private record Lock(UUID player, int session) {}

    private static final Map<UUID, History> HISTORY = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, History> eldest) {
            return size() > MAX_BOARDS;
        }
    };
    private static final Map<UUID, Lock> LOCKS = new java.util.HashMap<>();
    private static int nextSession = 1;

    private static Version version(Canvas canvas, String name) {
        ByteBuf buf = Unpooled.buffer();
        Canvas.STREAM_CODEC.encode(buf, canvas);
        ByteBufCodecs.STRING_UTF8.encode(buf, name);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return new Version(out);
    }

    /** The player editing the board, if they still are (online, nearby). */
    private static ServerPlayer editor(BoardEntity board) {
        Lock lock = LOCKS.get(board.getUUID());
        if (lock == null || board.level().getServer() == null) return null;
        ServerPlayer player = board.level().getServer().getPlayerList().getPlayer(lock.player());
        if (player == null || player.isRemoved() || player.level() != board.level() || player.distanceToSqr(board) > 144.0) {
            LOCKS.remove(board.getUUID());
            return null;
        }
        return player;
    }

    /** A player wants to edit the board: open the editor for them, or say who is busy with it. */
    static void open(ServerPlayer player, BoardEntity board) {
        ServerPlayer other = editor(board);
        if (other != null && other != player) {
            ActionBar.show(player, Component.translatable("message.enplotify.board_busy", other.getDisplayName()));
            return;
        }
        int session = nextSession++;
        LOCKS.put(board.getUUID(), new Lock(player.getUUID(), session));
        History history = HISTORY.get(board.getUUID());

        ByteBuf buf = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(buf, board.getId());
        ByteBufCodecs.VAR_INT.encode(buf, session);
        buf.writeBytes(version(board.canvas(), board.name()).data());
        List<Version> undo = history == null ? List.of() : history.undo;
        List<Version> redo = history == null ? List.of() : history.redo;
        ByteBufCodecs.VAR_INT.encode(buf, undo.size());
        for (Version v : undo) buf.writeBytes(v.data());
        ByteBufCodecs.VAR_INT.encode(buf, redo.size());
        for (Version v : redo) buf.writeBytes(v.data());
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();

        int total = Math.max(1, (data.length + PaperNetwork.DOWNLOAD_CHUNK - 1) / PaperNetwork.DOWNLOAD_CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * PaperNetwork.DOWNLOAD_CHUNK;
            PacketDistributor.sendToPlayer(player, new PaperNetwork.BoardData(session, i, total,
                    Arrays.copyOfRange(data, from, Math.min(data.length, from + PaperNetwork.DOWNLOAD_CHUNK))));
        }
    }

    /** The editor was closed without saving. */
    static void close(ServerPlayer player, int session) {
        LOCKS.values().removeIf(lock -> lock.player().equals(player.getUUID()) && lock.session() == session);
    }

    /**
     * A saved edit. {@code undoTags} / {@code redoTags} say which of the versions the editor was
     * opened with (0.. old undo, then the state it opened with, then old redo) are still there
     * to go back / forward to, in order.
     */
    static void save(ServerPlayer player, BoardEntity board, Canvas canvas, String name, int session, int[] undoTags, int[] redoTags) {
        Lock lock = LOCKS.get(board.getUUID());
        if (lock != null && !lock.player().equals(player.getUUID()) && editor(board) != null) return; // someone else has it
        History history = HISTORY.computeIfAbsent(board.getUUID(), k -> new History());
        Version before = version(board.canvas(), board.name());
        if (lock != null && lock.player().equals(player.getUUID()) && lock.session() == session) {
            List<Version> all = new ArrayList<>(history.undo);
            all.add(before);
            all.addAll(history.redo);
            List<Version> undo = pick(all, undoTags);
            List<Version> redo = pick(all, redoTags);
            history.undo.clear();
            history.undo.addAll(undo);
            history.redo.clear();
            history.redo.addAll(redo);
        } else {
            // Not opened the usual way: just remember what was there.
            history.undo.add(before);
            history.redo.clear();
        }
        LOCKS.remove(board.getUUID());
        trim(history);
        board.setCanvas(canvas, name);
    }

    private static List<Version> pick(List<Version> all, int[] tags) {
        List<Version> out = new ArrayList<>();
        boolean[] used = new boolean[all.size()];
        for (int tag : tags) {
            if (tag < 0 || tag >= all.size() || used[tag]) continue;
            used[tag] = true;
            out.add(all.get(tag));
        }
        return out;
    }

    private static void trim(History history) {
        while (history.undo.size() > MAX_VERSIONS) history.undo.remove(0);
        while (history.redo.size() > MAX_VERSIONS) history.redo.remove(history.redo.size() - 1);
        long bytes = 0;
        for (Version v : history.undo) bytes += v.data().length;
        for (Version v : history.redo) bytes += v.data().length;
        while (bytes > MAX_BYTES && !(history.undo.isEmpty() && history.redo.isEmpty())) {
            Version dropped = history.redo.size() > history.undo.size()
                    ? history.redo.remove(history.redo.size() - 1)
                    : history.undo.remove(0);
            bytes -= dropped.data().length;
        }
    }

    static void forget(UUID player) {
        LOCKS.values().removeIf(lock -> lock.player().equals(player));
    }

    private BoardSessions() {}
}
