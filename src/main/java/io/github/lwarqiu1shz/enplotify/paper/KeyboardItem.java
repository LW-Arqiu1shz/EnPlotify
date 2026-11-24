package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.function.Consumer;

/** Keyboard and mouse: goes into a terminal's cell (the terminal item stays the block's own item). */
public final class KeyboardItem extends BlockItem {
    public KeyboardItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void registerBlocks(Map<Block, Item> map, Item item) {
        // The block's item is the terminal.
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.enplotify.keyboard.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
