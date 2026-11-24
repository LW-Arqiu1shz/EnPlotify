package io.github.lwarqiu1shz.enplotify.compat;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.BoardEntity;
import io.github.lwarqiu1shz.enplotify.paper.PaperStackEntity;
import io.github.lwarqiu1shz.enplotify.paper.PosterEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.util.ModIdentification;

import java.util.List;

/**
 * Optional Jade support, found by Jade itself (never loaded without Jade): a paper pile whose
 * sheets all come from one other mod, or that mod's ready-made poster or board, shows that mod's
 * name instead of EnPlotify's.
 */
@WailaPlugin(EnPlotify.MOD_ID)
public final class JadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(PileModName.INSTANCE, PaperStackEntity.class);
        registration.registerEntityComponent(PileModName.INSTANCE, PosterEntity.class);
        registration.registerEntityComponent(PileModName.INSTANCE, BoardEntity.class);
        registration.registerBlockComponent(PrinterName.INSTANCE, io.github.lwarqiu1shz.enplotify.paper.PrinterBlock.class);
        registration.registerBlockComponent(PrinterName.INSTANCE, io.github.lwarqiu1shz.enplotify.paper.TerminalBlock.class);
    }

    /** A printer shows its own name ("打印机[x,y,z]" by default). */
    private enum PrinterName implements snownee.jade.api.IBlockComponentProvider {
        INSTANCE;

        private static final Identifier UID = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "printer_name");

        @Override
        public void appendTooltip(ITooltip tooltip, snownee.jade.api.BlockAccessor accessor, IPluginConfig config) {
            if (accessor.getBlockEntity() instanceof io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity printer) {
                tooltip.replace(JadeIds.CORE_OBJECT_NAME, IThemeHelper.get().title(printer.displayName()));
            } else if (accessor.getBlockEntity() instanceof io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity terminal
                    && io.github.lwarqiu1shz.enplotify.paper.TerminalBlock.hasScreen(accessor.getBlockState())) {
                tooltip.replace(JadeIds.CORE_OBJECT_NAME, IThemeHelper.get().title(terminal.displayName()));
            }
        }

        @Override
        public Identifier getUid() {
            return UID;
        }

        @Override
        public int getDefaultPriority() {
            return 10000;
        }

        @Override
        public boolean isRequired() {
            return true;
        }
    }

    private enum PileModName implements IEntityComponentProvider {
        INSTANCE;

        private static final Identifier UID = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "pile_mod_name");

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            String mod = switch (accessor.getEntity()) {
                case PaperStackEntity pile -> pile.sourceMod();
                case PosterEntity poster -> modOf(poster.getItem());
                case BoardEntity board -> modOf(board.getItem());
                default -> "";
            };
            if (mod.isEmpty()) return;
            String name = ModIdentification.getModName(mod).orElse(null);
            if (name == null || name.isEmpty()) return;
            tooltip.replace(JadeIds.CORE_MOD_NAME, lines ->
                    List.of(List.of(IThemeHelper.get().modNameElement(name).tag(JadeIds.CORE_MOD_NAME))));
        }

        /** A ready-made poster or board of another mod: that mod; else "". */
        private static String modOf(ItemStack item) {
            if (item.isEmpty()) return "";
            String mod = BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace();
            return mod.equals(EnPlotify.MOD_ID) ? "" : mod;
        }

        @Override
        public Identifier getUid() {
            return UID;
        }

        /** Right after Jade's own mod name line (9999). */
        @Override
        public int getDefaultPriority() {
            return 10000;
        }

        /** Always on: no switch in Jade's settings (it only corrects a name). */
        @Override
        public boolean isRequired() {
            return true;
        }
    }
}
