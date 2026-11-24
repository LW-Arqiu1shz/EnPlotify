package io.github.lwarqiu1shz.enplotify.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the story mode zero the already-computed movement vector while players are locked. */
@Mixin(value = ClientInput.class, remap = false)
public interface ClientInputAccessor {
    @Accessor(value = "moveVector", remap = false)
    void enplotify$setMoveVector(Vec2 moveVector);
}
