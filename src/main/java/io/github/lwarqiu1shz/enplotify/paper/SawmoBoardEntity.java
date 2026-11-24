package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * 望漠白板 (SAWMO whiteboard): a whiteboard that is also a display. Written on with a marker like a
 * whiteboard; connected to a terminal like a display, its screen plays under the writing. Its name
 * is the board's name (Alt + left click, or the editor's title).
 */
public class SawmoBoardEntity extends BoardEntity implements ScreenLink {
    private static final EntityDataAccessor<Optional<BlockPos>> DATA_TERMINAL =
            SynchedEntityData.defineId(SawmoBoardEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    public SawmoBoardEntity(EntityType<? extends SawmoBoardEntity> type, Level level) {
        super(type, level);
    }

    public SawmoBoardEntity(Level level, BlockPos pos, Direction direction, int width, int height, ItemStack item) {
        super(PaperRegistry.SAWMO_ENTITY.get(), level, pos, direction, width, height, item);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_TERMINAL, Optional.empty());
    }

    @Override
    public @Nullable BlockPos terminal() {
        return this.entityData.get(DATA_TERMINAL).orElse(null);
    }

    @Override
    public void connect(@Nullable BlockPos terminal) {
        this.entityData.set(DATA_TERMINAL, Optional.ofNullable(terminal == null ? null : terminal.immutable()));
    }

    @Override
    public String customName() {
        return name();
    }

    @Override
    public void rename(String name) {
        String s = net.minecraft.util.StringUtil.filterText(name).strip();
        if (s.length() > DisplayEntity.MAX_NAME) s = s.substring(0, DisplayEntity.MAX_NAME);
        setCanvas(canvas(), s);
    }

    @Override
    public int kind() {
        return KIND_SAWMO;
    }

    /** The whiteboard item's own name (another mod's reskin shows its name). */
    @Override
    public String kindKey() {
        return getItem().isEmpty() ? ScreenLink.super.kindKey() : getItem().getItem().getDescriptionId();
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

    /** Its name, or "望漠白板[x,y,z]" (what Jade and the terminal's list show). */
    @Override
    protected Component getTypeName() {
        BlockPos at = getPos();
        return name().isEmpty() ? ScreenLink.defaultName(kindKey(), at)
                : Component.literal(name());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        BlockPos t = terminal();
        if (t != null) output.putLong("terminal", t.asLong());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        connect(input.getLong("terminal").map(BlockPos::of).orElse(null));
    }
}
