package io.github.lwarqiu1shz.enplotify.client.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.lwarqiu1shz.enplotify.paper.BoardItem;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import io.github.lwarqiu1shz.enplotify.paper.DocumentItem;
import io.github.lwarqiu1shz.enplotify.paper.NewspaperItem;
import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import io.github.lwarqiu1shz.enplotify.paper.PaperItem;
import io.github.lwarqiu1shz.enplotify.paper.PosterItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The canvases a paper item shows: player-made ones from the item's data, or a mod's ready-made
 * content from {@code assets/<modid>/enplotify/<kind>/<name>.json} laid out on canvases.
 * Results for ready-made items are cached, so the same canvas objects come back every frame.
 */
public final class PaperViews {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static final Map<String, Object> PRESETS = new HashMap<>();

    /** Surface proportions (width / height). */
    public static final float HALF_ASPECT = 297.0F / 420.0F;
    public static final float PAGE_ASPECT = 210.0F / 297.0F;
    /** Page limit for ready-made documents from resources (player documents keep PaperContents.MAX_PAGES). */
    public static final int PRESET_MAX_PAGES = 64;
    /** Paper colours, 0xAARRGGBB. */
    public static final int POSTER_PAPER = 0xFFF3EFE4;
    public static final int NEWSPRINT = 0xFFBEBBB5;
    public static final int DOCUMENT_PAPER = 0xFFF5F2E9;
    public static final int BLACKBOARD = 0xFF2E3B33;
    public static final int WHITEBOARD = 0xFFF4F4F1;
    private static final int INK = 0x2A2522;

    public record NewspaperView(String title, List<Canvas> halves, String author) {}
    public record DocumentView(String title, List<Canvas> pages, String author) {}

    public static Canvas poster(ItemStack stack) {
        if (stack.getItem() instanceof PaperItem paper && paper.isPreset()) {
            return (Canvas) PRESETS.computeIfAbsent("poster|" + language() + "|" + paper.preset(), k -> {
                JsonObject json = presetJson("poster", paper.preset());
                List<Canvas.Element> elements = new ArrayList<>();
                String caption = text(json, "caption");
                String image = str(json, "image");
                float bottom = caption.isEmpty() ? 0.95F : 0.8F;
                if (!image.isEmpty()) elements.add(Canvas.Element.image(0.05F, 0.05F, 0.9F, bottom - 0.05F, image));
                if (!caption.isEmpty()) elements.add(Canvas.Element.text(0.05F, 0.83F, 0.9F, 0.14F, caption, INK | (1 << PaperContents.ALIGN_SHIFT), 0.07F));
                return new Canvas(elements);
            });
        }
        return PosterItem.content(stack).canvas();
    }

    public static NewspaperView newspaper(ItemStack stack) {
        if (stack.getItem() instanceof PaperItem paper && paper.isPreset()) {
            return (NewspaperView) PRESETS.computeIfAbsent("newspaper|" + language() + "|" + paper.preset(), k -> {
                JsonObject json = presetJson("newspaper", paper.preset());
                List<Canvas> halves = new ArrayList<>();
                if (json.get("halves") instanceof JsonArray array) {
                    for (JsonElement element : array) {
                        if (element instanceof JsonObject half) halves.add(simplePage(half, "headline", "body"));
                        else if (element instanceof JsonArray canvas) halves.add(canvas(canvas));
                    }
                } else {
                    halves.add(simplePage(json, "headline", "body"));
                }
                while (halves.size() < PaperContents.NEWSPAPER_HALVES) halves.add(Canvas.EMPTY);
                return new NewspaperView(text(json, "title"), List.copyOf(halves.subList(0, PaperContents.NEWSPAPER_HALVES)), "");
            });
        }
        PaperContents.Newspaper content = NewspaperItem.content(stack);
        return new NewspaperView(content.title(), content.halves(), content.author());
    }

    public static DocumentView document(ItemStack stack) {
        if (stack.getItem() instanceof PaperItem paper && paper.isPreset()) {
            return (DocumentView) PRESETS.computeIfAbsent("document|" + language() + "|" + paper.preset(), k -> {
                JsonObject json = presetJson("document", paper.preset());
                List<Canvas> pages = new ArrayList<>();
                for (String key : new String[] {"pages", "sheets"}) {
                    if (!(json.get(key) instanceof JsonArray array)) continue;
                    for (JsonElement element : array) {
                        if (element instanceof JsonObject page) pages.add(simplePage(page, "heading", "text"));
                        else if (element instanceof JsonArray canvas) pages.add(canvas(canvas));
                    }
                    break;
                }
                // Presets are read from resources on the client and never synced, so they are not
                // bound by the 16-page limit of player-written documents.
                if (pages.size() > PRESET_MAX_PAGES) pages = pages.subList(0, PRESET_MAX_PAGES);
                if (pages.isEmpty()) pages.add(Canvas.EMPTY);
                return new DocumentView(text(json, "title"), List.copyOf(pages), "");
            });
        }
        PaperContents.Document content = DocumentItem.content(stack);
        return new DocumentView(content.title(), content.pages(), content.author());
    }

