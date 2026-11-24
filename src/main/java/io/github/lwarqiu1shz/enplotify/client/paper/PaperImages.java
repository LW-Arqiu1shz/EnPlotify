package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.EnPlotifyServerConfig;
import io.github.lwarqiu1shz.enplotify.paper.GifInfo;
import io.github.lwarqiu1shz.enplotify.paper.ImageStore;
import io.github.lwarqiu1shz.enplotify.paper.PaperContents;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Client side of pictures: drawing them (uploaded ones by hash, mod-provided ones by texture id),
 * downloading uploaded ones on demand (cached on disk in {@code .minecraft/enplotify_cache}), and
 * choosing / shrinking / uploading new ones.
 */
public final class PaperImages {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");

    /** A drawable picture: texture id and its pixel size. */
    public record Tex(Identifier id, int width, int height) {}

    private static final Map<String, Tex> READY = new HashMap<>();
    /** Hashes being downloaded (or known missing): don't ask again. */
    private static final Map<String, byte[][]> DOWNLOADS = new HashMap<>();
    private static final Map<String, Boolean> MISSING = new HashMap<>();
    private static final Map<Identifier, Optional<Tex>> RESOURCES = new HashMap<>();
    private static final Map<Integer, Consumer<String>> UPLOAD_DONE = new HashMap<>();
    private static final Map<Integer, Consumer<Component>> UPLOAD_FAILED = new HashMap<>();
    private static int nextUploadId = 1;
    /** Decoded pictures for canvas painting (read from the painter thread). */
    private static final Map<String, java.awt.image.BufferedImage> AWT = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> AWT_FAILED = new HashMap<>();

