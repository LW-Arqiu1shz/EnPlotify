package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.lwarqiu1shz.enplotify.paper.BoardEntity;
import io.github.lwarqiu1shz.enplotify.paper.BoardItem;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import io.github.lwarqiu1shz.enplotify.paper.PosterEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Renderers for the wall-mounted kinds: posters and writing boards (one painted texture each). */
public final class WallRenderers {
    /** Front and back of the slab (the entity box is one sheet of paper thick, centred on the origin). */
    private static final float SURFACE = (float) (io.github.lwarqiu1shz.enplotify.paper.SizedHangingEntity.THICKNESS / 2);
    private static final float BACK = -SURFACE;
    /** Texture pixels per cell, and the cap on the longer side. */
    static final int CELL_PX = 256;
    static final int MAX_PX = 1024;
    /** Text is only drawn within this distance (squared, blocks). */
    static final double TEXT_DISTANCE_SQ = 48.0 * 48.0;
    /** Animated pictures play within this distance; further away they show their first frame. */
    static final double ANIMATION_DISTANCE_SQ = 32.0 * 32.0;

    /**
     * Nothing to draw: the shader pack's shadow pass (a flat thing on a wall casts no visible
     * shadow), or the camera is behind the wall the thing hangs on.
     */
    private static boolean skip(EntityRenderState state, Direction direction, CameraRenderState camera) {
        if (ShaderCompat.inShadowPass()) return true;
        double dx = camera.pos.x - state.x, dz = camera.pos.z - state.z;
        return dx * direction.getStepX() + dz * direction.getStepZ() < -0.05;
    }

    static int[] wallPixels(int width, int height) {
        int perCell = Math.min(CELL_PX, MAX_PX / Math.max(width, height));
        return new int[] {perCell * width, perCell * height};
    }

    public static class PosterState extends EntityRenderState {
        Direction direction = Direction.SOUTH;
        int width = 1;
        int height = 1;
        Canvas canvas = Canvas.EMPTY;
    }

    public static class BoardState extends EntityRenderState {
        Direction direction = Direction.SOUTH;
        int width = 1;
        int height = 1;
        BoardItem.Style style = BoardItem.Style.BLACKBOARD;
        Canvas canvas = Canvas.EMPTY;
    }

