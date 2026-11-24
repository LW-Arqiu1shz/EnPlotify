package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** The printer as an item: a short hint on how to use it. */
public final class PrinterItem extends BlockItem {
    public PrinterItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("block.enplotify.printer.tooltip.put").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("block.enplotify.printer.tooltip.take").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("block.enplotify.printer.tooltip.print").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("block.enplotify.printer.tooltip.tray").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("block.enplotify.printer.tooltip.more").withStyle(ChatFormatting.GRAY));
    }
}
