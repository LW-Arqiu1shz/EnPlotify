package io.github.lwarqiu1shz.enplotify.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Full-screen black page for {@link PlotCutscene}; swallows all input so the player stays put. */
public final class PlotScreen extends Screen {
    private boolean spaceDown;

    PlotScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        PlotCutscene.onScreenShown();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (PlotCutscene.usesOpaqueBackdrop()) {
            graphics.fill(0, 0, this.width, this.height, 0xFF000000);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        PlotCutscene.renderText(graphics, this.width, this.height);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (spaceDown) return true; // key repeat while held
            spaceDown = true;
            if (isDown(GLFW.GLFW_KEY_TAB)) PlotCutscene.skip();
            else PlotCutscene.advance();
        } else if (key == GLFW.GLFW_KEY_TAB) {
            if (spaceDown || isDown(GLFW.GLFW_KEY_SPACE)) PlotCutscene.skip();
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_SPACE) spaceDown = false;
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) PlotCutscene.advance();
        return true;
    }

    private boolean isDown(int key) {
        return InputConstants.isKeyDown(this.minecraft.getWindow(), key);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
