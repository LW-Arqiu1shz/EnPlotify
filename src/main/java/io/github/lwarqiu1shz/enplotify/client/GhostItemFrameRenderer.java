package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.frame.GhostItemFrame;
import io.github.lwarqiu1shz.enplotify.paper.PaperRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;

/**
 * Draws a ghost item frame like a vanilla one, but the frame itself only while the local player
 * holds a ghost item frame (like barriers). The item inside always stays exactly where it sits in
 * a normal frame, so it does not jump when the frame shows or hides.
 */
public final class GhostItemFrameRenderer extends ItemFrameRenderer<GhostItemFrame> {
    public GhostItemFrameRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void extractRenderState(GhostItemFrame entity, ItemFrameRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        if (!holdsGhostFrame()) state.frameModel.clear();
    }

    private static boolean holdsGhostFrame() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && (player.getMainHandItem().is(PaperRegistry.GHOST_ITEM_FRAME.get())
                || player.getOffhandItem().is(PaperRegistry.GHOST_ITEM_FRAME.get()));
    }
}
