package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A terminal's files and who is using it. One player at a time; others see what program is open
 * (synced: user name and program), never the files themselves.
 */
public class TerminalBlockEntity extends BlockEntity {
    public static final int MAX_FILES = 32;
    public static final int APP_DESKTOP = 0, APP_FILES = 1, APP_PRINTERS = 2, APP_EDITING = 3, APP_BROWSER = 4;
    /** Farther than this from the screen (blocks) and the user is let go. */
    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private final List<TerminalFile> files = new ArrayList<>();
    /** The printers in the order the user put them (ones out of reach keep their place). */
    private final List<BlockPos> printerOrder = new ArrayList<>();
    /** The printer chosen in the printers window: the one printing uses first. */
    private @Nullable BlockPos chosenPrinter;
    private static final int MAX_ORDER = 128;
    /** The displays in the order the user put them. */
    private final List<UUID> displayOrder = new ArrayList<>();
    private int nextId = 1;
    private @Nullable UUID controller;

    // Screen pictures for other players (server, not saved).
    final java.util.Map<UUID, TerminalServer.Watch> watchers = new java.util.HashMap<>();
    float sentRate = Float.NaN;
    int sentResolution = -1;
    byte @Nullable [] lastShot;
    long lastShotTime;
    /** Who used it last and when they left: their final picture may still be on its way. */
    @Nullable UUID lastUser;
    long releasedAt;

    // Synced.
    private String userName = "";
    /** Set by a player (Alt + right click); "" = "终端[x,y,z]". */
    private String name = "";
    public static final int MAX_NAME = 32;
    private int app;

