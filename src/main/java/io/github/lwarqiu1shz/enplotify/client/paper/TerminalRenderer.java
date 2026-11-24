package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.lwarqiu1shz.enplotify.paper.TerminalBlock;
import io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The picture on a terminal screen in the world: the desktop, or a sketch of the open program with
 * who is using it. Lit by itself. Only drawn up close; farther away the dark glass of the model shows.
 */
public final class TerminalRenderer implements BlockEntityRenderer<TerminalBlockEntity, TerminalRenderer.State> {
    private static final double NEAR_SQ = 24.0 * 24.0;
    private static final int FULL_BRIGHT = 0xF000F0;
    /** A crashed screen: frozen stripes, dark grey, black, light grey, white, twice. */
    private static final int[] CRASH_BARS = {0xFF4A4A4A, 0xFF000000, 0xFFB4B4B4, 0xFFFFFFFF,
            0xFF4A4A4A, 0xFF000000, 0xFFB4B4B4, 0xFFFFFFFF};

    public static final class State extends BlockEntityRenderState {
        public State() {}

        float yRot;
        double @Nullable [] display;
        String user = "";
        int app;
        boolean near;
        /** Used by someone else, and this player wants their screen as a picture. */
        boolean pictureWanted;
        net.minecraft.resources.@Nullable Identifier picture;
        int @Nullable [] pictureGrid;
        boolean currentWindowMode;
        /** This player is at this terminal: the system covers the screen, nothing is drawn under it. */
        boolean hidden;
        /** This player's own terminal with no picture of it yet: plain black, never the sketch. */
        boolean blank;
        int power;
        /** Ticks since the power state changed (with the partial tick). */
        float powerTicks;
        io.github.lwarqiu1shz.enplotify.api.TerminalTheme theme = io.github.lwarqiu1shz.enplotify.api.TerminalTheme.BUILT_IN;
        double @Nullable [] light;
    }

