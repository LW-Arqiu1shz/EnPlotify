package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** 望漠白板: hung and written on like a whiteboard, connected to a terminal like a display. */
public class SawmoItem extends BoardItem {
    public SawmoItem(Properties properties) {
        this(properties, 0, 0);
    }

    /** One that always hangs at {@code width x height} cells (0 = asks for its size, like EnPlotify's). */
    public SawmoItem(Properties properties, int width, int height) {
        super(properties, Style.WHITEBOARD, null, width, height);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.enplotify.sawmo_whiteboard.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        builder.accept(Component.translatable("item.enplotify.sawmo_whiteboard.tooltip.connect").withStyle(ChatFormatting.DARK_GRAY));
    }
}
