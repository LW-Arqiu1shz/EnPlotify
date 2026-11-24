package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gameevent.GameEvent;

/** Server: hangs a poster / board of a given size on the wall face the player clicked. */
public final class WallPlacement {
    /**
     * @param clicked the wall block that was clicked
     * @param face    the clicked face (horizontal); the poster faces this way
     * @return true if it was placed
     */
    public static boolean place(ServerPlayer player, InteractionHand hand, BlockPos clicked, Direction face, int width, int height) {
        if (!face.getAxis().isHorizontal()) return false;
        if (width < 1 || height < 1 || width > PaperContents.MAX_CELLS || height > PaperContents.MAX_CELLS) return false;
        if (player.distanceToSqr(clicked.getCenter()) > 64.0) return false;
        ItemStack held = player.getItemInHand(hand);
        BlockPos pos = clicked.relative(face);
        if (!player.mayUseItemAt(pos, face, held)) return false;

        SizedHangingEntity entity;
        if (held.getItem() instanceof PosterItem poster) {
            int[] size = poster.size(held);
            if (size[0] > 0 && (size[0] != width || size[1] != height)) return false;
            ItemStack placed = held.copyWithCount(1);
            if (!poster.isPreset()) {
                placed.set(PaperRegistry.POSTER_CONTENT.get(), PosterItem.content(held).withSize(width, height));
            }
            entity = new PosterEntity(player.level(), pos, face, width, height, placed);
        } else if (held.getItem() instanceof BoardItem board) {
            int[] size = board.size(held);
            if (size[0] > 0 && (size[0] != width || size[1] != height)) return false;
            entity = board instanceof SawmoItem
                    ? new SawmoBoardEntity(player.level(), pos, face, width, height, held)
                    : new BoardEntity(player.level(), pos, face, width, height, held);
        } else if (held.getItem() instanceof DisplayItem) {
            net.minecraft.network.chat.Component named = held.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            entity = new DisplayEntity(player.level(), pos, face, width, height, named == null ? "" : named.getString());
        } else {
            return false;
        }

        if (!entity.survives()) {
            ActionBar.show(player, Component.translatable("message.enplotify.no_room", width, height));
            return false;
        }
        entity.playPlacementSound();
        player.level().gameEvent(player, GameEvent.ENTITY_PLACE, entity.position());
        player.level().addFreshEntity(entity);
        held.consume(1, player);
        return true;
    }

    private WallPlacement() {}
}
