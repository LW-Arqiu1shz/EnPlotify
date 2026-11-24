package io.github.lwarqiu1shz.enplotify.frame;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.function.Consumer;

/** Places a {@link GhostItemFrame} on any face of a block, like a vanilla item frame. */
public final class GhostItemFrameItem extends Item {
    public GhostItemFrameItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPos placePos = context.getClickedPos().relative(context.getClickedFace());
        Direction direction = context.getClickedFace();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player != null && (player.level().isOutsideBuildHeight(placePos) || !player.mayUseItemAt(placePos, direction, stack))) {
            return InteractionResult.FAIL;
        }
        Level level = context.getLevel();
        GhostItemFrame frame = new GhostItemFrame(level, placePos, direction);
        EntityType.<GhostItemFrame>createDefaultStackConfig(level, stack, player).accept(frame);
        if (!frame.survives()) return InteractionResult.CONSUME;
        if (!level.isClientSide()) {
            frame.playPlacementSound();
            level.gameEvent(player, GameEvent.ENTITY_PLACE, frame.position());
            level.addFreshEntity(frame);
        }
        stack.shrink(1);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.enplotify.ghost_item_frame.tooltip").withStyle(ChatFormatting.DARK_GRAY));
    }
}
