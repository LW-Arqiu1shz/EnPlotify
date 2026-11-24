package io.github.lwarqiu1shz.enplotify.client.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * A sound in the world whose samples are made while it plays (a page's sound, a printer's motor).
 * It plays on Minecraft's streaming channels (like music discs), so a crowd of ordinary sounds
 * doesn't cut it off. Never runs dry: when there is nothing to play it plays silence, until it is
 * told to stop.
 */
final class StreamSound extends AbstractTickableSoundInstance {
    static final int RATE = 24000;
    /** Samples handed over per read: 50 ms; Minecraft keeps four of these queued (200 ms). */
    private static final int CHUNK = RATE * 50 / 1000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    /** Where the samples come from: fills {@code out} (silence where it has none). */
    interface Source {
        void fill(short[] out);
    }

    private final Source source;
    private volatile boolean ended;

    StreamSound(String name, SoundSource category, Vec3 at, float volume, Source source) {
        super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, name)),
                category, RandomSource.create());
        this.source = source;
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;
        this.volume = volume;
        this.pitch = 1.0F;
        this.looping = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
    }

    void end() {
        ended = true;
    }

    @Override
    public void tick() {
        if (ended) stop();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new AudioStream() {
            private final short[] samples = new short[CHUNK];

            @Override
            public AudioFormat getFormat() {
                return FORMAT;
            }

            @Override
            public ByteBuffer read(int size) {
                java.util.Arrays.fill(samples, (short) 0);
                if (!ended) {
                    try {
                        source.fill(samples);
                    } catch (RuntimeException e) {
                        java.util.Arrays.fill(samples, (short) 0);
                    }
                }
                ByteBuffer out = BufferUtils.createByteBuffer(CHUNK * 2);
                for (short s : samples) out.putShort(s);
                out.flip();
                return out;
            }

            @Override
            public void close() {}
        });
    }
}
