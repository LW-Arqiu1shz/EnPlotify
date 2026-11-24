package io.github.lwarqiu1shz.enplotify.paper;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A printer that copies documents, newspapers and posters. Facing = the side with the output tray.
 * All clicks are handled by {@link PrinterBlockEntity} through {@code PrinterAction} payloads (the
 * client knows about Ctrl, the server does not).
 */
public class PrinterBlock extends BaseEntityBlock {
    public static final MapCodec<PrinterBlock> CODEC = simpleCodec(PrinterBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    /** Model parts in 1/16 blocks, front (tray) towards north. */
    public static final double BODY_FRONT = 4.0, BODY_TOP = 9.0, TRAY_TOP = 1.5;
    /** The glass: as long as a document is high, as deep as it is wide (a page lies on it sideways). */
    public static final double GLASS_X0 = 2.0, GLASS_X1 = 14.0, GLASS_Z0 = 4.6,
            GLASS_Z1 = GLASS_Z0 + (GLASS_X1 - GLASS_X0) * 210.0 / 297.0, GLASS_TOP = 9.15;
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(1.0, 0.0, BODY_FRONT, 15.0, BODY_TOP, 15.0),   // body
            Block.box(2.0, BODY_TOP, 14.3, 14.0, 15.0, 15.0),         // paper rest at the back
            Block.box(2.0, 0.0, 0.5, 14.0, TRAY_TOP, BODY_FRONT)));   // output tray

    public PrinterBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PrinterBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, PaperRegistry.PRINTER_ENTITY.get(), PrinterBlockEntity::tick);
    }

    /** A redstone signal turning on starts a copy. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                   net.minecraft.world.level.redstone.@Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PrinterBlockEntity printer) {
            printer.redstone(level.hasNeighborSignal(pos));
        }
    }

    /** Where on a printer a click landed. */
    public enum Part { TOP, TRAY, OTHER }

    /** The part hit at {@code local} (hit position minus the block's corner). */
    public static Part partAt(BlockState state, Direction face, double x, double y, double z) {
        Direction facing = state.getValue(FACING);
        // Distance from the front edge, in 1/16 blocks.
        double fromFront = (0.5 - ((x - 0.5) * facing.getStepX() + (z - 0.5) * facing.getStepZ())) * 16.0;
        double height = y * 16.0;
        // The tray, and the front of the body around the output slot.
        if (fromFront < BODY_FRONT + 0.01 && height < 4.5) return Part.TRAY;
        if (face == Direction.UP && height > BODY_TOP - 0.01) return Part.TOP;
        return Part.OTHER;
    }
}
