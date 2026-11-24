package io.github.lwarqiu1shz.enplotify.frame;

import io.github.lwarqiu1shz.enplotify.paper.PaperRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Ghost item frame: an item frame whose frame is only seen by players holding a ghost item frame
 * (the item inside is always seen). Behaves like a vanilla item frame otherwise.
 */
public class GhostItemFrame extends ItemFrame {
    public GhostItemFrame(EntityType<? extends GhostItemFrame> type, Level level) {
        super(type, level);
    }

    public GhostItemFrame(Level level, BlockPos pos, Direction direction) {
        super(PaperRegistry.GHOST_ITEM_FRAME_ENTITY.get(), level, pos, direction);
    }

    /** Fixes {@code T = GhostItemFrame} for the entity type factory. */
    public static GhostItemFrame create(EntityType<GhostItemFrame> type, Level level) {
        return new GhostItemFrame(type, level);
    }

    /** Broken or picked: gives the ghost frame back, not a vanilla one. */
    @Override
    protected ItemStack getFrameItemStack() {
        return new ItemStack(PaperRegistry.GHOST_ITEM_FRAME.get());
    }
}
