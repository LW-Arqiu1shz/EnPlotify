package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Something on a wall a terminal can show its screen on (in its "设备" program): a display, or a
 * SAWMO whiteboard. One terminal each; a terminal may have many.
 */
public interface ScreenLink {
    int KIND_DISPLAY = 0, KIND_SAWMO = 1;

    /** The terminal showing its screen here, or null. */
    @Nullable BlockPos terminal();

    void connect(@Nullable BlockPos terminal);

    /** Set by a player; "" = the default name (its kind and where it was hung). */
    String customName();

    void rename(String name);

    int kind();

    /** What it is called before the coordinates in its default name (the item's name for a SAWMO whiteboard). */
    default String kindKey() {
        return kind() == KIND_SAWMO ? "entity.enplotify.sawmo_whiteboard" : "entity.enplotify.display";
    }

    /**
     * A device's default name: what it is (a translation key: the block's or item's name, so a
     * reskinned one from another mod shows its own) and where it is, e.g. "终端[1,2,3]".
     */
    static net.minecraft.network.chat.Component defaultName(String kindKey, BlockPos at) {
        if (UniqueDevices.isUnique(kindKey)) return net.minecraft.network.chat.Component.translatable(kindKey);
        return net.minecraft.network.chat.Component.translatable("enplotify.device.named",
                net.minecraft.network.chat.Component.translatable(kindKey), at.getX(), at.getY(), at.getZ());
    }

    /** Every alive screen of every kind in the world that passes {@code filter}. */
    static List<SizedHangingEntity> all(ServerLevel level, Predicate<SizedHangingEntity> filter) {
        List<SizedHangingEntity> out = new ArrayList<>();
        out.addAll(level.getEntities(PaperRegistry.DISPLAY_ENTITY.get(), filter::test));
        out.addAll(level.getEntities(PaperRegistry.SAWMO_ENTITY.get(), filter::test));
        return out;
    }
}
