package io.github.lwarqiu1shz.enplotify.paper;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * One cell of a desk: a screen (on a wall or standing on the desk) and/or a keyboard and mouse.
 * Both are placed into the same cell; the screen can only be used while the keyboard is there.
 * Facing = the way the screen looks (towards the user). Mined part by part: whatever the player
 * aims at comes off first. The keyboard has no collision.
 */
public class TerminalBlock extends BaseEntityBlock {
    public static final MapCodec<TerminalBlock> CODEC = simpleCodec(TerminalBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Mount> SCREEN = EnumProperty.create("screen", Mount.class);
    public static final BooleanProperty KEYBOARD = BooleanProperty.create("keyboard");

    public enum Mount implements StringRepresentable {
        NONE("none"), WALL("wall"), DESK("desk");

        private final String name;

        Mount(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    // Model parts in 1/16 blocks, the screen looking north (the user stands at low z).
    /** The picture on the screen: x0, y0, x1, y1 and the z of its front face. 16:10 (15 x 9.375 pixels). */
    public static final double[] DESK_DISPLAY = {0.5, 4.3125, 15.5, 13.6875, 10.0};
    public static final double[] WALL_DISPLAY = {0.5, 5.3125, 15.5, 14.6875, 14.0};

    private static final Map<Direction, VoxelShape> DESK_SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(5.0, 0.0, 10.0, 11.0, 1.0, 14.0),     // foot
            Block.box(7.0, 1.0, 12.0, 9.0, 4.0, 13.0),      // neck
            Block.box(0.0, 4.0, 10.0, 16.0, 14.0, 12.0),    // panel
            Block.box(3.0, 6.0, 12.0, 13.0, 12.0, 13.0),    // back
            Block.box(6.5, 14.0, 10.4, 9.5, 14.25, 11.6))); // power button, on top
    private static final Map<Direction, VoxelShape> WALL_SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(0.0, 5.0, 14.0, 16.0, 15.0, 16.0),
            Block.box(6.5, 15.0, 14.4, 9.5, 15.25, 15.6))); // power button, on top
    /** The power button (a little larger than drawn, so it is easy to hit). */
    private static final Map<Direction, VoxelShape> DESK_BUTTON = Shapes.rotateHorizontal(Block.box(6.0, 13.9, 10.0, 10.0, 14.8, 12.0));
    private static final Map<Direction, VoxelShape> WALL_BUTTON = Shapes.rotateHorizontal(Block.box(6.0, 14.9, 14.0, 10.0, 15.8, 16.0));
    /** The round power light on top of the button: centre x, top y, centre z (pixels), and its diameter. */
    public static final double[] DESK_LIGHT = {8.0, 14.25, 11.0, 1.05};
    public static final double[] WALL_LIGHT = {8.0, 15.25, 15.0, 1.05};
    private static final Map<Direction, VoxelShape> KEYBOARD_SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(5.0, 0.0, 3.0, 15.0, 1.0, 7.0),       // keyboard
            Block.box(2.0, 0.0, 3.5, 4.0, 1.0, 6.5)));      // mouse, on the user's right

    public TerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(SCREEN, Mount.DESK).setValue(KEYBOARD, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SCREEN, KEYBOARD);
    }

    public static boolean hasScreen(BlockState state) {
        return state.getValue(SCREEN) != Mount.NONE;
    }

    /** The display rectangle for this state (null when there is no screen). */
    public static double @Nullable [] display(BlockState state) {
        return switch (state.getValue(SCREEN)) {
            case DESK -> DESK_DISPLAY;
            case WALL -> WALL_DISPLAY;
            case NONE -> null;
        };
    }

    // ------------------------------------------------------------------ shapes

    private static VoxelShape screenShape(BlockState state) {
        return switch (state.getValue(SCREEN)) {
            case DESK -> DESK_SHAPES.get(state.getValue(FACING));
            case WALL -> WALL_SHAPES.get(state.getValue(FACING));
            case NONE -> Shapes.empty();
        };
    }

    private static VoxelShape keyboardShape(BlockState state) {
        return state.getValue(KEYBOARD) ? KEYBOARD_SHAPES.get(state.getValue(FACING)) : Shapes.empty();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.or(screenShape(state), keyboardShape(state));
    }

    /** Only the screen is solid; one walks through the keyboard. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return screenShape(state);
    }

    // ------------------------------------------------------------------ placing

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        ItemStack stack = context.getItemInHand();
        if (stack.getItem() instanceof KeyboardItem && !state.getValue(KEYBOARD)) return true;
        if (stack.getItem() instanceof TerminalItem && !hasScreen(state)) return true;
        return super.canBeReplaced(state, context);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState existing = level.getBlockState(pos);
        // Any terminal block (EnPlotify's or another mod's look of it) takes the missing part. The
        // keyboard joins whatever block is there; a screen brings its own block (look) along.
        boolean merge = existing.getBlock() instanceof TerminalBlock;
        BlockState base;
        if (!merge) {
            base = defaultBlockState().setValue(SCREEN, Mount.NONE)
                    .setValue(FACING, context.getHorizontalDirection().getOpposite());
        } else if (existing.is(this) || context.getItemInHand().getItem() instanceof KeyboardItem) {
            base = existing;
        } else {
            base = defaultBlockState().setValue(SCREEN, existing.getValue(SCREEN))
                    .setValue(KEYBOARD, existing.getValue(KEYBOARD)).setValue(FACING, existing.getValue(FACING));
        }
        BlockState result;
        if (context.getItemInHand().getItem() instanceof KeyboardItem) {
            if (merge && existing.getValue(KEYBOARD)) return null;
            result = base.setValue(KEYBOARD, true);
        } else {
            if (merge && hasScreen(existing)) return null;
            Direction face = context.getClickedFace();
            if (face == Direction.DOWN) return null;
            result = face == Direction.UP
                    ? base.setValue(SCREEN, Mount.DESK)
                    : base.setValue(SCREEN, Mount.WALL).setValue(FACING, face);
        }
        return supported(result, level, pos) ? result : null;
    }

    /** Every part in {@code state} has something to stand on / hang from. */
    private static boolean supported(BlockState state, LevelReader level, BlockPos pos) {
        boolean floor = level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
        if (state.getValue(KEYBOARD) && !floor) return false;
        return switch (state.getValue(SCREEN)) {
            case DESK -> floor;
            case WALL -> {
                Direction facing = state.getValue(FACING);
                BlockPos wall = pos.relative(facing.getOpposite());
                yield level.getBlockState(wall).isFaceSturdy(level, wall, facing);
            }
            case NONE -> true;
        };
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return supported(state, level, pos);
    }

    /** A part that lost its support falls off a tick later (with its drop). */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (!supported(state, level, pos)) ticks.scheduleTick(pos, this, 1);
        return state;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (supported(state, level, pos)) return;
        boolean floor = level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
        BlockState left = state;
        if (left.getValue(KEYBOARD) && !floor) {
            popResource(level, pos, new ItemStack(PaperRegistry.KEYBOARD_ITEM.get()));
            left = left.setValue(KEYBOARD, false);
        }
        if (hasScreen(left) && !supported(left.setValue(KEYBOARD, false), level, pos)) {
            dropScreen(level, pos);
            left = left.setValue(SCREEN, Mount.NONE);
        }
        if (!hasScreen(left) && !left.getValue(KEYBOARD)) {
            level.removeBlock(pos, false);
        } else {
            level.setBlock(pos, left, Block.UPDATE_ALL);
        }
    }

    /** Drops the screen as an item, with its files. */
    private static void dropScreen(Level level, BlockPos pos) {
        // The screen of whichever terminal block this is (another mod's look drops its own item).
        net.minecraft.world.item.Item screen = level.getBlockState(pos).getBlock().asItem();
        ItemStack item = new ItemStack(screen instanceof TerminalItem ? screen : PaperRegistry.TERMINAL_ITEM.get());
        if (level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal) {
            terminal.release();
            if (!terminal.files().isEmpty()) item.set(PaperRegistry.TERMINAL_FILES.get(), java.util.List.copyOf(terminal.files()));
            terminal.clearFiles();
            // The look and the power go with the screen.
            if (terminal.ownTheme() != null) item.set(PaperRegistry.TERMINAL_THEME.get(), terminal.ownTheme());
            if (!terminal.customName().isEmpty()) {
                item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(terminal.customName()));
            }
            terminal.screenGone();
        }
        popResource(level, pos, item);
    }

    // ------------------------------------------------------------------ mining one part at a time

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack toolStack,
                                       boolean willHarvest, FluidState fluid) {
        if (!hasScreen(state) || !state.getValue(KEYBOARD)) {
            return super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid);
        }
        boolean keyboard = aimsAtKeyboard(state, pos, player);
        BlockState left = keyboard ? state.setValue(KEYBOARD, false) : state.setValue(SCREEN, Mount.NONE);
        if (!level.isClientSide()) {
            if (keyboard) {
                if (willHarvest) popResource(level, pos, new ItemStack(PaperRegistry.KEYBOARD_ITEM.get()));
            } else if (willHarvest) {
                dropScreen(level, pos);
            } else if (level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal) {
                terminal.release();
                terminal.clearFiles();
            }
        }
        level.setBlock(pos, left, Block.UPDATE_ALL);
        return false;
    }

    /** Whether the player's line of sight meets the keyboard before the screen. */
    public static double @Nullable [] light(BlockState state) {
        return switch (state.getValue(SCREEN)) {
            case DESK -> DESK_LIGHT;
            case WALL -> WALL_LIGHT;
            case NONE -> null;
        };
    }

    /** Whether a click at {@code hit} pressed the power button. */
    private static boolean onButton(BlockState state, BlockPos pos, Vec3 hit) {
        Map<Direction, VoxelShape> shapes = switch (state.getValue(SCREEN)) {
            case DESK -> DESK_BUTTON;
            case WALL -> WALL_BUTTON;
            case NONE -> null;
        };
        if (shapes == null) return false;
        Vec3 local = hit.subtract(pos.getX(), pos.getY(), pos.getZ());
        return shapes.get(state.getValue(FACING)).bounds().inflate(0.002).contains(local);
    }

    private static boolean aimsAtKeyboard(BlockState state, BlockPos pos, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + 1.0));
        BlockHitResult keys = keyboardShape(state).clip(eye, end, pos);
        BlockHitResult screen = screenShape(state).clip(eye, end, pos);
        if (keys == null) return false;
        if (screen == null) return true;
        return keys.getLocation().distanceToSqr(eye) < screen.getLocation().distanceToSqr(eye);
    }

    // ------------------------------------------------------------------ using

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        // Holding the missing part: let the item put it in.
        if (stack.getItem() instanceof KeyboardItem && !state.getValue(KEYBOARD)) return InteractionResult.PASS;
        if (stack.getItem() instanceof TerminalItem && !hasScreen(state)) return InteractionResult.PASS;
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!hasScreen(state)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof TerminalBlockEntity terminal) {
            if (onButton(state, pos, hit.getLocation())) {
                if (terminal.crashed()) {
                    level.playSound(null, pos, net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_ON,
                            net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.4F);
                    ActionBar.show(serverPlayer, terminal.crashMessage());
                } else {
                    terminal.pressPower();
                }
            }
            else TerminalServer.control(serverPlayer, terminal);
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ the rest

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerminalBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, PaperRegistry.TERMINAL_ENTITY.get(), TerminalBlockEntity::serverTick);
    }
}
