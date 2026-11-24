package io.github.lwarqiu1shz.enplotify.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the printer clicks. The client decides what a click means (it alone knows about
 * Ctrl) and sends a {@link PaperNetwork.PrinterAction}; the vanilla click that follows is ignored.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class PrinterServer {
    public static final int NOTHING = 0, INSERT_PAPER = 1, TAKE_PAPER = 2, INSERT_ORIGINAL = 3, TAKE_ORIGINAL = 4,
            PRINT = 5, TAKE_COPY = 6, INSERT_COPIES = 7;

    /** A left click on this printer that was turned into a printer action: don't start breaking it. */
    private record Claimed(BlockPos pos, long until) {}
    private static final Map<UUID, Claimed> CLAIMED = new HashMap<>();

    public static void handle(ServerPlayer player, BlockPos pos, int action) {
        if (player.isSpectator() || !player.isWithinBlockInteractionRange(pos, 1.0)) return;
        if (!(player.level().getBlockEntity(pos) instanceof PrinterBlockEntity printer)) return;
        if (action == NOTHING || action == INSERT_PAPER || action == TAKE_PAPER) {
            CLAIMED.put(player.getUUID(), new Claimed(pos.immutable(), player.level().getGameTime() + 2));
        }
        switch (action) {
            case INSERT_PAPER -> printer.insertPaper(player);
            case TAKE_PAPER -> printer.takePaper(player);
            case INSERT_ORIGINAL -> printer.insertOriginal(player);
            case TAKE_ORIGINAL -> printer.takeOriginal(player);
            case PRINT -> printer.startPrint(player);
            case TAKE_COPY -> printer.takeCopy(player);
            case INSERT_COPIES -> printer.insertCopies(player);
            default -> {}
        }
    }

    public static void rename(ServerPlayer player, BlockPos pos, String name) {
        if (player.isSpectator() || !player.isWithinBlockInteractionRange(pos, 4.0)) return;
        if (player.level().getBlockEntity(pos) instanceof PrinterBlockEntity printer) printer.rename(name);
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Claimed claimed = CLAIMED.get(player.getUUID());
        if (claimed != null && claimed.pos().equals(event.getPos()) && player.level().getGameTime() <= claimed.until()) {
            event.setCanceled(true);
        }
    }

    /** Every right click on a printer comes as a printer action; the vanilla one does nothing. */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof PrinterBlock)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CLAIMED.remove(event.getEntity().getUUID());
    }

    private PrinterServer() {}
}
