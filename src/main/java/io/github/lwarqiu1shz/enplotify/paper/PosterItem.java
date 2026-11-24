package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Poster: hangs on a wall, mostly a picture, optionally with a caption.
 * Right click: edit (or look at a finished one). Sneak + right click a wall: hang it; an unsized
 * poster first asks for its size in cells.
 */
public class PosterItem extends PaperItem {
    private final int presetWidth;
    private final int presetHeight;

    public PosterItem(Properties properties, @Nullable Identifier preset, int presetWidth, int presetHeight) {
        super(properties.stacksTo(64), preset);
        this.presetWidth = presetWidth;
        this.presetHeight = presetHeight;
    }

    public static PaperContents.Poster content(ItemStack stack) {
        return stack.getOrDefault(PaperRegistry.POSTER_CONTENT.get(), PaperContents.Poster.EMPTY);
    }

    /** {width, height} in cells, or {0, 0} while the size is still open. */
    public int[] size(ItemStack stack) {
        if (isPreset()) return new int[] {presetWidth, presetHeight};
        PaperContents.Poster content = content(stack);
        return content.sized() ? new int[] {content.width(), content.height()} : new int[] {0, 0};
    }

    public boolean isEditable(ItemStack stack) {
        return !isPreset() && !content(stack).locked();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.openPoster(hand);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Direction face = context.getClickedFace();
        if (player == null || !player.isSecondaryUseActive() || !face.getAxis().isHorizontal()) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        int[] size = size(stack);
        if (context.getLevel().isClientSide()) {
            if (size[0] <= 0) {
                io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.openSizePicker(
                        context.getHand(), context.getClickedPos(), face, false);
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
        if (!isPreset()) PaperTooltips.addState(builder, content(stack).locked(), content(stack).author());
        builder.accept(Component.translatable("item.enplotify.poster.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
