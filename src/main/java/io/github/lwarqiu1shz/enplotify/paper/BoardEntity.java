package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A blackboard / whiteboard on a wall. Its drawing is kept in the board item it holds (so breaking
 * it drops the board with size and drawing). Anyone holding the matching tool (chalk / marker) can
 * edit it.
 */
public class BoardEntity extends SizedHangingEntity {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(BoardEntity.class, EntityDataSerializers.ITEM_STACK);

    public BoardEntity(EntityType<? extends BoardEntity> type, Level level) {
        super(type, level);
    }

    public BoardEntity(Level level, BlockPos pos, Direction direction, int width, int height, ItemStack item) {
        this(PaperRegistry.BOARD_ENTITY.get(), level, pos, direction, width, height, item);
    }

    protected BoardEntity(EntityType<? extends BoardEntity> type, Level level, BlockPos pos, Direction direction, int width, int height, ItemStack item) {
        super(type, level, pos, direction, width, height);
        ItemStack stack = item.copyWithCount(1);
        if (stack.getItem() instanceof BoardItem board && !board.isPreset()) {
            stack.set(PaperRegistry.BOARD_CONTENT.get(), new PaperContents.Board(width, height, BoardItem.content(item).canvas(), BoardItem.content(item).name()));
        }
        this.entityData.set(DATA_ITEM, stack);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_ITEM, ItemStack.EMPTY);
    }

    public ItemStack getItem() {
        return this.entityData.get(DATA_ITEM);
    }

    public BoardItem.Style style() {
        return getItem().getItem() instanceof BoardItem board ? board.style() : BoardItem.Style.BLACKBOARD;
    }

    public boolean isPreset() {
        return getItem().getItem() instanceof BoardItem board && board.isPreset();
    }

    /** The player-made drawing (empty for a mod-provided board). */
    public Canvas canvas() {
        return BoardItem.content(getItem()).canvas();
    }

    public String name() {
        return BoardItem.content(getItem()).name();
    }

    /** Server: replaces the drawing and the name. */
    public void setCanvas(Canvas canvas, String name) {
        ItemStack stack = getItem().copy();
        stack.set(PaperRegistry.BOARD_CONTENT.get(), new PaperContents.Board(cellsWide(), cellsHigh(), canvas, name));
        this.entityData.set(DATA_ITEM, stack);
    }

    /** True if {@code tool} writes on this board. */
    public boolean acceptsTool(ItemStack tool) {
        return !isPreset() && getItem().getItem() instanceof BoardItem board && board.acceptsTool(tool);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (!acceptsTool(player.getItemInHand(hand))) return InteractionResult.PASS;
        // The server opens the editor (only one player may edit a board at a time).
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) BoardSessions.open(serverPlayer, this);
        return InteractionResult.SUCCESS;
    }

    public ItemStack toItem() {
        return getItem().copyWithCount(1);
    }

    @Override
    public void dropItem(ServerLevel level, @Nullable Entity causedBy) {
        if (!level.getGameRules().get(GameRules.ENTITY_DROPS)) return;
        this.playSound(SoundEvents.PAINTING_BREAK, 1.0F, 1.0F);
        if (causedBy instanceof Player player && player.hasInfiniteMaterials()) return;
        this.spawnAtLocation(level, toItem());
    }

    @Override
    public void playPlacementSound() {
        this.playSound(SoundEvents.PAINTING_PLACE, 1.0F, 1.0F);
    }

    /** Named like its item, e.g. "黑板[2×3]: 名字" (what Jade and the like show). */
    @Override
    protected net.minecraft.network.chat.Component getTypeName() {
        ItemStack item = getItem();
        return item.isEmpty() ? super.getTypeName() : item.getHoverName();
    }

    @Override
    public ItemStack getPickResult() {
        return toItem();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("item", ItemStack.OPTIONAL_CODEC, getItem());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        ItemStack stack = input.read("item", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        // r3 kept the writing on the entity itself.
        String legacyText = input.getStringOr("text", "");
        if (!legacyText.isEmpty() && stack.getItem() instanceof BoardItem board && !board.isPreset()
                && BoardItem.content(stack).canvas().isEmpty()) {
            int[] colors = board.style().colors();
            int rgb = colors[Math.floorMod(input.getIntOr("color", 0), colors.length)];
            stack.set(PaperRegistry.BOARD_CONTENT.get(), new PaperContents.Board(cellsWide(), cellsHigh(),
                    new Canvas(java.util.List.of(Canvas.Element.text(0.04F, 0.04F, 0.92F, 0.92F, legacyText, rgb, 0.12F / Math.max(1, cellsHigh())))), ""));
        }
        this.entityData.set(DATA_ITEM, stack);
    }
}
