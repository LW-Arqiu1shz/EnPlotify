package io.github.lwarqiu1shz.enplotify.mixin.client;

import io.github.lwarqiu1shz.enplotify.client.paper.TerminalView;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a terminal is used the camera glides to its screen (see {@link TerminalView}). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private Vec3 position;
    @Shadow private Entity entity;

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Shadow protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void enplotify$terminalCamera(float partialTicks, CallbackInfo ci) {
        if (!TerminalView.active() || entity == null) return;
        Vec3 eye = entity.getEyePosition(partialTicks);
        Vec3 at = TerminalView.cameraPosition(eye);
        if (at == null) return;
        float[] turn = TerminalView.cameraRotation(entity.getViewYRot(partialTicks), entity.getViewXRot(partialTicks));
        if (turn != null) setRotation(turn[0], turn[1], 0.0F);
        setPosition(at);
    }
}
