package io.github.lwarqiu1shz.enplotify.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of terminals: who uses which terminal, and what they ask it to do. Everything a
 * client asks is checked against the terminal that player is using.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class TerminalServer {
    public static final int OP_CLOSE = 0, OP_APP = 1, OP_NEW = 2, OP_IMPORT = 3, OP_DELETE = 4, OP_RENAME = 5,
            OP_PRINT = 6, OP_OPEN_FILE = 7, OP_REFRESH = 8, OP_MOVE_FILE = 9, OP_MOVE_PRINTER = 10, OP_CHOOSE_PRINTER = 11,
            OP_MOVE_DISPLAY = 12, OP_TOGGLE_DISPLAY = 13;
    public static final int MAX_COPIES = 16;

    /** Which terminal each player is using. */
    private static final Map<UUID, BlockPos> USING = new HashMap<>();

    /** Right click on a terminal. */
    static void control(ServerPlayer player, TerminalBlockEntity terminal) {
        if (!terminal.getBlockState().getValue(TerminalBlock.KEYBOARD)) {
            ActionBar.show(player, Component.translatable("message.enplotify.terminal.no_keyboard"));
            return;
        }
        if (terminal.crashed()) {
            ActionBar.show(player, terminal.crashMessage());
            return;
        }
        if (terminal.power() != TerminalBlockEntity.POWER_ON) {
            ActionBar.show(player, Component.translatable(terminal.power() == TerminalBlockEntity.POWER_OFF
                    ? "message.enplotify.terminal.off" : "message.enplotify.terminal.starting"));
            return;
        }
        UUID user = terminal.controller();
        if (user != null && !user.equals(player.getUUID())) {
            ActionBar.show(player, Component.translatable("message.enplotify.terminal.in_use", terminal.userName()));
            return;
        }
        // Someone else left a window open on it (a page may still be playing): only Alt + right click
        // takes it over, so nobody ends someone's work by a stray click.
        boolean forced = takeOver(player, terminal.getBlockPos());
        if (user == null && terminal.app() != TerminalBlockEntity.APP_DESKTOP && terminal.lastUser != null
                && !terminal.lastUser.equals(player.getUUID()) && !forced) {
            ActionBar.show(player, Component.translatable("message.enplotify.terminal.working"));
            return;
        }
        // Leave any other terminal first.
        BlockPos other = USING.get(player.getUUID());
        if (other != null && !other.equals(terminal.getBlockPos())
                && player.level().getBlockEntity(other) instanceof TerminalBlockEntity old) {
            old.release();
        }
        terminal.take(player);
        USING.put(player.getUUID(), terminal.getBlockPos().immutable());
        send(player, terminal, true, "");
    }

    /** Players who right clicked a terminal holding Alt, just now (where, and the game time). */
    private static final Map<UUID, Object[]> TAKE_OVER = new HashMap<>();

    /** The client says: this right click on a terminal is with Alt held. */
    static void altClick(ServerPlayer player, BlockPos pos) {
        TAKE_OVER.put(player.getUUID(), new Object[] {pos.immutable(), player.level().getGameTime()});
    }

    /** Whether this player's right click on {@code pos} came with Alt (used up by asking). */
    private static boolean takeOver(ServerPlayer player, BlockPos pos) {
        Object[] alt = TAKE_OVER.remove(player.getUUID());
        return alt != null && pos.equals(alt[0]) && player.level().getGameTime() - (long) alt[1] <= 5;
    }

    /** Called by the terminal when its user is let go. */
    static void released(TerminalBlockEntity terminal, UUID player) {
        BlockPos pos = USING.get(player);
        if (pos == null || !pos.equals(terminal.getBlockPos())) return;
        USING.remove(player);
        if (terminal.getLevel() instanceof ServerLevel level) {
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
            if (online != null) {
                PacketDistributor.sendToPlayer(online, new PaperNetwork.TerminalState(terminal.getBlockPos(), false,
                        terminal.app(), List.of(), List.of(), List.of(), ""));
            }
        }
    }

    private static @Nullable TerminalBlockEntity using(ServerPlayer player) {
        BlockPos pos = USING.get(player.getUUID());
        if (pos == null || !(player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) return null;
        return player.getUUID().equals(terminal.controller()) ? terminal : null;
    }

    static void handle(ServerPlayer player, PaperNetwork.TerminalOp op) {
        TerminalBlockEntity terminal = using(player);
        if (terminal == null || !terminal.getBlockPos().equals(op.pos())) return;
        String message = "";
        switch (op.op()) {
            case OP_CLOSE -> {
                terminal.release();
                return;
            }
            case OP_APP -> {
                terminal.setApp(Math.clamp(op.a(), TerminalBlockEntity.APP_DESKTOP, TerminalBlockEntity.APP_BROWSER));
                return;
            }
            case OP_NEW -> {
                ItemStack blank = switch (op.a()) {
                    case PrinterBlockEntity.NEWSPAPER -> new ItemStack(PaperRegistry.NEWSPAPER.get());
                    case PrinterBlockEntity.POSTER -> new ItemStack(PaperRegistry.POSTER.get());
                    default -> new ItemStack(PaperRegistry.DOCUMENT.get());
                };
                message = terminal.addFile(op.text(), blank) ? "" : "message.enplotify.terminal.full";
            }
            case OP_IMPORT -> {
                ItemStack held = player.getMainHandItem();
                int kind = PrinterBlockEntity.kindOf(held);
                if (kind == PrinterBlockEntity.NONE || kind == PrinterBlockEntity.BOARD) {
                    message = "message.enplotify.terminal.cannot_import";
                } else {
                    message = terminal.addFile(op.text(), held) ? "message.enplotify.terminal.imported" : "message.enplotify.terminal.full";
                }
            }
            case OP_DELETE -> terminal.removeFile(op.a());
            case OP_RENAME -> {
                TerminalFile file = terminal.file(op.a());
                String name = net.minecraft.util.StringUtil.filterText(op.text()).strip();
                if (file != null && !name.isEmpty()) terminal.replaceFile(file.id(), name, file.stack());
            }
            case OP_PRINT -> message = print(player, terminal, op);
            case OP_OPEN_FILE -> {
                TerminalFile file = terminal.file(op.a());
                if (file != null) {
                    PacketDistributor.sendToPlayer(player, new PaperNetwork.TerminalFileData(terminal.getBlockPos(), file.id(), file.stack()));
                }
                return;
            }
            case OP_REFRESH -> {}
            case OP_MOVE_FILE -> terminal.moveFile(op.a(), op.b());
            case OP_MOVE_PRINTER -> {
                // The printer at op.other() goes to index op.b() of the list as the user sees it.
                List<BlockPos> order = new ArrayList<>();
                for (PrinterBlockEntity p : printersInReach(terminal)) order.add(p.getBlockPos());
                if (order.remove(op.other())) {
                    order.add(Math.clamp(op.b(), 0, order.size()), op.other().immutable());
                    terminal.setPrinterOrder(order);
                }
            }
            // a = 1: the chosen one clicked again, no printer is chosen any more.
            case OP_CHOOSE_PRINTER -> terminal.choosePrinter(op.a() == 1 ? null : op.other());
            case OP_MOVE_DISPLAY -> {
                UUID id = uuid(op.text());
                List<UUID> order = new ArrayList<>();
                for (SizedHangingEntity d : displaysInReach(terminal)) order.add(d.getUUID());
                if (id != null && order.remove(id)) {
                    order.add(Math.clamp(op.b(), 0, order.size()), id);
                    terminal.setDisplayOrder(order);
                }
            }
            case OP_TOGGLE_DISPLAY -> message = toggleDisplay(terminal, uuid(op.text()));
            default -> {
                return;
            }
        }
        send(player, terminal, true, message);
    }

    private static String print(ServerPlayer player, TerminalBlockEntity terminal, PaperNetwork.TerminalOp op) {
        TerminalFile file = terminal.file(op.a());
        if (file == null) return "";
        BlockPos target = op.other();
        PrinterBlockEntity printer = null;
        for (PrinterBlockEntity p : PrinterBlockEntity.loaded(terminal.getLevel())) {
            if (p.getBlockPos().equals(target) && !p.isRemoved()) printer = p;
        }
        if (printer == null) return "message.enplotify.terminal.printer_gone";
        int count = Math.clamp(op.b(), 1, MAX_COPIES);
        if (!printer.queue(file.stack(), count)) return "message.enplotify.terminal.queue_full";
        int kind = PrinterBlockEntity.kindOf(file.stack());
        return printer.paperKindNow() == kind ? "message.enplotify.terminal.sent" : "message.enplotify.terminal.sent_no_paper";
    }

    private static @Nullable UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Connects a display to this terminal, or lets it go if it already was. */
    private static String toggleDisplay(TerminalBlockEntity terminal, @Nullable UUID id) {
        if (id == null || !(terminal.getLevel() instanceof ServerLevel level)) return "";
        if (!(level.getEntity(id) instanceof ScreenLink display) || !((Entity) display).isAlive()) return "message.enplotify.terminal.display_gone";
        BlockPos here = terminal.getBlockPos();
        BlockPos owner = display.terminal();
        if (here.equals(owner)) {
            display.connect(null);
            return "";
        }
        if (owner != null && stillThere(level, owner)) return "message.enplotify.terminal.display_taken";
        display.connect(here);
        return "";
    }

    /** A terminal is (as far as can be told) still at {@code pos}: unloaded ones count as there. */
    private static boolean stillThere(ServerLevel level, BlockPos pos) {
        return !level.isLoaded(pos) || level.getBlockEntity(pos) instanceof TerminalBlockEntity;
    }

    /** An edit made in the editor opened from a terminal file. */
    static void editFile(ServerPlayer player, int id, Object content) {
        TerminalBlockEntity terminal = using(player);
        if (terminal == null) return;
        TerminalFile file = terminal.file(id);
        if (file == null) return;
        ItemStack stack = file.stack().copy();
        boolean ok = switch (content) {
            case PaperContents.Poster poster -> PaperServer.applyPoster(player, stack, poster);
            case PaperContents.Newspaper paper -> PaperServer.applyNewspaper(player, stack, paper);
            case PaperContents.Document doc -> PaperServer.applyDocument(player, stack, doc);
            default -> false;
        };
        if (!ok) return;
        terminal.replaceFile(id, file.name(), stack);
        send(player, terminal, true, "message.enplotify.terminal.saved");
    }

    /** Sends the file list and the printers in reach. */
    private static void send(ServerPlayer player, TerminalBlockEntity terminal, boolean open, String message) {
        List<PaperNetwork.FileInfo> files = new ArrayList<>();
        for (TerminalFile f : terminal.files()) {
            boolean editable = f.stack().getItem() instanceof SheetItem sheet && sheet.isEditable(f.stack());
            files.add(new PaperNetwork.FileInfo(f.id(), f.name(), PrinterBlockEntity.kindOf(f.stack()), editable));
        }
        List<PaperNetwork.PrinterInfo> printers = new ArrayList<>();
        BlockPos here = terminal.getBlockPos();
        for (PrinterBlockEntity p : printersInReach(terminal)) {
            printers.add(new PaperNetwork.PrinterInfo(p.getBlockPos(), p.customName(), p.getBlockState().getBlock().getDescriptionId(),
                    p.paperLeft(), p.paperKindNow(),
                    p.busy(), p.queued(), p.getBlockPos().equals(terminal.chosenPrinter())));
        }
        List<PaperNetwork.DisplayInfo> displays = new ArrayList<>();
        ServerLevel level = (ServerLevel) terminal.getLevel();
        for (SizedHangingEntity d : displaysInReach(terminal)) {
            ScreenLink link = (ScreenLink) d;
            BlockPos owner = link.terminal();
            int state = owner == null || !stillThere(level, owner) ? PaperNetwork.DisplayInfo.FREE
                    : owner.equals(here) ? PaperNetwork.DisplayInfo.MINE : PaperNetwork.DisplayInfo.TAKEN;
            TerminalBlockEntity other = state == PaperNetwork.DisplayInfo.TAKEN && level.isLoaded(owner)
                    && level.getBlockEntity(owner) instanceof TerminalBlockEntity t ? t : null;
            String ownerName = other != null ? other.customName() : "";
            String ownerKey = other != null ? other.getBlockState().getBlock().getDescriptionId() : "block.enplotify.terminal";
            displays.add(new PaperNetwork.DisplayInfo(d.getUUID(), d.getPos(), link.customName(), link.kindKey(),
                    link.kind() << 16 | d.cellsWide() << 8 | d.cellsHigh(),
                    state, owner == null ? BlockPos.ZERO : owner, ownerName, ownerKey));
        }
        PacketDistributor.sendToPlayer(player, new PaperNetwork.TerminalState(here, open, terminal.app(), files, printers, displays, message));
    }

    /** The displays a terminal reaches (all hung in its world), in the user's order (new ones after, nearest first). */
    private static List<SizedHangingEntity> displaysInReach(TerminalBlockEntity terminal) {
        if (!(terminal.getLevel() instanceof ServerLevel level)) return List.of();
        BlockPos here = terminal.getBlockPos();
        List<UUID> order = terminal.displayOrder();
        List<SizedHangingEntity> loaded = new ArrayList<>(ScreenLink.all(level, Entity::isAlive));
        loaded.sort(Comparator.<SizedHangingEntity>comparingInt(d -> {
            int i = order.indexOf(d.getUUID());
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparingDouble(d -> d.getPos().distSqr(here)));
        return loaded.size() > 64 ? loaded.subList(0, 64) : loaded;
    }

    /** The printers a terminal reaches, in the user's order (new ones after, nearest first). */
    private static List<PrinterBlockEntity> printersInReach(TerminalBlockEntity terminal) {
        BlockPos here = terminal.getBlockPos();
        List<BlockPos> order = terminal.printerOrder();
        List<PrinterBlockEntity> loaded = new ArrayList<>();
        for (PrinterBlockEntity p : PrinterBlockEntity.loaded(terminal.getLevel())) if (!p.isRemoved()) loaded.add(p);
        loaded.sort(Comparator.<PrinterBlockEntity>comparingInt(p -> {
            int i = order.indexOf(p.getBlockPos());
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparingDouble(p -> p.getBlockPos().distSqr(here)));
        return loaded.size() > 64 ? loaded.subList(0, 64) : loaded;
    }

    // ------------------------------------------------------------------ sound of pages

    /** Players farther than this (blocks) don't get a terminal's sound. */
    private static final double AUDIO_DISTANCE_SQ = 48.0 * 48.0;

    /** Sound from the browser of a terminal's user (or of the one who left it running): to everyone near. */
    static void audio(ServerPlayer player, PaperNetwork.TerminalAudio audio) {
        BlockPos pos = audio.pos();
        if (audio.samples().length == 0 || player.distanceToSqr(pos.getCenter()) > 64.0 * 64.0
                || !player.level().isLoaded(pos) || !(player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) {
            return;
        }
        boolean painter = player.getUUID().equals(terminal.controller())
                || (terminal.controller() == null && player.getUUID().equals(terminal.lastUser));
        if (!painter) return;
        PaperNetwork.TerminalAudioData data = new PaperNetwork.TerminalAudioData(pos, audio.stream(), audio.samples());
        List<net.minecraft.world.phys.Vec3> displays = displayCentres((ServerLevel) player.level(), pos);
        for (ServerPlayer other : ((ServerLevel) player.level()).players()) {
            if (other == player) continue;
            boolean near = other.distanceToSqr(pos.getCenter()) <= AUDIO_DISTANCE_SQ;
            for (int i = 0; i < displays.size() && !near; i++) near = other.distanceToSqr(displays.get(i)) <= AUDIO_DISTANCE_SQ;
            if (near) PacketDistributor.sendToPlayer(other, data);
        }
    }

    private record Centres(List<net.minecraft.world.phys.Vec3> list, long until) {}
    /** The middles of the displays each terminal is connected to (looked up at most once a second). */
    private static final Map<BlockPos, Centres> DISPLAYS_OF = new HashMap<>();

    private static List<net.minecraft.world.phys.Vec3> displayCentres(ServerLevel level, BlockPos pos) {
        long now = System.currentTimeMillis();
        Centres c = DISPLAYS_OF.get(pos);
        if (c != null && c.until() > now) return c.list();
        List<net.minecraft.world.phys.Vec3> list = new ArrayList<>();
        for (SizedHangingEntity d : ScreenLink.all(level, d -> pos.equals(((ScreenLink) d).terminal()))) {
            list.add(d.getBoundingBox().getCenter());
        }
        DISPLAYS_OF.entrySet().removeIf(e -> e.getValue().until() < now);
        DISPLAYS_OF.put(pos.immutable(), new Centres(list, now + 1000));
        return list;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = USING.get(player.getUUID());
        if (pos != null && player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal) terminal.release();
        USING.remove(player.getUUID());
        TAKE_OVER.remove(player.getUUID());
        PIECES.removeIf(k -> k.player().equals(player.getUUID()));
    }

    // ------------------------------------------------------------------ screen pictures for other players

    /** Viewers farther than this (blocks) get no pictures. */
    private static final double WATCH_DISTANCE_SQ = 32.0 * 32.0;
    /** A watch lasts this long unless renewed (viewers renew every second). */
    private static final long WATCH_MS = 3000;

    /** One viewer of one terminal. */
    static final class Watch {
        float fps;
        int resolution;
        long expires;
        long lastSent;
    }

    static void watch(ServerPlayer player, BlockPos pos, float fps, int resolution) {
        if (!(player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) return;
        if ((player.distanceToSqr(pos.getCenter()) > WATCH_DISTANCE_SQ && !nearDisplayOf(player, pos))
                || player.getUUID().equals(terminal.controller())) return;
        long now = System.currentTimeMillis();
        Watch watch = terminal.watchers.get(player.getUUID());
        boolean fresh = watch == null;
        if (fresh) {
            watch = new Watch();
            terminal.watchers.put(player.getUUID(), watch);
        }
        watch.fps = Float.isFinite(fps) ? Math.clamp(fps, 0.0F, 60.0F) : 0.0F;
        watch.resolution = Math.clamp(resolution, 0, 15);
        watch.expires = now + WATCH_MS;
        // A new viewer gets the latest picture at once.
        if (fresh && terminal.lastShot != null) {
            watch.lastSent = now;
            sendShot(player, pos, terminal.lastShot);
        }
        updateRate(terminal);
    }

    /** A display showing this terminal hangs near the player (who sees its screen there). */
    private static boolean nearDisplayOf(ServerPlayer player, BlockPos pos) {
        return !player.level().getEntitiesOfClass(SizedHangingEntity.class, player.getBoundingBox().inflate(32.0),
                d -> d instanceof ScreenLink link && pos.equals(link.terminal())).isEmpty();
    }

    /** Tells the user how often to take pictures: as often as the keenest viewer wants. */
    static void updateRate(TerminalBlockEntity terminal) {
        long now = System.currentTimeMillis();
        terminal.watchers.values().removeIf(w -> w.expires < now);
        float rate = -1.0F;
        int resolution = 0;
        for (Watch w : terminal.watchers.values()) {
            rate = Math.max(rate, w.fps);
            resolution = Math.max(resolution, w.resolution);
        }
        // Whoever paints the screen: its user, or (nobody at it) the one who left it.
        UUID painter = terminal.controller() != null ? terminal.controller() : terminal.lastUser;
        if ((rate == terminal.sentRate && resolution == terminal.sentResolution) || painter == null
                || !(terminal.getLevel() instanceof ServerLevel level)) return;
        ServerPlayer user = level.getServer().getPlayerList().getPlayer(painter);
        if (user == null) return;
        terminal.sentRate = rate;
        terminal.sentResolution = resolution;
        PacketDistributor.sendToPlayer(user, new PaperNetwork.TerminalCaptureRate(terminal.getBlockPos(), rate, resolution));
    }

    /** Tells a former user to stop sending pictures of this terminal. */
    static void stopPictures(TerminalBlockEntity terminal, UUID former) {
        if (!(terminal.getLevel() instanceof ServerLevel level)) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(former);
        if (player != null) PacketDistributor.sendToPlayer(player, new PaperNetwork.TerminalCaptureRate(terminal.getBlockPos(), -1.0F, 0));
    }

    private record PieceKey(UUID player, BlockPos pos) {}
    private static final PaperNetwork.Pieces<PieceKey> PIECES = new PaperNetwork.Pieces<>();

    /** A piece of a picture from a user: once all pieces are here, it is the picture. */
    static void shotPiece(ServerPlayer player, PaperNetwork.TerminalShot piece) {
        byte[] whole = PIECES.add(new PieceKey(player.getUUID(), piece.pos().immutable()), piece.jpeg(), piece.index(), piece.total());
        if (whole != null) shot(player, piece.pos(), whole, piece.switched());
    }

    private static void sendShot(ServerPlayer viewer, BlockPos pos, byte[] picture) {
        PaperNetwork.inPieces(picture, PaperNetwork.MAX_SHOT_DATA_BYTES, (part, index, total) ->
                PacketDistributor.sendToPlayer(viewer, new PaperNetwork.TerminalShotData(pos, part, index, total)));
    }

    static void shot(ServerPlayer player, BlockPos pos, byte[] jpeg, boolean switched) {
        if (!(player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) return;
        // From its user, or from the one who left it (its last picture, or the browser left running).
        boolean leaving = terminal.controller() == null && player.getUUID().equals(terminal.lastUser);
        if ((!player.getUUID().equals(terminal.controller()) && !leaving) || jpeg.length == 0) return;
        long now = System.currentTimeMillis();
        // At most about 60 pictures a second, whatever the client claims.
        if (!switched && now - terminal.lastShotTime < 15) return;
        terminal.lastShot = jpeg;
        terminal.lastShotTime = now;
        terminal.setChanged();
        ServerLevel level = (ServerLevel) player.level();
        for (Map.Entry<UUID, Watch> e : terminal.watchers.entrySet()) {
            Watch w = e.getValue();
            if (w.expires < now) continue;
            boolean due = switched || (w.fps > 0.0F && now - w.lastSent >= (long) (1000.0F / w.fps) - 5);
            if (!due) continue;
            ServerPlayer viewer = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (viewer == null || viewer.level() != level) continue;
            w.lastSent = now;
            sendShot(viewer, pos, jpeg);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        USING.clear();
        TAKE_OVER.clear();
        PIECES.clear();
        DISPLAYS_OF.clear();
        PrinterBlockEntity.clearLoaded();
        TerminalBlockEntity.clearLoaded();
    }

    private TerminalServer() {}
}
