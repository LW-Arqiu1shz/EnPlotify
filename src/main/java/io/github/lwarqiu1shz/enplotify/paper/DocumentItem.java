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

/** Document: portrait A4 pages, mostly text with optional illustrations, any number of pages. */
public class DocumentItem extends SheetItem {
    /** As tall as a newspaper on the table, A4 proportions (210 : 297). */
    private static final float[] FOOTPRINT = {420.0F / 594.0F * 210.0F / 297.0F, 420.0F / 594.0F};

    public DocumentItem(Properties properties, @Nullable Identifier preset) {
        super(properties.stacksTo(64), preset);
    }

    public static PaperContents.Document content(ItemStack stack) {
        return stack.getOrDefault(PaperRegistry.DOCUMENT_CONTENT.get(), PaperContents.Document.EMPTY);
    }

    @Override
    public float[] footprint() {
        return FOOTPRINT;
    }

    @Override
    public char layerCode() {
        return 'D';
    }

    @Override
    public boolean isEditable(ItemStack stack) {
        return !isPreset() && !content(stack).locked();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) io.github.lwarqiu1shz.enplotify.client.paper.PaperClientHooks.openDocument(hand);
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (isPreset()) return PaperTooltips.presetName(Component.translatable("item.enplotify.document"), super.getName(stack));
        return PaperTooltips.name(super.getName(stack), 0, 0, content(stack).title());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> builder, TooltipFlag flag) {
        if (!isPreset()) {
            PaperContents.Document doc = content(stack);
            builder.accept(Component.translatable("item.enplotify.document.tooltip.pages", doc.pages().size()).withStyle(ChatFormatting.GRAY));
            PaperTooltips.addState(builder, doc.locked(), doc.author());
        }
        builder.accept(Component.translatable("item.enplotify.sheet.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
