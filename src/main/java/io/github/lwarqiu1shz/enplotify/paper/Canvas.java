package io.github.lwarqiu1shz.enplotify.paper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * One drawable surface (a poster, one half of a newspaper, one document page, a board) holding
 * freely placed elements. Positions and sizes are fractions of the surface (0 = left / top edge,
 * 1 = right / bottom edge), so the same content works at any resolution.
 */
public record Canvas(List<Element> elements) {
    public static final int MAX_ELEMENTS = 160;
    public static final int MAX_TEXT = 3000;
    public static final int MAX_LATEX = 400;
    public static final int MAX_STROKE_POINTS = 4000;
    public static final int MAX_TOTAL_POINTS = 60000;

    public static final int TEXT = 0;
    public static final int IMAGE = 1;
    public static final int FORMULA = 2;
    public static final int STROKE = 3;

    /** Character style flags. */
    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8, SUPER = 16, SUB = 32;

    public static final Canvas EMPTY = new Canvas(List.of());

    /**
     * A styled range of a text element's characters, {@code [start, end)}.
     *
     * @param color 0xRRGGBB, or -1 for the element's own colour
     */
    public record Run(int start, int end, int flags, int color) {
        public static final Codec<Run> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("s").forGetter(Run::start),
                Codec.INT.fieldOf("e").forGetter(Run::end),
                Codec.INT.optionalFieldOf("f", 0).forGetter(Run::flags),
                Codec.INT.optionalFieldOf("c", -1).forGetter(Run::color)
        ).apply(i, Run::new));
        public static final StreamCodec<ByteBuf, Run> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Run::start,
                ByteBufCodecs.VAR_INT, Run::end,
                ByteBufCodecs.VAR_INT, Run::flags,
                ByteBufCodecs.INT, Run::color,
                Run::new);
    }

    /**
     * One element. Which fields matter depends on {@link #type}:
     * <ul>
     *   <li>TEXT: box x/y/w/h, {@code text}, {@code runs}, {@code color}, {@code size} = font height as a fraction of the surface height;</li>
     *   <li>IMAGE: box x/y/w/h, {@code text} = picture (upload hash or texture id);</li>
     *   <li>FORMULA: box x/y/w/h, {@code text} = LaTeX, {@code color}, {@code size} = font height;</li>
     *   <li>STROKE: {@code points} (x &lt;&lt; 16 | y, each 0..65535), {@code color}, {@code size} = pen width.</li>
     * </ul>
     */
    public record Element(int type, float x, float y, float w, float h, String text, List<Run> runs,
                          int color, float size, List<Integer> points) {
        public static final Codec<Element> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("type").forGetter(Element::type),
                Codec.FLOAT.optionalFieldOf("x", 0.0F).forGetter(Element::x),
                Codec.FLOAT.optionalFieldOf("y", 0.0F).forGetter(Element::y),
                Codec.FLOAT.optionalFieldOf("w", 0.0F).forGetter(Element::w),
                Codec.FLOAT.optionalFieldOf("h", 0.0F).forGetter(Element::h),
                Codec.STRING.optionalFieldOf("text", "").forGetter(Element::text),
                Run.CODEC.listOf().optionalFieldOf("runs", List.of()).forGetter(Element::runs),
                Codec.INT.optionalFieldOf("color", 0).forGetter(Element::color),
                Codec.FLOAT.optionalFieldOf("size", 0.05F).forGetter(Element::size),
                Codec.INT.listOf().optionalFieldOf("points", List.of()).forGetter(Element::points)
        ).apply(i, Element::new));
        private static final StreamCodec<ByteBuf, List<Integer>> POINTS =
                ByteBufCodecs.INT.apply(ByteBufCodecs.list(MAX_STROKE_POINTS));
        public static final StreamCodec<ByteBuf, Element> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Element decode(ByteBuf buf) {
                int type = ByteBufCodecs.VAR_INT.decode(buf);
                float x = buf.readFloat(), y = buf.readFloat(), w = buf.readFloat(), h = buf.readFloat();
                String text = ByteBufCodecs.STRING_UTF8.decode(buf);
                List<Run> runs = Run.STREAM_CODEC.apply(ByteBufCodecs.list(512)).decode(buf);
                int color = buf.readInt();
                float size = buf.readFloat();
                List<Integer> points = POINTS.decode(buf);
                return new Element(type, x, y, w, h, text, runs, color, size, points);
            }

            @Override
            public void encode(ByteBuf buf, Element e) {
                ByteBufCodecs.VAR_INT.encode(buf, e.type());
                buf.writeFloat(e.x()).writeFloat(e.y()).writeFloat(e.w()).writeFloat(e.h());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.text());
                Run.STREAM_CODEC.apply(ByteBufCodecs.list(512)).encode(buf, e.runs());
                buf.writeInt(e.color());
                buf.writeFloat(e.size());
                POINTS.encode(buf, e.points());
            }
        };

        public static Element text(float x, float y, float w, float h, String text, int color, float size) {
            return new Element(TEXT, x, y, w, h, text, List.of(), color, size, List.of());
        }

        public static Element image(float x, float y, float w, float h, String image) {
            return new Element(IMAGE, x, y, w, h, image, List.of(), 0, 0.0F, List.of());
        }
    }

    public static final Codec<Canvas> CODEC = Element.CODEC.listOf().xmap(Canvas::new, Canvas::elements);
    public static final StreamCodec<ByteBuf, Canvas> STREAM_CODEC =
            Element.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ELEMENTS)).map(Canvas::new, Canvas::elements);

    public Canvas {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /** Server: clamps everything into range and drops what is not allowed on this kind of surface. */
    public Canvas sanitize(boolean allowStrokes) {
        List<Element> clean = new ArrayList<>();
        int totalPoints = 0;
        for (Element e : elements) {
            if (clean.size() >= MAX_ELEMENTS) break;
            float x = clamp01(e.x()), y = clamp01(e.y());
            float w = Mth.clamp(e.w(), 0.005F, 1.0F), h = Mth.clamp(e.h(), 0.005F, 1.0F);
            int color = e.color() & 0x0FFFFFFF;
            float size = Mth.clamp(Float.isFinite(e.size()) ? e.size() : 0.05F, 0.002F, 1.0F);
            switch (e.type()) {
                case TEXT -> {
                    String text = PaperContents.clip(e.text(), MAX_TEXT);
                    List<Run> runs = new ArrayList<>();
                    for (Run run : e.runs()) {
                        if (runs.size() >= 512) break;
                        int start = Mth.clamp(run.start(), 0, text.length());
                        int end = Mth.clamp(run.end(), start, text.length());
                        if (end > start) runs.add(new Run(start, end, run.flags() & 63, run.color() < 0 ? -1 : run.color() & 0xFFFFFF));
                    }
                    clean.add(new Element(TEXT, x, y, w, h, text, runs, color, size, List.of()));
                }
                case IMAGE -> {
                    if (PaperContents.isImageHash(e.text())) clean.add(new Element(IMAGE, x, y, w, h, e.text(), List.of(), 0, 0.0F, List.of()));
                }
                case FORMULA -> clean.add(new Element(FORMULA, x, y, w, h, PaperContents.clip(e.text(), MAX_LATEX), List.of(), color, size, List.of()));
                case STROKE -> {
                    if (!allowStrokes || e.points().size() < 2) continue;
                    List<Integer> points = e.points().size() > MAX_STROKE_POINTS ? e.points().subList(0, MAX_STROKE_POINTS) : e.points();
                    if (totalPoints + points.size() > MAX_TOTAL_POINTS) continue;
                    totalPoints += points.size();
                    clean.add(new Element(STROKE, 0, 0, 1, 1, "", List.of(), color, Mth.clamp(size, 0.001F, 0.2F), List.copyOf(points)));
                }
                default -> {}
            }
        }
        return new Canvas(clean);
    }

    private static float clamp01(float v) {
        return Float.isFinite(v) ? Mth.clamp(v, -0.5F, 1.0F) : 0.0F;
    }

    /** Packs a stroke point (fractions of the surface) into one int. */
    public static int packPoint(float x, float y) {
        int px = Mth.clamp(Math.round(x * 65535.0F), 0, 65535);
        int py = Mth.clamp(Math.round(y * 65535.0F), 0, 65535);
        return (px << 16) | py;
    }

    public static float pointX(int packed) {
        return (packed >>> 16) / 65535.0F;
    }

    public static float pointY(int packed) {
        return (packed & 0xFFFF) / 65535.0F;
    }
}
