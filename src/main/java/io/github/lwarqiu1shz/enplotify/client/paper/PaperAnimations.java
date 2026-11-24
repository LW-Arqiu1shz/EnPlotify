package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.system.MemoryUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays animated poster pictures. Each animation that is being looked at gets one texture and one
 * background thread that decodes the next few frames ahead; the render thread only copies a ready
 * frame into the texture when its time comes. Animations nobody looked at for a few seconds stop
 * and free everything; at most {@link #MAX_PLAYING} play at once (others show their first frame).
 */
public final class PaperAnimations {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static final int MAX_PLAYING = 12;
    private static final long IDLE_MS = 3000L;

    private record Ready(int[] abgr, int delayMs) {}

    private static final class Player {
        final Identifier id;
        final DynamicTexture texture;
        final int width, height;
        final ArrayBlockingQueue<Ready> queue = new ArrayBlockingQueue<>(3);
        volatile boolean stopped;
        long nextAt;
        long lastUsed;

        Player(String hash, Gifs.Decoder decoder) {
            this.width = decoder.width();
            this.height = decoder.height();
            this.id = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "animation/" + hash);
            this.texture = new DynamicTexture(() -> "enplotify animation " + hash, new NativeImage(width, height, false));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            Thread thread = new Thread(() -> decode(decoder), "EnPlotify animation");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }

        private void decode(Gifs.Decoder decoder) {
            try (decoder) {
                int[] argb = new int[width * height];
                boolean any = false;
                while (!stopped) {
                    Gifs.Frame frame = decoder.next();
                    if (frame == null) {
                        if (!any) return;
                        decoder.restart();
                        continue;
                    }
                    any = true;
                    BufferedImage image = frame.image();
                    image.getRGB(0, 0, width, height, argb, 0, width);
                    int[] abgr = new int[argb.length];
                    for (int i = 0; i < argb.length; i++) {
                        int c = argb[i];
                        abgr[i] = (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
                    }
                    Ready ready = new Ready(abgr, Math.max(20, frame.delayMs()));
                    while (!stopped && !queue.offer(ready, 200, TimeUnit.MILLISECONDS)) {
                        // wait for the player to take a frame
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("EnPlotify: an animation stopped", e);
            }
        }

        /** Shows the next frame if its time has come (once per frame, before the world is drawn). */
        void advance(long now) {
            if (now < nextAt) return;
            Ready ready = queue.poll();
            if (ready == null) return;
            NativeImage pixels = texture.getPixels();
            if (pixels == null) return;
            // One block copy into the image (same layout as setPixelABGR), then one upload.
            MemoryUtil.memIntBuffer(pixels.getPointer(), width * height).put(ready.abgr());
            texture.upload();
            // Behind by more than a moment (e.g. the game froze): carry on from now.
            nextAt = (nextAt == 0 || now - nextAt > 250L ? now : nextAt) + ready.delayMs();
        }

        void stop() {
            stopped = true;
            queue.clear();
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    private static final Map<String, Player> PLAYING = new HashMap<>();
    private static final Map<String, Boolean> BROKEN = new HashMap<>();
    private static long lastSweep;

    /**
     * The texture showing the current frame of an animated picture, or null if the picture is still
     * (or not loaded yet). Render thread; calling it keeps the animation playing.
     */
    static @Nullable Identifier frame(String hash) {
        long now = Util.getMillis();
        if (now - lastSweep > 1000L) sweep(now);
        Player player = PLAYING.get(hash);
        if (player != null) {
            player.lastUsed = now;
            return player.nextAt == 0 ? null : player.id;
        }
        if (BROKEN.containsKey(hash) || PLAYING.size() >= MAX_PLAYING) return null;
        byte[] data = PaperImages.animation(hash);
        if (data == null) return null;
        try {
            player = new Player(hash, new Gifs.Decoder(data));
        } catch (Exception | LinkageError e) {
            LOGGER.warn("EnPlotify: could not play animation {}", hash, e);
            BROKEN.put(hash, true);
            return null;
        }
        player.lastUsed = now;
        PLAYING.put(hash, player);
        // The first frame is shown once the next frame starts.
        return null;
    }

    /**
     * Moves every animation that was drawn lately to its next frame. Called once per frame before
     * anything is drawn, so textures never change in the middle of drawing (which is slow, above
     * all with shader packs, as they draw the world several times).
     */
    public static void onFrame() {
        if (PLAYING.isEmpty()) return;
        long now = Util.getMillis();
        for (Player player : PLAYING.values()) {
            if (now - player.lastUsed < 1000L) player.advance(now);
        }
    }

    private static void sweep(long now) {
        lastSweep = now;
        Iterator<Player> it = PLAYING.values().iterator();
        while (it.hasNext()) {
            Player player = it.next();
            if (now - player.lastUsed > IDLE_MS) {
                player.stop();
                it.remove();
            }
        }
    }

    /** {x, y, w, h} of a picture of the given size fitted (centred, whole) into a box. */
    static float[] fit(float x, float y, float w, float h, int pw, int ph) {
        float scale = Math.min(w / pw, h / ph);
        float dw = pw * scale, dh = ph * scale;
        return new float[] {x + (w - dw) / 2, y + (h - dh) / 2, dw, dh};
    }

    /** Pixel size of a playing animation, or null. */
    static int @Nullable [] size(String hash) {
        Player player = PLAYING.get(hash);
        return player == null ? null : new int[] {player.width, player.height};
    }

    /** Draws the current frames of a canvas's animated pictures over its painted picture. */
    static void drawGui(GuiGraphicsExtractor graphics, Canvas canvas, PaperGui.Rect r) {
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() != Canvas.IMAGE) continue;
            Identifier texture = frame(e.text());
            int[] size = texture == null ? null : size(e.text());
            if (size == null) continue;
            float[] box = fit(r.x() + e.x() * r.w(), r.y() + e.y() * r.h(), e.w() * r.w(), e.h() * r.h(), size[0], size[1]);
            graphics.blit(texture, Math.round(box[0]), Math.round(box[1]), Math.round(box[0] + box[2]), Math.round(box[1] + box[3]),
                    0.0F, 1.0F, 0.0F, 1.0F);
        }
    }

    static boolean hasImages(Canvas canvas) {
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() == Canvas.IMAGE) return true;
        }
        return false;
    }

    public static void clear() {
        for (Player player : PLAYING.values()) player.stop();
        PLAYING.clear();
        BROKEN.clear();
    }

    private PaperAnimations() {}
}
