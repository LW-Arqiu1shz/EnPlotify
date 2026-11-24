package io.github.lwarqiu1shz.enplotify.client;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

@Mod(value = EnPlotify.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class EnPlotifyClient {
    public EnPlotifyClient(ModContainer container) {
        EnPlotifyConfigScreen.register(container);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "story_overlay"), PlotCutscene::renderOverlay);
        event.registerAboveAll(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "story_line"), StoryLineBar::render);
        event.registerAboveAll(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "quest_banner"), QuestBanner::render);
        event.registerAboveAll(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "plot_notice"), PlotNotice::render);
    }
}
