package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

import java.util.function.Consumer;

/** A display: right click a wall to choose its size and hang it (like a writing board). */
public class DisplayItem extends Item {
    public DisplayItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Direction face = context.getClickedFace();
        if (context.getPlayer() == null || !face.getAxis().isHorizontal()) return InteractionResult.PASS;
        if (context.getLevel().isClientSide()) {
            io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.openDisplaySizePicker(
                    context.getHand(), context.getClickedPos(), face);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.enplotify.display.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        builder.accept(Component.translatable("item.enplotify.display.tooltip.connect").withStyle(ChatFormatting.DARK_GRAY));
    }
}
