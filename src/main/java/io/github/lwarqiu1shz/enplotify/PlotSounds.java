package io.github.lwarqiu1shz.enplotify;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PlotSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, EnPlotify.MOD_ID);

    /** Soft tick for each typed character. */
    public static final DeferredHolder<SoundEvent, SoundEvent> TYPE_TICK =
            SOUND_EVENTS.register("type_tick",
                    () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "type_tick")));

    private PlotSounds() {}
}
