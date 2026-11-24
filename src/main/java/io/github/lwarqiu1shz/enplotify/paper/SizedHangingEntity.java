package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A wall-mounted rectangle of {@code width x height} cells, positioned the way paintings are. */
public abstract class SizedHangingEntity extends HangingEntity {
    /** As thick as one sheet of paper (a pile of documents grows by this much per sheet). */
    public static final double THICKNESS = 1.0 / 64.0;
    private static final EntityDataAccessor<Integer> DATA_WIDTH =
            SynchedEntityData.defineId(SizedHangingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HEIGHT =
            SynchedEntityData.defineId(SizedHangingEntity.class, EntityDataSerializers.INT);

    protected SizedHangingEntity(EntityType<? extends SizedHangingEntity> type, Level level) {
        super(type, level);
    }

    protected SizedHangingEntity(EntityType<? extends SizedHangingEntity> type, Level level, BlockPos pos,
                                 Direction direction, int width, int height) {
        super(type, level, pos);
        this.entityData.set(DATA_WIDTH, clampSize(width));
        this.entityData.set(DATA_HEIGHT, clampSize(height));
        this.setDirection(direction);
    }

    private static int clampSize(int size) {
        return Mth.clamp(size, 1, PaperContents.MAX_CELLS);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_WIDTH, 1);
        entityData.define(DATA_HEIGHT, 1);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_WIDTH.equals(accessor) || DATA_HEIGHT.equals(accessor)) this.recalculateBoundingBox();
    }

    public int cellsWide() {
        return this.entityData.get(DATA_WIDTH);
    }

    public int cellsHigh() {
        return this.entityData.get(DATA_HEIGHT);
    }

    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction direction) {
        Vec3 attachedToWall = Vec3.atCenterOf(pos).relative(direction, -(0.5 - THICKNESS / 2));
        int width = cellsWide();
        int height = cellsHigh();
        // The clicked cell is the lower-left corner as seen from the front: grow right and up.
        Direction right = direction.getCounterClockWise();
        Vec3 position = attachedToWall.relative(right, (width - 1) / 2.0)
                .relative(Direction.UP, (height - 1) / 2.0);
        Direction.Axis axis = direction.getAxis();
        double xSize = axis == Direction.Axis.X ? THICKNESS : width;
        double zSize = axis == Direction.Axis.Z ? THICKNESS : width;
        return AABB.ofSize(position, xSize, height, zSize);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.store("facing", Direction.LEGACY_ID_CODEC_2D, this.getDirection());
        output.putInt("cells_wide", cellsWide());
        output.putInt("cells_high", cellsHigh());
        super.addAdditionalSaveData(output);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        Direction direction = input.read("facing", Direction.LEGACY_ID_CODEC_2D).orElse(Direction.SOUTH);
        this.entityData.set(DATA_WIDTH, clampSize(input.getIntOr("cells_wide", 1)));
        this.entityData.set(DATA_HEIGHT, clampSize(input.getIntOr("cells_high", 1)));
        super.readAdditionalSaveData(input);
        this.setDirection(direction);
    }

    @Override
    public void snapTo(double x, double y, double z, float yRot, float xRot) {
        this.setPos(x, y, z);
    }

    @Override
    public Vec3 trackingPosition() {
        return Vec3.atLowerCornerOf(this.pos);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, this.getDirection().get3DDataValue(), this.getPos());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        this.setDirection(Direction.from3DDataValue(packet.getData()));
    }
}
