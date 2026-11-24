package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Blackboard / whiteboard: right click a wall to hang it (an unsized board first asks for its
 * size in cells), then write on it by right clicking it with chalk / a whiteboard marker.
 */
public class BoardItem extends PaperItem {
    public enum Style {
        BLACKBOARD, WHITEBOARD;

        public int[] colors() {
            return this == BLACKBOARD ? PaperContents.CHALK_COLORS : PaperContents.MARKER_COLORS;
        }
    }

    private final Style style;
    private final int presetWidth;
    private final int presetHeight;

    public BoardItem(Properties properties, Style style, @Nullable Identifier preset, int presetWidth, int presetHeight) {
        super(properties.stacksTo(64), preset);
        this.style = style;
        this.presetWidth = presetWidth;
        this.presetHeight = presetHeight;
    }

    public Style style() {
        return style;
    }

    public static PaperContents.Board content(ItemStack stack) {
        return stack.getOrDefault(PaperRegistry.BOARD_CONTENT.get(), PaperContents.Board.EMPTY);
    }

    public int[] size(ItemStack stack) {
        // A preset board, or a writable one made in one size only.
        if (isPreset() || presetWidth > 0) return new int[] {presetWidth, presetHeight};
        PaperContents.Board content = content(stack);
        return content.sized() ? new int[] {content.width(), content.height()} : new int[] {0, 0};
    }

    /** True if {@code tool} writes on this board (chalk on a blackboard, a marker on a whiteboard). */
    public boolean acceptsTool(ItemStack tool) {
        return style == Style.BLACKBOARD ? tool.is(PaperRegistry.CHALK.get()) : tool.is(PaperRegistry.WHITEBOARD_MARKER.get());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Direction face = context.getClickedFace();
        if (player == null || !face.getAxis().isHorizontal()) return InteractionResult.PASS;
        int[] size = size(context.getItemInHand());
        if (context.getLevel().isClientSide()) {
            if (size[0] <= 0) {
                io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.openSizePicker(
                        context.getHand(), context.getClickedPos(), face, true);
            }
            return InteractionResult.SUCCESS;
        }
        if (size[0] > 0 && player instanceof ServerPlayer serverPlayer) {
            WallPlacement.place(serverPlayer, context.getHand(), context.getClickedPos(), face, size[0], size[1]);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (isPreset()) return super.getName(stack);
        int[] size = size(stack);
        return PaperTooltips.name(super.getName(stack), size[0], size[1], content(stack).name());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable(style == Style.BLACKBOARD
                ? "item.enplotify.blackboard.tooltip.hint" : "item.enplotify.whiteboard.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
