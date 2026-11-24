package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * The printer's contents: blank paper (any number, one kind), the original being copied (one),
 * the sheet being printed and the finished copies in the tray. Paper and original must be the
 * same kind (documents, newspapers or posters). A copy is the original exactly; the paper used
 * for it is used up whatever was on it.
 * <p>
 * Clients get counts and kinds, plus the original and the top copy (to draw their content).
 */
public class PrinterBlockEntity extends BlockEntity {
    public static final int NONE = 0, DOCUMENT = 1, NEWSPAPER = 2, POSTER = 3, BOARD = 4;
    public static final int MAX_PAPER = 64, MAX_COPIES = 64;
    /** Five seconds per copy. */
    public static final int PRINT_TICKS = 100;

    private final List<ItemStack> paper = new ArrayList<>();
    private ItemStack original = ItemStack.EMPTY;
    private ItemStack printing = ItemStack.EMPTY;
    /** What comes out when the sheet being printed is done. */
    private ItemStack printOut = ItemStack.EMPTY;
    /** The sheet being printed is a copy of the original on the glass (stops if it is taken away). */
    private boolean fromOriginal;
    /** Prints sent from terminals, waiting their turn (the printed thing each). */
    private final List<ItemStack> jobs = new ArrayList<>();
    public static final int MAX_JOBS = 64;
    private int progress;
    private final List<ItemStack> copies = new ArrayList<>();
    /** The original's bottom edge points to the printer's left (low x in the model) — the side its owner stood on. */
    private boolean tailLowX;
    /** Which way the sheets in the tray lie: like the original's tail, or towards whoever put them in. */
    private boolean copyTailLowX;
    /** Set by players (Alt + left click); "" = the default name. */
    private String name = "";
    /** Had a redstone signal last time a neighbour changed (copies on the rising edge). */
    private boolean powered;

    // What clients see.
    private int paperCount, paperKind, originalKind, copyCount, copyKind, printKind;
    private ItemStack shownOriginal = ItemStack.EMPTY, shownCopy = ItemStack.EMPTY, shownPrint = ItemStack.EMPTY;

    public PrinterBlockEntity(BlockPos pos, BlockState state) {
        super(PaperRegistry.PRINTER_ENTITY.get(), pos, state);
    }

    /** Which kind of sheet {@code stack} is. */
    public static int kindOf(ItemStack stack) {
        return switch (stack.getItem()) {
            case DocumentItem ignored -> DOCUMENT;
            case NewspaperItem ignored -> NEWSPAPER;
            case PosterItem ignored -> POSTER;
            case BoardItem ignored -> BOARD;
            default -> NONE;
        };
    }

    // ------------------------------------------------------------------ actions (server)

    /**
     * Puts up to {@code count} blank sheets like {@code sheet} into the paper stack (for mods: a
     * printer that starts with paper in it). Returns how many went in.
     */
    public int addPaper(ItemStack sheet, int count) {
        int kind = kindOf(sheet);
        if (kind == NONE || kind == BOARD || !matches(kind)) return 0;
        int n = Math.min(count, MAX_PAPER - paper.size() - (printing.isEmpty() ? 0 : 1));
        if (n <= 0) return 0;
        for (int i = 0; i < n; i++) paper.add(sheet.copyWithCount(1));
        changed();
        return n;
    }

    public void insertPaper(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        int kind = kindOf(held);
        if (kind == NONE || kind == BOARD) {
            tell(player, "not_paper");
            return;
        }
        if (!matches(kind)) {
            tell(player, "mismatch");
            return;
        }
        int room = MAX_PAPER - paper.size() - (printing.isEmpty() ? 0 : 1);
        if (room <= 0) {
            tell(player, "full");
            return;
        }
        int n = Math.min(room, held.getCount());
        for (int i = 0; i < n; i++) paper.add(held.copyWithCount(1));
        if (!player.hasInfiniteMaterials()) held.shrink(n);
        sound(SoundEvents.BOOK_PAGE_TURN, 1.0F);
        changed();
    }

    public void insertOriginal(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        int kind = kindOf(held);
        if (kind == NONE || kind == BOARD) {
            tell(player, "cannot_copy");
            return;
        }
        if (!original.isEmpty()) {
            tell(player, "has_original");
            return;
        }
        if (!matches(kind)) {
            tell(player, "mismatch");
            return;
        }
        original = held.copyWithCount(1);
        tailLowX = sideLowX(player);
        if (!player.hasInfiniteMaterials()) held.shrink(1);
        sound(SoundEvents.BOOK_PAGE_TURN, 0.8F);
        changed();
    }