    /** A mod-provided board's fixed writing. */
    public static Canvas presetBoard(BoardItem board, int cellsHigh) {
        return (Canvas) PRESETS.computeIfAbsent("board|" + language() + "|" + board.preset(), k -> {
            JsonObject json = presetJson("board", board.preset());
            int index = json.get("color") != null && json.get("color").isJsonPrimitive() ? json.get("color").getAsInt() : 0;
            int[] colors = board.style().colors();
            String text = text(json, "text");
            if (text.isEmpty()) return Canvas.EMPTY;
            return new Canvas(List.of(Canvas.Element.text(0.04F, 0.04F, 0.92F, 0.92F, text,
                    colors[Math.floorMod(index, colors.length)], 0.12F / Math.max(1, cellsHigh))));
        });
    }

    /** heading on top, picture below it, then the text: the simple layout ready-made content uses. */
    private static Canvas simplePage(JsonObject json, String headingKey, String bodyKey) {
        List<Canvas.Element> elements = new ArrayList<>();
        float top = 0.05F;
        String heading = text(json, headingKey);
        if (heading.isEmpty() && !"heading".equals(headingKey)) heading = text(json, "heading");
        if (!heading.isEmpty()) {
            elements.add(Canvas.Element.text(0.06F, top, 0.88F, 0.1F, heading, INK | (1 << PaperContents.ALIGN_SHIFT), 0.045F));
            top += 0.11F;
        }
        String image = str(json, "image");
        if (!image.isEmpty()) {
            elements.add(Canvas.Element.image(0.06F, top, 0.88F, 0.3F, image));
            top += 0.32F;
        }
        String body = text(json, bodyKey);
        if (!body.isEmpty()) elements.add(Canvas.Element.text(0.06F, top, 0.88F, 0.95F - top, body, INK, 0.025F));
        return new Canvas(elements);
    }

    /** A whole canvas written out element by element (the fields of {@link Canvas.Element}). */
    private static Canvas canvas(JsonArray elements) {
        return Canvas.Element.CODEC.listOf().parse(JsonOps.INSTANCE, elements)
                .resultOrPartial(error -> LOGGER.warn("EnPlotify: bad canvas in a content file: {}", error))
                .map(list -> list.size() > Canvas.MAX_ELEMENTS ? new Canvas(list.subList(0, Canvas.MAX_ELEMENTS)) : new Canvas(list))
                .orElse(Canvas.EMPTY);
    }

    private static String language() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    /** Languages to try, like stories: the selected one, Simplified Chinese for any Chinese, then English. */
    private static List<String> languages() {
        String selected = language();
        List<String> out = new ArrayList<>();
        out.add(selected);
        if (selected.startsWith("zh_") && !selected.equals("zh_cn")) out.add("zh_cn");
        if (!selected.equals("en_us")) out.add("en_us");
        return out;
    }

    private static JsonObject presetJson(String kind, Identifier id) {
        if (id == null) return new JsonObject();
        String base = "enplotify/" + kind + "/" + id.getPath();
        Optional<Resource> resource = Optional.empty();
        Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), base + ".json");
        for (String language : languages()) {
            Identifier localized = Identifier.fromNamespaceAndPath(id.getNamespace(), base + "." + language + ".json");
            resource = Minecraft.getInstance().getResourceManager().getResource(localized);
            if (resource.isPresent()) {
                file = localized;
                break;
            }
        }
        if (resource.isEmpty()) resource = Minecraft.getInstance().getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            LOGGER.warn("EnPlotify: no content file {} for {}", file, id);
            return new JsonObject();
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonElement json = JsonParser.parseReader(reader);
            return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOGGER.warn("EnPlotify: could not read {}", file, e);
            return new JsonObject();
        }
    }

    private static String str(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** A language key if one exists with that name, else the text itself. */
    private static String text(JsonObject json, String key) {
        String raw = str(json, key);
        return !raw.isEmpty() && I18n.exists(raw) ? I18n.get(raw) : raw;
    }

    public static void onLogout() {
        PRESETS.clear();
    }

    private PaperViews() {}
}
