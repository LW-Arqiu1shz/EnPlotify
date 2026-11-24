package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Chooses the size (in cells) of a poster / board the first time it is hung on a wall. */
final class SizePickerScreen extends Screen {
    private static int lastWidth = 1;
    private static int lastHeight = 1;

    private final InteractionHand hand;
    private final BlockPos clicked;
    private final Direction face;
    private int cellsWide;
    private int cellsHigh;

    /** For a display: the last size used. */
    SizePickerScreen(InteractionHand hand, BlockPos clicked, Direction face, Component title) {
        super(title);
        this.hand = hand;
        this.clicked = clicked;
        this.face = face;
        this.cellsWide = lastWidth;
        this.cellsHigh = lastHeight;
    }

    SizePickerScreen(InteractionHand hand, BlockPos clicked, Direction face, boolean board, int suggestedW, int suggestedH) {
        super(Component.translatable(board ? "enplotify.paper.size.title_board" : "enplotify.paper.size.title_poster"));
        this.hand = hand;
        this.clicked = clicked;
        this.face = face;
        // A poster suggests the size it was designed for; otherwise the last size used.
        boolean suggested = !board && (suggestedW > 1 || suggestedH > 1);
        this.cellsWide = suggested ? suggestedW : lastWidth;
        this.cellsHigh = suggested ? suggestedH : lastHeight;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 + 50;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> cellsWide = Math.max(1, cellsWide - 1)).bounds(cx - 110, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> cellsWide = Math.min(PaperContents.MAX_CELLS, cellsWide + 1)).bounds(cx - 30, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> cellsHigh = Math.max(1, cellsHigh - 1)).bounds(cx + 10, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> cellsHigh = Math.min(PaperContents.MAX_CELLS, cellsHigh + 1)).bounds(cx + 90, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("enplotify.paper.size.place"), b -> place())
                .bounds(cx - 102, y + 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, y + 28, 100, 20).build());
    }

    private void place() {
        lastWidth = cellsWide;
        lastHeight = cellsHigh;
        ClientPacketDistributor.sendToServer(new PaperNetwork.PlaceWall(hand, clicked, face, cellsWide, cellsHigh));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int cx = this.width / 2;
        graphics.centeredText(this.font, this.title, cx, this.height / 2 - 92, 0xFFFFFFFF);
        // Preview: one square per cell.
        int cell = Math.min(16, 120 / Math.max(cellsWide, cellsHigh));
        int pw = cell * cellsWide;
        int ph = cell * cellsHigh;
        int px = cx - pw / 2;
        int py = this.height / 2 - 20 - ph / 2;
        graphics.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF8A8377);
        for (int gx = 0; gx < cellsWide; gx++) {
            for (int gy = 0; gy < cellsHigh; gy++) {
                int x = px + gx * cell;
                int y = py + gy * cell;
                graphics.fill(x, y, x + cell - 1, y + cell - 1, 0xFFF3EFE4);
            }
        }
        int y = this.height / 2 + 56;
        graphics.centeredText(this.font, Component.translatable("enplotify.paper.size.width", cellsWide), cx - 60, y, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("enplotify.paper.size.height", cellsHigh), cx + 60, y, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("enplotify.paper.size.hint"), cx, this.height / 2 + 104, 0xFFA8A8A8);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