    public TerminalRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(TerminalBlockEntity terminal, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(terminal, state, partialTicks, cameraPosition, breakProgress);
        BlockState block = terminal.getBlockState();
        state.yRot = block.getValue(TerminalBlock.FACING).toYRot() + 180.0F;
        state.display = TerminalBlock.display(block);
        state.user = terminal.userName();
        state.app = terminal.app();
        state.near = Vec3.atCenterOf(terminal.getBlockPos()).distanceToSqr(cameraPosition) < NEAR_SQ;
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        boolean mine = minecraft.player != null && state.user.equals(minecraft.player.getGameProfile().name());
        state.currentWindowMode = !mine && io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.terminalDisplay()
                == io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.TerminalDisplay.CURRENT_WINDOW;
        // Used by someone else or by nobody: the screen shows its last picture (kept by the server,
        // kept up to date while the browser left open on it still runs).
        state.pictureWanted = !mine && !state.currentWindowMode && state.near && state.display != null;
        // Seen from behind (or drawn only for a shader's shadows): no new pictures are asked for.
        net.minecraft.core.Direction facing = block.getValue(TerminalBlock.FACING);
        Vec3 fromScreen = cameraPosition.subtract(Vec3.atCenterOf(terminal.getBlockPos()));
        boolean seen = !ShaderCompat.inShadowPass() && fromScreen.x * facing.getStepX() + fromScreen.z * facing.getStepZ() > -0.1;
        state.picture = state.pictureWanted ? TerminalShots.picture(terminal.getBlockPos(), seen) : null;
        state.hidden = false;
        if (mine) {
            // Using it: under the system nothing (or its edges would peek out); while the camera
            // glides in or out, the screen as it was last seen.
            BlockPos at = TerminalView.pos();
            boolean here = at != null && at.equals(terminal.getBlockPos());
            state.hidden = here && TerminalView.arrived();
            if (!state.hidden) state.picture = TerminalShots.cachedPicture(terminal.getBlockPos());
        }
        state.blank = mine && state.picture == null;
        state.pictureGrid = state.picture == null ? null : TerminalShots.pictureGrid(terminal.getBlockPos());
        state.power = terminal.power();
        state.powerTicks = terminal.getLevel() == null ? 0.0F
                : (float) (terminal.getLevel().getGameTime() - terminal.powerSince()) + partialTicks;
        state.theme = terminal.theme();
        state.light = TerminalBlock.light(block);
        if (state.power != TerminalBlockEntity.POWER_ON) {
            // Off, starting or stopping: no pictures, drawn here.
            state.picture = null;
            TerminalShots.forget(terminal.getBlockPos());
            state.hidden = false;
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        // A shader's shadow pass: a lit screen casts no shadow (the terminal's body, a block model, still does).
        if (ShaderCompat.inShadowPass()) return;
        powerLight(state, poseStack, collector);
        double[] d = state.display;
        if (d == null || !state.near || state.hidden || state.power == TerminalBlockEntity.POWER_OFF) return;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        poseStack.translate(-0.5F, 0.0F, -0.5F);
        // To the middle of the picture, turned so +z points out of the screen and +x to the viewer's right.
        poseStack.translate((d[0] + d[2]) / 32.0, (d[1] + d[3]) / 32.0, d[4] / 16.0 - 0.0008);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        drawScreen(state, poseStack, collector, (float) ((d[2] - d[0]) / 16.0));
        poseStack.popPose();
    }

    /**
     * The screen's content (logo, loading, picture, desktop...), centred on the pose (+x to the
     * viewer's right, +y up, drawn towards +z), {@code screenW} wide and 240:150 (16:10) in shape.
     */
    static void drawScreen(State state, PoseStack poseStack, SubmitNodeCollector collector, float screenW) {
        TerminalUi.size(TerminalUi.GRID_W, TerminalUi.GRID_H);
        float unit = screenW / TerminalUi.GRID_W;
        float left = -TerminalUi.GRID_W * unit / 2.0F, top = TerminalUi.GRID_H * unit / 2.0F;

        if (state.power == TerminalBlockEntity.POWER_CRASHED) {
            List<SheetDraw.Rect> bars = new ArrayList<>();
            float w = -2.0F * left;
            for (int i = 0; i < CRASH_BARS.length; i++) {
                bars.add(new SheetDraw.Rect(left + w * i / CRASH_BARS.length, -top, left + w * (i + 1) / CRASH_BARS.length, top, 0.0002F, CRASH_BARS[i]));
            }
            SheetDraw.rects(poseStack, collector, bars, FULL_BRIGHT);
            return;
        }
        if (state.power == TerminalBlockEntity.POWER_BOOT && state.powerTicks < state.theme.logoTicks()) {
            drawLogo(poseStack, collector, state.theme, unit, left, top);
            return;
        }
        if (state.power != TerminalBlockEntity.POWER_ON) {
            // Loading (after the logo, or before going off).
            List<SheetDraw.Rect> dark = new ArrayList<>();
            dark.add(new SheetDraw.Rect(left, -top, -left, top, 0.0002F, TerminalUi.LOADING_BACKGROUND));
            TerminalUi.loading(rectPainter(dark, unit, left, top, 0.0004F), TerminalUi.GRID_W / 2.0F, TerminalUi.GRID_H / 2.0F, 6.0F, 2.2F);
            SheetDraw.rects(poseStack, collector, dark, FULL_BRIGHT);
            return;
        }
        if (state.picture != null) {
            // The user's screen as they see it, with the clock kept going (the picture's own may be old).
            SheetDraw.quad(poseStack, collector, state.picture, left, -top, -left, top, 0.0002F, 0xFFFFFFFF, FULL_BRIGHT);
            if (state.pictureGrid != null) liveClock(poseStack, collector, state.pictureGrid, -2.0F * left, 2.0F * top);
            return;
        }
        if (state.blank) {
            SheetDraw.rects(poseStack, collector, List.of(new SheetDraw.Rect(left, -top, -left, top, 0.0002F, 0xFF000000)), FULL_BRIGHT);
            return;
        }
        if (state.currentWindowMode || (state.pictureWanted && !state.user.isEmpty())) {
            // Black, and which window is open (also while a picture is on its way).
            drawCurrentWindow(poseStack, collector, state, unit, left, top);
            return;
        }
        // Drawn on this player's own system grid, so it looks just like the desktop on the screen.
        int[] grid = TerminalScreen.guiGrid();
        TerminalUi.size(grid[0], grid[1]);
        float gunit = screenW / grid[0];
        float gleft = left, gtop = grid[1] * gunit / 2.0F;
        drawSystem(state, poseStack, collector, gunit, gleft, gtop);
        TerminalUi.size(TerminalUi.GRID_W, TerminalUi.GRID_H);
    }

    /**
     * The same screen content as {@link #drawScreen}, drawn in a GUI (a SAWMO whiteboard's editor):
     * top left at x, y, {@code screenW} wide, 240:150 (16:10) in shape.
     */
    static void drawScreenGui(State state, net.minecraft.client.gui.GuiGraphicsExtractor graphics, float x, float y, float screenW) {
        float screenH = screenW * TerminalUi.GRID_H / TerminalUi.GRID_W;
        TerminalUi.size(TerminalUi.GRID_W, TerminalUi.GRID_H);
        float unit = screenW / TerminalUi.GRID_W;
        Font font = TerminalUi.font();
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        try {
            if (state.power == TerminalBlockEntity.POWER_CRASHED) {
                for (int i = 0; i < CRASH_BARS.length; i++) {
                    guiFill(graphics, screenW * i / CRASH_BARS.length, 0, screenW * (i + 1) / CRASH_BARS.length, screenH, CRASH_BARS[i]);
                }
                return;
            }
            if (state.power == TerminalBlockEntity.POWER_BOOT && state.powerTicks < state.theme.logoTicks()) {
                io.github.lwarqiu1shz.enplotify.api.TerminalTheme theme = state.theme;
                guiFill(graphics, 0, 0, screenW, screenH, theme.logoBackground());
                if (theme.logoImage().isPresent()) {
                    float w = screenW * theme.logoWidth(), h = w / theme.logoAspect();
                    guiImage(graphics, theme.logoImage().get(), (screenW - w) / 2.0F, (screenH - h) / 2.0F, w, h);
                } else {
                    FormattedCharSequence text = theme.logoText().orElseGet(TerminalUi::builtInLogo).getVisualOrderText();
                    int width = Math.max(1, font.width(text));
                    float size = screenW * 0.6F / width;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate((screenW - width * size) / 2.0F, screenH / 2.0F - 3.5F * size);
                    graphics.pose().scale(size, size);
                    graphics.text(font, text, 0, 0, 0xFFFFFFFF, false);
                    graphics.pose().popMatrix();
                }
                return;
            }
            if (state.power != TerminalBlockEntity.POWER_ON) {
                guiFill(graphics, 0, 0, screenW, screenH, TerminalUi.LOADING_BACKGROUND);
                TerminalUi.loading(guiPainter(graphics, unit), TerminalUi.GRID_W / 2.0F, TerminalUi.GRID_H / 2.0F, 6.0F, 2.2F);
                return;
            }
            if (state.picture != null) {
                guiImage(graphics, state.picture, 0, 0, screenW, screenH);
                return;
            }
            if (state.blank) {
                guiFill(graphics, 0, 0, screenW, screenH, 0xFF000000);
                return;
            }
            if (state.currentWindowMode || (state.pictureWanted && !state.user.isEmpty())) {
                guiFill(graphics, 0, 0, screenW, screenH, 0xFF000000);
                String[] lines = {net.minecraft.client.resources.language.I18n.get("terminal.enplotify.now_in"),
                        net.minecraft.client.resources.language.I18n.get("terminal.enplotify.window_bracket", TerminalUi.windowName(state.app))};
                float size = unit * 2.0F;
                for (int i = 0; i < lines.length; i++) {
                    float w = font.width(lines[i]) * size;
                    float ty = screenH / 2.0F - (i == 0 ? 10.0F : -2.0F) * size - 4.0F * size;
                    graphics.pose().pushMatrix();
                    graphics.pose().translate((screenW - w) / 2.0F, ty);
                    graphics.pose().scale(size, size);
                    graphics.text(font, lines[i], 0, 0, 0xFFFFFFFF, false);
                    graphics.pose().popMatrix();
                }
                return;
            }
            // The desktop (or a sketch of the open window), on this player's own system grid as on the wall.
            int[] grid = TerminalScreen.guiGrid();
            TerminalUi.size(grid[0], grid[1]);
            float gunit = screenW / grid[0];
            float gy = (screenH - grid[1] * gunit) / 2.0F;
            graphics.pose().pushMatrix();
            graphics.pose().translate(0.0F, gy);
            TerminalUi.Painter p = guiPainter(graphics, gunit);
            if (state.user.isEmpty() || state.app == TerminalBlockEntity.APP_DESKTOP) {
                TerminalUi.desktop(p, state.user, -1, state.theme.wallpaper().orElse(null), state.theme.iconText());
            } else {
                TerminalUi.windowSketch(p, state.app);
                TerminalUi.taskbar(p, state.user);
            }
            graphics.pose().popMatrix();
        } finally {
            graphics.pose().popMatrix();
            TerminalUi.size(TerminalUi.GRID_W, TerminalUi.GRID_H);
        }
    }

    private static void guiFill(net.minecraft.client.gui.GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x0, y0);
        graphics.pose().scale(x1 - x0, y1 - y0);
        graphics.fill(0, 0, 1, 1, argb);
        graphics.pose().popMatrix();
    }

