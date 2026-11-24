package io.github.lwarqiu1shz.enplotify.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Server-side story events, posted on {@code NeoForge.EVENT_BUS}.
 * <ol>
 *   <li>{@link Started}: the story was sent to the player;</li>
 *   <li>{@link Released}: the black screen starts fading and the player can see the world again
 *       (or the story was skipped): they can move and be hurt again. A logo may still be fading out;</li>
 *   <li>{@link Finished}: everything, including the ending, is over.</li>
 * </ol>
 * {@link Failed} replaces both when the player's client has no text for this story (for example a
 * client without the mod or resource pack that provides it): nothing was shown, so don't treat the
 * story as seen. If the player logs out mid-story none of the later events fire.
 */
public abstract class PlotEvent extends PlayerEvent {
    private final Identifier plot;

    protected PlotEvent(ServerPlayer player, Identifier plot) {
        super(player);
        this.plot = plot;
    }

    public Identifier getPlot() {
        return plot;
    }

    @Override
    public ServerPlayer getEntity() {
        return (ServerPlayer) super.getEntity();
    }

    public static final class Started extends PlotEvent {
        public Started(ServerPlayer player, Identifier plot) {
            super(player, plot);
        }
    }

    public static final class Released extends PlotEvent {
        private final boolean skipped;

        public Released(ServerPlayer player, Identifier plot, boolean skipped) {
            super(player, plot);
            this.skipped = skipped;
        }

        /** True when the player skipped the story with Tab + Space. */
        public boolean wasSkipped() {
            return skipped;
        }
    }

    /** The client could not find the story's text; the player was released without seeing anything. */
    public static final class Failed extends PlotEvent {
        public Failed(ServerPlayer player, Identifier plot) {
            super(player, plot);
        }
    }

    public static final class Finished extends PlotEvent {
        private final boolean skipped;

        public Finished(ServerPlayer player, Identifier plot, boolean skipped) {
            super(player, plot);
            this.skipped = skipped;
        }

        /** True when the player skipped the story with Tab + Space. */
        public boolean wasSkipped() {
            return skipped;
        }
    }
}
