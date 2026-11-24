package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Other players reading a story show " (剧情中)" after their name tag. */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class PlotNameTags {
    private static final Set<UUID> WATCHING = new HashSet<>();

    public static void set(UUID player, boolean watching) {
        if (watching) WATCHING.add(player);
        else WATCHING.remove(player);
    }

    /** True if the server reported that this player is reading a story. */
    public static boolean isWatching(UUID player) {
        return WATCHING.contains(player);
    }

    @SubscribeEvent
    public static void onNameTag(RenderNameTagEvent.CanRender event) {
        if (!(event.getEntity() instanceof Player player) || !WATCHING.contains(player.getUUID())) return;
        Component content = event.getContent();
        if (content == null) return;
        event.setContent(Component.empty().append(content).append(Component.translatable("enplotify.nametag.in_story")));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WATCHING.clear();
    }

    private PlotNameTags() {}
}
