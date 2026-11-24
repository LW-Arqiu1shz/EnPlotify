package io.github.lwarqiu1shz.enplotify.mixin.client;

import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultilineTextField.class)
public interface MultilineTextFieldAccessor {
    @Accessor("selectCursor")
    int enplotify$selectCursor();
}
