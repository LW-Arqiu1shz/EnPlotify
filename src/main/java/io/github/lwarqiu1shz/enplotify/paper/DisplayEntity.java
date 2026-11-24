package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A display on a wall, of any size in cells. It does nothing by itself: a terminal connected to it
 * (in its "设备" program) shows its screen on it. One terminal per display; a terminal may have many.
 */
public class DisplayEntity extends SizedHangingEntity implements ScreenLink {
    public static final int MAX_NAME = 32;
    private static final EntityDataAccessor<String> DATA_NAME =
            SynchedEntityData.defineId(DisplayEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_TERMINAL =
            SynchedEntityData.defineId(DisplayEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    public DisplayEntity(EntityType<? extends DisplayEntity> type, Level level) {
        super(type, level);
    }

    public DisplayEntity(Level level, BlockPos pos, Direction direction, int width, int height, String name) {
        super(PaperRegistry.DISPLAY_ENTITY.get(), level, pos, direction, width, height);
        this.entityData.set(DATA_NAME, clean(name));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_NAME, "");
        entityData.define(DATA_TERMINAL, Optional.empty());
    }

    private static String clean(String name) {
        String s = net.minecraft.util.StringUtil.filterText(name).strip();
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    /** Set by a player (Alt + left click); "" = "显示器[x,y,z]" (where it was hung: its lower-left cell). */
    @Override
    public String customName() {
        return this.entityData.get(DATA_NAME);
    }

    @Override
    public void rename(String name) {
        this.entityData.set(DATA_NAME, clean(name));
    }

    public Component displayName() {
        String name = customName();
        BlockPos at = getPos();
        return name.isEmpty() ? ScreenLink.defaultName(kindKey(), at)
                : Component.literal(name);
    }

    @Override
    public int kind() {
        return KIND_DISPLAY;
    }

    /** The terminal showing its screen here, or null. */
    @Override
    public @Nullable BlockPos terminal() {
        return this.entityData.get(DATA_TERMINAL).orElse(null);
    }

    @Override
    public void connect(@Nullable BlockPos terminal) {
        this.entityData.set(DATA_TERMINAL, Optional.ofNullable(terminal == null ? null : terminal.immutable()));
    }

    @Override
    public void tick() {
        super.tick();
        // The terminal was broken: free again.
        if (!level().isClientSide() && tickCount % 20 == 0) {
            BlockPos t = terminal();
            if (t != null && level().isLoaded(t) && !(level().getBlockEntity(t) instanceof TerminalBlockEntity)) connect(null);
        }
    }

    public ItemStack toItem() {
        ItemStack stack = new ItemStack(PaperRegistry.DISPLAY.get());
        if (!customName().isEmpty()) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(customName()));
        return stack;
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

    /** Its name (what Jade and the like show). */
    @Override
    protected Component getTypeName() {
        return displayName();
    }

    @Override
    public ItemStack getPickResult() {
        return toItem();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (!customName().isEmpty()) output.putString("name", customName());
        BlockPos t = terminal();
        if (t != null) output.putLong("terminal", t.asLong());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_NAME, clean(input.getStringOr("name", "")));
        connect(input.getLong("terminal").map(BlockPos::of).orElse(null));
    }
}
