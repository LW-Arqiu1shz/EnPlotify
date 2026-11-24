package io.github.lwarqiu1shz.enplotify.api;

import io.github.lwarqiu1shz.enplotify.paper.BoardItem;
import io.github.lwarqiu1shz.enplotify.paper.DocumentItem;
import io.github.lwarqiu1shz.enplotify.paper.NewspaperItem;
import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import io.github.lwarqiu1shz.enplotify.paper.PosterItem;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Lets another mod add its own ready-made posters, newspapers, documents and boards. The items are
 * registered in the calling mod's {@link DeferredRegister.Items}, so they belong to that mod (their id
 * is {@code <that mod>:<name>}); EnPlotify only supplies how they behave and are drawn.
 * <p>
 * The content comes from the calling mod's resources, {@code assets/<modid>/enplotify/<kind>/<name>.json}:
 * <pre>
 *   poster/&lt;name&gt;.json     { "image": "modid:textures/enplotify/lab_poster.png", "caption": "lang.key or text" }
 *   newspaper/&lt;name&gt;.json  { "headline": "...", "image": "...", "body": "..." }
 *   document/&lt;name&gt;.json   { "title": "...", "pages": [ { "image": "...", "text": "..." }, ... ] }
 *   board/&lt;name&gt;.json      { "text": "...", "color": 0 }
 * </pre>
 * Every text field may be a language key (then it follows the player's language) or plain text;
 * every field is optional.
 * <p>
 * Instead of the simple layout above, newspapers and documents may give whole canvases, each a list
 * of elements with the fields of {@code Canvas.Element} ({@code type} 0 = text, 1 = picture, 2 = LaTeX;
 * {@code x y w h} as fractions of the sheet; {@code text}; {@code runs} [{@code s e f c}] = styled
 * character ranges, f: 1 bold, 2 italic, c: 0xRRGGBB or -1; {@code color} = 0xRRGGBB plus alignment
 * bits from 24; {@code size} = text height as a fraction of the sheet height):
 * <pre>
 *   newspaper/&lt;name&gt;.json  { "title": "...", "halves": [ [ {element}, ... ] x 4 ] }   front L, front R, back L, back R
 *   document/&lt;name&gt;.json   { "title": "...", "sheets": [ [ {element}, ... ], ... ] }  up to 16 pages
 * </pre>
 * A list entry may be a simple page object or a canvas array, mixed freely.
 * <p>
 * Each content file may also come per language as {@code <name>.<lang>.json} (e.g.
 * {@code report.zh_cn.json}, {@code report.en_us.json}), useful when page breaks differ between
 * languages. The first that exists is used: the selected language, {@code zh_cn} for any Chinese,
 * {@code en_us}, then the plain {@code <name>.json}.
 * <p>
 * Ready-made newspapers and documents are named "&lt;kind&gt;: &lt;item name&gt;" (e.g. "Document: ..."). The item still needs the usual item model ({@code assets/<modid>/items/<name>.json}),
 * which may simply point at EnPlotify's models, e.g. {@code "enplotify:item/poster"}.
 */
public final class EnPlotifyContent {
    /** A poster of {@code width x height} cells (1 to 8 each). */
    public static DeferredItem<PosterItem> poster(DeferredRegister.Items items, String name, int width, int height) {
        Identifier id = Identifier.fromNamespaceAndPath(items.getNamespace(), name);
        int w = Mth.clamp(width, 1, PaperContents.MAX_CELLS);
        int h = Mth.clamp(height, 1, PaperContents.MAX_CELLS);
        return items.registerItem(name, p -> new PosterItem(p, id, w, h));
    }

    public static DeferredItem<NewspaperItem> newspaper(DeferredRegister.Items items, String name) {
        Identifier id = Identifier.fromNamespaceAndPath(items.getNamespace(), name);
        return items.registerItem(name, p -> new NewspaperItem(p, id));
    }

    public static DeferredItem<DocumentItem> document(DeferredRegister.Items items, String name) {
        Identifier id = Identifier.fromNamespaceAndPath(items.getNamespace(), name);
        return items.registerItem(name, p -> new DocumentItem(p, id));
    }

    /** A board of {@code width x height} cells with fixed writing (players can't write on it). */
    public static DeferredItem<BoardItem> board(DeferredRegister.Items items, String name, boolean whiteboard, int width, int height) {
        Identifier id = Identifier.fromNamespaceAndPath(items.getNamespace(), name);
        int w = Mth.clamp(width, 1, PaperContents.MAX_CELLS);
        int h = Mth.clamp(height, 1, PaperContents.MAX_CELLS);
        BoardItem.Style style = whiteboard ? BoardItem.Style.WHITEBOARD : BoardItem.Style.BLACKBOARD;
        return items.registerItem(name, p -> new BoardItem(p, style, id, w, h));
    }

    // ------------------------------------------------------------------ other looks of EnPlotify's devices

    /** A block and its item, registered under the same name. */
    public record BlockAndItem<B extends net.minecraft.world.level.block.Block, I extends net.minecraft.world.item.BlockItem>(
            net.neoforged.neoforge.registries.DeferredBlock<B> block, DeferredItem<I> item) {}

    /**
     * A terminal of the calling mod: it works exactly like EnPlotify's (same block entity, programs,
     * power, files, printers and screens), only its look and name are the mod's own. EnPlotify's
     * keyboard goes into it as usual. The mod provides:
     * <ul>
     *   <li>{@code blockstates/<name>.json} with the properties {@code facing}, {@code screen}
     *   (none / wall / desk) and {@code keyboard} (copy EnPlotify's {@code terminal.json} and point
     *   it at the mod's own models, or at {@code enplotify:block/...});</li>
     *   <li>{@code items/<name>.json};</li>
     *   <li>{@code data/<modid>/loot_table/blocks/<name>.json} (EnPlotify's {@code terminal.json}
     *   with the block and screen item renamed; the keyboard stays {@code enplotify:keyboard}).</li>
     * </ul>
     * A start-up look or ready files go on the item as the components {@code enplotify:terminal_theme}
     * and {@code enplotify:terminal_files} (e.g. in {@code ModifyDefaultComponentsEvent}).
     */
    public static BlockAndItem<io.github.lwarqiu1shz.enplotify.paper.TerminalBlock, io.github.lwarqiu1shz.enplotify.paper.TerminalItem> terminal(
            DeferredRegister.Blocks blocks, DeferredRegister.Items items, String name) {
        var block = blocks.registerBlock(name, io.github.lwarqiu1shz.enplotify.paper.TerminalBlock::new,
                io.github.lwarqiu1shz.enplotify.paper.PaperRegistry::terminalProperties);
        io.github.lwarqiu1shz.enplotify.paper.PaperRegistry.EXTRA_TERMINALS.add(block);
        var item = items.registerItem(name, p -> new io.github.lwarqiu1shz.enplotify.paper.TerminalItem(block.get(), p.useBlockDescriptionPrefix()));
        return new BlockAndItem<>(block, item);
    }

    /**
     * A printer of the calling mod, working exactly like EnPlotify's. The mod provides
     * {@code blockstates/<name>.json} (property {@code facing}), {@code items/<name>.json} and
     * {@code data/<modid>/loot_table/blocks/<name>.json} (EnPlotify's {@code printer.json} renamed).
     * Paper can be put in from code with {@code PrinterBlockEntity.addPaper}.
     */
    public static BlockAndItem<io.github.lwarqiu1shz.enplotify.paper.PrinterBlock, io.github.lwarqiu1shz.enplotify.paper.PrinterItem> printer(
            DeferredRegister.Blocks blocks, DeferredRegister.Items items, String name) {
        var block = blocks.registerBlock(name, io.github.lwarqiu1shz.enplotify.paper.PrinterBlock::new,
                io.github.lwarqiu1shz.enplotify.paper.PaperRegistry::printerProperties);
        io.github.lwarqiu1shz.enplotify.paper.PaperRegistry.EXTRA_PRINTERS.add(block);
        var item = items.registerItem(name, p -> new io.github.lwarqiu1shz.enplotify.paper.PrinterItem(block.get(), p.useBlockDescriptionPrefix()));
        return new BlockAndItem<>(block, item);
    }

    /**
     * A SAWMO whiteboard of the calling mod: written on and connected to terminals exactly like
     * EnPlotify's. It hangs at {@code width x height} cells (1 to 8 each; 0, 0 = asks for its size).
     * On the wall it is EnPlotify's SAWMO whiteboard holding this item (breaking it gives the item back).
     */
    public static DeferredItem<io.github.lwarqiu1shz.enplotify.paper.SawmoItem> sawmo(DeferredRegister.Items items, String name, int width, int height) {
        int w = width <= 0 ? 0 : Mth.clamp(width, 1, PaperContents.MAX_CELLS);
        int h = width <= 0 ? 0 : Mth.clamp(height, 1, PaperContents.MAX_CELLS);
        return items.registerItem(name, p -> new io.github.lwarqiu1shz.enplotify.paper.SawmoItem(p, w, h));
    }

    /**
     * Marks a device (terminal, printer or SAWMO whiteboard, given by its block or item) as one of a
     * kind: its default name is just its name, without "[x,y,z]". Call it while registering.
     */
    public static void oneOfAKind(java.util.function.Supplier<? extends net.minecraft.world.level.ItemLike> device) {
        io.github.lwarqiu1shz.enplotify.paper.UniqueDevices.add(device);
    }

    private EnPlotifyContent() {}
}
