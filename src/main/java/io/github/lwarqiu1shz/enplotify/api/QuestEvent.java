package io.github.lwarqiu1shz.enplotify.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted on the NeoForge event bus (server side) as a player moves through {@link StoryQuests}. */
public abstract class QuestEvent extends Event {
    private final ServerPlayer player;
    private final Identifier line;
    private final String stage;

    QuestEvent(ServerPlayer player, Identifier line, String stage) {
        this.player = player;
        this.line = line;
        this.stage = stage;
    }

    public ServerPlayer getPlayer() { return player; }
    public Identifier getLine() { return line; }
    public String getStage() { return stage; }

    /** The stage became available (it is now the player's current task). */
    public static final class StageUnlocked extends QuestEvent {
        public StageUnlocked(ServerPlayer player, Identifier line, String stage) { super(player, line, stage); }
    }

    /** The stage is finished. */
    public static final class StageCompleted extends QuestEvent {
        public StageCompleted(ServerPlayer player, Identifier line, String stage) { super(player, line, stage); }
    }
}
