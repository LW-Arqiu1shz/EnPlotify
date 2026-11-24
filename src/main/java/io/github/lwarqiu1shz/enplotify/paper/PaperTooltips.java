package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

final class PaperTooltips {
    static void addState(Consumer<Component> builder, boolean locked, String author) {
        if (locked) {
            builder.accept(author.isEmpty()
                    ? Component.translatable("item.enplotify.tooltip.locked").withStyle(ChatFormatting.GOLD)
                    : Component.translatable("item.enplotify.tooltip.locked_by", author).withStyle(ChatFormatting.GOLD));
        }
    }

    /**
     * Item name in the form "Blackboard[2×3]: name": the size when it has one, the player's name
     * (or title) when there is one.
     */
    static Component name(Component base, int width, int height, String name) {
        Component out = base;
        if (width > 0 && height > 0) out = Component.translatable("item.enplotify.name.sized", out, width, height);
        if (name != null && !name.isBlank()) out = Component.translatable("item.enplotify.name.named", out, name);
        return out;
    }

    /** A ready-made sheet: "<kind>: <its own item name>". */
    static Component presetName(Component kind, Component name) {
        return Component.translatable("item.enplotify.name.named", kind, name);
    }

    private PaperTooltips() {}
}
