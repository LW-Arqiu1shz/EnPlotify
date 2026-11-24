package io.github.lwarqiu1shz.enplotify.api;

import io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * Posted on {@code NeoForge.EVENT_BUS} (server) when a terminal has finished starting: the logo and
 * the loading are over and its desktop is up. Not posted for terminals that load already on.
 */
public final class TerminalStartedEvent extends Event {
    private final ServerLevel level;
    private final TerminalBlockEntity terminal;

    public TerminalStartedEvent(ServerLevel level, TerminalBlockEntity terminal) {
        this.level = level;
        this.terminal = terminal;
    }

    public ServerLevel getLevel() {
        return level;
    }

    /** The terminal's block entity; its block is {@code getTerminal().getBlockState().getBlock()}. */
    public TerminalBlockEntity getTerminal() {
        return terminal;
    }
}
