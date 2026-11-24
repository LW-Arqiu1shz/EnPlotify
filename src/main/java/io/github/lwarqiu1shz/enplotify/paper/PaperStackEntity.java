package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A pile of newspapers / documents lying on a surface. Each sheet is 1/64 of a block thick and the
 * pile can grow until it touches the block above. Right click: read the top sheet; sneak + right
 * click with an empty hand: pick the top sheet up; right click with a sheet: put it on top; hit it:
 * the whole pile drops.
 */
public class PaperStackEntity extends Entity {
    public static final float SHEET = 1.0F / 64.0F;
    /** Hard cap so a pile in a tall room cannot grow without end. */
    public static final int MAX_SHEETS = 1024;
    /** How many sheets, from the top, clients get the content of. */
    public static final int VISIBLE = 4;

    @SuppressWarnings("unchecked")
    private static final EntityDataAccessor<ItemStack>[] DATA_VISIBLE = new EntityDataAccessor[VISIBLE];
    static {
        for (int i = 0; i < VISIBLE; i++) {
            DATA_VISIBLE[i] = SynchedEntityData.defineId(PaperStackEntity.class, EntityDataSerializers.ITEM_STACK);
        }
    }
    /** One letter per sheet, bottom first ('N' newspaper, 'D' document), so clients can draw the pile. */
    private static final EntityDataAccessor<String> DATA_LAYERS =
            SynchedEntityData.defineId(PaperStackEntity.class, EntityDataSerializers.STRING);
    /** Degrees the pile leans back (away from the reader), e.g. on a lectern's sloped top. */
    private static final EntityDataAccessor<Float> DATA_TILT =
            SynchedEntityData.defineId(PaperStackEntity.class, EntityDataSerializers.FLOAT);
    /** Which way the slope rises (yaw of someone facing up the slope); the pile itself may turn any way on it. */
    private static final EntityDataAccessor<Float> DATA_SLOPE_YAW =
            SynchedEntityData.defineId(PaperStackEntity.class, EntityDataSerializers.FLOAT);
    /** Drawn this many tiny steps up, so a pile put down later over another one shows on top of it. */
    private static final EntityDataAccessor<Integer> DATA_LAYER =
            SynchedEntityData.defineId(PaperStackEntity.class, EntityDataSerializers.INT);
    /** The mod every sheet comes from when that is one other mod (its ready-made papers), else "". */
    private static final EntityDataAccessor<String> DATA_SOURCE_MOD =
            SynchedEntityData.defineId(PaperStackEntity.class, EntityDataSerializers.STRING);
    public static final int MAX_LAYER = 5;

    /** Server-side contents, bottom first. */
    private final List<ItemStack> sheets = new ArrayList<>();

