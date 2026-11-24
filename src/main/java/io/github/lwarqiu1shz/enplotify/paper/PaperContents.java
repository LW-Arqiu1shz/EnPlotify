package io.github.lwarqiu1shz.enplotify.paper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a player made on a paper item. Every surface is a {@link Canvas} of freely placed text,
 * formulas and pictures (boards also take handwriting). Items written by r3 (fixed picture +
 * caption / headline + body layouts) are converted to canvases when read.
 */
public final class PaperContents {
    public static final int MAX_CELLS = 8;
    public static final int MAX_TITLE = 64;
    public static final int MAX_PAGES = 16;
    public static final int MAX_AUTHOR = 16;
    public static final int NEWSPAPER_HALVES = 4;

    /** Text colour flag bits above the RGB: 0 left, 1 centred, 2 right aligned. */
    public static final int ALIGN_SHIFT = 24;
    /** Next two bits: 0 top, 1 middle, 2 bottom. */
    public static final int VALIGN_SHIFT = 26;
    /** Centred both ways: what new text boxes get. */
    public static final int CENTERED = (1 << ALIGN_SHIFT) | (1 << VALIGN_SHIFT);
    public static final int MAX_NAME = 64;

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public static boolean isImageHash(String value) {
        return value != null && HASH.matcher(value).matches();
    }

    public static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static <T> List<T> fixedSize(List<T> list, int size, T filler) {
        List<T> out = new ArrayList<>(list == null ? List.of() : list);
        while (out.size() < size) out.add(filler);
        return List.copyOf(out.subList(0, size));
    }

    // ------------------------------------------------------------------ poster

    /**
     * @param width    cells across once hung (0 while it has never been hung)
     * @param height   cells down once hung
     * @param previewW the size the player designed it for (pre-fills the size picker)
     * @param previewH see previewW
     */
    public record Poster(Canvas canvas, int width, int height, int previewW, int previewH, boolean locked, String author, String name) {
        public static final Poster EMPTY = new Poster(Canvas.EMPTY, 0, 0, 1, 1, false, "", "");
        public static final Codec<Poster> CODEC = RecordCodecBuilder.create(i -> i.group(
                Canvas.CODEC.optionalFieldOf("canvas", Canvas.EMPTY).forGetter(Poster::canvas),
                Codec.INT.optionalFieldOf("width", 0).forGetter(Poster::width),
                Codec.INT.optionalFieldOf("height", 0).forGetter(Poster::height),
                Codec.INT.optionalFieldOf("preview_width", 1).forGetter(Poster::previewW),
                Codec.INT.optionalFieldOf("preview_height", 1).forGetter(Poster::previewH),
                Codec.BOOL.optionalFieldOf("locked", false).forGetter(Poster::locked),
                Codec.STRING.optionalFieldOf("author", "").forGetter(Poster::author),
                Codec.STRING.optionalFieldOf("image", "").forGetter(p -> ""),
                Codec.STRING.optionalFieldOf("caption", "").forGetter(p -> ""),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Poster::name)
        ).apply(i, (canvas, w, h, pw, ph, locked, author, image, caption, name) -> {
            if (canvas.isEmpty() && (!image.isEmpty() || !caption.isEmpty())) canvas = legacyPoster(image, caption);
            return new Poster(canvas, w, h, pw, ph, locked, author, name);
        }));
        public static final StreamCodec<ByteBuf, Poster> STREAM_CODEC = StreamCodec.composite(
                Canvas.STREAM_CODEC, Poster::canvas,
                ByteBufCodecs.VAR_INT, Poster::width,
                ByteBufCodecs.VAR_INT, Poster::height,
                ByteBufCodecs.VAR_INT, Poster::previewW,
                ByteBufCodecs.VAR_INT, Poster::previewH,
                ByteBufCodecs.BOOL, Poster::locked,
                ByteBufCodecs.STRING_UTF8, Poster::author,
                ByteBufCodecs.STRING_UTF8, Poster::name,
                Poster::new);

        public boolean sized() {
            return width > 0 && height > 0;
        }