    private static void guiImage(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.resources.Identifier texture,
                                 float x, float y, float w, float h) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(w / 64.0F, h / 64.0F);
        graphics.blit(texture, 0, 0, 64, 64, 0.0F, 1.0F, 0.0F, 1.0F);
        graphics.pose().popMatrix();
    }

    /** Grid coordinates (y down) scaled by {@code unit} onto the current GUI pose. */
    private static TerminalUi.Painter guiPainter(net.minecraft.client.gui.GuiGraphicsExtractor graphics, float unit) {
        Font font = TerminalUi.font();
        return new TerminalUi.Painter() {
            @Override
            public void fill(float x0, float y0, float x1, float y1, int argb) {
                guiFill(graphics, x0 * unit, y0 * unit, x1 * unit, y1 * unit, argb);
            }

            @Override
            public void text(String text, float x, float y, int argb) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(x * unit, y * unit);
                graphics.pose().scale(unit, unit);
                graphics.text(font, text, 0, 0, argb, false);
                graphics.pose().popMatrix();
            }

            @Override
            public void image(net.minecraft.resources.Identifier texture, float x0, float y0, float x1, float y1) {
                guiImage(graphics, texture, x0 * unit, y0 * unit, (x1 - x0) * unit, (y1 - y0) * unit);
            }
        };
    }

    /** Fills {@code state} to show this terminal's screen on a display connected to it. */
    static void extractForDisplay(TerminalBlockEntity terminal, State state, float partialTicks, boolean seen) {
        BlockPos pos = terminal.getBlockPos();
        state.display = null;
        state.light = null;
        state.near = true;
        state.user = terminal.userName();
        state.app = terminal.app();
        // At this terminal: its own pictures, taken for the display; otherwise asked of its user.
        boolean painter = pos.equals(TerminalView.pos());
        state.currentWindowMode = !painter && io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.terminalDisplay()
                == io.github.lwarqiu1shz.enplotify.EnPlotifyConfig.TerminalDisplay.CURRENT_WINDOW;
        state.pictureWanted = !painter && !state.currentWindowMode;
        state.picture = painter ? TerminalShots.ownPicture(pos, seen) : state.pictureWanted ? TerminalShots.picture(pos, seen) : null;
        state.hidden = false;
        state.blank = painter && state.picture == null;
        state.pictureGrid = state.picture == null ? null : TerminalShots.pictureGrid(pos);
        state.power = terminal.power();
        state.powerTicks = terminal.getLevel() == null ? 0.0F
                : (float) (terminal.getLevel().getGameTime() - terminal.powerSince()) + partialTicks;
        state.theme = terminal.theme();
        if (state.power != TerminalBlockEntity.POWER_ON) state.picture = null;
    }

    /** The terminal is too far away for this player's world: only its pictures (black until one comes). */
    static void extractFarAway(BlockPos pos, State state, boolean seen) {
        state.display = null;
        state.light = null;
        state.near = true;
        state.user = "";
        state.app = TerminalBlockEntity.APP_DESKTOP;
        state.currentWindowMode = false;
        state.pictureWanted = true;
        state.picture = TerminalShots.picture(pos, seen);
        state.hidden = false;
        state.blank = state.picture == null;
        state.pictureGrid = state.picture == null ? null : TerminalShots.pictureGrid(pos);
        state.power = TerminalBlockEntity.POWER_ON;
        state.powerTicks = 1000.0F;
        state.theme = io.github.lwarqiu1shz.enplotify.api.TerminalTheme.BUILT_IN;
    }

    /** The desktop (or a sketch of the open window) drawn here, on the given grid. */
    private static void drawSystem(State state, PoseStack poseStack, SubmitNodeCollector collector, float unit, float left, float top) {
        List<SheetDraw.Rect> rects = new ArrayList<>();
        List<Object[]> texts = new ArrayList<>();
        float[] depth = {0.0F};
        TerminalUi.Painter painter = new TerminalUi.Painter() {
            @Override
            public void fill(float x0, float y0, float x1, float y1, int argb) {
                depth[0] += 0.00002F;
                rects.add(new SheetDraw.Rect(left + x0 * unit, top - y1 * unit, left + x1 * unit, top - y0 * unit, depth[0], argb));
            }

            @Override
            public void text(String text, float x, float y, int argb) {
                texts.add(new Object[] {text, left + x * unit, top - y * unit, argb});
            }

            @Override
            public void image(net.minecraft.resources.Identifier texture, float x0, float y0, float x1, float y1) {
                SheetDraw.quad(poseStack, collector, texture, left + x0 * unit, top - y1 * unit, left + x1 * unit, top - y0 * unit,
                        0.00001F, 0xFFFFFFFF, FULL_BRIGHT);
            }
        };
        if (state.user.isEmpty() || state.app == TerminalBlockEntity.APP_DESKTOP) {
            // Never pictured yet (a new terminal) or my own: a drawn desktop.
            TerminalUi.desktop(painter, state.user, -1, state.theme.wallpaper().orElse(null), state.theme.iconText());
        } else {
            // Only the window and the taskbar: desktop labels would show through the window.
            TerminalUi.windowSketch(painter, state.app);
            TerminalUi.taskbar(painter, state.user);
        }
        SheetDraw.rects(poseStack, collector, rects, FULL_BRIGHT);
        float textZ = depth[0] + 0.0002F;
        for (Object[] t : texts) {
            poseStack.pushPose();
            poseStack.translate((float) t[1], (float) t[2], textZ);
            poseStack.scale(unit, -unit, unit);
            collector.submitText(poseStack, 0.0F, 0.0F, FormattedCharSequence.forward((String) t[0], Style.EMPTY), false,
                    Font.DisplayMode.NORMAL, FULL_BRIGHT, (int) t[3], 0, 0);
            poseStack.popPose();
        }
    }

    /** Covers the clock in a picture's taskbar and draws the time now (laid out like {@link TerminalUi#taskbar}). */
    private static void liveClock(PoseStack poseStack, SubmitNodeCollector collector, int[] grid, float screenW, float screenH) {
        float unit = screenW / grid[0];
        float left = -screenW / 2.0F, top = screenH / 2.0F;
        Font font = TerminalUi.font();
        String clock = TerminalUi.clock();
        int widest = Math.max(font.width(clock), font.width(net.minecraft.client.resources.language.I18n.get(
                "terminal.enplotify.clock_night", 9999, "00:00")));
        float y0 = grid[1] - TerminalUi.taskbar(grid[0], grid[1]), y1 = grid[1];
        float x0 = grid[0] - 5 - widest, x1 = grid[0];
        SheetDraw.rects(poseStack, collector, List.of(new SheetDraw.Rect(left + x0 * unit, top - y1 * unit, left + x1 * unit,
                top - y0 * unit, 0.0004F, 0xFF101820)), FULL_BRIGHT);
        float tx = grid[0] - 3 - font.width(clock), ty = (y0 + y1) / 2.0F - 3.5F;
        poseStack.pushPose();
        poseStack.translate(left + tx * unit, top - ty * unit, 0.0006F);
        poseStack.scale(unit, -unit, unit);
        collector.submitText(poseStack, 0.0F, 0.0F, FormattedCharSequence.forward(clock, Style.EMPTY), false,
                Font.DisplayMode.NORMAL, FULL_BRIGHT, TerminalUi.TEXT_LIGHT, 0, 0);
        poseStack.popPose();
    }

    /** A painter that only collects rectangles (screen grid → this pose). */
    private static TerminalUi.Painter rectPainter(List<SheetDraw.Rect> rects, float unit, float left, float top, float z) {
        return new TerminalUi.Painter() {
            @Override
            public void fill(float x0, float y0, float x1, float y1, int argb) {
                rects.add(new SheetDraw.Rect(left + x0 * unit, top - y1 * unit, left + x1 * unit, top - y0 * unit, z, argb));
            }

            @Override
            public void text(String text, float x, float y, int argb) {}
        };
    }

    /** The start-up logo, in the middle: the theme's picture or text (the built-in "EnPlotify OS" if none). */
    private static void drawLogo(PoseStack poseStack, SubmitNodeCollector collector, io.github.lwarqiu1shz.enplotify.api.TerminalTheme theme,
                                 float unit, float left, float top) {
        SheetDraw.rects(poseStack, collector, List.of(new SheetDraw.Rect(left, -top, -left, top, 0.0002F, theme.logoBackground())), FULL_BRIGHT);
        float screenW = -2.0F * left;
        if (theme.logoImage().isPresent()) {
            float w = screenW * theme.logoWidth(), h = w / theme.logoAspect();
            SheetDraw.quad(poseStack, collector, theme.logoImage().get(), -w / 2.0F, -h / 2.0F, w / 2.0F, h / 2.0F, 0.0004F, 0xFFFFFFFF, FULL_BRIGHT);
            return;
        }
        net.minecraft.network.chat.Component logo = theme.logoText().orElseGet(TerminalUi::builtInLogo);
        Font font = TerminalUi.font();
        FormattedCharSequence text = logo.getVisualOrderText();
        int width = Math.max(1, font.width(text));
        // About 60 % of the screen wide; letters are 7 font pixels high.
        float size = screenW * 0.6F / width;
        poseStack.pushPose();
        poseStack.translate(-width * size / 2.0F, 3.5F * size, 0.0006F);
        poseStack.scale(size, -size, size);
        collector.submitText(poseStack, 0.0F, 0.0F, text, false, Font.DisplayMode.NORMAL, FULL_BRIGHT, 0xFFFFFFFF, 0, theme.logoOutline());
        poseStack.popPose();
    }

    private static final net.minecraft.resources.Identifier LIGHT_ON =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("enplotify", "textures/block/terminal_power_on.png");
    private static final net.minecraft.resources.Identifier LIGHT_OFF =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("enplotify", "textures/block/terminal_power_off.png");

    /** The round power light in the middle of the button on top: blue when on (or starting), red when off. */
    private static void powerLight(State state, PoseStack poseStack, SubmitNodeCollector collector) {
        double[] l = state.light;
        if (l == null) return;
        boolean on = state.power == TerminalBlockEntity.POWER_ON || state.power == TerminalBlockEntity.POWER_BOOT
                || state.power == TerminalBlockEntity.POWER_CRASHED;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.yRot));
        poseStack.translate(-0.5F, 0.0F, -0.5F);
        poseStack.translate(l[0] / 16.0, l[1] / 16.0 + 0.0006, l[2] / 16.0);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        // Lying on the top, its top edge towards the back of the screen.
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        float r = (float) (l[3] / 32.0);
        SheetDraw.quad(poseStack, collector, on ? LIGHT_ON : LIGHT_OFF, -r, -r, r, r, 0.0F, 0xFFFFFFFF, FULL_BRIGHT);
        poseStack.popPose();
    }

    /** Black screen with two white lines: "当前处在" / "【window】". */
    private static void drawCurrentWindow(PoseStack poseStack, SubmitNodeCollector collector, State state,
                                          float unit, float left, float top) {
        SheetDraw.rects(poseStack, collector, List.of(new SheetDraw.Rect(left, -top, -left, top, 0.0002F, 0xFF000000)), FULL_BRIGHT);
        Font font = TerminalUi.font();
        String[] lines = {net.minecraft.client.resources.language.I18n.get("terminal.enplotify.now_in"),
                net.minecraft.client.resources.language.I18n.get("terminal.enplotify.window_bracket", TerminalUi.windowName(state.app))};
        float size = unit * 2.0F; // text twice the usual grid size, readable from a few blocks away
        for (int i = 0; i < lines.length; i++) {
            float w = font.width(lines[i]) * size;
            float y = (i == 0 ? 10.0F : -2.0F) * size;
            poseStack.pushPose();
            poseStack.translate(-w / 2.0F, y + 4.0F * size, 0.0006F);
            poseStack.scale(size, -size, size);
            collector.submitText(poseStack, 0.0F, 0.0F, FormattedCharSequence.forward(lines[i], Style.EMPTY), false,
                    Font.DisplayMode.NORMAL, FULL_BRIGHT, 0xFFFFFFFF, 0, 0);
            poseStack.popPose();
        }
    }
}
