package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.BoardEntity;
import io.github.lwarqiu1shz.enplotify.paper.BoardItem;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import io.github.lwarqiu1shz.enplotify.paper.DocumentItem;
import io.github.lwarqiu1shz.enplotify.paper.NewspaperItem;
import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import io.github.lwarqiu1shz.enplotify.paper.PaperRegistry;
import io.github.lwarqiu1shz.enplotify.paper.PosterItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.Arrays;
import java.util.List;

/** Client entry points called from items / entities (only ever on the client side). */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class PaperClientHooks {
    private static final int INK = 0x2A2522;
    private static int nextEditId = 1;

    private static ItemStack held(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemInHand(hand);
    }

    private static void open(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    /** Sends an edit in pieces small enough for client-to-server packets. */
    static void sendEdit(byte[] data) {
        int id = nextEditId++;
        int total = Math.max(1, (data.length + PaperNetwork.UPLOAD_CHUNK - 1) / PaperNetwork.UPLOAD_CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * PaperNetwork.UPLOAD_CHUNK;
            byte[] part = Arrays.copyOfRange(data, from, Math.min(data.length, from + PaperNetwork.UPLOAD_CHUNK));
            ClientPacketDistributor.sendToServer(new PaperNetwork.EditChunk(id, i, total, part));
        }
    }

    // ------------------------------------------------------------------ posters

    public static void openPoster(InteractionHand hand) {
        openPoster(held(hand), hand.ordinal());
    }

    /** {@code target}: what the edit is sent for ({@link PaperNetwork#encodeItemEdit(byte, int, Object)}). */
    static void openPoster(ItemStack stack, int target) {
        if (!(stack.getItem() instanceof PosterItem poster)) return;
        PaperContents.Poster content = PosterItem.content(stack);
        if (!poster.isEditable(stack)) {
            int[] size = poster.size(stack);
            int w = size[0] > 0 ? size[0] : content.previewW();
            int h = size[0] > 0 ? size[1] : content.previewH();
            open(CanvasReaderScreen.poster(PaperViews.poster(stack), w, h));
            return;
        }
        CanvasEditorScreen.Config config = new CanvasEditorScreen.Config();
        config.title = Component.translatable("enplotify.paper.poster.edit");
        config.surfaces.add(EditCanvas.of(content.canvas()));
        config.poster = true;
        if (content.sized()) {
            config.posterW = content.width();
            config.posterH = content.height();
        } else {
            config.posterW = Math.max(1, content.previewW());
            config.posterH = Math.max(1, content.previewH());
            // A new poster gets its size first; one with content (from an older version) keeps it.
            config.posterNeedsSize = content.canvas().isEmpty();
        }
        config.paper = PaperViews.POSTER_PAPER;
        config.textColor = INK;
        config.screenPx = true;
        config.textPx = 8;
        config.imageMaxSide = 1024;
        config.animations = true;
        config.itemTitle = content.name();
        config.titleHint = "enplotify.paper.name";
        config.saver = (surfaces, title, w, h, lock) -> sendEdit(PaperNetwork.encodeItemEdit(PaperNetwork.EDIT_POSTER, target,
                new PaperContents.Poster(surfaces.get(0), w, h, w, h, lock, "", title)));
        open(new CanvasEditorScreen(config));
    }

    // ------------------------------------------------------------------ newspapers and documents

    public static void openNewspaper(InteractionHand hand) {
        openNewspaper(held(hand), hand.ordinal());
    }

    /** {@code target}: what the edit is sent for ({@link PaperNetwork#encodeItemEdit(byte, int, Object)}). */
    static void openNewspaper(ItemStack stack, int target) {
        if (!(stack.getItem() instanceof NewspaperItem paper)) return;
        if (!paper.isEditable(stack)) {
            open(CanvasReaderScreen.newspaper(PaperViews.newspaper(stack)));
            return;
        }
        PaperContents.Newspaper content = NewspaperItem.content(stack);
        CanvasEditorScreen.Config config = new CanvasEditorScreen.Config();
        config.title = Component.translatable("enplotify.paper.newspaper.edit");
        // Each side of the sheet is edited as one spread: its left and right halves side by side.
        config.surfaces.add(Spreads.merge(content.halves().get(0), content.halves().get(1)));
        config.surfaces.add(Spreads.merge(content.halves().get(2), content.halves().get(3)));
        config.surfaceNames = List.of(Component.translatable("enplotify.paper.side_front"), Component.translatable("enplotify.paper.side_back"));
        config.aspect = 2.0F * PaperViews.HALF_ASPECT;
        config.heightPx = 420.0F / 594.0F * CanvasEditorScreen.PX_PER_BLOCK;
        config.fold = true;
        config.grid = Spreads.spreadGrid();
        config.paper = PaperViews.NEWSPRINT;
        config.textColor = INK;
        config.screenPx = true;
        config.textPx = 8;
        config.itemTitle = content.title();
        config.saver = (surfaces, title, w, h, lock) -> {
            List<Canvas> halves = new java.util.ArrayList<>();
            for (Canvas side : surfaces) halves.addAll(Spreads.split(side));
            sendEdit(PaperNetwork.encodeItemEdit(PaperNetwork.EDIT_NEWSPAPER, target, new PaperContents.Newspaper(title, halves, lock, "")));
        };
        open(new CanvasEditorScreen(config));
    }

    public static void openDocument(InteractionHand hand) {
        openDocument(held(hand), hand.ordinal());
    }

    /** {@code target}: what the edit is sent for ({@link PaperNetwork#encodeItemEdit(byte, int, Object)}). */
    static void openDocument(ItemStack stack, int target) {
        if (!(stack.getItem() instanceof DocumentItem paper)) return;
        if (!paper.isEditable(stack)) {
            open(CanvasReaderScreen.document(PaperViews.document(stack)));
            return;
        }
        PaperContents.Document content = DocumentItem.content(stack);
        CanvasEditorScreen.Config config = new CanvasEditorScreen.Config();
        config.title = Component.translatable("enplotify.paper.document.edit");
        for (Canvas page : content.pages()) config.surfaces.add(EditCanvas.of(page));
        config.pages = true;
        config.aspect = PaperViews.PAGE_ASPECT;
        config.heightPx = 420.0F / 594.0F * CanvasEditorScreen.PX_PER_BLOCK;
        config.grid = Spreads.pageGrid();
        config.paper = PaperViews.DOCUMENT_PAPER;
        config.textColor = INK;
        config.screenPx = true;
        config.textPx = 8;
        config.itemTitle = content.title();
        config.saver = (surfaces, title, w, h, lock) -> sendEdit(PaperNetwork.encodeItemEdit(PaperNetwork.EDIT_DOCUMENT, target,
                new PaperContents.Document(title, surfaces, lock, "")));
        open(new CanvasEditorScreen(config));
    }

    /** Opens a terminal file: the editor if it can still be edited, else the reader. */
    static void openTerminalFile(ItemStack stack, int id) {
        int target = PaperNetwork.TERMINAL_TARGET + id;
        if (stack.getItem() instanceof PosterItem) openPoster(stack, target);
        else if (stack.getItem() instanceof NewspaperItem) openNewspaper(stack, target);
        else if (stack.getItem() instanceof DocumentItem) openDocument(stack, target);
    }

    // ------------------------------------------------------------------ boards

    private static final java.util.Map<Integer, byte[][]> BOARD_PARTS = new java.util.HashMap<>();

    /** A piece of a board the server opened for editing; once all are here the editor opens. */
    public static void onBoardData(int session, int index, int total, byte[] data) {
        if (total <= 0 || index < 0 || index >= total) return;
        byte[][] parts = BOARD_PARTS.get(session);
        if (parts == null || parts.length != total) {
            parts = new byte[total][];
            BOARD_PARTS.put(session, parts);
        }
        parts[index] = data;
        for (byte[] part : parts) {
            if (part == null) return;
        }
        BOARD_PARTS.remove(session);
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) joined.writeBytes(part);
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(joined.toByteArray());
        try {
            var varInt = net.minecraft.network.codec.ByteBufCodecs.VAR_INT;
            int entityId = varInt.decode(buf);
            varInt.decode(buf); // session, already known
            CanvasEditorScreen.Saved current = readSaved(buf);
            List<CanvasEditorScreen.Saved> undo = new java.util.ArrayList<>();
            for (int i = varInt.decode(buf); i > 0; i--) undo.add(readSaved(buf));
            List<CanvasEditorScreen.Saved> redo = new java.util.ArrayList<>();
            for (int i = varInt.decode(buf); i > 0; i--) redo.add(readSaved(buf));
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.level.getEntity(entityId) instanceof BoardEntity board) {
                editBoard(board, session, current, undo, redo);
                return;
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("EnPlotify").warn("EnPlotify: could not open a board", e);
        } finally {
            buf.release();
        }
        ClientPacketDistributor.sendToServer(new PaperNetwork.BoardClose(session));
    }

    private static CanvasEditorScreen.Saved readSaved(io.netty.buffer.ByteBuf buf) {
        Canvas canvas = Canvas.STREAM_CODEC.decode(buf);
        return new CanvasEditorScreen.Saved(canvas, net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.decode(buf));
    }

    private static void editBoard(BoardEntity board, int session, CanvasEditorScreen.Saved current,
                                  List<CanvasEditorScreen.Saved> undo, List<CanvasEditorScreen.Saved> redo) {
        CanvasEditorScreen.Config config = new CanvasEditorScreen.Config();
        boolean black = board.style() == BoardItem.Style.BLACKBOARD;
        config.title = Component.translatable(black ? "enplotify.paper.board.edit_blackboard" : "enplotify.paper.board.edit_whiteboard");
        config.surfaces.add(EditCanvas.of(current.canvas()));
        config.sharedHistory = true;
        config.undoVersions = undo;
        config.redoVersions = redo;
        config.aspect = board.cellsWide() / (float) board.cellsHigh();
        config.paper = black ? PaperViews.BLACKBOARD : PaperViews.WHITEBOARD;
        config.board = true;
        if (board instanceof io.github.lwarqiu1shz.enplotify.paper.SawmoBoardEntity sawmo) {
            config.backdrop = (graphics, x, y, w, h) -> WallRenderers.drawScreenGui(sawmo, graphics, x, y, w, h);
        }
        config.quickColors = board.style().colors();
        config.textColor = config.quickColors[0];
        config.heightPx = board.cellsHigh() * CanvasEditorScreen.PX_PER_BLOCK;
        config.textPx = 12;
        config.imageMaxSide = 1024;
        config.canFinalize = false;
        config.itemTitle = current.name();
        config.titleHint = "enplotify.paper.name";
        int entityId = board.getId();
        config.saver = (surfaces, title, w, h, lock) -> sendEdit(PaperNetwork.encodeBoardEdit(entityId, surfaces.get(0), title,
                session, config.undoTags, config.redoTags));
        config.onCancelled = () -> ClientPacketDistributor.sendToServer(new PaperNetwork.BoardClose(session));
        open(new CanvasEditorScreen(config));
    }

    // ------------------------------------------------------------------ placing and reading

    public static void openSizePicker(InteractionHand hand, BlockPos clicked, Direction face, boolean board) {
        ItemStack stack = held(hand);
        int w = 1, h = 1;
        if (stack.getItem() instanceof PosterItem) {
            PaperContents.Poster content = PosterItem.content(stack);
            w = content.previewW();
            h = content.previewH();
        }
        open(new SizePickerScreen(hand, clicked, face, board, w, h));
    }

    public static void openDisplaySizePicker(InteractionHand hand, BlockPos clicked, Direction face) {
        open(new SizePickerScreen(hand, clicked, face, net.minecraft.network.chat.Component.translatable("enplotify.paper.size.title_display")));
    }

    public static void viewPoster(ItemStack stack) {
        if (!(stack.getItem() instanceof PosterItem poster)) return;
        int[] size = poster.size(stack);
        open(CanvasReaderScreen.poster(PaperViews.poster(stack), Math.max(1, size[0]), Math.max(1, size[1])));
    }

    /** Reads the top sheet of a pile (read-only, whatever its state). */
    public static void readSheet(ItemStack stack) {
        if (stack.getItem() instanceof NewspaperItem) open(CanvasReaderScreen.newspaper(PaperViews.newspaper(stack)));
        else if (stack.getItem() instanceof DocumentItem) open(CanvasReaderScreen.document(PaperViews.document(stack)));
    }

    @SubscribeEvent
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        PaperAnimations.onFrame();
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PaperRegistry.POSTER_ENTITY.get(), WallRenderers.PosterRenderer::new);
        event.registerEntityRenderer(PaperRegistry.BOARD_ENTITY.get(), WallRenderers.BoardRenderer::new);
        event.registerEntityRenderer(PaperRegistry.DISPLAY_ENTITY.get(), WallRenderers.DisplayRenderer::new);
        event.registerEntityRenderer(PaperRegistry.SAWMO_ENTITY.get(), WallRenderers.SawmoRenderer::new);
        event.registerEntityRenderer(PaperRegistry.PAPER_STACK_ENTITY.get(), PaperStackRenderer::new);
        event.registerBlockEntityRenderer(PaperRegistry.PRINTER_ENTITY.get(), PrinterRenderer::new);
        event.registerBlockEntityRenderer(PaperRegistry.TERMINAL_ENTITY.get(), TerminalRenderer::new);
        event.registerEntityRenderer(PaperRegistry.GHOST_ITEM_FRAME_ENTITY.get(), io.github.lwarqiu1shz.enplotify.client.GhostItemFrameRenderer::new);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PaperImages.onLogout();
        PaperViews.onLogout();
        CanvasTextures.clear();
        PaperAnimations.clear();
        BOARD_PARTS.clear();
    }

    private PaperClientHooks() {}
}
