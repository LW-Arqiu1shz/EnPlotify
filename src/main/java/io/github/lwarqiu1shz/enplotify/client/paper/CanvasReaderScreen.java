package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Reading a poster, a newspaper (one side of the unfolded sheet at a time: two halves side by side)
 * or a document (one page at a time).
 */
final class CanvasReaderScreen extends Screen {
    enum Kind { POSTER, NEWSPAPER, DOCUMENT }

    private final Kind kind;
    private final List<Canvas> surfaces;
    private final float posterAspect;
    private final String author;
    private int index;

    private CanvasReaderScreen(Kind kind, Component title, List<Canvas> surfaces, float posterAspect, String author) {
        super(title);
        this.kind = kind;
        this.surfaces = surfaces;
        this.posterAspect = posterAspect;
        this.author = author;
    }

    static CanvasReaderScreen poster(Canvas canvas, int cellsWide, int cellsHigh) {
        return new CanvasReaderScreen(Kind.POSTER, Component.translatable("item.enplotify.poster"), List.of(canvas),
                Math.max(1, cellsWide) / (float) Math.max(1, cellsHigh), "");
    }

    static CanvasReaderScreen newspaper(PaperViews.NewspaperView view) {
        Component title = view.title().isEmpty() ? Component.translatable("item.enplotify.newspaper") : Component.literal(view.title());
        return new CanvasReaderScreen(Kind.NEWSPAPER, title, view.halves(), 1.0F, view.author());
    }

    static CanvasReaderScreen document(PaperViews.DocumentView view) {
        Component title = view.title().isEmpty() ? Component.translatable("item.enplotify.document") : Component.literal(view.title());
        return new CanvasReaderScreen(Kind.DOCUMENT, title, view.pages(), 1.0F, view.author());
    }

    private int pageCount() {
        return kind == Kind.NEWSPAPER ? 2 : kind == Kind.DOCUMENT ? surfaces.size() : 1;
    }

    private float aspect() {
        return switch (kind) {
            case POSTER -> posterAspect;
            case NEWSPAPER -> 2.0F * PaperViews.HALF_ASPECT;
            case DOCUMENT -> PaperViews.PAGE_ASPECT;
        };
    }

    private PaperGui.Rect rect() {
        return PaperGui.fit(this.width, 18, this.height - 18, aspect(), 26);
    }

    @Override
    protected void init() {
        if (pageCount() <= 1) return;
        PaperGui.Rect r = rect();
        int y = r.y() + r.h() / 2 - 10;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> index = Math.max(0, index - 1))
                .bounds(Math.max(2, r.x() - 24), y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> index = Math.min(pageCount() - 1, index + 1))
                .bounds(Math.min(this.width - 22, r.x() + r.w() + 4), y, 20, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        PaperGui.Rect r = rect();
        graphics.centeredText(this.font, this.title, this.width / 2, 5, 0xFFFFFFFF);
        switch (kind) {
            case POSTER -> {
                int pxW = PaperGui.realPixels(r.w()), pxH = Math.max(1, Math.round(pxW / aspect()));
                PaperGui.canvas(graphics, surfaces.get(0), r, pxW, pxH, PaperViews.POSTER_PAPER);
            }
            case NEWSPAPER -> {
                int[] px = SheetDraw.pixels(PaperViews.HALF_ASPECT, PaperStackRenderer.SURFACE_PX);
                int half = r.w() / 2;
                PaperGui.canvas(graphics, surfaces.get(index * 2), new PaperGui.Rect(r.x(), r.y(), half, r.h()), px[0], px[1], PaperViews.NEWSPRINT);
                PaperGui.canvas(graphics, surfaces.get(index * 2 + 1), new PaperGui.Rect(r.x() + half, r.y(), r.w() - half, r.h()), px[0], px[1], PaperViews.NEWSPRINT);
                graphics.fill(r.x() + half - 1, r.y(), r.x() + half + 1, r.y() + r.h(), 0x55000000);
            }
            case DOCUMENT -> {
                int[] px = SheetDraw.pixels(PaperViews.PAGE_ASPECT, PaperStackRenderer.SURFACE_PX);
                PaperGui.canvas(graphics, surfaces.get(Math.min(index, surfaces.size() - 1)), r, px[0], px[1], PaperViews.DOCUMENT_PAPER);
            }
        }
        Component footer = switch (kind) {
            case NEWSPAPER -> Component.translatable(index == 0 ? "enplotify.paper.side_front" : "enplotify.paper.side_back");
            case DOCUMENT -> Component.translatable("enplotify.paper.page_of", index + 1, surfaces.size());
            default -> Component.empty();
        };
        if (!author.isEmpty()) footer = Component.empty().append(footer).append("   ").append(Component.translatable("enplotify.paper.by", author));
        graphics.centeredText(this.font, footer, this.width / 2, this.height - 13, 0xFFD0D0D0);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
