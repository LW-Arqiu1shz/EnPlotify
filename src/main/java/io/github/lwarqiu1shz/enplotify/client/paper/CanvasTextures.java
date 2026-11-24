package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.paper.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Painted canvases as GPU textures. Painting runs on a background thread; until it is done the
 * previous picture of the same surface (or nothing) is shown. Textures not used for a while are freed.
 * Canvases are looked up by identity: the same item / entity hands over the same {@link Canvas}
 * object every frame, and any edit produces a new one.
 */
public final class CanvasTextures {
    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EnPlotify canvas painter");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final long KEEP_MS = 8000L;
    private static final int MAX_ENTRIES = 40;

    private record Key(Canvas canvas, int width, int height, int background) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.canvas == canvas && k.width == width && k.height == height && k.background == background;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(canvas) * 31 + width * 7 + height * 13 + background;
        }
    }

    private static final class Entry {
        @Nullable Identifier id;
        @Nullable DynamicTexture texture;
        @Nullable CompletableFuture<int[]> job;
        Set<String> waiting = Set.of();
        long lastUsed;
    }

    private static final Map<Key, Entry> ENTRIES = new HashMap<>();
    private static int serial;
    private static long lastSweep;

    private static final class LinearTexture extends DynamicTexture {
        LinearTexture(String label, NativeImage image) {
            super(() -> label, image);
            this.sampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
        }
    }

    /**
     * The texture of {@code canvas} painted at {@code width x height} pixels over {@code background}
     * (0xAARRGGBB), or null while it is being painted for the first time. Render thread only.
     */
    public static @Nullable Identifier get(Canvas canvas, int width, int height, int background) {
        long now = Util.getMillis();
        sweep(now);
        Key key = new Key(canvas, Math.max(1, width), Math.max(1, height), background);
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(key, entry);
            schedule(key, entry);
        }
        entry.lastUsed = now;
        if (entry.job != null && entry.job.isDone()) {
            int[] pixels = entry.job.getNow(null);
            entry.job = null;
            if (pixels != null) upload(key, entry, pixels);
        }
        // A picture was still downloading when this was painted: paint again once it is here.
        if (entry.job == null && !entry.waiting.isEmpty()) {
            boolean arrived = false;
            for (String ref : entry.waiting) {
                if (PaperImages.awt(ref) != null) {
                    arrived = true;
                    break;
                }
            }
            if (arrived) schedule(key, entry);
        }
        return entry.id;
    }

    private static void schedule(Key key, Entry entry) {
        Map<String, BufferedImage> images = new HashMap<>();
        Set<String> waiting = new HashSet<>();
        for (Canvas.Element e : key.canvas().elements()) {
            if (e.type() != Canvas.IMAGE) continue;
            BufferedImage image = PaperImages.awt(e.text());
            if (image != null) images.put(e.text(), image);
            else if (PaperImages.mayArrive(e.text())) waiting.add(e.text());
        }
        entry.waiting = waiting;
        entry.job = CompletableFuture.supplyAsync(
                () -> CanvasRaster.paint(key.canvas(), key.width(), key.height(), key.background(), images), PAINTER)
                .exceptionally(t -> null);
    }

    private static void upload(Key key, Entry entry, int[] argb) {
        NativeImage image = new NativeImage(key.width(), key.height(), false);
        for (int y = 0; y < key.height(); y++) {
            int row = y * key.width();
            for (int x = 0; x < key.width(); x++) image.setPixel(x, y, argb[row + x]);
        }
        if (entry.id == null) entry.id = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "canvas/" + (serial++));
        else if (entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.id);
        entry.texture = new LinearTexture("enplotify canvas", image);
        Minecraft.getInstance().getTextureManager().register(entry.id, entry.texture);
    }

    private static void sweep(long now) {
        if (now - lastSweep < 1000L && ENTRIES.size() <= MAX_ENTRIES) return;
        lastSweep = now;
        List<Map.Entry<Key, Entry>> all = new ArrayList<>(ENTRIES.entrySet());
        all.sort((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed));
        int excess = all.size() - MAX_ENTRIES;
        Iterator<Map.Entry<Key, Entry>> it = all.iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> next = it.next();
            boolean stale = now - next.getValue().lastUsed > KEEP_MS;
            if (!stale && excess <= 0) break;
            free(next.getValue());
            ENTRIES.remove(next.getKey());
            excess--;
        }
    }

    private static void free(Entry entry) {
        if (entry.job != null) entry.job.cancel(false);
        if (entry.id != null && entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.id);
        entry.texture = null;
    }

    /** False when the canvas only holds text (drawn with the font, nothing to paint). */
    public static boolean needsPaint(Canvas canvas) {
        for (Canvas.Element e : canvas.elements()) {
            if (e.type() != Canvas.TEXT) return true;
        }
        return false;
    }

    /** True once {@code canvas} has a painted texture at this size (the editor swaps previews then). */
    public static boolean isReady(Canvas canvas, int width, int height, int background) {
        Entry entry = ENTRIES.get(new Key(canvas, Math.max(1, width), Math.max(1, height), background));
        return entry != null && entry.texture != null && entry.job == null;
    }

    /** Frees every texture painted from this exact canvas object. */
    public static void forget(Canvas canvas) {
        Iterator<Map.Entry<Key, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> next = it.next();
            if (next.getKey().canvas() == canvas) {
                free(next.getValue());
                it.remove();
            }
        }
    }

    /** Frees everything (on leaving a world). */
    public static void clear() {
        for (Entry entry : ENTRIES.values()) free(entry);
        ENTRIES.clear();
    }

    private CanvasTextures() {}
}
