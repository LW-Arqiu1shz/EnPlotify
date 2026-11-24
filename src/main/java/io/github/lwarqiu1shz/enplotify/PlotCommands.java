package io.github.lwarqiu1shz.enplotify;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.lwarqiu1shz.enplotify.api.StoryQuests;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;

/**
 * {@code /enplotify play <targets> <plot>}: show a story;
 * {@code /enplotify quest start|reset <targets> <line>} and {@code quest unlock|complete <targets> <line> <stage>}:
 * move players through a quest line (operators only).
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class PlotCommands {
    private static final SuggestionProvider<CommandSourceStack> LINES = (context, builder) ->
            SharedSuggestionProvider.suggestResource(StoryQuests.lines().stream().map(l -> l.id), builder);

    private static final SuggestionProvider<CommandSourceStack> STAGES = (context, builder) -> {
        StoryQuests.Line line = StoryQuests.get(IdentifierArgument.getId(context, "line"));
        return SharedSuggestionProvider.suggest(line == null ? java.util.stream.Stream.<String>empty()
                : line.stages().stream().map(s -> s.id), builder);
    };

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(EnPlotify.MOD_ID)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("play")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("plot", IdentifierArgument.id())
                                        .executes(context -> {
                                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
                                            Identifier plot = IdentifierArgument.getId(context, "plot");
                                            for (ServerPlayer player : targets) EnPlotify.play(player, plot);
                                            context.getSource().sendSuccess(() -> Component.translatable(
                                                    "commands.enplotify.play", plot.toString(), targets.size()), true);
                                            return targets.size();
                                        }))))
                .then(Commands.literal("quest")
                        // Only when some mod has quest lines.
                        .requires(source -> !StoryQuests.lines().isEmpty())
                        .then(lineCommand("start", false))
                        .then(lineCommand("reset", false))
                        .then(lineCommand("unlock", true))
                        .then(lineCommand("complete", true))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> lineCommand(String action, boolean withStage) {
        var line = Commands.argument("line", IdentifierArgument.id()).suggests(LINES);
        if (withStage) {
            line.then(Commands.argument("stage", StringArgumentType.word()).suggests(STAGES)
                    .executes(context -> runQuest(context, action, StringArgumentType.getString(context, "stage"))));
        } else {
            line.executes(context -> runQuest(context, action, null));
        }
        return Commands.literal(action).then(Commands.argument("targets", EntityArgument.players()).then(line));
    }

    private static int runQuest(CommandContext<CommandSourceStack> context, String action, String stage) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        Identifier id = IdentifierArgument.getId(context, "line");
        StoryQuests.Line line = StoryQuests.get(id);
        if (line == null || (stage != null && line.get(stage) == null)) {
            context.getSource().sendFailure(Component.translatable("commands.enplotify.quest.unknown",
                    stage == null ? id.toString() : id + " " + stage));
            return 0;
        }
        for (ServerPlayer player : targets) {
            switch (action) {
                case "start" -> StoryQuests.start(player, id);
                case "reset" -> StoryQuests.reset(player, id);
                case "unlock" -> StoryQuests.unlock(player, id, stage);
                default -> StoryQuests.complete(player, id, stage);
            }
        }
        context.getSource().sendSuccess(() -> Component.translatable("commands.enplotify.quest.done", targets.size()), true);
        return targets.size();
    }

    private PlotCommands() {}
}
