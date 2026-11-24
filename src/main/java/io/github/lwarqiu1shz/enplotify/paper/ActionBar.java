package io.github.lwarqiu1shz.enplotify.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Hints over the hotbar. With 金源工艺 (OriGolds) installed they go through its text broadcast
 * service, which shares the line with other mods instead of overwriting them; without it, the
 * game's own action bar. Looked up by reflection: OriGolds is not needed to build or run.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class ActionBar {
    private static final String OWNER = EnPlotify.MOD_ID;
    /** Kept up this long (ticks); it then fades like the game's own. */
    private static final int SHOWN_TICKS = 20;

    private static boolean looked;
    private static @Nullable Method show, stop;
    private static @Nullable Object channel;
    /** Players with a hint up, and the server tick it is taken down at. */
    private static final Map<UUID, Long> UNTIL = new HashMap<>();

    public static void show(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer && origolds()) {
            try {
                show.invoke(null, serverPlayer, channel, OWNER, message);
                UNTIL.put(serverPlayer.getUUID(), (long) serverPlayer.level().getServer().getTickCount() + SHOWN_TICKS);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LoggerFactory.getLogger("EnPlotify").warn("EnPlotify: OriGolds text broadcast failed, using the action bar", e);
                show = null;
            }
        }
        player.sendOverlayMessage(message);
    }

    private static boolean origolds() {
        if (!looked) {
            looked = true;
            if (ModList.get() != null && ModList.get().isLoaded("origolds")) {
                try {
                    Class<?> service = Class.forName("cn.arqiuowo.origolds.api.TextBroadcastService");
                    Class<?> channels = Class.forName("cn.arqiuowo.origolds.api.TextBroadcastChannel");
                    channel = channels.getField("PLAYER_INFO").get(null);
                    show = service.getMethod("show", Player.class, channels, String.class, Component.class);
                    stop = service.getMethod("stop", Player.class, channels, String.class);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LoggerFactory.getLogger("EnPlotify").warn("EnPlotify: OriGolds text broadcast not found, using the action bar", e);
                    show = null;
                }
            }
        }
        return show != null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (UNTIL.isEmpty()) return;
        long now = event.getServer().getTickCount();
        Iterator<Map.Entry<UUID, Long>> it = UNTIL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue()) continue;
            it.remove();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (player != null && stop != null) {
                try {
                    stop.invoke(null, player, channel, OWNER);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        UNTIL.clear();
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UNTIL.remove(event.getEntity().getUUID());
    }

    private ActionBar() {}
}
