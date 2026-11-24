package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Devices (terminals, printers, displays, SAWMO whiteboards) that are one of a kind: their default
 * name is just their name, without the coordinates. See EnPlotifyContent.oneOfAKind.
 */
public final class UniqueDevices {
    private static final List<Supplier<? extends ItemLike>> DEVICES = new CopyOnWriteArrayList<>();
    private static volatile Set<String> keys;

    public static void add(Supplier<? extends ItemLike> device) {
        DEVICES.add(device);
        keys = null;
    }

    static boolean isUnique(String kindKey) {
        if (DEVICES.isEmpty()) return false;
        Set<String> k = keys;
        if (k == null) {
            k = new HashSet<>();
            for (Supplier<? extends ItemLike> d : DEVICES) {
                Item item = d.get().asItem();
                k.add(item.getDescriptionId());
                if (item instanceof BlockItem block) k.add(block.getBlock().getDescriptionId());
            }
            keys = k;
        }
        return k.contains(kindKey);
    }

    private UniqueDevices() {}
}