        public Poster withSize(int w, int h) {
            return new Poster(canvas, w, h, previewW, previewH, locked, author, name);
        }

        public boolean isBlank() {
            return canvas.isEmpty() && !sized() && !locked && name.isEmpty();
        }
    }

    static Canvas legacyPoster(String image, String caption) {
        List<Canvas.Element> elements = new ArrayList<>();
        float imageBottom = caption.isEmpty() ? 0.95F : 0.8F;
        if (isImageHash(image)) elements.add(Canvas.Element.image(0.05F, 0.05F, 0.9F, imageBottom - 0.05F, image));
        if (!caption.isEmpty()) {
            elements.add(Canvas.Element.text(0.05F, 0.83F, 0.9F, 0.14F, caption, 0x2A2522 | (1 << ALIGN_SHIFT), 0.07F));
        }
        return new Canvas(elements);
    }

    // ------------------------------------------------------------------ newspaper

    /**
     * A sheet folded in half: {@code halves} are front-left, front-right, back-left, back-right,
     * each an A3 portrait surface (297 x 420).
     */
    public record Newspaper(String title, List<Canvas> halves, boolean locked, String author) {
        public static final Newspaper EMPTY = new Newspaper("", List.of(), false, "");
        public static final Codec<Newspaper> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("title", "").forGetter(Newspaper::title),
                Canvas.CODEC.listOf().optionalFieldOf("halves", List.of()).forGetter(Newspaper::halves),
                Codec.BOOL.optionalFieldOf("locked", false).forGetter(Newspaper::locked),
                Codec.STRING.optionalFieldOf("author", "").forGetter(Newspaper::author),
                Codec.STRING.optionalFieldOf("headline", "").forGetter(n -> ""),
                Codec.STRING.optionalFieldOf("image", "").forGetter(n -> ""),
                Codec.STRING.optionalFieldOf("body", "").forGetter(n -> "")
        ).apply(i, (title, halves, locked, author, headline, image, body) -> {
            boolean empty = halves.stream().allMatch(Canvas::isEmpty);
            if (empty && (!headline.isEmpty() || !image.isEmpty() || !body.isEmpty())) {
                halves = List.of(legacyPage(headline, image, body, 297.0F / 420.0F));
                if (title.isEmpty()) title = headline;
            }
            return new Newspaper(title, halves, locked, author);
        }));
        public static final StreamCodec<ByteBuf, Newspaper> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Newspaper::title,
                Canvas.STREAM_CODEC.apply(ByteBufCodecs.list(NEWSPAPER_HALVES)), Newspaper::halves,
                ByteBufCodecs.BOOL, Newspaper::locked,
                ByteBufCodecs.STRING_UTF8, Newspaper::author,
                Newspaper::new);

        public Newspaper {
            halves = fixedSize(halves, NEWSPAPER_HALVES, Canvas.EMPTY);
        }

        public boolean isBlank() {
            return title.isEmpty() && !locked && halves.stream().allMatch(Canvas::isEmpty);
        }
    }

    /** r3 layout (headline on top, picture on the left, text beside it) as canvas elements. */
    static Canvas legacyPage(String headline, String image, String body, float aspect) {
        List<Canvas.Element> elements = new ArrayList<>();
        float top = 0.05F;
        if (!headline.isEmpty()) {
            elements.add(Canvas.Element.text(0.06F, top, 0.88F, 0.1F, headline, 0x2A2522 | (1 << ALIGN_SHIFT), 0.045F));
            top += 0.11F;
        }
        float textLeft = 0.06F;
        if (isImageHash(image)) {
            elements.add(Canvas.Element.image(0.06F, top, 0.88F, 0.3F, image));
            top += 0.32F;
        }
        if (!body.isEmpty()) elements.add(Canvas.Element.text(textLeft, top, 0.88F, 0.95F - top, body, 0x2A2522, 0.025F));
        return new Canvas(elements);
    }

    // ------------------------------------------------------------------ document

    /** Portrait A4 pages (210 x 297). */
    public record Document(String title, List<Canvas> pages, boolean locked, String author) {
        public static final Document EMPTY = new Document("", List.of(Canvas.EMPTY), false, "");
        private record LegacyPage(String image, String text) {
            static final Codec<LegacyPage> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.optionalFieldOf("image", "").forGetter(LegacyPage::image),
                    Codec.STRING.optionalFieldOf("text", "").forGetter(LegacyPage::text)
            ).apply(i, LegacyPage::new));
        }
        public static final Codec<Document> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("title", "").forGetter(Document::title),
                Canvas.CODEC.listOf().optionalFieldOf("sheets", List.of()).forGetter(Document::pages),
                Codec.BOOL.optionalFieldOf("locked", false).forGetter(Document::locked),
                Codec.STRING.optionalFieldOf("author", "").forGetter(Document::author),
                LegacyPage.CODEC.listOf().optionalFieldOf("pages", List.of()).forGetter(d -> List.of())
        ).apply(i, (title, pages, locked, author, legacy) -> {
            if (pages.isEmpty() && !legacy.isEmpty()) {
                List<Canvas> converted = new ArrayList<>();
                for (LegacyPage page : legacy) converted.add(legacyPage("", page.image(), page.text(), 210.0F / 297.0F));
                pages = converted;
            }
            return new Document(title, pages, locked, author);
        }));
        public static final StreamCodec<ByteBuf, Document> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Document::title,
                Canvas.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PAGES)), Document::pages,
                ByteBufCodecs.BOOL, Document::locked,
                ByteBufCodecs.STRING_UTF8, Document::author,
                Document::new);

        public Document {
            pages = pages == null || pages.isEmpty() ? List.of(Canvas.EMPTY)
                    : List.copyOf(pages.subList(0, Math.min(pages.size(), MAX_PAGES)));
        }

        public boolean isBlank() {
            return title.isEmpty() && !locked && pages.size() == 1 && pages.get(0).isEmpty();
        }
    }

    // ------------------------------------------------------------------ writing board

    /** Chalk / marker colours offered first on boards. */
    public static final int[] CHALK_COLORS = {0xF2F0E6, 0xF3E27A, 0xF2A7C3, 0x9FD3F2};
    public static final int[] MARKER_COLORS = {0x1E1E1E, 0xC8322D, 0x2D5AC8, 0x2E8B3A};

    /** @param width cells across, 0 while unsized (size is chosen on placement) */
    public record Board(int width, int height, Canvas canvas, String name) {
        public static final Board EMPTY = new Board(0, 0, Canvas.EMPTY, "");
        public static final Codec<Board> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("width", 0).forGetter(Board::width),
                Codec.INT.optionalFieldOf("height", 0).forGetter(Board::height),
                Canvas.CODEC.optionalFieldOf("canvas", Canvas.EMPTY).forGetter(Board::canvas),
                Codec.STRING.optionalFieldOf("text", "").forGetter(b -> ""),
                Codec.INT.optionalFieldOf("color", 0).forGetter(b -> 0),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Board::name)
        ).apply(i, (w, h, canvas, text, color, name) -> {
            if (canvas.isEmpty() && !text.isEmpty()) {
                int rgb = CHALK_COLORS[Math.floorMod(color, CHALK_COLORS.length)];
                canvas = new Canvas(List.of(Canvas.Element.text(0.04F, 0.04F, 0.92F, 0.92F, text, rgb, 0.12F / Math.max(1, h))));
            }
            return new Board(w, h, canvas, name);
        }));
        public static final StreamCodec<ByteBuf, Board> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Board::width,
                ByteBufCodecs.VAR_INT, Board::height,
                Canvas.STREAM_CODEC, Board::canvas,
                ByteBufCodecs.STRING_UTF8, Board::name,
                Board::new);

        public Board withCanvas(Canvas next, String nextName) {
            return new Board(width, height, next, nextName);
        }

        public boolean sized() {
            return width > 0 && height > 0;
        }

        public boolean isBlank() {
            return !sized() && canvas.isEmpty() && name.isEmpty();
        }
    }

    private PaperContents() {}
}
