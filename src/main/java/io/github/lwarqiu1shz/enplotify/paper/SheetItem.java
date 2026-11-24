package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;


/**
 * A sheet that lies on horizontal surfaces (newspaper, document). Sneak + right click the top of a
 * block to lay it down as a new pile; right clicking a sheet that already lies there (the pile
 * entity itself) puts it on top of that pile instead.
 */
public abstract class SheetItem extends PaperItem {
    protected SheetItem(Properties properties, @Nullable Identifier preset) {
        super(properties, preset);
    }

    /**
     * The lectern's sloped top board (vanilla model, in 1/16 blocks, measured in from the reader's
     * side): its low edge is 0.32 in at height 13.48, its high edge 12.33 in; it rises at 22.5°.
     * A pile's middle is kept a little inside the board.
     */
    private static final double LECTERN_LOW_Z = 0.32, LECTERN_LOW_Y = 13.48;
    private static final double LECTERN_IN_MIN = 2.5, LECTERN_IN_MAX = 10.0, LECTERN_HALF_W = 0.3;
    private static final float LECTERN_TILT = 22.5F;

    /** Footprint on the table in blocks: {across, along}. */
    public abstract float[] footprint();

    /** One letter per kind, used to sync a pile's layers: 'N' newspaper, 'D' document. */
    public abstract char layerCode();

    public abstract boolean isEditable(ItemStack stack);

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()) return InteractionResult.PASS;
        net.minecraft.world.level.block.state.BlockState clicked = context.getLevel().getBlockState(context.getClickedPos());
        boolean lectern = clicked.getBlock() instanceof net.minecraft.world.level.block.LecternBlock;
        if (!lectern && context.getClickedFace() != Direction.UP) return InteractionResult.PASS;
        if (context.getLevel() instanceof ServerLevel level) {
            Vec3 at = context.getClickLocation();
            float yaw = player.getYRot();
            float tilt = 0.0F;
            float slopeYaw = 0.0F;
            if (lectern) {
                // Lies on the sloped top where it was clicked, turned the way the player looks.
                Direction facing = clicked.getValue(net.minecraft.world.level.block.LecternBlock.FACING);
                Vec3 centre = Vec3.atBottomCenterOf(context.getClickedPos());
                Direction side = facing.getClockWise();
                double dx = at.x - centre.x, dz = at.z - centre.z;
                // Across the board, and how far in from its low (reader's) edge, in 1/16 blocks.
                double across = Math.clamp(dx * side.getStepX() + dz * side.getStepZ(), -LECTERN_HALF_W, LECTERN_HALF_W);
                double in = Math.clamp((0.5 - (dx * facing.getStepX() + dz * facing.getStepZ())) * 16.0, LECTERN_IN_MIN, LECTERN_IN_MAX);
                double toward = 0.5 - in / 16.0; // towards the reader, from the block centre
                double height = (LECTERN_LOW_Y + (in - LECTERN_LOW_Z) * Math.tan(Math.toRadians(LECTERN_TILT))) / 16.0;
                at = new Vec3(centre.x + side.getStepX() * across + facing.getStepX() * toward,
                        centre.y + height,
                        centre.z + side.getStepZ() * across + facing.getStepZ() * toward);
                slopeYaw = facing.getOpposite().toYRot();
                tilt = LECTERN_TILT;
            }
            ItemStack stack = context.getItemInHand();
            PaperStackEntity pile = new PaperStackEntity(level, at, yaw, stack.copyWithCount(1));
            pile.setTilt(tilt, slopeYaw);
            pile.layOver();
            level.addFreshEntity(pile);
            pile.playSound(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 0.8F, 1.0F);
            stack.consume(1, player);
        }
        return InteractionResult.SUCCESS;
    }
}
