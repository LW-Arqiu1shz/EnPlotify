package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.paper.PrinterBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The running sound of a printer, made as it plays (no sound file): a smooth, steady, high hum of
 * a quick machine, fading in when a copy starts and out when it is done.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = io.github.lwarqiu1shz.enplotify.EnPlotify.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class PrinterSound implements StreamSound.Source {
    private static final Map<BlockPos, PrinterSound> PLAYING = new HashMap<>();
    private static boolean warned;

    private final StreamSound sound;
    private long sample;
    private final java.util.Random random = new java.util.Random();
    long lastSeen;

    private PrinterSound(BlockPos pos) {
        sound = new StreamSound("printer_motor", SoundSource.BLOCKS, Vec3.atCenterOf(pos), 1.0F, this);
    }

    /** Called every client tick of every printer: starts or ends its sound. */
    public static void tick(PrinterBlockEntity printer, boolean printing) {
        BlockPos pos = printer.getBlockPos();
        PrinterSound playing = PLAYING.get(pos);
        Minecraft minecraft = Minecraft.getInstance();
        if (printing) {
            if (playing == null || !minecraft.getSoundManager().isActive(playing.sound)) {
                if (playing != null) playing.sound.end();
                playing = new PrinterSound(pos.immutable());
                PLAYING.put(pos.immutable(), playing);
                minecraft.getSoundManager().play(playing.sound);
                if (!minecraft.getSoundManager().isActive(playing.sound) && !warned) {
                    warned = true;
                    org.slf4j.LoggerFactory.getLogger("EnPlotify").warn("EnPlotify: the printer sound didn't start (another mod may have stopped it)");
                }
            }
            playing.lastSeen = System.currentTimeMillis();
        } else if (playing != null) {
            playing.fadeOut();
            PLAYING.remove(pos);
        }
    }

    /** The printer at {@code pos} is gone (broken, unloaded): its sound fades out now. */
    public static void removed(BlockPos pos) {
        PrinterSound playing = PLAYING.remove(pos);
        if (playing != null) playing.fadeOut();
    }

    /**
     * Printers that stopped being ticked go quiet too. Checked here, not from a printer's tick: with
     * the only printer broken nothing would tick to notice.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (PLAYING.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean paused = Minecraft.getInstance().isPaused();
        Iterator<PrinterSound> it = PLAYING.values().iterator();
        while (it.hasNext()) {
            PrinterSound s = it.next();
            if (paused) {
                s.lastSeen = now; // the game stands still, printers don't tick
            } else if (now - s.lastSeen > 500) {
                s.fadeOut();
                it.remove();
            }
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        for (PrinterSound s : PLAYING.values()) s.sound.end();
        PLAYING.clear();
    }

    /** Fading out (the copy is done): then the sound ends by itself. */
    private volatile boolean fadingOut;
    private double level, low1, low2;
    private double p1, p2, p3, p4;

    /** A soft, steady machine hum: a high, consonant whine over a quiet rush of air, swelling in and out. */
    @Override
    public synchronized void fill(short[] out) {
        double rate = StreamSound.RATE;
        for (int i = 0; i < out.length; i++, sample++) {
            double t = sample / rate;
            // Fade in over 0.25 s, out over 0.2 s.
            double target = fadingOut ? 0.0 : 1.0;
            level += (target - level) * (fadingOut ? 1.0 / (0.2 * rate) * 5 : 1.0 / (0.25 * rate) * 5);
            // A barely moving pitch and loudness, so it sounds alive but never pulses.
            double drift = 1.0 + 0.004 * Math.sin(t * 2 * Math.PI * 0.7);
            double swell = 1.0 + 0.05 * Math.sin(t * 2 * Math.PI * 1.3);
            p1 += 880.0 * drift / rate;
            p2 += 1320.0 * drift / rate;
            p3 += 1760.0 * drift / rate;
            p4 += 220.0 / rate;
            double tone = 0.20 * Math.sin(p1 * 2 * Math.PI) + 0.10 * Math.sin(p2 * 2 * Math.PI)
                    + 0.04 * Math.sin(p3 * 2 * Math.PI) + 0.08 * Math.sin(p4 * 2 * Math.PI);
            // Air: white noise smoothed twice (a soft "shhh"), the lowest part taken off.
            double white = random.nextDouble() * 2.0 - 1.0;
            low1 += (white - low1) * 0.25;
            low2 += (low1 - low2) * 0.02;
            double air = (low1 - low2) * 0.35;
            double v = (tone * swell + air) * level;
            out[i] = (short) Math.max(-32767, Math.min(32767, v * 26000.0));
        }
        if (fadingOut && level < 0.002) sound.end();
    }

    /** Lets the sound fade away instead of stopping dead. */
    private void fadeOut() {
        fadingOut = true;
    }
}