    /** Power: off, starting (logo, then loading), on, shutting down (loading), crashed (see {@link #setCrashed}). */
    public static final int POWER_OFF = 0, POWER_BOOT = 1, POWER_ON = 2, POWER_SHUTDOWN = 3, POWER_CRASHED = 4;
    /** Ticks of "loading" after the logo, and before going off. */
    public static final int LOADING_TICKS = 20;
    /** Placed terminals start off; ones saved before there was a power button load as on. */
    private int power = POWER_OFF;
    private long powerSince;
    /** Shown when someone presses power or clicks the screen of a crashed terminal (server, saved). */
    private net.minecraft.network.chat.@Nullable Component crashMessage;
    /** This terminal's own look (null: the default one). */
    private io.github.lwarqiu1shz.enplotify.api.@Nullable TerminalTheme theme;

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(PaperRegistry.TERMINAL_ENTITY.get(), pos, state);
    }

    public List<TerminalFile> files() {
        return files;
    }

    public void clearFiles() {
        files.clear();
        setChanged();
    }

    public @Nullable TerminalFile file(int id) {
        for (TerminalFile f : files) if (f.id() == id) return f;
        return null;
    }

    /** Adds a file; false when there are too many. */
    public boolean addFile(String name, ItemStack stack) {
        if (files.size() >= MAX_FILES) return false;
        files.add(new TerminalFile(nextId++, name, stack.copyWithCount(1)));
        setChanged();
        return true;
    }

    public void replaceFile(int id, String name, ItemStack stack) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).id() == id) {
                files.set(i, new TerminalFile(id, name, stack));
                setChanged();
                return;
            }
        }
    }

    /** Moves a file to {@code index} in the list. */
    public void moveFile(int id, int index) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).id() == id) {
                TerminalFile f = files.remove(i);
                files.add(Math.clamp(index, 0, files.size()), f);
                setChanged();
                return;
            }
        }
    }

    public List<BlockPos> printerOrder() {
        return printerOrder;
    }

    /** Puts {@code ordered} (the printers in reach, in their new order) first, the rest after, as they were. */
    public void setPrinterOrder(List<BlockPos> ordered) {
        List<BlockPos> next = new ArrayList<>();
        for (BlockPos p : ordered) if (!next.contains(p)) next.add(p.immutable());
        for (BlockPos p : printerOrder) if (!next.contains(p)) next.add(p);
        while (next.size() > MAX_ORDER) next.removeLast();
        printerOrder.clear();
        printerOrder.addAll(next);
        setChanged();
    }

    public List<UUID> displayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(List<UUID> ordered) {
        List<UUID> next = new ArrayList<>();
        for (UUID d : ordered) if (!next.contains(d)) next.add(d);
        for (UUID d : displayOrder) if (!next.contains(d)) next.add(d);
        while (next.size() > MAX_ORDER) next.removeLast();
        displayOrder.clear();
        displayOrder.addAll(next);
        setChanged();
    }

    public @Nullable BlockPos chosenPrinter() {
        return chosenPrinter;
    }

    public void choosePrinter(@Nullable BlockPos pos) {
        chosenPrinter = pos == null ? null : pos.immutable();
        setChanged();
    }

    public void removeFile(int id) {
        if (files.removeIf(f -> f.id() == id)) setChanged();
    }

    // ------------------------------------------------------------------ the user

    public @Nullable UUID controller() {
        return controller;
    }

    public void take(ServerPlayer player) {
        controller = player.getUUID();
        userName = player.getGameProfile().name();
        // Comes back to the window it was left on (an editor is closed by then: back to the files).
        if (app == APP_EDITING) app = APP_FILES;
        // Whoever left it before stops taking pictures of it (their left-open browser closes too).
        if (lastUser != null && !lastUser.equals(controller)) TerminalServer.stopPictures(this, lastUser);
        lastUser = controller;
        sentRate = Float.NaN;
        sentResolution = -1;
        changed();
        TerminalServer.updateRate(this);
    }

    /** Lets the user go (and tells them, if online). */
    public void release() {
        if (controller == null) return;
        UUID was = controller;
        lastUser = was;
        releasedAt = System.currentTimeMillis();
        controller = null;
        userName = "";
        if (app == APP_EDITING) app = APP_FILES;
        sentRate = Float.NaN;
        sentResolution = -1;
        changed();
        TerminalServer.released(this, was);
    }

    public void setApp(int app) {
        if (this.app == app) return;
        this.app = app;
        changed();
    }

    public String userName() {
        return userName;
    }

    public int app() {
        return app;
    }

    public void rename(String text) {
        String clean = net.minecraft.util.StringUtil.filterText(text).strip();
        if (clean.length() > MAX_NAME) clean = clean.substring(0, MAX_NAME);
        if (clean.equals(name)) return;
        name = clean;
        changed();
    }

    public String customName() {
        return name;
    }

    /** What the terminal is called: its own name, or "终端[x,y,z]". */
    public net.minecraft.network.chat.Component displayName() {
        return name.isEmpty()
                ? ScreenLink.defaultName(getBlockState().getBlock().getDescriptionId(), worldPosition)
                : net.minecraft.network.chat.Component.literal(name);
    }

    public int power() {
        return power;
    }

    public boolean crashed() {
        return power == POWER_CRASHED;
    }

    /**
     * Crashes the terminal: the screen freezes on grey stripes, nobody can use it and the power
     * button only shows {@code message}. Null: it comes back to life, switched off.
     */
    public void setCrashed(net.minecraft.network.chat.@Nullable Component message) {
        if (level == null || level.isClientSide()) return;
        if (message != null) {
            crashMessage = message;
            if (power == POWER_CRASHED) return;
            release();
            app = APP_DESKTOP;
            lastShot = null;
            setPower(POWER_CRASHED);
        } else if (power == POWER_CRASHED) {
            crashMessage = null;
            setPower(POWER_OFF);
        }
    }

    /** What a crashed terminal says when someone tries it. */
    public net.minecraft.network.chat.Component crashMessage() {
        return crashMessage != null ? crashMessage : net.minecraft.network.chat.Component.translatable("message.enplotify.terminal.crashed");
    }

    /** Game time when the power state last changed. */
    public long powerSince() {
        return powerSince;
    }

    public io.github.lwarqiu1shz.enplotify.api.@Nullable TerminalTheme ownTheme() {
        return theme;
    }

    /** The screen was taken off: its look goes with it, and what is left is off. */
    public void screenGone() {
        theme = null;
        name = "";
        power = POWER_OFF;
        app = APP_DESKTOP;
        lastShot = null;
        changed();
    }

    /** Puts a theme on this terminal (null: back to the default look). */
    public void setTheme(io.github.lwarqiu1shz.enplotify.api.@Nullable TerminalTheme theme) {
        this.theme = theme;
        changed();
    }

    public io.github.lwarqiu1shz.enplotify.api.TerminalTheme theme() {
        return theme != null ? theme : io.github.lwarqiu1shz.enplotify.api.TerminalTheme.getDefault();
    }

    /**
     * The power button. Off: starts (logo, loading, desktop). On: shuts down (loading, off); the
     * user is let go and every window is closed, the files stay. Ignored while starting or stopping.
     */
    public void pressPower() {
        if (level == null || level.isClientSide()) return;
        level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_ON,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.4F);
        if (power == POWER_OFF) {
            setPower(POWER_BOOT);
        } else if (power == POWER_ON) {
            release();
            app = APP_DESKTOP;
            lastShot = null;
            setPower(POWER_SHUTDOWN);
        }
    }

    private void setPower(int next) {
        power = next;
        powerSince = level == null ? 0 : level.getGameTime();
        changed();
    }

    /** Whether the terminal can be used: screen and keyboard both there. */
    public boolean usable() {
        BlockState state = getBlockState();
        return TerminalBlock.hasScreen(state) && state.getValue(TerminalBlock.KEYBOARD);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TerminalBlockEntity terminal) {
        long since = level.getGameTime() - terminal.powerSince;
        if (terminal.power == POWER_BOOT && since >= terminal.theme().logoTicks() + LOADING_TICKS) {
            terminal.setPower(POWER_ON);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new io.github.lwarqiu1shz.enplotify.api.TerminalStartedEvent((ServerLevel) level, terminal));
        }
        else if (terminal.power == POWER_SHUTDOWN && since >= LOADING_TICKS) terminal.setPower(POWER_OFF);
        if (level.getGameTime() % 10 != 0) return;
        if (terminal.controller == null) {
            // Nobody at it: the last user's client may still send pictures (a browser left open).
            if (terminal.lastUser != null && level.getGameTime() % 20 == 0) TerminalServer.updateRate(terminal);
            return;
        }
        if (level.getGameTime() % 20 == 0) TerminalServer.updateRate(terminal);
        ServerPlayer player = ((ServerLevel) level).getServer().getPlayerList().getPlayer(terminal.controller);
        if (player == null || player.level() != level || !terminal.usable() || player.isSpectator()
                || player.distanceToSqr(pos.getCenter()) > MAX_DISTANCE_SQ) {
            terminal.release();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) release();
        forget();
    }

    /** Terminals loaded in each level (server). */
    private static final java.util.Map<Level, java.util.Set<TerminalBlockEntity>> LOADED = new java.util.HashMap<>();

    /** Forgets every level (the server stopped). */
    static void clearLoaded() {
        LOADED.clear();
    }

    public static java.util.Collection<TerminalBlockEntity> loaded(Level level) {
        java.util.Set<TerminalBlockEntity> set = LOADED.get(level);
        return set == null ? List.of() : List.copyOf(set);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            LOADED.computeIfAbsent(level, l -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())).add(this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        forget();
    }

    private void forget() {
        if (level == null) return;
        java.util.Set<TerminalBlockEntity> set = LOADED.get(level);
        if (set != null) set.remove(this);
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    // ------------------------------------------------------------------ saving, syncing, item form

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ValueOutput.TypedOutputList<TerminalFile> list = output.list("files", TerminalFile.CODEC);
        for (TerminalFile f : files) list.add(f);
        output.putInt("next_id", nextId);
        if (!printerOrder.isEmpty()) output.store("printer_order", BlockPos.CODEC.listOf(), List.copyOf(printerOrder));
        if (chosenPrinter != null) output.putLong("chosen_printer", chosenPrinter.asLong());
        if (!displayOrder.isEmpty()) output.store("display_order", net.minecraft.core.UUIDUtil.CODEC.listOf(), List.copyOf(displayOrder));
        if (app != APP_DESKTOP) output.putInt("app", app);
        if (!name.isEmpty()) output.putString("name", name);
        if (lastUser != null) output.store("last_user", net.minecraft.core.UUIDUtil.CODEC, lastUser);
        // Starting / stopping finish at once when loaded again.
        output.putInt("power", power == POWER_BOOT ? POWER_ON : power == POWER_SHUTDOWN ? POWER_OFF : power);
        if (theme != null) output.store("theme", io.github.lwarqiu1shz.enplotify.api.TerminalTheme.CODEC, theme);
        if (crashMessage != null) output.store("crash_message", net.minecraft.network.chat.ComponentSerialization.CODEC, crashMessage);
        // The last picture of the screen, so it shows the same after a restart.
        // As bytes: a text over 64 KB can't be written into the save.
        if (lastShot != null) output.store("shot", com.mojang.serialization.Codec.BYTE_BUFFER, java.nio.ByteBuffer.wrap(lastShot));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (input.getBooleanOr("sync", false)) {
            userName = input.getStringOr("user", "");
            app = input.getIntOr("app", APP_DESKTOP);
            power = input.getIntOr("power", POWER_ON);
            powerSince = input.getLongOr("power_since", 0L);
            name = input.getStringOr("name", "");
            theme = input.read("theme", io.github.lwarqiu1shz.enplotify.api.TerminalTheme.CODEC).orElse(null);
            return;
        }
        power = input.getIntOr("power", POWER_ON);
        name = input.getStringOr("name", "");
        theme = input.read("theme", io.github.lwarqiu1shz.enplotify.api.TerminalTheme.CODEC).orElse(null);
        crashMessage = input.read("crash_message", net.minecraft.network.chat.ComponentSerialization.CODEC).orElse(null);
        lastUser = input.read("last_user", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        files.clear();
        for (TerminalFile f : input.listOrEmpty("files", TerminalFile.CODEC)) files.add(f);
        nextId = Math.max(input.getIntOr("next_id", 1), maxId() + 1);
        printerOrder.clear();
        input.read("printer_order", BlockPos.CODEC.listOf()).ifPresent(order -> {
            for (BlockPos l : order) if (printerOrder.size() < MAX_ORDER) printerOrder.add(l);
        });
        chosenPrinter = input.getLong("chosen_printer").map(BlockPos::of).orElse(null);
        displayOrder.clear();
        input.read("display_order", net.minecraft.core.UUIDUtil.CODEC.listOf()).ifPresent(order -> {
            for (UUID d : order) if (displayOrder.size() < MAX_ORDER) displayOrder.add(d);
        });
        app = input.getIntOr("app", APP_DESKTOP);
        if (app == APP_EDITING) app = APP_FILES;
        lastShot = input.read("shot", com.mojang.serialization.Codec.BYTE_BUFFER).map(b -> {
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            return bytes;
        }).filter(b -> b.length > 0).orElse(null);
        if (lastShot == null) {
            // older saves kept it as text
            String shot = input.getStringOr("shot", "");
            try {
                lastShot = shot.isEmpty() ? null : java.util.Base64.getDecoder().decode(shot);
            } catch (IllegalArgumentException e) {
                lastShot = null;
            }
        }
    }

    private int maxId() {
        int max = 0;
        for (TerminalFile f : files) max = Math.max(max, f.id());
        return max;
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (!files.isEmpty()) builder.set(PaperRegistry.TERMINAL_FILES.get(), List.copyOf(files));
        if (theme != null) builder.set(PaperRegistry.TERMINAL_THEME.get(), theme);
        if (!name.isEmpty()) builder.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name));
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        io.github.lwarqiu1shz.enplotify.api.TerminalTheme givenTheme = components.get(PaperRegistry.TERMINAL_THEME.get());
        if (givenTheme != null) theme = givenTheme;
        net.minecraft.network.chat.Component givenName = components.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        if (givenName != null) name = givenName.getString();
        List<TerminalFile> given = components.get(PaperRegistry.TERMINAL_FILES.get());
        if (given == null) return;
        // Ids are given again, in order: files from different sources never clash.
        files.clear();
        nextId = 1;
        for (TerminalFile f : given) {
            if (files.size() >= MAX_FILES) break;
            if (!f.stack().isEmpty()) files.add(new TerminalFile(nextId++, f.name(), f.stack().copyWithCount(1)));
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("files");
        output.discard("next_id");
        output.discard("shot");
        output.discard("printer_order");
        output.discard("theme");
        output.discard("name");
        output.discard("chosen_printer");
        output.discard("display_order");
        output.discard("last_user");
        output.discard("crash_message");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("sync", true);
        tag.putString("user", userName);
        tag.putInt("app", app);
        tag.putInt("power", power);
        tag.putString("name", name);
        tag.putLong("power_since", powerSince);
        if (theme != null) {
            io.github.lwarqiu1shz.enplotify.api.TerminalTheme.CODEC
                    .encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), theme)
                    .result().ifPresent(t -> tag.put("theme", t));
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