    public PaperStackEntity(EntityType<? extends PaperStackEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public PaperStackEntity(Level level, Vec3 at, float yaw, ItemStack first) {
        this(PaperRegistry.PAPER_STACK_ENTITY.get(), level);
        this.setPos(at.x, at.y, at.z);
        this.setYRot(yaw);
        this.yRotO = yaw;
        sheets.add(first.copyWithCount(1));
        sync();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        for (EntityDataAccessor<ItemStack> accessor : DATA_VISIBLE) entityData.define(accessor, ItemStack.EMPTY);
        entityData.define(DATA_LAYERS, "");
        entityData.define(DATA_TILT, 0.0F);
        entityData.define(DATA_SLOPE_YAW, 0.0F);
        entityData.define(DATA_LAYER, 0);
        entityData.define(DATA_SOURCE_MOD, "");
    }

    /** Mod id that every sheet in the pile comes from, if it is a single mod other than EnPlotify; else "". */
    public String sourceMod() {
        return this.entityData.get(DATA_SOURCE_MOD);
    }

    public int layer() {
        return this.entityData.get(DATA_LAYER);
    }

    /**
     * Called before a new pile is added: puts it one step above every pile it overlaps on the same
     * surface (the same floor height, or the same lectern).
     */
    public void layOver() {
        double reach = 1.3;
        int top = -1;
        for (PaperStackEntity other : level().getEntitiesOfClass(PaperStackEntity.class,
                new AABB(getX() - reach, getY() - 0.5, getZ() - reach, getX() + reach, getY() + 0.5, getZ() + reach))) {
            if (other == this || !sameSurface(other)) continue;
            double dx = other.getX() - getX(), dz = other.getZ() - getZ();
            double r = radius() + other.radius();
            if (dx * dx + dz * dz < r * r) top = Math.max(top, other.layer());
        }
        this.entityData.set(DATA_LAYER, Math.min(MAX_LAYER, top + 1));
    }

    private boolean sameSurface(PaperStackEntity other) {
        if (tilt() != 0.0F || other.tilt() != 0.0F) {
            return tilt() == other.tilt() && slopeYaw() == other.slopeYaw() && support().equals(other.support());
        }
        return Math.abs(other.getY() - getY()) < 0.01;
    }

    /** Half the diagonal of the widest sheet in the pile. */
    private double radius() {
        return layers().indexOf('N') >= 0 ? 0.62 : 0.44;
    }

    public float tilt() {
        return this.entityData.get(DATA_TILT);
    }

    public float slopeYaw() {
        return this.entityData.get(DATA_SLOPE_YAW);
    }

    public void setTilt(float degrees, float slopeYaw) {
        this.entityData.set(DATA_TILT, degrees);
        this.entityData.set(DATA_SLOPE_YAW, slopeYaw);
    }

    /** The block holding the pile (a lectern's top can rise above its own block). */
    private BlockPos support() {
        return BlockPos.containing(getX(), getY() - (tilt() != 0.0F ? 0.25 : 0.01), getZ());
    }

    /**
     * "文档: 标题" for a single sheet; otherwise "一叠文档" / "一叠报纸" when all sheets are one kind,
     * "一叠纸" for a mix (what Jade and the like show).
     */
    @Override
    protected net.minecraft.network.chat.Component getTypeName() {
        String layers = layers();
        if (layers.length() == 1 && !top().isEmpty()) return top().getHoverName();
        if (!layers.isEmpty() && layers.chars().allMatch(c -> c == 'D')) {
            return net.minecraft.network.chat.Component.translatable("entity.enplotify.paper_stack.documents");
        }
        if (!layers.isEmpty() && layers.chars().allMatch(c -> c == 'N')) {
            return net.minecraft.network.chat.Component.translatable("entity.enplotify.paper_stack.newspapers");
        }
        return super.getTypeName();
    }

    /** Lying on a pressure plate does not press it. */
    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_LAYERS.equals(accessor)) this.refreshDimensions();
    }

    /** The top sheet. */
    public ItemStack top() {
        return this.entityData.get(DATA_VISIBLE[0]);
    }

    /** The {@code i}-th sheet from the top (0 = top) whose content clients know, or EMPTY. */
    public ItemStack visible(int i) {
        return i >= 0 && i < VISIBLE ? this.entityData.get(DATA_VISIBLE[i]) : ItemStack.EMPTY;
    }

    public String layers() {
        return this.entityData.get(DATA_LAYERS);
    }

    /** Server: puts a sheet on top; false if the pile touches the ceiling or the item is not a sheet. */
    public boolean addOnTop(ItemStack sheet) {
        if (!(sheet.getItem() instanceof SheetItem) || sheets.size() >= MAX_SHEETS) return false;
        double bottom = getY() + sheets.size() * SHEET;
        // On a lectern the pile starts inside the lectern's own block; only what is above counts.
        if (tilt() != 0.0F) bottom = Math.max(bottom, support().getY() + 1.0);
        AABB slab = new AABB(getX() - 0.05, bottom, getZ() - 0.05, getX() + 0.05, bottom + SHEET, getZ() + 0.05);
        if (!level().noBlockCollision(null, slab)) return false;
        sheets.add(sheet.copyWithCount(1));
        sync();
        return true;
    }

    /**
     * Sends the top sheets' contents: from the top down, until one newspaper (which covers anything
     * below it) has been included.
     */
    private void sync() {
        StringBuilder layers = new StringBuilder();
        for (ItemStack sheet : sheets) layers.append(sheet.getItem() instanceof SheetItem item ? item.layerCode() : 'N');
        int slot = 0;
        for (int i = sheets.size() - 1; i >= 0 && slot < VISIBLE; i--) {
            ItemStack sheet = sheets.get(i);
            this.entityData.set(DATA_VISIBLE[slot++], sheet.copy());
            if (sheet.getItem() instanceof NewspaperItem) break;
        }
        for (; slot < VISIBLE; slot++) this.entityData.set(DATA_VISIBLE[slot], ItemStack.EMPTY);
        this.entityData.set(DATA_LAYERS, layers.toString());
        String source = null;
        for (ItemStack sheet : sheets) {
            String mod = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sheet.getItem()).getNamespace();
            if (source == null) source = mod;
            else if (!source.equals(mod)) {
                source = "";
                break;
            }
        }
        this.entityData.set(DATA_SOURCE_MOD, source == null || source.equals(io.github.lwarqiu1shz.enplotify.EnPlotify.MOD_ID) ? "" : source);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        String layers = layers();
        float across = layers.indexOf('N') >= 0 ? 1.0F : 0.71F;
        return EntityDimensions.fixed(across, Math.max(0.03F, layers.length() * SHEET));
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel level && this.tickCount % 20 == 0) {
            if (sheets.isEmpty()) {
                discard();
                return;
            }
            // The table under the pile is gone: the sheets fall as items. Anything with a shape
            // holds it (also thin things one can walk through, such as pressure plates and carpets).
            BlockPos below = support();
            if (level.getBlockState(below).getShape(level, below).isEmpty()) dropAll(level);
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof SheetItem) {
            if (level().isClientSide()) return InteractionResult.SUCCESS;
            if (addOnTop(held)) {
                held.consume(1, player);
                playSound(SoundEvents.BOOK_PAGE_TURN, 0.8F, 1.0F);
                return InteractionResult.SUCCESS;
            }
            ActionBar.show(player, net.minecraft.network.chat.Component.translatable("message.enplotify.pile_full"));
            return InteractionResult.FAIL;
        }
        if (!held.isEmpty() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player.isSecondaryUseActive()) {
            if (!level().isClientSide() && !sheets.isEmpty()) {
                ItemStack taken = sheets.remove(sheets.size() - 1);
                player.setItemInHand(hand, taken);
                playSound(SoundEvents.BOOK_PAGE_TURN, 0.8F, 1.2F);
                if (sheets.isEmpty()) discard();
                else sync();
            }
            return InteractionResult.SUCCESS;
        }
        if (level().isClientSide()) io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.readSheet(top());
        return InteractionResult.SUCCESS;
    }

    private void dropAll(ServerLevel level) {
        for (ItemStack sheet : sheets) {
            ItemEntity drop = new ItemEntity(level, getX(), getY() + 0.1, getZ(), sheet.copy());
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
        sheets.clear();
        discard();
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (isRemoved() || isInvulnerableToBase(source)) return false;
        playSound(SoundEvents.BOOK_PAGE_TURN, 1.0F, 0.8F);
        if (source.getEntity() instanceof Player player && player.hasInfiniteMaterials()) {
            sheets.clear();
            discard();
        } else {
            dropAll(level);
        }
        return true;
    }

    @Override
    public ItemStack getPickResult() {
        return top().copy();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        if (tilt() != 0.0F) {
            output.putFloat("tilt", tilt());
            output.putFloat("slope_yaw", slopeYaw());
        }
        if (layer() != 0) output.putInt("layer", layer());
        ValueOutput.TypedOutputList<ItemStack> list = output.list("sheets", ItemStack.CODEC);
        for (ItemStack sheet : sheets) list.add(sheet);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        sheets.clear();
        for (ItemStack sheet : input.listOrEmpty("sheets", ItemStack.CODEC)) sheets.add(sheet);
        float tilt = input.getFloatOr("tilt", 0.0F);
        // r12 piles had no slope direction: they faced straight up the slope.
        setTilt(tilt, input.getFloatOr("slope_yaw", getYRot()));
        this.entityData.set(DATA_LAYER, Math.clamp(input.getIntOr("layer", 0), 0, MAX_LAYER));
        sync();
    }
}
