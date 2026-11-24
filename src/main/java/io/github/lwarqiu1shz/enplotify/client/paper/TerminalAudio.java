package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The sound of terminal pages, played in the world at the terminal and its displays (music-disc
 * volume slider) for everybody near; the player whose browser plays it hears the page itself. Samples are 24000 a second, one
 * byte each (µ-law), one stream per page.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class TerminalAudio {
    /** Wait for this much before starting (and after running dry), so small delays don't stutter. */
    private static final int PRIME = StreamSound.RATE * 80 / 1000;
    /** More than this waiting and the oldest is dropped: the sound stays close to the page. */
    private static final int MAX_WAITING = StreamSound.RATE * 300 / 1000;
    /** Fade in after silence and out when running dry (no clicks): 8 ms. */
    private static final int FADE = StreamSound.RATE * 8 / 1000;
    private static final long QUIET_MS = 2000;

    /** emitter: 0 = the terminal itself, otherwise the id of a display connected to it. */
    private record Key(BlockPos pos, int stream, int emitter) {}

    /** One stream being played. */
    private static final class Stream implements StreamSound.Source {
        private final short[] ring = new short[StreamSound.RATE * 2];
        private int read, count;
        private boolean primed;
        long lastData = System.currentTimeMillis();
        StreamSound sound;

        /** Starts where {@code lead} is now (a display joining a sound already playing stays in step). */
        void copyFrom(Stream lead) {
            synchronized (lead) {
                System.arraycopy(lead.ring, 0, ring, 0, ring.length);
                read = lead.read;
                count = lead.count;
                primed = lead.primed;
            }
        }

        synchronized void add(byte[] mulaw) {
            for (byte b : mulaw) {
                if (count == ring.length) {
                    read = (read + 1) % ring.length;
                    count--;
                }
                ring[(read + count) % ring.length] = decode(b);
                count++;
            }
            if (count > MAX_WAITING) {
                int drop = count - MAX_WAITING / 2;
                read = (read + drop) % ring.length;
                count -= drop;
            }
            lastData = System.currentTimeMillis();
        }

        @Override
        public synchronized void fill(short[] out) {
            boolean starting = false;
            if (!primed) {
                if (count < PRIME) return;
                primed = true;
                starting = true;
            }
            int n = Math.min(out.length, count);
            for (int i = 0; i < n; i++) {
                out[i] = ring[read];
                read = (read + 1) % ring.length;
            }
            count -= n;
            if (starting) for (int i = 0; i < Math.min(FADE, n); i++) out[i] = (short) (out[i] * i / FADE);
            if (count == 0) {
                primed = false;
                for (int i = 0; i < Math.min(FADE, n); i++) out[n - 1 - i] = (short) (out[n - 1 - i] * i / FADE);
            }
        }
    }

    private static final Map<Key, Stream> STREAMS = new HashMap<>();

    // ------------------------------------------------------------------ hearing

    /**
     * Sound from the server (someone else's page): played at the terminal and, the same samples at
     * the same moment, from the middle of every display connected to it.
     */
    public static void onData(BlockPos pos, int stream, byte[] samples) {
        if (Minecraft.getInstance().level == null) return;
        BlockPos at = pos.immutable();
        Stream lead = play(new Key(at, stream, 0), Vec3.atCenterOf(at), samples, null);
        for (net.minecraft.world.entity.Entity d : displaysOf(at)) play(new Key(at, stream, d.getId()), d.getBoundingBox().getCenter(), samples, lead);
    }

    private static Stream play(Key key, Vec3 where, byte[] samples, @Nullable Stream lead) {
        Minecraft minecraft = Minecraft.getInstance();
        Stream s = STREAMS.get(key);
        if (s == null) {
            s = new Stream();
            STREAMS.put(key, s);
            if (lead != null) s.copyFrom(lead); // already holds these samples
            else s.add(samples);
        } else {
            s.add(samples);
        }
        s.lastData = System.currentTimeMillis();
        if (s.sound == null || !minecraft.getSoundManager().isActive(s.sound)) {
            if (s.sound != null) s.sound.end();
            s.sound = new StreamSound("terminal_audio", SoundSource.RECORDS, where, 1.0F, s);
            minecraft.getSoundManager().play(s.sound);
        }
        return s;
    }

    /** The displays (in this player's world, within 64 blocks) connected to the terminal at {@code pos}. */
    static java.util.List<net.minecraft.world.entity.Entity> displaysOf(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return java.util.List.of();
        return minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, minecraft.player.getBoundingBox().inflate(64.0),
                d -> d instanceof io.github.lwarqiu1shz.enplotify.paper.ScreenLink link && pos.equals(link.terminal()));
    }

    /** How far {@code from} is from the nearest place this terminal's sound comes out (it or a display). */
    static double nearestDistance(BlockPos pos, Vec3 from) {
        double best = from.distanceTo(Vec3.atCenterOf(pos));
        for (net.minecraft.world.entity.Entity d : displaysOf(pos)) best = Math.min(best, from.distanceTo(d.getBoundingBox().getCenter()));
        return best;
    }

    // ------------------------------------------------------------------ playing (this player's pages)

    private static final Map<Key, ByteArrayOutputStream> OUTGOING = new HashMap<>();

    /**
     * New samples from one of this player's pages, shown on the terminal at {@code pos}: for the
     * players near (this player hears the page itself, straight away, see the page script).
     */
    static void fromPage(BlockPos pos, int page, byte[] samples) {
        if (samples.length == 0) return;
        Key key = new Key(pos.immutable(), page, 0);
        OUTGOING.computeIfAbsent(key, k -> new ByteArrayOutputStream()).writeBytes(samples);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().getConnection() != null) {
            for (Map.Entry<Key, ByteArrayOutputStream> e : OUTGOING.entrySet()) {
                byte[] all = e.getValue().toByteArray();
                for (int at = 0; at < all.length; at += PaperNetwork.MAX_AUDIO_BYTES) {
                    byte[] part = java.util.Arrays.copyOfRange(all, at, Math.min(all.length, at + PaperNetwork.MAX_AUDIO_BYTES));
                    ClientPacketDistributor.sendToServer(new PaperNetwork.TerminalAudio(e.getKey().pos(), e.getKey().stream(), part));
                }
            }
        }
        OUTGOING.clear();
        long now = System.currentTimeMillis();
        Iterator<Stream> it = STREAMS.values().iterator();
        while (it.hasNext()) {
            Stream s = it.next();
            if (now - s.lastData > QUIET_MS) {
                if (s.sound != null) s.sound.end();
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (Stream s : STREAMS.values()) if (s.sound != null) s.sound.end();
        STREAMS.clear();
        OUTGOING.clear();
    }

    // ------------------------------------------------------------------ µ-law

    static short decode(byte b) {
        int u = ~b & 0xFF;
        int sign = u & 0x80, exponent = (u >> 4) & 7, mantissa = u & 0x0F;
        int sample = (((mantissa << 3) + 132) << exponent) - 132;
        return (short) (sign != 0 ? -sample : sample);
    }

    private TerminalAudio() {}
}
