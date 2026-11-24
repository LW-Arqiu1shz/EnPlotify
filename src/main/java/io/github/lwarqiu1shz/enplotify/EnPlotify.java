package io.github.lwarqiu1shz.enplotify;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * EnPlotify (沉浸式剧情): a full-screen, typed-out story mode other mods can trigger.
 *
 * <h2>Writing a story</h2>
 * A story is identified by an id such as {@code mymod:intro} and lives in the mod's assets:
 * <pre>
 *   assets/mymod/plots/intro/zh_cn.txt     story text, one file per language
 *   assets/mymod/plots/intro/en_us.txt     (en_us is the fallback language)
 *   assets/mymod/plots/intro/plot.json     optional settings
 * </pre>
 * In the text, one line break starts a new line on the same page, and an empty line (two line
 * breaks) starts a new page. Instead of .txt files the text may also be a single language key
 * {@code mymod.plot.intro} written with {@code \n} / {@code \n\n}.
 * <p>
 * {@code plot.json} (every field optional):
 * <pre>
 *   {
 *     "logo": "mymod:textures/gui/logo.png",   // shown after the last page, then fades out
 *     "zoom_last_page": true                    // the last page rushes towards the screen before the logo
 *   }
 * </pre>
 *
 * <h2>Playing it</h2>
 * Call {@link #play(ServerPlayer, Identifier)} on the server (or use {@code /enplotify play}).
 * Until the black screen starts to fade the player is invulnerable, held in place and ignored by mobs, and
 * other players see " (剧情中)" after their name tag. Listen to
 * {@link io.github.lwarqiu1shz.enplotify.api.PlotEvent} subclasses to react when it ends.
 */
@Mod(EnPlotify.MOD_ID)
public final class EnPlotify {
    public static final String MOD_ID = "enplotify";

    public EnPlotify(IEventBus modBus, ModContainer container) {
        PlotSounds.SOUND_EVENTS.register(modBus);
        PlotNetwork.register(modBus);
        io.github.lwarqiu1shz.enplotify.quest.QuestProgress.register(modBus);
        // This mod's own files all live in config/enplotify/ (the client settings, the remembered
        // terminal tabs). The server settings stay with each world, as NeoForge keeps them.
        moveOldClientConfig();
        container.registerConfig(ModConfig.Type.CLIENT, EnPlotifyConfig.SPEC, "enplotify/client.toml");
        container.registerConfig(ModConfig.Type.SERVER, EnPlotifyServerConfig.SPEC);
        io.github.lwarqiu1shz.enplotify.paper.PaperRegistry.register(modBus);
        io.github.lwarqiu1shz.enplotify.paper.PaperNetwork.register(modBus);
    }

    /** Settings from before the folder (config/enplotify-client.toml) move into it, kept as they were. */
    private static void moveOldClientConfig() {
        try {
            java.nio.file.Path config = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
            java.nio.file.Path old = config.resolve("enplotify-client.toml");
            java.nio.file.Path now = config.resolve("enplotify").resolve("client.toml");
            if (java.nio.file.Files.exists(old) && !java.nio.file.Files.exists(now)) {
                java.nio.file.Files.createDirectories(now.getParent());
                java.nio.file.Files.move(old, now);
            }
        } catch (java.io.IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("EnPlotify").warn("EnPlotify: couldn't move the old client settings", e);
        }
    }

    /** Shows the story {@code plot} to the player, restarting it if one is already playing. */
    public static void play(ServerPlayer player, Identifier plot) {
        PlotServer.start(player, plot, java.util.Map.of(), true);
    }

    /**
     * Shows the story with variables: every {@code {name}} in its text becomes {@code vars.get(name)},
     * shown in the player's language (a translatable component follows it). Unknown names stay as written.
     */
    public static void play(ServerPlayer player, Identifier plot, java.util.Map<String, net.minecraft.network.chat.Component> vars) {
        PlotServer.start(player, plot, vars, true);
    }

    /**
     * As above; {@code protect = false} keeps the player vulnerable and targetable while reading
     * (still held in place), e.g. for a story the player chose to watch again.
     */
    public static void play(ServerPlayer player, Identifier plot, java.util.Map<String, net.minecraft.network.chat.Component> vars,
                            boolean protect) {
        PlotServer.start(player, plot, vars, protect);
    }

    /**
     * Ends the player's story at once: the black screen fades away and the player is free.
     * Posts {@code Released} (if still reading) and {@code Finished}, both as skipped.
     */
    public static void stop(ServerPlayer player) {
        PlotServer.stop(player);
    }

    /**
     * Skips whatever story the player is in: a playing story is stopped as by {@link #stop} (which
     * also takes the story lines down); otherwise the story lines ({@code api.StoryLines}) are taken down.
     * False when there was nothing to skip.
     */
    public static boolean skip(ServerPlayer player) {
        if (PlotServer.currentPlot(player) != null) {
            PlotServer.stop(player);
            return true;
        }
        if (io.github.lwarqiu1shz.enplotify.api.StoryLines.isTalking(player)) {
            io.github.lwarqiu1shz.enplotify.api.StoryLines.clear(player);
            return true;
        }
        return false;
    }

    /** True while the player is reading a story: held in place (and, unless started unprotected, invulnerable, untargetable). */
    public static boolean isWatching(ServerPlayer player) {
        return PlotServer.isWatching(player);
    }

    /** The story the player is playing (text or ending), or null. */
    public static Identifier currentPlot(ServerPlayer player) {
        return PlotServer.currentPlot(player);
    }

    /** True once the player has watched (or skipped) the story; with others online it then doesn't pull them in again. */
    public static boolean hasSeen(ServerPlayer player, Identifier plot) {
        return io.github.lwarqiu1shz.enplotify.quest.QuestProgress.hasSeen(player, plot);
    }

    /** Counts the story as watched (e.g. for players who saw it before EnPlotify kept track). */
    public static void markSeen(ServerPlayer player, Identifier plot) {
        io.github.lwarqiu1shz.enplotify.quest.QuestProgress.markSeen(player, plot);
    }
}