    /** Which side of the printer (seen from the front) the player is on: true = the low-x side of the model. */
    private boolean sideLowX(net.minecraft.world.entity.player.Player player) {
        net.minecraft.core.Direction right = getBlockState().getValue(PrinterBlock.FACING).getClockWise();
        double side = (player.getX() - (worldPosition.getX() + 0.5)) * right.getStepX()
                + (player.getZ() - (worldPosition.getZ() + 0.5)) * right.getStepZ();
        return side < 0.0;
    }

    public void takePaper(ServerPlayer player) {
        if (paper.isEmpty()) return;
        give(player, paper.removeLast());
        sound(SoundEvents.BOOK_PAGE_TURN, 1.2F);
        changed();
    }

    public void takeOriginal(ServerPlayer player) {
        if (original.isEmpty()) return;
        cancelPrint();
        give(player, original);
        original = ItemStack.EMPTY;
        sound(SoundEvents.BOOK_PAGE_TURN, 1.2F);
        changed();
    }

    /** Copies when the redstone signal turns on. */
    public void redstone(boolean nowPowered) {
        if (nowPowered == powered) return;
        powered = nowPowered;
        setChanged();
        if (nowPowered) startPrint(null);
    }

    public static final int MAX_NAME = 32;

    /** {@code text} becomes the name; blank = the default "打印机[x,y,z]". */
    public void rename(String text) {
        String clean = net.minecraft.util.StringUtil.filterText(text).strip();
        if (clean.length() > MAX_NAME) clean = clean.substring(0, MAX_NAME);
        if (clean.equals(name)) return;
        name = clean;
        changed();
    }

    /** The name set by a player, "" if none. */
    public String customName() {
        return name;
    }

    /** What the printer is called: its own name, or "打印机[x,y,z]". */
    public Component displayName() {
        return name.isEmpty()
                ? ScreenLink.defaultName(getBlockState().getBlock().getDescriptionId(), worldPosition)
                : Component.literal(name);
    }

    /** {@code player} null = started by redstone (no messages). */
    public void startPrint(@org.jspecify.annotations.Nullable ServerPlayer player) {
        if (!printing.isEmpty()) {
            tell(player, "busy");
        } else if (original.isEmpty()) {
            tell(player, "no_original");
        } else if (paper.isEmpty()) {
            tell(player, "no_paper");
        } else if (copies.size() >= MAX_COPIES) {
            tell(player, "full");
        } else {
            printing = paper.removeLast();
            printOut = original.copyWithCount(1);
            fromOriginal = true;
            progress = 0;
            sound(SoundEvents.DISPENSER_DISPENSE, 1.6F);
            changed();
        }
    }

    // ------------------------------------------------------------------ prints from terminals

    /** Adds {@code count} prints of {@code sheet}; false when the queue is full. */
    public boolean queue(ItemStack sheet, int count) {
        if (jobs.size() + count > MAX_JOBS) return false;
        for (int i = 0; i < count; i++) jobs.add(sheet.copyWithCount(1));
        setChanged();
        return true;
    }

    public int queued() {
        return jobs.size();
    }

    public boolean busy() {
        return !printing.isEmpty();
    }

    public int paperLeft() {
        return paper.size();
    }

    public int paperKindNow() {
        return paper.isEmpty() ? NONE : kindOf(paper.getFirst());
    }

    /** Printers loaded in each level: what terminals can reach. */
    private static final java.util.Map<Level, java.util.Set<PrinterBlockEntity>> LOADED = new java.util.HashMap<>();

    /** Forgets every level (the server stopped). */
    static void clearLoaded() {
        LOADED.clear();
    }

