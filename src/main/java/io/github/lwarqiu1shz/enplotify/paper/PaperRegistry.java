package io.github.lwarqiu1shz.enplotify.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Everything the paper features (poster, newspaper, document, writing boards) register. */
public final class PaperRegistry {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EnPlotify.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EnPlotify.MOD_ID);
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(EnPlotify.MOD_ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EnPlotify.MOD_ID);
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EnPlotify.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, EnPlotify.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EnPlotify.MOD_ID);

    // ------------------------------------------------------------------ data components

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PaperContents.Poster>> POSTER_CONTENT =
            COMPONENTS.registerComponentType("poster", b -> b
                    .persistent(PaperContents.Poster.CODEC).networkSynchronized(PaperContents.Poster.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PaperContents.Newspaper>> NEWSPAPER_CONTENT =
            COMPONENTS.registerComponentType("newspaper", b -> b
                    .persistent(PaperContents.Newspaper.CODEC).networkSynchronized(PaperContents.Newspaper.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PaperContents.Document>> DOCUMENT_CONTENT =
            COMPONENTS.registerComponentType("document", b -> b
                    .persistent(PaperContents.Document.CODEC).networkSynchronized(PaperContents.Document.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PaperContents.Board>> BOARD_CONTENT =
            COMPONENTS.registerComponentType("board", b -> b
                    .persistent(PaperContents.Board.CODEC).networkSynchronized(PaperContents.Board.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<java.util.List<TerminalFile>>> TERMINAL_FILES =
            COMPONENTS.registerComponentType("terminal_files", b -> b
                    .persistent(TerminalFile.LIST_CODEC).networkSynchronized(TerminalFile.LIST_STREAM_CODEC));

    /** A terminal's own start-up logo and wallpaper (see {@link io.github.lwarqiu1shz.enplotify.api.TerminalTheme}). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<io.github.lwarqiu1shz.enplotify.api.TerminalTheme>> TERMINAL_THEME =
            COMPONENTS.registerComponentType("terminal_theme", b -> b
                    .persistent(io.github.lwarqiu1shz.enplotify.api.TerminalTheme.CODEC)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.fromCodecWithRegistries(
                            io.github.lwarqiu1shz.enplotify.api.TerminalTheme.CODEC)));

    // ------------------------------------------------------------------ entities

    public static final DeferredHolder<EntityType<?>, EntityType<PosterEntity>> POSTER_ENTITY =
            ENTITIES.registerEntityType("poster", PosterEntity::new, MobCategory.MISC, b -> b
                    .noLootTable().sized(0.5F, 0.5F).eyeHeight(0.0F).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE));
    public static final DeferredHolder<EntityType<?>, EntityType<BoardEntity>> BOARD_ENTITY =
            ENTITIES.registerEntityType("writing_board", BoardEntity::new, MobCategory.MISC, b -> b
                    .noLootTable().sized(0.5F, 0.5F).eyeHeight(0.0F).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE));
    public static final DeferredHolder<EntityType<?>, EntityType<DisplayEntity>> DISPLAY_ENTITY =
            ENTITIES.registerEntityType("display", DisplayEntity::new, MobCategory.MISC, b -> b
                    .noLootTable().sized(0.5F, 0.5F).eyeHeight(0.0F).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE));
    public static final DeferredHolder<EntityType<?>, EntityType<SawmoBoardEntity>> SAWMO_ENTITY =
            ENTITIES.registerEntityType("sawmo_whiteboard", SawmoBoardEntity::new, MobCategory.MISC, b -> b
                    .noLootTable().sized(0.5F, 0.5F).eyeHeight(0.0F).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE));
    public static final DeferredHolder<EntityType<?>, EntityType<PaperStackEntity>> PAPER_STACK_ENTITY =
            ENTITIES.registerEntityType("paper_stack", PaperStackEntity::new, MobCategory.MISC, b -> b
                    .noLootTable().sized(0.6F, 0.05F).eyeHeight(0.0F).clientTrackingRange(8).updateInterval(Integer.MAX_VALUE));

    public static final DeferredHolder<EntityType<?>, EntityType<io.github.lwarqiu1shz.enplotify.frame.GhostItemFrame>> GHOST_ITEM_FRAME_ENTITY =
            ENTITIES.registerEntityType("ghost_item_frame", io.github.lwarqiu1shz.enplotify.frame.GhostItemFrame::create, MobCategory.MISC, b -> b
                    .noLootTable().sized(0.5F, 0.5F).eyeHeight(0.0F).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE));

    // ------------------------------------------------------------------ printer

    public static final net.neoforged.neoforge.registries.DeferredBlock<PrinterBlock> PRINTER = BLOCKS.registerBlock("printer",
            PrinterBlock::new, PaperRegistry::printerProperties);

    /** How every printer block behaves as a block (other mods' looks of it too). */
    public static net.minecraft.world.level.block.state.BlockBehaviour.Properties printerProperties(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties p) {
        return p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_GRAY)
                .strength(0.65F).sound(net.minecraft.world.level.block.SoundType.METAL).noOcclusion();
    }
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<PrinterBlockEntity>> PRINTER_ENTITY = BLOCK_ENTITIES.register("printer",
            () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(PrinterBlockEntity::new, PRINTER.get()));

    // ------------------------------------------------------------------ terminal

    public static final net.neoforged.neoforge.registries.DeferredBlock<TerminalBlock> TERMINAL = BLOCKS.registerBlock("terminal",
            TerminalBlock::new, PaperRegistry::terminalProperties);

    /** How every terminal block behaves as a block (other mods' looks of it too). */
    public static net.minecraft.world.level.block.state.BlockBehaviour.Properties terminalProperties(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties p) {
        return p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_BLACK)
                .strength(0.65F).sound(net.minecraft.world.level.block.SoundType.METAL).noOcclusion()
                .lightLevel(s -> TerminalBlock.hasScreen(s) ? 4 : 0);
    }

    /** Other mods' terminal and printer blocks (see EnPlotifyContent): they share the block entities. */
    public static final java.util.List<java.util.function.Supplier<? extends TerminalBlock>> EXTRA_TERMINALS = new java.util.ArrayList<>();
    public static final java.util.List<java.util.function.Supplier<? extends PrinterBlock>> EXTRA_PRINTERS = new java.util.ArrayList<>();

    private static void onAddBlocks(net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent event) {
        for (var block : EXTRA_TERMINALS) event.modify(TERMINAL_ENTITY.get(), block.get());
        for (var block : EXTRA_PRINTERS) event.modify(PRINTER_ENTITY.get(), block.get());
    }
    public static final DeferredHolder<net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<TerminalBlockEntity>> TERMINAL_ENTITY = BLOCK_ENTITIES.register("terminal",
            () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(TerminalBlockEntity::new, TERMINAL.get()));

    // ------------------------------------------------------------------ items (the blank, player-made kinds)

    public static final DeferredItem<PosterItem> POSTER = ITEMS.registerItem("poster",
            p -> new PosterItem(p, null, 0, 0));
    public static final DeferredItem<NewspaperItem> NEWSPAPER = ITEMS.registerItem("newspaper",
            p -> new NewspaperItem(p, null));
    public static final DeferredItem<DocumentItem> DOCUMENT = ITEMS.registerItem("document",
            p -> new DocumentItem(p, null));
    public static final DeferredItem<BoardItem> BLACKBOARD = ITEMS.registerItem("blackboard",
            p -> new BoardItem(p, BoardItem.Style.BLACKBOARD, null, 0, 0));
    public static final DeferredItem<BoardItem> WHITEBOARD = ITEMS.registerItem("whiteboard",
            p -> new BoardItem(p, BoardItem.Style.WHITEBOARD, null, 0, 0));
    public static final DeferredItem<Item> CHALK = ITEMS.registerSimpleItem("chalk");
    public static final DeferredItem<Item> WHITEBOARD_MARKER = ITEMS.registerItem("whiteboard_marker",
            Item::new, p -> p.stacksTo(16));
    public static final DeferredItem<io.github.lwarqiu1shz.enplotify.frame.GhostItemFrameItem> GHOST_ITEM_FRAME =
            ITEMS.registerItem("ghost_item_frame", io.github.lwarqiu1shz.enplotify.frame.GhostItemFrameItem::new);
    public static final DeferredItem<PrinterItem> PRINTER_ITEM = ITEMS.registerItem("printer",
            p -> new PrinterItem(PRINTER.get(), p.useBlockDescriptionPrefix()));
    public static final DeferredItem<TerminalItem> TERMINAL_ITEM = ITEMS.registerItem("terminal",
            p -> new TerminalItem(TERMINAL.get(), p.useBlockDescriptionPrefix()));
    public static final DeferredItem<DisplayItem> DISPLAY = ITEMS.registerItem("display", DisplayItem::new);
    public static final DeferredItem<SawmoItem> SAWMO_WHITEBOARD = ITEMS.registerItem("sawmo_whiteboard", SawmoItem::new);
    public static final DeferredItem<KeyboardItem> KEYBOARD_ITEM = ITEMS.registerItem("keyboard",
            p -> new KeyboardItem(TERMINAL.get(), p));

    // ------------------------------------------------------------------ recipes, creative tab

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<PaperResetRecipe>> RESET_RECIPE =
            SERIALIZERS.register("reset_paper", () -> PaperResetRecipe.SERIALIZER);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.enplotify"))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(() -> POSTER.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(POSTER.get());
                        output.accept(NEWSPAPER.get());
                        output.accept(DOCUMENT.get());
                        output.accept(BLACKBOARD.get());
                        output.accept(WHITEBOARD.get());
                        output.accept(CHALK.get());
                        output.accept(WHITEBOARD_MARKER.get());
                        output.accept(GHOST_ITEM_FRAME.get());
                        output.accept(PRINTER_ITEM.get());
                        output.accept(TERMINAL_ITEM.get());
                        output.accept(KEYBOARD_ITEM.get());
                        output.accept(DISPLAY.get());
                        output.accept(SAWMO_WHITEBOARD.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(PaperRegistry::onRegisterCapabilities);
        modBus.addListener(PaperRegistry::onAddBlocks);
        SERIALIZERS.register(modBus);
        TABS.register(modBus);
    }

    /** Hoppers, pipes and the like put blank paper into printers and take copies out of the tray. */
    private static void onRegisterCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, PRINTER_ENTITY.get(),
                (printer, side) -> printer.itemHandler());
    }

    private PaperRegistry() {}
}
