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

/** A poster hanging on a wall. It keeps the poster item (with its size) and drops it when broken. */
public class PosterEntity extends SizedHangingEntity {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(PosterEntity.class, EntityDataSerializers.ITEM_STACK);

    public PosterEntity(EntityType<? extends PosterEntity> type, Level level) {
        super(type, level);
    }

    public PosterEntity(Level level, BlockPos pos, Direction direction, int width, int height, ItemStack item) {
        super(PaperRegistry.POSTER_ENTITY.get(), level, pos, direction, width, height);
        setItem(item);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_ITEM, ItemStack.EMPTY);
    }

    public ItemStack getItem() {
        return this.entityData.get(DATA_ITEM);
    }

    private void setItem(ItemStack item) {
        this.entityData.set(DATA_ITEM, item.copyWithCount(1));
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
        if (level().isClientSide()) io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.viewPoster(getItem());
        return InteractionResult.SUCCESS;
    }

    @Override
    public void dropItem(ServerLevel level, @Nullable Entity causedBy) {
        if (!level.getGameRules().get(GameRules.ENTITY_DROPS)) return;
        this.playSound(SoundEvents.PAINTING_BREAK, 1.0F, 1.0F);
        if (causedBy instanceof Player player && player.hasInfiniteMaterials()) return;
        this.spawnAtLocation(level, getItem().copy());
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
        return getItem().copy();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("item", ItemStack.OPTIONAL_CODEC, getItem());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setItem(input.read("item", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
    }
}
