package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.quest.QuestSync;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** The quest map key (J) and the map the server last sent. */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class QuestClient {
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "main"));
    /**
     * The key, made only when some mod has defined quest lines (in its constructor, so before keys
     * are registered): with EnPlotify alone there is no J key and no quest map. Null otherwise.
     */
    public static KeyMapping KEY;

    /**
     * Quest lines are defined, or another installed mod needs EnPlotify (it may define its lines
     * later): then the key is in Controls, as every key is.
     */
    private static boolean someModUsesUs() {
        if (!io.github.lwarqiu1shz.enplotify.api.StoryQuests.lines().isEmpty()) return true;
        for (net.neoforged.neoforgespi.language.IModInfo mod : net.neoforged.fml.ModList.get().getMods()) {
            if (mod.getModId().equals(EnPlotify.MOD_ID)) continue;
            for (net.neoforged.neoforgespi.language.IModInfo.ModVersion dep : mod.getDependencies()) {
                if (dep.getModId().equals(EnPlotify.MOD_ID)) return true;
            }
        }
        return false;
    }

    /** True when some mod uses the quest map. */
    public static boolean enabled() {
        return KEY != null;
    }

    private static QuestSync.Payload map = new QuestSync.Payload(List.of(), "", true);
    /** Opened the map since joining (before the server's word comes back). */
    private static boolean openedHere;
    /** False until the first map since joining arrives. */
    private static boolean received;

    public static void set(QuestSync.Payload payload) {
        QuestBanner.compare(received ? map : null, payload);
        map = payload;
        received = true;
    }

    public static QuestSync.Payload map() {
        return map;
    }

    /** The player has opened the quest map (now or in an earlier session). */
    public static boolean everOpened() {
        return openedHere || map.opened();
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        if (!someModUsesUs()) return;
        KEY = new KeyMapping("key.enplotify.quests", GLFW.GLFW_KEY_J, CATEGORY);
        event.registerCategory(CATEGORY);
        event.register(KEY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (KEY == null) return;
        Minecraft mc = Minecraft.getInstance();
        QuestBanner.tick(mc, received);
        boolean lines = !io.github.lwarqiu1shz.enplotify.api.StoryQuests.lines().isEmpty();
        while (KEY.consumeClick()) {
            if (lines && mc.player != null && mc.screen == null) {
                mc.setScreen(new QuestScreen());
                if (!openedHere && !map.opened()) {
                    net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                            io.github.lwarqiu1shz.enplotify.PlotNetwork.QuestOpenedPayload.INSTANCE);
                }
                openedHere = true;
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        map = new QuestSync.Payload(List.of(), "", true);
        openedHere = false;
        received = false;
        QuestBanner.clear();
    }

    private QuestClient() {}
}