    public static java.util.Collection<PrinterBlockEntity> loaded(Level level) {
        java.util.Set<PrinterBlockEntity> set = LOADED.get(level);
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
    public void setRemoved() {
        super.setRemoved();
        forget();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        forget();
    }

    private void forget() {
        if (level == null) return;
        if (level.isClientSide()) {
            io.github.lwarqiu1shz.enplotify.client.paper.PrinterSound.removed(worldPosition);
            return;
        }
        java.util.Set<PrinterBlockEntity> set = LOADED.get(level);
        if (set != null) set.remove(this);
    }

    /** Puts the held sheets straight into the tray, as if printed (for setting up scenes). */
    public void insertCopies(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        int kind = kindOf(held);
        if (kind == NONE || kind == BOARD) {
            tell(player, "not_sheet");
            return;
        }
        int n = Math.min(MAX_COPIES - copies.size(), held.getCount());
        if (n <= 0) {
            tell(player, "full");
            return;
        }
        for (int i = 0; i < n; i++) copies.add(held.copyWithCount(1));
        // Turned towards the player who put them in (same rule as the original on the glass).
        copyTailLowX = sideLowX(player);
        if (!player.hasInfiniteMaterials()) held.shrink(n);
        sound(SoundEvents.BOOK_PAGE_TURN, 1.0F);
        changed();
    }

    public void takeCopy(ServerPlayer player) {
        if (copies.isEmpty()) return;
        give(player, copies.removeLast());
        sound(SoundEvents.BOOK_PAGE_TURN, 1.0F);
        changed();
    }

    /** Paper and original must be the same kind; {@code kind} is what is being put in. */
    private boolean matches(int kind) {
        if (!paper.isEmpty() && kindOf(paper.getFirst()) != kind) return false;
        if (!printing.isEmpty() && kindOf(printing) != kind) return false;
        return original.isEmpty() || kindOf(original) == kind;
    }

    private void cancelPrint() {
        if (printing.isEmpty()) return;
        paper.add(printing);
        printing = ItemStack.EMPTY;
        printOut = ItemStack.EMPTY;
        progress = 0;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    private static void tell(@org.jspecify.annotations.Nullable ServerPlayer player, String key) {
        if (player != null) ActionBar.show(player, Component.translatable("message.enplotify.printer." + key));
    }

    private void sound(net.minecraft.sounds.SoundEvent sound, float pitch) {
        if (level != null) level.playSound(null, worldPosition, sound, SoundSource.BLOCKS, 0.7F, pitch);
    }

    // ------------------------------------------------------------------ hoppers and pipes

    private final Handler handler = new Handler();

    /** What hoppers, pipes and the like see: slot 0 takes blank paper in, slot 1 gives copies out. */
    public net.neoforged.neoforge.transfer.ResourceHandler<net.neoforged.neoforge.transfer.item.ItemResource> itemHandler() {
        return handler;
    }

    private record Contents(List<ItemStack> paper, List<ItemStack> copies) {}

    private final class Handler extends net.neoforged.neoforge.transfer.transaction.SnapshotJournal<Contents>
            implements net.neoforged.neoforge.transfer.ResourceHandler<net.neoforged.neoforge.transfer.item.ItemResource> {
        private static final int PAPER_SLOT = 0, COPY_SLOT = 1;

        @Override
        public int size() {
            return 2;
        }

        @Override
        public net.neoforged.neoforge.transfer.item.ItemResource getResource(int index) {
            return index == COPY_SLOT && !copies.isEmpty()
                    ? net.neoforged.neoforge.transfer.item.ItemResource.of(copies.getLast())
                    : net.neoforged.neoforge.transfer.item.ItemResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            return index == COPY_SLOT ? topRun() : 0;
        }

        @Override
        public long getCapacityAsLong(int index, net.neoforged.neoforge.transfer.item.ItemResource resource) {
            return index == PAPER_SLOT ? paperRoom() : index == COPY_SLOT ? MAX_COPIES : 0;
        }

        @Override
        public boolean isValid(int index, net.neoforged.neoforge.transfer.item.ItemResource resource) {
            return index == PAPER_SLOT && acceptsPaper(resource);
        }

        @Override
        public int insert(int index, net.neoforged.neoforge.transfer.item.ItemResource resource, int amount,
                          net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            net.neoforged.neoforge.transfer.TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (index != PAPER_SLOT || !acceptsPaper(resource)) return 0;
            int n = Math.min(amount, paperRoom());
            if (n <= 0) return 0;
            updateSnapshots(transaction);
            for (int i = 0; i < n; i++) paper.add(resource.toStack(1));
            return n;
        }

        @Override
        public int extract(int index, net.neoforged.neoforge.transfer.item.ItemResource resource, int amount,
                           net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            net.neoforged.neoforge.transfer.TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
            if (index != COPY_SLOT || copies.isEmpty() || !resource.matches(copies.getLast())) return 0;
            int n = Math.min(amount, topRun());
            if (n <= 0) return 0;
            updateSnapshots(transaction);
            for (int i = 0; i < n; i++) copies.removeLast();
            return n;
        }

        /** How many copies on top of the tray are exactly alike. */
        private int topRun() {
            if (copies.isEmpty()) return 0;
            ItemStack top = copies.getLast();
            int n = 0;
            for (int i = copies.size() - 1; i >= 0 && ItemStack.isSameItemSameComponents(copies.get(i), top); i--) n++;
            return n;
        }

        private int paperRoom() {
            return Math.max(0, MAX_PAPER - paper.size() - (printing.isEmpty() ? 0 : 1));
        }

        private boolean acceptsPaper(net.neoforged.neoforge.transfer.item.ItemResource resource) {
            int kind = kindOf(resource.toStack(1));
            return kind != NONE && kind != BOARD && matches(kind);
        }

        @Override
        protected Contents createSnapshot() {
            return new Contents(new ArrayList<>(paper), new ArrayList<>(copies));
        }

        @Override
        protected void revertToSnapshot(Contents snapshot) {
            paper.clear();
            paper.addAll(snapshot.paper());
            copies.clear();
            copies.addAll(snapshot.copies());
        }

        @Override
        protected void onRootCommit(Contents originalState) {
            changed();
        }
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(Level level, BlockPos pos, BlockState state, PrinterBlockEntity printer) {
        if (level.isClientSide()) {
            if (printer.printKind != NONE && printer.progress < PRINT_TICKS) printer.progress++;
            io.github.lwarqiu1shz.enplotify.client.paper.PrinterSound.tick(printer, printer.printKind != NONE);
            return;
        }
        if (printer.printing.isEmpty()) {
            printer.nextJob();
            return;
        }
        if (printer.fromOriginal && printer.original.isEmpty()) {
            printer.cancelPrint();
            printer.changed();
            return;
        }
        printer.progress++;
        if (printer.progress >= PRINT_TICKS) {
            printer.copies.add(printer.printOut.isEmpty() ? printer.original.copyWithCount(1) : printer.printOut);
            printer.copyTailLowX = printer.tailLowX; // printed copies lie like the original
            printer.printing = ItemStack.EMPTY;
            printer.printOut = ItemStack.EMPTY;
            printer.progress = 0;
            printer.sound(SoundEvents.BOOK_PAGE_TURN, 1.0F); // a copy is done (the motor sound is the client's)
            printer.changed();
        }
    }

    /** Starts the next terminal print when there is matching paper and room in the tray. */
    private void nextJob() {
        if (jobs.isEmpty() || paper.isEmpty() || copies.size() >= MAX_COPIES) return;
        if (kindOf(paper.getFirst()) != kindOf(jobs.getFirst())) return;
        printing = paper.removeLast();
        printOut = jobs.removeFirst();
        fromOriginal = false;
        progress = 0;
        sound(SoundEvents.DISPENSER_DISPENSE, 1.6F);
        changed();
    }

    /** Saves, and tells clients what to draw. */
    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    // ------------------------------------------------------------------ drops, saving, syncing

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        List<ItemStack> all = new ArrayList<>(paper);
        all.add(printing);
        all.add(original);
        all.addAll(copies);
        for (ItemStack stack : all) {
            if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack.copy());
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ValueOutput.TypedOutputList<ItemStack> paperList = output.list("paper", ItemStack.CODEC);
        for (ItemStack stack : paper) paperList.add(stack);
        if (!original.isEmpty()) output.store("original", ItemStack.CODEC, original);
        if (tailLowX) output.putBoolean("tail_low_x", true);
        if (copyTailLowX) output.putBoolean("copy_tail_low_x", true);
        if (!printing.isEmpty()) {
            output.store("printing", ItemStack.CODEC, printing);
            output.putInt("progress", progress);
            if (!printOut.isEmpty()) output.store("print_out", ItemStack.CODEC, printOut);
            if (fromOriginal) output.putBoolean("from_original", true);
        }
        ValueOutput.TypedOutputList<ItemStack> jobList = output.list("jobs", ItemStack.CODEC);
        for (ItemStack stack : jobs) jobList.add(stack);
        ValueOutput.TypedOutputList<ItemStack> copyList = output.list("copies", ItemStack.CODEC);
        for (ItemStack stack : copies) copyList.add(stack);
        if (!name.isEmpty()) output.putString("name", name);
        if (powered) output.putBoolean("powered", true);
    }

    /** A named printer keeps its name as an item. */
    @Override
    protected void collectImplicitComponents(net.minecraft.core.component.DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (!name.isEmpty()) builder.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
    }

    @Override
    protected void applyImplicitComponents(net.minecraft.core.component.DataComponentGetter components) {
        super.applyImplicitComponents(components);
        Component custom = components.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        if (custom != null) rename(custom.getString());
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("name");
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (input.getBooleanOr("sync", false)) {
            paperCount = input.getIntOr("paper_n", 0);
            paperKind = input.getIntOr("paper_kind", NONE);
            originalKind = input.getIntOr("original_kind", NONE);
            copyCount = input.getIntOr("copy_n", 0);
            copyKind = input.getIntOr("copy_kind", NONE);
            int newPrintKind = input.getIntOr("print_kind", NONE);
            int newProgress = input.getIntOr("progress", 0);
            // Keep the client's own smooth count unless the server says otherwise.
            if (newPrintKind != printKind || Math.abs(newProgress - progress) > 10) progress = newProgress;
            printKind = newPrintKind;
            tailLowX = input.getBooleanOr("tail_low_x", false);
            copyTailLowX = input.getBooleanOr("copy_tail_low_x", tailLowX);
            name = input.getStringOr("name", "");
            // Keep the very same stacks while their content is unchanged: drawn pages are cached per
            // content object, and a fresh copy would be painted again (the sheet would flicker).
            ItemStack newOriginal = input.read("original_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            ItemStack newCopy = input.read("copy_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            ItemStack newPrint = input.read("print_item", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            ItemStack oldOriginal = shownOriginal, oldCopy = shownCopy, oldPrint = shownPrint;
            shownOriginal = reuse(reuse(newOriginal, oldOriginal, oldCopy), oldPrint, oldPrint);
            shownCopy = reuse(reuse(newCopy, shownOriginal, oldCopy), oldPrint, oldPrint);
            shownPrint = reuse(reuse(newPrint, shownOriginal, shownCopy), oldPrint, oldPrint);
            return;
        }
        paper.clear();
        for (ItemStack stack : input.listOrEmpty("paper", ItemStack.CODEC)) paper.add(stack);
        original = input.read("original", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        tailLowX = input.getBooleanOr("tail_low_x", false);
        copyTailLowX = input.getBooleanOr("copy_tail_low_x", tailLowX);
        printing = input.read("printing", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        progress = printing.isEmpty() ? 0 : input.getIntOr("progress", 0);
        printOut = input.read("print_out", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        // Older saves: whatever was printing was a copy of the original.
        fromOriginal = !printing.isEmpty() && (printOut.isEmpty() || input.getBooleanOr("from_original", false));
        jobs.clear();
        for (ItemStack stack : input.listOrEmpty("jobs", ItemStack.CODEC)) jobs.add(stack);
        copies.clear();
        for (ItemStack stack : input.listOrEmpty("copies", ItemStack.CODEC)) copies.add(stack);
        name = input.getStringOr("name", "");
        powered = input.getBooleanOr("powered", false);
    }

    /** {@code fresh}, or one of the stacks already shown if it has exactly the same content. */
    private static ItemStack reuse(ItemStack fresh, ItemStack a, ItemStack b) {
        if (fresh.isEmpty()) return ItemStack.EMPTY;
        if (ItemStack.isSameItemSameComponents(fresh, a)) return a;
        if (ItemStack.isSameItemSameComponents(fresh, b)) return b;
        return fresh;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("sync", true);
        tag.putInt("paper_n", paper.size());
        tag.putInt("paper_kind", paper.isEmpty() ? NONE : kindOf(paper.getFirst()));
        tag.putInt("original_kind", kindOf(original));
        tag.putInt("copy_n", copies.size());
        tag.putInt("copy_kind", copies.isEmpty() ? NONE : kindOf(copies.getLast()));
        tag.putInt("print_kind", kindOf(printing));
        tag.putInt("progress", progress);
        // The original and the top copy whole, so their content can be drawn.
        net.minecraft.resources.RegistryOps<net.minecraft.nbt.Tag> ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        tag.putBoolean("tail_low_x", tailLowX);
        tag.putBoolean("copy_tail_low_x", copyTailLowX);
        if (!name.isEmpty()) tag.putString("name", name);
        if (!original.isEmpty()) tag.store("original_item", ItemStack.CODEC, ops, original);
        if (!copies.isEmpty()) tag.store("copy_item", ItemStack.CODEC, ops, copies.getLast());
        ItemStack out = printOut.isEmpty() ? original : printOut;
        if (!printing.isEmpty() && !out.isEmpty()) tag.store("print_item", ItemStack.CODEC, ops, out);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------ for drawing (client)

    public int paperCount() { return paperCount; }
    public int paperKind() { return paperKind; }
    public int originalKind() { return originalKind; }
    public int copyCount() { return copyCount; }
    public int copyKind() { return copyKind; }
    public int printKind() { return printKind; }
    public boolean tailLowX() { return tailLowX; }
    public boolean copyTailLowX() { return copyTailLowX; }
    public ItemStack shownOriginal() { return shownOriginal; }
    public ItemStack shownCopy() { return shownCopy; }
    public ItemStack shownPrint() { return shownPrint; }

    /** How far the sheet being printed is out, 0..1. */
    public float printProgress(float partialTicks) {
        return printKind == NONE ? 0.0F : Math.min(1.0F, (progress + partialTicks) / PRINT_TICKS);
    }
}
