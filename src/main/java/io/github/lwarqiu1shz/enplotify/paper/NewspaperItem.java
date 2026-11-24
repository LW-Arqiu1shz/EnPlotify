package io.github.lwarqiu1shz.enplotify.paper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Newspaper: a sheet folded in half; both halves of both sides are editable. */
public class NewspaperItem extends SheetItem {
    /** Unfolded on a table: one block wide, A2 proportions (594 : 420). */
    private static final float[] FOOTPRINT = {1.0F, 420.0F / 594.0F};

    public NewspaperItem(Properties properties, @Nullable Identifier preset) {
        super(properties.stacksTo(64), preset);
    }

    public static PaperContents.Newspaper content(ItemStack stack) {
        return stack.getOrDefault(PaperRegistry.NEWSPAPER_CONTENT.get(), PaperContents.Newspaper.EMPTY);
    }

    @Override
    public float[] footprint() {
        return FOOTPRINT;
    }

    @Override
    public char layerCode() {
        return 'N';
    }

    @Override
    public boolean isEditable(ItemStack stack) {
        return !isPreset() && !content(stack).locked();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.openNewspaper(hand);
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (isPreset()) return PaperTooltips.presetName(Component.translatable("item.enplotify.newspaper"), super.getName(stack));
        return PaperTooltips.name(super.getName(stack), 0, 0, content(stack).title());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        if (!isPreset()) PaperTooltips.addState(builder, content(stack).locked(), content(stack).author());
        builder.accept(Component.translatable("item.enplotify.sheet.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