    /** Current frames of animated pictures, just in front of the painted poster. */
    private static void drawAnimations(PoseStack poseStack, SubmitNodeCollector collector, Canvas canvas, float halfW, float halfH, int light) {
        float w = 2 * halfW, h = 2 * halfH;
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() != Canvas.IMAGE) continue;
            Identifier texture = PaperAnimations.frame(e.text());
            int[] size = texture == null ? null : PaperAnimations.size(e.text());
            if (size == null) continue;
            float[] box = PaperAnimations.fit(e.x() * w, e.y() * h, e.w() * w, e.h() * h, size[0], size[1]);
            float x0 = -halfW + box[0], x1 = x0 + box[2];
            float y1 = halfH - box[1], y0 = y1 - box[3];
            SheetDraw.quad(poseStack, collector, texture, x0, y0, x1, y1, SURFACE + 0.0008F, 0xFFFFFFFF, light);
        }
    }

    private static void faceOut(PoseStack poseStack, Direction direction) {
        poseStack.mulPose(Axis.YP.rotationDegrees(-direction.get2DDataValue() * 90.0F));
    }

    public static final class PosterRenderer extends EntityRenderer<PosterEntity, PosterState> {
        public PosterRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public PosterState createRenderState() {
            return new PosterState();
        }

        @Override
        public void extractRenderState(PosterEntity entity, PosterState state, float partialTicks) {
            super.extractRenderState(entity, state, partialTicks);
            state.direction = entity.getDirection();
            state.width = entity.cellsWide();
            state.height = entity.cellsHigh();
            state.canvas = PaperViews.poster(entity.getItem());
        }

        @Override
        public void submit(PosterState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            if (skip(state, state.direction, camera)) return;
            poseStack.pushPose();
            faceOut(poseStack, state.direction);
            float halfW = state.width / 2.0F;
            float halfH = state.height / 2.0F;
            int[] px = wallPixels(state.width, state.height);
            Identifier texture = CanvasTextures.needsPaint(state.canvas)
                    ? CanvasTextures.get(state.canvas, px[0], px[1], PaperViews.POSTER_PAPER) : null;
            if (texture != null) {
                SheetDraw.quad(poseStack, collector, texture, -halfW, -halfH, halfW, halfH, SURFACE, 0xFFFFFFFF, state.lightCoords);
            } else {
                SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, halfH, SURFACE, PaperViews.POSTER_PAPER, state.lightCoords);
            }
            SheetDraw.edges(poseStack, collector, -halfW, -halfH, halfW, halfH, BACK, SURFACE, PaperViews.POSTER_PAPER, state.lightCoords);
            if (state.distanceToCameraSq < ANIMATION_DISTANCE_SQ && PaperAnimations.hasImages(state.canvas)) {
                drawAnimations(poseStack, collector, state.canvas, halfW, halfH, state.lightCoords);
            }
            if (state.distanceToCameraSq < TEXT_DISTANCE_SQ) {
                CanvasText.drawWorld(poseStack, collector, state.canvas, -halfW, -halfH, halfW, halfH, SURFACE + 0.0015F, state.lightCoords);
            }
            poseStack.popPose();
            super.submit(state, poseStack, collector, camera);
        }
    }

    public static final class BoardRenderer extends EntityRenderer<BoardEntity, BoardState> {
        public BoardRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public BoardState createRenderState() {
            return new BoardState();
        }

        @Override
        public void extractRenderState(BoardEntity entity, BoardState state, float partialTicks) {
            super.extractRenderState(entity, state, partialTicks);
            state.direction = entity.getDirection();
            state.width = entity.cellsWide();
            state.height = entity.cellsHigh();
            state.style = entity.style();
            state.canvas = entity.isPreset() && entity.getItem().getItem() instanceof BoardItem board
                    ? PaperViews.presetBoard(board, state.height) : entity.canvas();
        }

        @Override
        public void submit(BoardState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            if (skip(state, state.direction, camera)) return;
            poseStack.pushPose();
            faceOut(poseStack, state.direction);
            float halfW = state.width / 2.0F;
            float halfH = state.height / 2.0F;
            int light = state.lightCoords;
            boolean black = state.style == BoardItem.Style.BLACKBOARD;
            float frame = 0.07F;
            int surface = black ? PaperViews.BLACKBOARD : PaperViews.WHITEBOARD;
            int[] px = wallPixels(state.width, state.height);
            Identifier texture = CanvasTextures.needsPaint(state.canvas) ? CanvasTextures.get(state.canvas, px[0], px[1], surface) : null;
            if (texture != null) {
                SheetDraw.quad(poseStack, collector, texture, -halfW, -halfH, halfW, halfH, SURFACE, 0xFFFFFFFF, light);
            } else {
                SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, halfH, SURFACE, surface, light);
            }
            if (state.distanceToCameraSq < TEXT_DISTANCE_SQ) {
                CanvasText.drawWorld(poseStack, collector, state.canvas, -halfW, -halfH, halfW, halfH, SURFACE + 0.0015F, light);
            }
            // The frame lies over the edge of the drawing surface.
            int frameColor = black ? 0xFF6B4A2B : 0xFFB8BCC0;
            float z = SURFACE + 0.002F;
            SheetDraw.edges(poseStack, collector, -halfW, -halfH, halfW, halfH, BACK, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, -halfW, halfH - frame, halfW, halfH, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, -halfH + frame, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, -halfW, -halfH + frame, -halfW + frame, halfH - frame, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, halfW - frame, -halfH + frame, halfW, halfH - frame, z, frameColor, light);
            poseStack.popPose();
            super.submit(state, poseStack, collector, camera);
        }
    }

    public static class DisplayState extends EntityRenderState {
        Direction direction = Direction.SOUTH;
        int width = 1;
        int height = 1;
        boolean connected;
        /** Close enough to show the terminal's screen. */
        boolean showing;
        final TerminalRenderer.State screen = new TerminalRenderer.State();
    }

    /** Displays show their terminal's screen this far away (squared, blocks). */
    private static final double DISPLAY_DISTANCE_SQ = 48.0 * 48.0;
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * A display: a thin dark frame round black glass. Connected to a terminal that is on, it shows
     * that terminal's screen, as large as fits (240:150), lit by itself.
     */
    public static final class DisplayRenderer extends EntityRenderer<io.github.lwarqiu1shz.enplotify.paper.DisplayEntity, DisplayState> {
        public DisplayRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public DisplayState createRenderState() {
            return new DisplayState();
        }

        @Override
        public void extractRenderState(io.github.lwarqiu1shz.enplotify.paper.DisplayEntity entity, DisplayState state, float partialTicks) {
            super.extractRenderState(entity, state, partialTicks);
            extractScreen(entity, entity.terminal(), state, partialTicks);
        }

        @Override
        public void submit(DisplayState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            submitDisplay(state, poseStack, collector, camera);
            super.submit(state, poseStack, collector, camera);
        }
    }

    /** The terminal screen of a display (or a SAWMO whiteboard) connected to {@code terminal}. */
    static void extractScreen(io.github.lwarqiu1shz.enplotify.paper.SizedHangingEntity entity, net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos terminal,
                              DisplayState state, float partialTicks) {
            state.direction = entity.getDirection();
            state.width = entity.cellsWide();
            state.height = entity.cellsHigh();
            state.connected = terminal != null;
            state.showing = false;
            if (terminal == null || state.distanceToCameraSq > DISPLAY_DISTANCE_SQ) return;
            net.minecraft.world.level.Level level = entity.level();
            // Seen from behind (or drawn only for a shader's shadows): no new pictures are asked for.
            net.minecraft.world.phys.Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().position();
            net.minecraft.world.phys.Vec3 centre = entity.getBoundingBox().getCenter();
            boolean seen = !ShaderCompat.inShadowPass() && (camera.x - centre.x) * state.direction.getStepX()
                    + (camera.z - centre.z) * state.direction.getStepZ() > -0.05;
            if (level.isLoaded(terminal) && level.getBlockEntity(terminal) instanceof io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity t) {
                TerminalRenderer.extractForDisplay(t, state.screen, partialTicks, seen);
            } else {
                TerminalRenderer.extractFarAway(terminal, state.screen, seen);
            }
            state.showing = state.screen.power != io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity.POWER_OFF;
    }

    private static void submitDisplay(DisplayState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            if (skip(state, state.direction, camera)) return;
            poseStack.pushPose();
            faceOut(poseStack, state.direction);
            float halfW = state.width / 2.0F;
            float halfH = state.height / 2.0F;
            int light = state.lightCoords;
            float bezel = 0.0625F;
            SheetDraw.edges(poseStack, collector, -halfW, -halfH, halfW, halfH, BACK, SURFACE, 0xFF2A2E34, light);
            SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, halfH, SURFACE, 0xFF2A2E34, light);
            float gx = halfW - bezel, gy = halfH - bezel;
            SheetDraw.fill(poseStack, collector, -gx, -gy, gx, gy, SURFACE + 0.0005F, 0xFF07090C, light);
            if (state.showing) {
                // The screen as large as fits in the glass; black bars round it.
                float aspect = TerminalUi.GRID_W / (float) TerminalUi.GRID_H;
                float w = Math.min(2 * gx, 2 * gy * aspect);
                poseStack.pushPose();
                poseStack.translate(0.0F, 0.0F, SURFACE + 0.001F);
                TerminalRenderer.drawScreen(state.screen, poseStack, collector, w);
                poseStack.popPose();
            } else if (!state.connected && state.distanceToCameraSq < DISPLAY_DISTANCE_SQ) {
                // Not connected to any terminal: a small "无信号" in the middle.
                net.minecraft.client.gui.Font font = TerminalUi.font();
                String text = net.minecraft.client.resources.language.I18n.get("entity.enplotify.display.no_signal");
                float size = Math.min(2 * gx, 2 * gy) * 0.06F / 8.0F;
                poseStack.pushPose();
                poseStack.translate(-font.width(text) * size / 2.0F, 4.0F * size, SURFACE + 0.0015F);
                poseStack.scale(size, -size, size);
                collector.submitText(poseStack, 0.0F, 0.0F, net.minecraft.util.FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY),
                        false, net.minecraft.client.gui.Font.DisplayMode.NORMAL, FULL_BRIGHT, 0xFF6A7380, 0, 0);
                poseStack.popPose();
            }
            poseStack.popPose();
    }

    private static final TerminalRenderer.State GUI_SCREEN = new TerminalRenderer.State();

    /**
     * The screen of a SAWMO whiteboard drawn in its editor, placed as on the wall (inside the
     * frame), so the writing lines up with it. False when it shows nothing (plain white then).
     */
    static boolean drawScreenGui(io.github.lwarqiu1shz.enplotify.paper.SawmoBoardEntity board,
                                 net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        net.minecraft.core.BlockPos terminal = board.terminal();
        if (terminal == null) return false;
        net.minecraft.world.level.Level level = board.level();
        TerminalRenderer.State s = GUI_SCREEN;
        if (level.isLoaded(terminal) && level.getBlockEntity(terminal) instanceof io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity t) {
            TerminalRenderer.extractForDisplay(t, s, 0.0F, true);
        } else {
            TerminalRenderer.extractFarAway(terminal, s, true);
        }
        if (s.power == io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity.POWER_OFF) return false;
        graphics.fill(x, y, x + w, y + h, 0xFF07090C);
        // Everything the wall shows (logo, loading, picture, or the drawn desktop), not only pictures.
        float perBlock = w / (float) Math.max(1, board.cellsWide());
        float gw = w - 2 * 0.07F * perBlock, gh = h - 2 * 0.07F * perBlock;
        float aspect = TerminalUi.GRID_W / (float) TerminalUi.GRID_H;
        float sw = Math.min(gw, gh * aspect), sh = sw / aspect;
        TerminalRenderer.drawScreenGui(s, graphics, x + (w - sw) / 2.0F, y + (h - sh) / 2.0F, sw);
        return true;
    }

    public static class SawmoState extends DisplayState {
        Canvas canvas = Canvas.EMPTY;
    }

    /** No ink here: the writing of a SAWMO whiteboard is painted over its screen, see-through elsewhere. */
    private static final int CLEAR = 0x00FFFFFF;

    /**
     * 望漠白板: a whiteboard (white, framed) whose surface is a screen. Connected to a terminal that is
     * on, the screen plays on it; the writing stays on top.
     */
    public static final class SawmoRenderer extends EntityRenderer<io.github.lwarqiu1shz.enplotify.paper.SawmoBoardEntity, SawmoState> {
        public SawmoRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public SawmoState createRenderState() {
            return new SawmoState();
        }

        @Override
        public void extractRenderState(io.github.lwarqiu1shz.enplotify.paper.SawmoBoardEntity entity, SawmoState state, float partialTicks) {
            super.extractRenderState(entity, state, partialTicks);
            extractScreen(entity, entity.terminal(), state, partialTicks);
            state.canvas = entity.canvas();
        }

        @Override
        public void submit(SawmoState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            if (skip(state, state.direction, camera)) return;
            poseStack.pushPose();
            faceOut(poseStack, state.direction);
            float halfW = state.width / 2.0F;
            float halfH = state.height / 2.0F;
            int light = state.lightCoords;
            float frame = 0.07F;
            if (state.showing) {
                // The screen as large as fits inside the frame; black round it.
                SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, halfH, SURFACE, 0xFF07090C, light);
                float gx = halfW - frame, gy = halfH - frame;
                float aspect = TerminalUi.GRID_W / (float) TerminalUi.GRID_H;
                float w = Math.min(2 * gx, 2 * gy * aspect);
                poseStack.pushPose();
                poseStack.translate(0.0F, 0.0F, SURFACE + 0.001F);
                TerminalRenderer.drawScreen(state.screen, poseStack, collector, w);
                poseStack.popPose();
            } else {
                SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, halfH, SURFACE, PaperViews.WHITEBOARD, light);
            }
            // The writing, over the screen.
            int[] px = wallPixels(state.width, state.height);
            Identifier ink = CanvasTextures.needsPaint(state.canvas) ? CanvasTextures.get(state.canvas, px[0], px[1], CLEAR) : null;
            if (ink != null) SheetDraw.quadTranslucent(poseStack, collector, ink, -halfW, -halfH, halfW, halfH, SURFACE + 0.003F, 0xFFFFFFFF, light);
            if (state.distanceToCameraSq < TEXT_DISTANCE_SQ) {
                CanvasText.drawWorld(poseStack, collector, state.canvas, -halfW, -halfH, halfW, halfH, SURFACE + 0.0035F, light);
            }
            // A whiteboard's frame, over the edge.
            int frameColor = 0xFFB8BCC0;
            float z = SURFACE + 0.004F;
            SheetDraw.edges(poseStack, collector, -halfW, -halfH, halfW, halfH, BACK, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, -halfW, halfH - frame, halfW, halfH, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, -halfW, -halfH, halfW, -halfH + frame, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, -halfW, -halfH + frame, -halfW + frame, halfH - frame, z, frameColor, light);
            SheetDraw.fill(poseStack, collector, halfW - frame, -halfH + frame, halfW, halfH - frame, z, frameColor, light);
            poseStack.popPose();
            super.submit(state, poseStack, collector, camera);
        }
    }

    private WallRenderers() {}
}
