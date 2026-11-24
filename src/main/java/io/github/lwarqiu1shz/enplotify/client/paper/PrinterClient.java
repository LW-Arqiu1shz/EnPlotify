package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import io.github.lwarqiu1shz.enplotify.paper.PrinterBlock;
import io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity;
import io.github.lwarqiu1shz.enplotify.paper.PrinterServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Works out what a click on a printer means and tells the server:
 * <ul>
 *   <li>Shift + left click holding paper: put it in as blank paper; Ctrl + left click: take blank paper out;</li>
 *   <li>Shift + right click holding a sheet: put it in to be copied; Ctrl + right click: take it out;</li>
 *   <li>right click on top: copy; right click on the tray: take a copy;</li>
 *   <li>Alt + right click on the tray holding sheets: put them in the tray as they are;</li>
 *   <li>Alt + left click: rename (displays too).</li>
 * </ul>
 * Plain left clicks still break the printer.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class PrinterClient {
    /** Attack was held at the end of the last tick: a held click acts only once. */
    private static boolean attackWasDown;

    /** Alt held (either Alt key; asked of the keyboard itself). */
    static boolean altDown() {
        Minecraft minecraft = Minecraft.getInstance();
        long window = minecraft.getWindow().handle();
        return minecraft.hasAltDown()
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    /**
     * Renaming: Alt + left click on a printer or a terminal. Caught when the
     * mouse button is pressed, before the game does anything with the click, so neither is hit
     * (the server never hears of the click).
     */
    @SubscribeEvent
    public static void onClickInput(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null || !altDown()) return;
        if (event.isAttack() && minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult entityHit
                && entityHit.getEntity() instanceof io.github.lwarqiu1shz.enplotify.paper.ScreenLink display
                && entityHit.getEntity() instanceof io.github.lwarqiu1shz.enplotify.paper.SizedHangingEntity hung) {
            event.setCanceled(true);
            event.setSwingHand(false);
            net.minecraft.core.BlockPos at = hung.getPos();
            minecraft.setScreen(PrinterNameScreen.display(hung.getId(), display.customName(),
                    io.github.lwarqiu1shz.enplotify.paper.ScreenLink.defaultName(display.kindKey(), at).getString()));
            return;
        }
        if (!(minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        if (event.isAttack() && state.getBlock() instanceof PrinterBlock
                && minecraft.level.getBlockEntity(pos) instanceof PrinterBlockEntity printer) {
            event.setCanceled(true);
            event.setSwingHand(false);
            minecraft.setScreen(new PrinterNameScreen(pos, printer.customName(),
                    io.github.lwarqiu1shz.enplotify.paper.ScreenLink.defaultName(state.getBlock().getDescriptionId(), pos).getString()));
        } else if (event.isAttack() && state.getBlock() instanceof io.github.lwarqiu1shz.enplotify.paper.TerminalBlock
                && io.github.lwarqiu1shz.enplotify.paper.TerminalBlock.hasScreen(state)
                && minecraft.level.getBlockEntity(pos) instanceof io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity terminal) {
            event.setCanceled(true);
            event.setSwingHand(false);
            minecraft.setScreen(PrinterNameScreen.terminal(pos, terminal.customName(),
                    io.github.lwarqiu1shz.enplotify.paper.ScreenLink.defaultName(state.getBlock().getDescriptionId(), pos).getString()));
        }
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.getLevel().isClientSide() || event.getEntity() != minecraft.player) return;
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof PrinterBlock)) return;
        LocalPlayer player = minecraft.player;
        if (minecraft.hasAltDown()) {
            // Alt + left click: rename. The server is told only so it doesn't start breaking the block.
            event.setCanceled(true);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
            send(event.getPos(), PrinterServer.NOTHING);
            if (!attackWasDown && event.getLevel().getBlockEntity(event.getPos()) instanceof PrinterBlockEntity printer) {
                minecraft.setScreen(new PrinterNameScreen(event.getPos(), printer.customName(),
                        io.github.lwarqiu1shz.enplotify.paper.ScreenLink.defaultName(
                                event.getLevel().getBlockState(event.getPos()).getBlock().getDescriptionId(), event.getPos()).getString()));
            }
            return;
        }
        boolean ctrl = minecraft.hasControlDown();
        boolean putPaper = !ctrl && player.isShiftKeyDown() && PrinterBlockEntity.kindOf(player.getMainHandItem()) != PrinterBlockEntity.NONE;
        if (!ctrl && !putPaper) return;
        event.setCanceled(true);
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        int action = attackWasDown ? PrinterServer.NOTHING : ctrl ? PrinterServer.TAKE_PAPER : PrinterServer.INSERT_PAPER;
        send(event.getPos(), action);
        if (action != PrinterServer.NOTHING) player.swing(InteractionHand.MAIN_HAND);
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!event.getLevel().isClientSide() || event.getEntity() != minecraft.player) return;
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        // Alt + right click on a terminal takes over a window someone left open: said just before the click.
        if (state.getBlock() instanceof io.github.lwarqiu1shz.enplotify.paper.TerminalBlock && altDown()
                && event.getHand() == InteractionHand.MAIN_HAND) {
            ClientPacketDistributor.sendToServer(new PaperNetwork.TerminalAltClick(pos.immutable()));
            return;
        }
        if (!(state.getBlock() instanceof PrinterBlock)) return;
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            event.setCancellationResult(InteractionResult.PASS);
            return;
        }
        LocalPlayer player = minecraft.player;
        int action;
        Vec3 hit = event.getHitVec().getLocation();
        PrinterBlock.Part part = PrinterBlock.partAt(state, event.getHitVec().getDirection(),
                hit.x - pos.getX(), hit.y - pos.getY(), hit.z - pos.getZ());
        if (minecraft.hasAltDown() && part == PrinterBlock.Part.TRAY) {
            action = PrinterServer.INSERT_COPIES;
        } else if (minecraft.hasControlDown()) {
            action = PrinterServer.TAKE_ORIGINAL;
        } else if (player.isShiftKeyDown() && PrinterBlockEntity.kindOf(player.getMainHandItem()) != PrinterBlockEntity.NONE) {
            action = PrinterServer.INSERT_ORIGINAL;
        } else {
            action = switch (part) {
                case TOP -> PrinterServer.PRINT;
                case TRAY -> PrinterServer.TAKE_COPY;
                case OTHER -> PrinterServer.NOTHING;
            };
        }
        // Never PASS: that would go on to use the held item (e.g. open a document).
        event.setCancellationResult(action == PrinterServer.NOTHING ? InteractionResult.CONSUME : InteractionResult.SUCCESS);
        if (action != PrinterServer.NOTHING) send(pos, action);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        attackWasDown = Minecraft.getInstance().options.keyAttack.isDown();
    }

    private static void send(BlockPos pos, int action) {
        ClientPacketDistributor.sendToServer(new PaperNetwork.PrinterAction(pos.immutable(), action));
    }

    private PrinterClient() {}
}
