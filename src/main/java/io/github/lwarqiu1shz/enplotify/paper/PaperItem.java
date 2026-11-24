package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Common base of the paper items. A {@linkplain #preset() preset} item belongs to another mod and
 * shows that mod's fixed content (from its resources); a non-preset item is the blank, player-made
 * kind whose content lives in the stack's data component.
 */
public abstract class PaperItem extends Item {
    private final @Nullable Identifier preset;

    protected PaperItem(Properties properties, @Nullable Identifier preset) {
        super(properties);
        this.preset = preset;
    }

    /** Id of the mod-provided content, or null for the player-made kind. */
    public @Nullable Identifier preset() {
        return preset;
    }

    public boolean isPreset() {
        return preset != null;
    }
}
