package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.function.Consumer;

/** The screen of a terminal. Carries its files when mined. */
public final class TerminalItem extends BlockItem {
    public TerminalItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("block.enplotify.terminal.tooltip.place").withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("block.enplotify.terminal.tooltip.use").withStyle(ChatFormatting.GRAY));
        List<TerminalFile> files = stack.get(PaperRegistry.TERMINAL_FILES.get());
        if (files != null && !files.isEmpty()) {
            builder.accept(Component.translatable("block.enplotify.terminal.tooltip.files", files.size()).withStyle(ChatFormatting.GRAY));
        }
    }
}