    /**
     * The decoded picture for painting a canvas, or null while it is still downloading (or if it is
     * missing). Render thread only; starts the download if needed.
     */
    public static java.awt.image.@Nullable BufferedImage awt(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        java.awt.image.BufferedImage cached = AWT.get(ref);
        if (cached != null || AWT_FAILED.containsKey(ref)) return cached;
        byte[] data = null;
        if (PaperContents.isImageHash(ref)) {
            if (MISSING.containsKey(ref) || DOWNLOADS.containsKey(ref)) return null;
            Path file = cacheFile(ref);
            try {
                if (Files.exists(file)) data = Files.readAllBytes(file);
            } catch (IOException ignored) {
                // download again below
            }
            if (data == null || !ref.equals(ImageStore.sha256(data))) {
                DOWNLOADS.put(ref, null);
                ClientPacketDistributor.sendToServer(new PaperNetwork.RequestImage(ref));
                return null;
            }
        } else {
            Identifier id = Identifier.tryParse(ref);
            Optional<Resource> resource = id == null ? Optional.empty() : Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                AWT_FAILED.put(ref, true);
                return null;
            }
            try (InputStream in = resource.get().open()) {
                data = in.readAllBytes();
            } catch (IOException e) {
                AWT_FAILED.put(ref, true);
                return null;
            }
        }
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (image == null) {
                AWT_FAILED.put(ref, true);
                return null;
            }
            AWT.put(ref, image);
            return image;
        } catch (IOException | LinkageError e) {
            AWT_FAILED.put(ref, true);
            return null;
        }
    }

    /** True while the picture may still arrive (worth painting the canvas again later). */
    public static boolean mayArrive(String ref) {
        return PaperContents.isImageHash(ref) && !MISSING.containsKey(ref) && !AWT_FAILED.containsKey(ref);
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The picture for an image field: an upload hash, or a texture id of a mod-provided picture.
     * Returns null while it is still downloading, or if there is none.
     */
    public static @Nullable Tex get(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        if (PaperContents.isImageHash(ref)) return uploaded(ref);
        Identifier id = Identifier.tryParse(ref);
        if (id == null) return null;
        return RESOURCES.computeIfAbsent(id, PaperImages::resourceSize).orElse(null);
    }

    private static Optional<Tex> resourceSize(Identifier id) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            LOGGER.warn("EnPlotify: picture {} not found", id);
            return Optional.empty();
        }
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            return Optional.of(new Tex(id, image.getWidth(), image.getHeight()));
        } catch (IOException e) {
            LOGGER.warn("EnPlotify: could not read picture {}", id, e);
            return Optional.empty();
        }
    }

    private static @Nullable Tex uploaded(String hash) {
        Tex ready = READY.get(hash);
        if (ready != null || MISSING.containsKey(hash) || DOWNLOADS.containsKey(hash)) return ready;
        Path cached = cacheFile(hash);
        if (Files.exists(cached)) {
            try {
                byte[] png = Files.readAllBytes(cached);
                if (hash.equals(ImageStore.sha256(png))) return register(hash, png);
            } catch (IOException ignored) {
                // fall through to a download
            }
        }
        DOWNLOADS.put(hash, null);
        ClientPacketDistributor.sendToServer(new PaperNetwork.RequestImage(hash));
        return null;
    }

    private static @Nullable Tex register(String hash, byte[] png) {
        try {
            NativeImage image = GifInfo.isGif(png) ? firstFrame(png) : NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "dynamic/" + hash);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "enplotify picture " + hash, image));
            Tex tex = new Tex(id, image.getWidth(), image.getHeight());
            READY.put(hash, tex);
            return tex;
        } catch (IOException e) {
            LOGGER.warn("EnPlotify: picture {} is not a readable PNG", hash, e);
            MISSING.put(hash, true);
            return null;
        }
    }

    /** The first frame of a GIF as a game image. */
    private static NativeImage firstFrame(byte[] gif) throws IOException {
        java.awt.image.BufferedImage frame = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(gif));
        if (frame == null) throw new IOException("unreadable GIF");
        NativeImage image = new NativeImage(frame.getWidth(), frame.getHeight(), false);
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                int argb = frame.getRGB(x, y);
                image.setPixelABGR(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
            }
        }
        return image;
    }

    private static final Map<String, Boolean> ANIMATED = new HashMap<>();

    /**
     * The GIF data of an animated picture (more than one frame), or null for a still one or while it
     * is still downloading. Render thread.
     */
    static byte @Nullable [] animation(String hash) {
        if (!PaperContents.isImageHash(hash)) return null;
        Boolean known = ANIMATED.get(hash);
        if (Boolean.FALSE.equals(known)) return null;
        if (get(hash) == null) return null;
        try {
            byte[] data = Files.readAllBytes(cacheFile(hash));
            if (known == null) {
                GifInfo info = GifInfo.read(data);
                known = info != null && info.frames() > 1;
                ANIMATED.put(hash, known);
            }
            return known ? data : null;
        } catch (IOException e) {
            ANIMATED.put(hash, false);
            return null;
        }
    }

    public static void onImageData(String hash, int index, int total, byte[] data) {
        if (!PaperContents.isImageHash(hash)) return;
        if (total <= 0) {
            DOWNLOADS.remove(hash);
            MISSING.put(hash, true);
            return;
        }
        byte[][] parts = DOWNLOADS.get(hash);
        if (parts == null || parts.length != total) {
            parts = new byte[total][];
            DOWNLOADS.put(hash, parts);
        }
        if (index < 0 || index >= total) return;
        parts[index] = data;
        for (byte[] part : parts) {
            if (part == null) return;
        }
        DOWNLOADS.remove(hash);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) joined.writeBytes(part);
        byte[] png = joined.toByteArray();
        if (!hash.equals(ImageStore.sha256(png))) {
            MISSING.put(hash, true);
            return;
        }
        saveToCache(hash, png);
        register(hash, png);
    }

    private static Path cacheFile(String hash) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("enplotify_cache").resolve(hash + ".png");
    }

    private static void saveToCache(String hash, byte[] png) {
        try {
            Path file = cacheFile(hash);
            Files.createDirectories(file.getParent());
            Files.write(file, png);
        } catch (IOException e) {
            LOGGER.warn("EnPlotify: could not cache picture {}", hash, e);
        }
    }

    /** Forget pictures that failed, so a new world / server is asked again. */
    public static void onLogout() {
        MISSING.clear();
        DOWNLOADS.clear();
        AWT_FAILED.clear();
        UPLOAD_DONE.clear();
        UPLOAD_FAILED.clear();
        RESOURCES.clear();
        ANIMATED.clear();
    }

    // ------------------------------------------------------------------ choosing and uploading

    private static final String[] PICTURES = {"*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"};
    private static final String[] VIDEOS = {"*.mp4", "*.mov", "*.m4v", "*.mkv"};

    /** How an upload is done: size, recolouring (boards), animations (posters) and what to report. */
    public record UploadSpec(int maxSide, int recolor, boolean animated, int background,
                             Consumer<String> done, Consumer<Component> failed, Consumer<Component> progress) {}

    /** Hashes of uploads whose GIF / video was cut to the time limit. */
    private static final java.util.Set<String> CUT = ConcurrentHashMap.newKeySet();

    public static boolean wasCut(String hash) {
        return CUT.contains(hash);
    }

    /** Opens the system file chooser (off the render thread) and uploads the chosen picture. */
    public static void chooseAndUpload(UploadSpec spec) {
        Consumer<Component> failed = spec.failed();
        Thread thread = new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                String[] patterns = spec.animated() ? concat(PICTURES, VIDEOS) : PICTURES;
                PointerBuffer filters = stack.mallocPointer(patterns.length);
                for (String pattern : patterns) filters.put(stack.UTF8(pattern));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog(
                        Component.translatable("enplotify.paper.choose_image").getString(), null, filters,
                        spec.animated() ? "PNG / JPG / BMP / GIF / MP4 / MOV / MKV" : "PNG / JPG / BMP / GIF", false);
            } catch (Throwable t) {
                LOGGER.warn("EnPlotify: the file chooser could not be opened", t);
                path = null;
                Minecraft.getInstance().execute(() -> failed.accept(Component.translatable("message.enplotify.upload.no_chooser")));
            }
            if (path != null) {
                uploadFile(Path.of(path), spec);
            } else {
                Minecraft.getInstance().execute(() -> failed.accept(Component.empty()));
            }
        }, "EnPlotify file chooser");
        thread.setDaemon(true);
        thread.start();
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean isVideo(Path file, byte[] head) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String pattern : VIDEOS) {
            if (name.endsWith(pattern.substring(1))) return true;
        }
        // MP4 / MOV ("ftyp" box) or Matroska (EBML header).
        boolean mp4 = head.length > 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        boolean mkv = head.length > 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        return mp4 || mkv;
    }

    /**
     * Shrinks a picture to at most {@code maxSide} pixels per side, converts it to PNG and uploads it.
     * On posters an animated GIF or a video becomes an animated GIF instead (see {@link Gifs}).
     * With {@code recolor} (boards) every pixel becomes that colour, as opaque as the original was dark.
     */
    public static void uploadFile(Path file, UploadSpec spec) {
        Thread thread = new Thread(() -> {
            byte[] data = null;
            boolean cut = false;
            Component problem = Component.translatable("message.enplotify.upload.invalid");
            try {
                if (spec.animated() && isVideo(file, head(file))) {
                    report(spec, Component.translatable("enplotify.paper.converting", "0"));
                    Gifs.Result result = GifVideo.fromVideo(file.toFile(), EnPlotifyServerConfig.maxAnimationSeconds(),
                            EnPlotifyServerConfig.maxAnimationBytes(), spec.background(), progress(spec));
                    data = result.gif();
                    cut = result.cut();
                } else {
                    byte[] raw = Files.readAllBytes(file);
                    GifInfo info = spec.animated() && GifInfo.isGif(raw) ? GifInfo.read(raw) : null;
                    if (info != null && info.frames() > 1) {
                        report(spec, Component.translatable("enplotify.paper.converting", "0"));
                        Gifs.Result result = Gifs.fromGif(raw, EnPlotifyServerConfig.maxAnimationSeconds(),
                                EnPlotifyServerConfig.maxAnimationBytes(), spec.background(), progress(spec));
                        data = result.gif();
                        cut = result.cut();
                    } else {
                        data = toPng(raw, spec.maxSide(), spec.recolor());
                    }
                }
            } catch (Gifs.TooLarge e) {
                problem = Component.translatable("message.enplotify.upload.animation_too_large");
            } catch (Exception | LinkageError e) {
                LOGGER.warn("EnPlotify: could not read {}", file, e);
                if (spec.animated() && isVideo(file, new byte[0])) problem = Component.translatable("message.enplotify.upload.video_unsupported");
            }
            byte[] result = data;
            boolean wasCut = cut;
            Component reason = problem;
            Minecraft.getInstance().execute(() -> {
                if (result == null) {
                    spec.failed().accept(reason);
                } else {
                    if (wasCut) CUT.add(ImageStore.sha256(result));
                    sendUpload(result, spec.done(), spec.failed());
                }
            });
        }, "EnPlotify picture upload");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendUpload(byte[] png, Consumer<String> done, Consumer<Component> failed) {
        String hash = ImageStore.sha256(png);
        // Keep our own copy so the uploader never has to download it again.
        saveToCache(hash, png);
        int id = nextUploadId++;
        UPLOAD_DONE.put(id, done);
        UPLOAD_FAILED.put(id, failed);
        int total = Math.max(1, (png.length + PaperNetwork.UPLOAD_CHUNK - 1) / PaperNetwork.UPLOAD_CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * PaperNetwork.UPLOAD_CHUNK;
            byte[] part = Arrays.copyOfRange(png, from, Math.min(png.length, from + PaperNetwork.UPLOAD_CHUNK));
            ClientPacketDistributor.sendToServer(new PaperNetwork.UploadChunk(id, i, total, part));
        }
    }

    public static void onUploadResult(int uploadId, String hash, String error) {
        Consumer<String> done = UPLOAD_DONE.remove(uploadId);
        Consumer<Component> failed = UPLOAD_FAILED.remove(uploadId);
        if (!hash.isEmpty() && PaperContents.isImageHash(hash)) {
            if (done != null) done.accept(hash);
        } else if (failed != null) {
            failed.accept(Component.translatable(error.isEmpty() ? "message.enplotify.upload.failed" : error));
        }
    }

    private static byte[] head(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(16);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static void report(UploadSpec spec, Component message) {
        Minecraft.getInstance().execute(() -> spec.progress().accept(message));
    }

    /** Conversion progress as "n seconds done", at most a few times a second. */
    private static java.util.function.DoubleConsumer progress(UploadSpec spec) {
        long[] last = {0L};
        return seconds -> {
            long now = System.currentTimeMillis();
            if (now - last[0] < 250L) return;
            last[0] = now;
            report(spec, Component.translatable("enplotify.paper.converting", String.format(java.util.Locale.ROOT, "%.1f", seconds)));
        };
    }

    /** Decodes PNG / JPG / BMP / GIF (first frame), box-filters it down to maxSide, encodes PNG. */
    private static byte[] toPng(byte[] encoded, int maxSide, int recolor) throws IOException {
        ByteBuffer input = MemoryUtil.memAlloc(encoded.length);
        ByteBuffer pixels = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            input.put(encoded).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(input, w, h, channels, 4);
            if (pixels == null) throw new IOException("Unsupported picture: " + STBImage.stbi_failure_reason());
            int srcW = w.get(0);
            int srcH = h.get(0);
            double scale = Math.min(1.0, maxSide / (double) Math.max(srcW, srcH));
            int dstW = Math.max(1, (int) Math.round(srcW * scale));
            int dstH = Math.max(1, (int) Math.round(srcH * scale));
            try (NativeImage out = new NativeImage(dstW, dstH, false)) {
                for (int y = 0; y < dstH; y++) {
                    int y0 = (int) ((long) y * srcH / dstH);
                    int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * srcH / dstH));
                    for (int x = 0; x < dstW; x++) {
                        int x0 = (int) ((long) x * srcW / dstW);
                        int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * srcW / dstW));
                        long r = 0, g = 0, b = 0, a = 0;
                        int n = 0;
                        for (int sy = y0; sy < y1; sy++) {
                            for (int sx = x0; sx < x1; sx++) {
                                int at = (sy * srcW + sx) * 4;
                                r += pixels.get(at) & 0xFF;
                                g += pixels.get(at + 1) & 0xFF;
                                b += pixels.get(at + 2) & 0xFF;
                                a += pixels.get(at + 3) & 0xFF;
                                n++;
                            }
                        }
                        int ar = (int) (r / n), ag = (int) (g / n), ab = (int) (b / n), aa = (int) (a / n);
                        if (recolor >= 0) {
                            float luminance = (0.299F * ar + 0.587F * ag + 0.114F * ab) / 255.0F;
                            aa = Math.round((1.0F - luminance) * aa);
                            ar = (recolor >> 16) & 0xFF;
                            ag = (recolor >> 8) & 0xFF;
                            ab = recolor & 0xFF;
                        }
                        int abgr = aa << 24 | ab << 16 | ag << 8 | ar;
                        out.setPixelABGR(x, y, abgr);
                    }
                }
                Path temp = Files.createTempFile("enplotify", ".png");
                try {
                    out.writeToFile(temp);
                    return Files.readAllBytes(temp);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        } finally {
            if (pixels != null) STBImage.stbi_image_free(pixels);
            MemoryUtil.memFree(input);
        }
    }

    private PaperImages() {}
}
