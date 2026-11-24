package io.github.lwarqiu1shz.enplotify.paper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A file kept in a terminal: a document, newspaper or poster (the item itself, so printing it gives
 * exactly that item) and the name shown in the file list. A terminal item can carry its files too
 * (component {@code enplotify:terminal_files}), so a mod can hand out a terminal with ready files.
 */
public record TerminalFile(int id, String name, ItemStack stack) {
    public static final int MAX_NAME = 32;

    public static final Codec<TerminalFile> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(TerminalFile::id),
            Codec.STRING.fieldOf("name").forGetter(TerminalFile::name),
            ItemStack.CODEC.fieldOf("item").forGetter(TerminalFile::stack)
    ).apply(i, TerminalFile::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalFile> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TerminalFile::id,
            ByteBufCodecs.stringUtf8(256), TerminalFile::name,
            ItemStack.STREAM_CODEC, TerminalFile::stack,
            TerminalFile::new);
    public static final Codec<List<TerminalFile>> LIST_CODEC = CODEC.listOf();
    public static final StreamCodec<RegistryFriendlyByteBuf, List<TerminalFile>> LIST_STREAM_CODEC =
            STREAM_CODEC.apply(ByteBufCodecs.list(TerminalBlockEntity.MAX_FILES));

    public TerminalFile {
        name = PaperContents.clip(name, MAX_NAME);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TerminalFile f && f.id == id && f.name.equals(name) && ItemStack.matches(f.stack, stack);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * id + name.hashCode()) + ItemStack.hashItemAndComponents(stack);
    }
}
