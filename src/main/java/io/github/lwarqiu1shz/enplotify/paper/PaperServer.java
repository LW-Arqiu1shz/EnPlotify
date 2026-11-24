package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Server-side checks for edits sent by clients: right item, still editable, sane contents. */
final class PaperServer {
    private static String author(ServerPlayer player) {
        return PaperContents.clip(player.getGameProfile().name(), PaperContents.MAX_AUTHOR);
    }

    static void editPoster(ServerPlayer player, InteractionHand hand, PaperContents.Poster edit) {
        applyPoster(player, player.getItemInHand(hand), edit);
    }

    /** Puts an edit into {@code held} if it is an editable poster; false if not. */
    static boolean applyPoster(ServerPlayer player, ItemStack held, PaperContents.Poster edit) {
        if (!(held.getItem() instanceof PosterItem poster) || !poster.isEditable(held)) return false;
        // The size is chosen in the editor (and may change later, the content keeps its place).
        int w = Mth.clamp(edit.width(), 1, PaperContents.MAX_CELLS);
        int h = Mth.clamp(edit.height(), 1, PaperContents.MAX_CELLS);
        held.set(PaperRegistry.POSTER_CONTENT.get(), new PaperContents.Poster(
                edit.canvas().sanitize(false), w, h, w, h,
                edit.locked(),
                edit.locked() ? author(player) : "",
                PaperContents.clip(edit.name(), PaperContents.MAX_NAME)));
        return true;
    }

    static void editNewspaper(ServerPlayer player, InteractionHand hand, PaperContents.Newspaper edit) {
        applyNewspaper(player, player.getItemInHand(hand), edit);
    }

    static boolean applyNewspaper(ServerPlayer player, ItemStack held, PaperContents.Newspaper edit) {
        if (!(held.getItem() instanceof NewspaperItem paper) || !paper.isEditable(held)) return false;
        List<Canvas> halves = new ArrayList<>();
        for (Canvas half : edit.halves()) halves.add(half.sanitize(false));
        held.set(PaperRegistry.NEWSPAPER_CONTENT.get(), new PaperContents.Newspaper(
                PaperContents.clip(edit.title(), PaperContents.MAX_TITLE), halves,
                edit.locked(), edit.locked() ? author(player) : ""));
        return true;
    }

    static void editDocument(ServerPlayer player, InteractionHand hand, PaperContents.Document edit) {
        applyDocument(player, player.getItemInHand(hand), edit);
    }

    static boolean applyDocument(ServerPlayer player, ItemStack held, PaperContents.Document edit) {
        if (!(held.getItem() instanceof DocumentItem paper) || !paper.isEditable(held)) return false;
        List<Canvas> pages = new ArrayList<>();
        for (Canvas page : edit.pages()) {
            if (pages.size() >= PaperContents.MAX_PAGES) break;
            pages.add(page.sanitize(false));
        }
        held.set(PaperRegistry.DOCUMENT_CONTENT.get(), new PaperContents.Document(
                PaperContents.clip(edit.title(), PaperContents.MAX_TITLE), pages,
                edit.locked(), edit.locked() ? author(player) : ""));
        return true;
    }

    static void editBoard(ServerPlayer player, int entityId, Canvas canvas, String name, int session, int[] undoTags, int[] redoTags) {
        Entity entity = player.level().getEntity(entityId);
        if (!(entity instanceof BoardEntity board) || board.isRemoved()) return;
        if (player.distanceToSqr(board) > 100.0) return;
        if (!board.acceptsTool(player.getMainHandItem()) && !board.acceptsTool(player.getOffhandItem())) return;
        BoardSessions.save(player, board, canvas.sanitize(true), PaperContents.clip(name, PaperContents.MAX_NAME), session, undoTags, redoTags);
    }

    private PaperServer() {}
}
