package io.github.lwarqiu1shz.enplotify.mixin.client;

import io.github.lwarqiu1shz.enplotify.client.PlotNotice;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes where everything on the HUD is drawn, so the story notice can stay clear of it (minimaps…). */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    @Inject(method = "addGuiElement", at = @At("HEAD"))
    private void enplotify$element(GuiElementRenderState state, CallbackInfo ci) {
        PlotNotice.noteDrawn(state.bounds());
    }

    @Inject(method = "addText", at = @At("HEAD"))
    private void enplotify$text(GuiTextRenderState state, CallbackInfo ci) {
        PlotNotice.noteDrawn(state.bounds());
    }

    @Inject(method = "addItem", at = @At("HEAD"))
    private void enplotify$item(GuiItemRenderState state, CallbackInfo ci) {
        PlotNotice.noteDrawn(state.bounds());
    }

    @Inject(method = "addPicturesInPictureState", at = @At("HEAD"))
    private void enplotify$picture(PictureInPictureRenderState state, CallbackInfo ci) {
        PlotNotice.noteDrawn(state.bounds());
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void enplotify$reset(CallbackInfo ci) {
        PlotNotice.frameDone();
    }
}
