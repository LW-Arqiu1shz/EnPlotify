package io.github.lwarqiu1shz.enplotify.paper;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * One player-made poster / newspaper / document / board alone in the crafting grid: it comes back
 * blank (contents, size and finalisation cleared), ready to be made into something new.
 * Mod-provided (preset) items and items that are already blank don't match.
 */
public final class PaperResetRecipe extends CustomRecipe {
    public static final PaperResetRecipe INSTANCE = new PaperResetRecipe();
    public static final MapCodec<PaperResetRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, PaperResetRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<PaperResetRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private PaperResetRecipe() {}

    private static ItemStack single(CraftingInput input) {
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;
            if (!found.isEmpty()) return ItemStack.EMPTY;
            found = stack;
        }
        return found;
    }

    private static boolean needsReset(ItemStack stack) {
        if (!(stack.getItem() instanceof PaperItem paper) || paper.isPreset()) return false;
        if (paper instanceof PosterItem) return !PosterItem.content(stack).isBlank();
        if (paper instanceof NewspaperItem) return !NewspaperItem.content(stack).isBlank();
        if (paper instanceof DocumentItem) return !DocumentItem.content(stack).isBlank();
        if (paper instanceof BoardItem) return !BoardItem.content(stack).isBlank();
        return false;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return needsReset(single(input));
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack stack = single(input);
        if (!needsReset(stack)) return ItemStack.EMPTY;
        return new ItemStack(stack.getItem());
    }

    @Override
    public RecipeSerializer<PaperResetRecipe> getSerializer() {
        return SERIALIZER;
    }
}
