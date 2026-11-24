package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import io.github.lwarqiu1shz.enplotify.paper.TerminalBlock;
import io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity;
import io.github.lwarqiu1shz.enplotify.paper.TerminalServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Using a terminal: the camera glides up to the screen until the screen fills most of the view,
 * then the system is drawn exactly over it ({@link TerminalScreen}). Alt+Q leaves; the camera
 * glides back. Also keeps what the server last said (files, printers, a status line).
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class TerminalView {
    private static final long GLIDE_MS = 450;
    /** Field of view (vertical, degrees) while at the screen; fixed so the system lines up with it. */
    private static final float FOV = 70.0F;
    /** How much of the window height (or width) the screen takes. */
    private static final float FILL = 0.84F;
    static final float ASPECT = 16.0F / 10.0F;

    private static @Nullable BlockPos pos;
    private static boolean leaving;
    private static long glideStart;
    /** The app shown before an editor was opened from it. */
    private static boolean inEditor;
    private static int refreshTicks;

    // What the server said.
    static List<PaperNetwork.FileInfo> files = List.of();
    static List<PaperNetwork.PrinterInfo> printers = List.of();
    static List<PaperNetwork.DisplayInfo> displays = List.of();
    /** In the devices program: 0 the choice, 1 printers, 2 displays. */
    static int devicePage;
    static String message = "";
    static long messageTime;

    // What the system shows (kept while an editor is open over it).
    static int app = TerminalBlockEntity.APP_DESKTOP;
    /** The browser's address, kept while the browser itself is closed. */
    static String browserUrl = WebBrowser.HOME;
    static int selected = -1;
    static int scroll;

    public static boolean active() {
        return pos != null;
    }

    static @Nullable BlockPos pos() {
        return pos;
    }

    /** The wallpaper of the terminal in use (null: the built-in one). */
    static net.minecraft.resources.@Nullable Identifier wallpaper() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pos == null || minecraft.level == null || !(minecraft.level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) return null;
        return terminal.theme().wallpaper().orElse(null);
    }

    /** The colour of the desktop icons' names on the terminal in use. */
    static int iconText() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pos == null || minecraft.level == null || !(minecraft.level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) {
            return io.github.lwarqiu1shz.enplotify.api.TerminalTheme.BUILT_IN.iconText();
        }
        return terminal.theme().iconText();
    }

    /** 0 = at the player's eyes, 1 = at the screen. */
    static float progress() {
        if (pos == null) return 0.0F;
        float t = Mth.clamp((System.currentTimeMillis() - glideStart) / (float) GLIDE_MS, 0.0F, 1.0F);
        float eased = t * t * (3.0F - 2.0F * t);
        return leaving ? 1.0F - eased : eased;
    }

    /** At the screen and not leaving: the system can be used. */
    static boolean arrived() {
        return pos != null && !leaving && progress() >= 1.0F;
    }

    // ------------------------------------------------------------------ server messages

    public static void onState(PaperNetwork.TerminalState state) {
        if (!state.open()) {
            if (pos != null && pos.equals(state.pos()) && !leaving) leave();
            return;
        }
        if (pos == null || leaving || !pos.equals(state.pos())) start(state.pos(), state.app());
        files = state.files();
        printers = state.printers();
        displays = state.displays();
        if (!state.message().isEmpty()) {
            message = state.message();
            messageTime = System.currentTimeMillis();
        }
        if (selected >= 0 && files.stream().noneMatch(f -> f.id() == selected)) selected = -1;
    }

    public static void onFile(PaperNetwork.TerminalFileData data) {
        if (pos == null || leaving || !pos.equals(data.pos())) return;
        inEditor = true;
        send(TerminalServer.OP_APP, TerminalBlockEntity.APP_EDITING);
        PaperClientHooks.openTerminalFile(data.stack(), data.id());
    }

    /** Each terminal's browser tabs (this session): their addresses, and which one was shown. */
    private static final java.util.Map<BlockPos, java.util.List<String>> URLS = new java.util.HashMap<>();
    private static final java.util.Map<BlockPos, Integer> ACTIVE_TAB = new java.util.HashMap<>();

    private static void start(BlockPos at, int window) {
        pos = at.immutable();
        leaving = false;
        glideStart = System.currentTimeMillis();
        // Back at a terminal whose browser is still running (shown or minimised): the same pages.
        // Otherwise its remembered tabs; their web views only start when the browser is opened.
        if (!WebBrowser.unpark(pos)) {
            WebBrowser.prepare(URLS.getOrDefault(pos, java.util.List.of(WebBrowser.HOME)), ACTIVE_TAB.getOrDefault(pos, 0));
        }
        browserUrl = WebBrowser.activeUrl();
        app = window == TerminalBlockEntity.APP_EDITING ? TerminalBlockEntity.APP_FILES : window;
        selected = -1;
        scroll = 0;
        inEditor = false;
        message = "";
        Minecraft.getInstance().setScreen(new TerminalScreen());
    }

    /** The player asked to stop (Alt+Q). */
    static void exit() {
        if (pos == null || leaving) return;
        // The screen as it is left: what others see on it from now on.
        TerminalShots.captureNow(pos);
        send(TerminalServer.OP_CLOSE, 0);
        leave();
    }

    private static void leave() {
        // Glide back from wherever the camera is now.
        float p = progress();
        leaving = true;
        glideStart = System.currentTimeMillis() - (long) ((1.0F - p) * GLIDE_MS);
        inEditor = false;
        park();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) minecraft.setScreen(null);
    }

    /** The browser window stays with the terminal, still running (shown on it, or minimised). */
    private static void park() {
        if (pos == null) return;
        rememberBrowser();
        WebBrowser.park(pos, app == TerminalBlockEntity.APP_BROWSER);
    }

    /** Keeps the window's tabs for this terminal (addresses only). */
    private static void rememberBrowser() {
        if (pos == null || WebBrowser.tabCount() == 0) return;
        URLS.put(pos, WebBrowser.urls());
        ACTIVE_TAB.put(pos, WebBrowser.activeIndex());
        browserUrl = WebBrowser.activeUrl();
    }

    /** Closes the browser window for good: its pages end and its tabs are forgotten. */
    static void closeBrowser() {
        WebBrowser.closeAll();
        if (pos != null) {
            URLS.remove(pos);
            ACTIVE_TAB.remove(pos);
            if (!world.isEmpty()) TabStore.save(world, URLS, ACTIVE_TAB);
        }
        browserUrl = WebBrowser.HOME;
    }

    private static void reset() {
        park();
        pos = null;
        leaving = false;
        inEditor = false;
        files = List.of();
        printers = List.of();
        displays = List.of();
    }

    static void send(int op, int a) {
        if (pos != null) ClientPacketDistributor.sendToServer(PaperNetwork.TerminalOp.of(pos, op, a));
    }

    static void send(PaperNetwork.TerminalOp op) {
        ClientPacketDistributor.sendToServer(op);
    }

    static void setApp(int newApp) {
        // Leaving the browser for another window only minimises it: its pages keep running.
        if (newApp != TerminalBlockEntity.APP_BROWSER) WebBrowser.hoverOffAll();
        app = newApp;
        selected = -1;
        scroll = 0;
        if (newApp == TerminalBlockEntity.APP_PRINTERS) devicePage = 0;
        send(TerminalServer.OP_APP, newApp);
        if (newApp == TerminalBlockEntity.APP_PRINTERS) send(TerminalServer.OP_REFRESH, 0);
    }

    // ------------------------------------------------------------------ where the camera goes

    /** The middle of the screen picture, in the world. */
    private static @Nullable Vec3 screenCentre(BlockState state, BlockPos at) {
        double[] d = TerminalBlock.display(state);
        if (d == null) return null;
        Direction facing = state.getValue(TerminalBlock.FACING);
        Direction right = facing.getClockWise();
        double cx = (d[0] + d[2]) / 32.0 - 0.5, cy = (d[1] + d[3]) / 32.0, depth = -(d[4] / 16.0 - 0.5);
        return new Vec3(at.getX() + 0.5 + facing.getStepX() * depth + right.getStepX() * cx,
                at.getY() + cy,
                at.getZ() + 0.5 + facing.getStepZ() * depth + right.getStepZ() * cx);
    }

    /** Height of the window that the screen picture takes, 0..1. */
    private static float heightShare() {
        Minecraft minecraft = Minecraft.getInstance();
        float w = minecraft.getWindow().getWidth(), h = Math.max(1, minecraft.getWindow().getHeight());
        float rectH = Math.min(FILL * h, FILL * w / ASPECT);
        return rectH / h;
    }

    /** The screen picture's place in GUI coordinates: x, y, width, height. */
    static float[] guiRect(int guiW, int guiH) {
        float share = heightShare();
        float h = share * guiH, w = h * ASPECT;
        return new float[] {(guiW - w) / 2.0F, (guiH - h) / 2.0F, w, h};
    }

    private static @Nullable Vec3 target() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pos == null || minecraft.level == null) return null;
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof TerminalBlock)) return null;
        Vec3 centre = screenCentre(state, pos);
        if (centre == null) return null;
        double pictureH = (TerminalBlock.DESK_DISPLAY[3] - TerminalBlock.DESK_DISPLAY[1]) / 16.0;
        double distance = pictureH / (2.0 * Math.tan(Math.toRadians(FOV / 2.0)) * heightShare());
        Direction facing = state.getValue(TerminalBlock.FACING);
        return centre.add(facing.getStepX() * distance, 0.0, facing.getStepZ() * distance);
    }

    private static float targetYaw() {
        Minecraft minecraft = Minecraft.getInstance();
        if (pos == null || minecraft.level == null) return 0.0F;
        BlockState state = minecraft.level.getBlockState(pos);
        return state.getBlock() instanceof TerminalBlock ? state.getValue(TerminalBlock.FACING).getOpposite().toYRot() : 0.0F;
    }

    /** Camera position while using a terminal (null = the usual one). Called by the camera mixin. */
    public static @Nullable Vec3 cameraPosition(Vec3 usual) {
        float p = progress();
        if (pos == null || p <= 0.0F) return null;
        Vec3 target = target();
        return target == null ? null : usual.lerp(target, p);
    }

    /** Camera turn while using a terminal: {yaw, pitch}, or null. */
    public static float @Nullable [] cameraRotation(float yaw, float pitch) {
        float p = progress();
        if (pos == null || p <= 0.0F || target() == null) return null;
        return new float[] {yaw + Mth.wrapDegrees(targetYaw() - yaw) * p, pitch * (1.0F - p)};
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        float p = progress();
        if (pos != null && p > 0.0F) event.setFOV(Mth.lerp(p, (float) event.getFOV(), FOV));
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (pos != null && progress() > 0.0F) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Pre event) {
        if (pos != null && progress() > 0.0F) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onOutline(ExtractBlockOutlineRenderStateEvent event) {
        if (pos != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        checkParked(minecraft);
        if (inWorld && ++saveTicks >= 200) {
            saveTicks = 0;
            rememberAll(true);
        }
        if (pos == null) return;
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !player.isAlive()) {
            reset();
            return;
        }
        if (leaving) {
            if (progress() <= 0.0F) reset();
            return;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof TerminalBlock) || !TerminalBlock.hasScreen(state) || !state.getValue(TerminalBlock.KEYBOARD)
                || (minecraft.level.getBlockEntity(pos) instanceof TerminalBlockEntity t && t.power() != TerminalBlockEntity.POWER_ON)) {
            exit();
            return;
        }
        if (minecraft.screen == null) {
            // Back from an editor (or anything else): show the system again.
            if (inEditor) {
                inEditor = false;
                send(TerminalServer.OP_APP, app);
            }
            minecraft.setScreen(new TerminalScreen());
        }
        // Printer states change by themselves: ask again now and then while they are shown.
        if (minecraft.screen instanceof TerminalScreen screen && screen.showsPrinters() && ++refreshTicks >= 40) {
            refreshTicks = 0;
            send(TerminalServer.OP_REFRESH, 0);
        }
    }

    /**
     * Browser windows of terminals the player is not at. A window ends when the terminal is gone,
     * when someone else takes it, or (minimised ones only) when its chunk unloads; one shown on the
     * screen keeps running even then.
     */
    private static void checkParked(Minecraft minecraft) {
        java.util.Set<BlockPos> parked = WebBrowser.parked();
        if (parked.isEmpty()) return;
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) return;
        String me = player.getGameProfile().name();
        boolean settling = System.currentTimeMillis() < graceUntil;
        for (BlockPos at : parked) {
            if (settling && (!minecraft.level.isLoaded(at) || !(minecraft.level.getBlockEntity(at) instanceof TerminalBlockEntity))) continue;
            if (!minecraft.level.isLoaded(at)) {
                if (!WebBrowser.parkedForeground(at)) sleepParked(at);
                continue;
            }
            if (!(minecraft.level.getBlockEntity(at) instanceof TerminalBlockEntity terminal) || !TerminalBlock.hasScreen(terminal.getBlockState())) {
                forget(at);
                continue;
            }
            if (terminal.power() != TerminalBlockEntity.POWER_ON) {
                // Switched off: every window, shown or minimised, ends.
                forget(at);
                continue;
            }
            String user = terminal.userName();
            if (!user.isEmpty() && !user.equals(me)) {
                forget(at);
                continue;
            }
            if (user.isEmpty()) WebBrowser.setParkedForeground(at, terminal.app() == TerminalBlockEntity.APP_BROWSER);
        }
    }

    /** Ends a terminal's put-aside window (far away) but keeps its tabs for next time. */
    private static void sleepParked(BlockPos at) {
        Object[] kept = WebBrowser.parkedAddresses().get(at);
        if (kept != null) {
            @SuppressWarnings("unchecked") java.util.List<String> urls = (java.util.List<String>) kept[0];
            URLS.put(at, urls);
            ACTIVE_TAB.put(at, (Integer) kept[1]);
        }
        WebBrowser.closeParked(at);
        if (!world.isEmpty()) TabStore.save(world, URLS, ACTIVE_TAB);
    }

    /** Ends a terminal's put-aside window and forgets its tabs. */
    private static void forget(BlockPos at) {
        WebBrowser.closeParked(at);
        URLS.remove(at);
        ACTIVE_TAB.remove(at);
        if (!world.isEmpty()) TabStore.save(world, URLS, ACTIVE_TAB);
    }

    /** Which world (save or server) the player is in: windows and remembered tabs belong to it. */
    private static String world = "";
    /** Just entered a world: its chunks are still arriving, so windows aren't judged gone for a while. */
    private static long graceUntil;

    /** In a world now (the tabs on disk may be written). */
    private static boolean inWorld;
    /** What was last written to disk, so it is only written again when something changed. */
    private static String lastSaved = "";
    private static int saveTicks;

    /**
     * Puts every window's addresses (the one in use and those put aside) into the remembered tabs
     * and writes them to disk (only if anything changed, when {@code onlyIfChanged}).
     */
    private static void rememberAll(boolean onlyIfChanged) {
        if (pos != null && WebBrowser.tabCount() > 0) {
            URLS.put(pos, WebBrowser.urls());
            ACTIVE_TAB.put(pos, WebBrowser.activeIndex());
        }
        for (java.util.Map.Entry<BlockPos, Object[]> e : WebBrowser.parkedAddresses().entrySet()) {
            @SuppressWarnings("unchecked") java.util.List<String> urls = (java.util.List<String>) e.getValue()[0];
            URLS.put(e.getKey(), urls);
            ACTIVE_TAB.put(e.getKey(), (Integer) e.getValue()[1]);
        }
        if (world.isEmpty()) return;
        String now = URLS + "|" + ACTIVE_TAB;
        if (onlyIfChanged && now.equals(lastSaved)) return;
        lastSaved = now;
        TabStore.save(world, URLS, ACTIVE_TAB);
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        lastSaved = "";
        world = worldKey();
        graceUntil = System.currentTimeMillis() + 15000;
        URLS.clear();
        ACTIVE_TAB.clear();
        TabStore.load(world, URLS, ACTIVE_TAB);
        WebBrowser.wake(world);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!inWorld) return;
        reset(); // the shown window is put aside like any other
        // Remember every window's addresses on disk (in case the game is closed), then let them sleep.
        rememberAll(false);
        inWorld = false;
        WebBrowser.sleep(world);
        URLS.clear();
        ACTIVE_TAB.clear();
    }

    /** The game is closing: no page may keep running after it. */
    @SubscribeEvent
    public static void onGameClosing(net.neoforged.neoforge.event.GameShuttingDownEvent event) {
        // Closed straight from a world: write the tabs down before the pages end.
        if (inWorld) {
            rememberAll(false);
            inWorld = false;
        }
        WebBrowser.closeEverything();
    }

    private static String worldKey() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.getSingleplayerServer() != null) {
                return "save:" + minecraft.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .toAbsolutePath().normalize();
            }
            if (minecraft.getCurrentServer() != null) return "server:" + minecraft.getCurrentServer().ip;
        } catch (RuntimeException e) {
            // fall through
        }
        return "unknown";
    }

    private TerminalView() {}
}
