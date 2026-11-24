package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.EnPlotifyConfig;
import io.github.lwarqiu1shz.enplotify.paper.PaperNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures of terminal screens for other players ("桌面截图").
 * <ul>
 *   <li>Using a terminal: when the server says someone watches, the screen area is read back from
 *   the frame at the rate asked (and once after each window change), made a small JPEG and sent.</li>
 *   <li>Watching: terminals in use by others that are drawn nearby are watched at this player's
 *   own rate; the pictures that come back are shown on those screens.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
public final class TerminalShots {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    /** Makes pictures (two at once: a big JPEG takes a while). */
    private static final ExecutorService ENCODER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "EnPlotify terminal pictures");
        t.setDaemon(true);
        return t;
    });
    /** Reads the pictures that come in (apart from making ours, so neither waits for the other). */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EnPlotify terminal pictures (in)");
        t.setDaemon(true);
        return t;
    });
    /** Pictures being made right now; no new one is started while two are. */
    private static final java.util.concurrent.atomic.AtomicInteger BUSY = new java.util.concurrent.atomic.AtomicInteger();
    /** Numbers pictures in the order they were taken, so a slower older one never replaces a newer one. */
    private static long nextSeq;
    private static final Map<BlockPos, Long> SENT_SEQ = new HashMap<>();
    private static final Map<BlockPos, Long> SHOWN_SEQ = new HashMap<>();

    // ------------------------------------------------------------------ taking pictures (the user)

    /** How often the server wants pictures of each terminal this player paints (in use or left). */
    private static final Map<BlockPos, Float> RATES = new HashMap<>();
    private static final Map<BlockPos, Long> LAST_CAPTURE = new HashMap<>();
    private static long lastCapture;
    private static int lastWindow = Integer.MIN_VALUE;
    private static long switchAt;
    private static boolean capturing;
    private static @Nullable GpuBuffer buffer;
    private static long bufferSize;

    /** The picture size the viewers of each terminal asked for (the largest). */
    private static final Map<BlockPos, Integer> RESOLUTIONS = new HashMap<>();

    public static void onCaptureRate(BlockPos pos, float fps, int resolution) {
        if (fps < 0.0F) {
            RATES.remove(pos);
            RESOLUTIONS.remove(pos);
        } else {
            RATES.put(pos.immutable(), fps);
            RESOLUTIONS.put(pos.immutable(), resolution);
        }
    }

    /**
     * The size (16:10) of the next picture of a terminal: the largest anyone watching asked for,
     * and no larger than the screen really is in this window's pixels ({@code areaW}).
     * {@code own}: at least this player's own setting (the picture left on the screen on leaving:
     * a display connected later starts from it, and so does the page left playing).
     */
    private static int[] shotSize(BlockPos pos, int areaW, boolean own) {
        int r = RESOLUTIONS.getOrDefault(pos, 0);
        if (own || localWatch(pos)) r = Math.max(r, EnPlotifyConfig.terminalResolution().ordinal());
        EnPlotifyConfig.ScreenshotResolution res = EnPlotifyConfig.ScreenshotResolution.byIndex(r);
        int w = Math.max(160, Math.min(res.width, areaW));
        return new int[] {w, w * 10 / 16};
    }

    private static float rate(BlockPos pos) {
        float r = RATES.getOrDefault(pos, -1.0F);
        // A display of the terminal this player is at is in sight: pictures for it too.
        if (localWatch(pos)) r = Math.max(r, (float) EnPlotifyConfig.terminalFps());
        return r;
    }

    /** Terminals of this player whose displays were drawn lately (when). */
    private static final Map<BlockPos, Long> LOCAL = new HashMap<>();

    private static boolean localWatch(BlockPos pos) {
        Long t = LOCAL.get(pos);
        return t != null && System.currentTimeMillis() - t < 1500;
    }

    /** For a display of the terminal this player is at: the newest picture this player took of it. */
    static @Nullable Identifier ownPicture(BlockPos pos) {
        LOCAL.put(pos.immutable(), System.currentTimeMillis());
        return cachedPicture(pos);
    }

    /** What is shown now, to notice window changes (the editor counts as its own window). */
    private static int window() {
        if (!(Minecraft.getInstance().screen instanceof TerminalScreen)) return 100;
        // A different tab counts as a different window: the viewers get a picture right away.
        return (TerminalView.app << 8) | Math.max(0, WebBrowser.activeIndex());
    }

    /** Where the browser page sits on the screen (fractions x0, y0, x1, y1), set while it is drawn. */
    static float @Nullable [] pageArea;
    /** The last picture taken (the whole screen), and the page area in it: the frame for live page pictures. */
    private record Base(int[] rgb, int w, int h, float @Nullable [] page, int gridW, int gridH) {}

    /**
     * A picture's bytes: the JPEG, then its grid (width, height: two bytes each) and "GR". Decoders
     * stop at the JPEG's end, so the extra bytes are only read by us (to draw a live clock on it).
     */
    private static byte[] withGrid(byte[] jpeg, int gridW, int gridH) {
        byte[] out = java.util.Arrays.copyOf(jpeg, jpeg.length + 6);
        out[jpeg.length] = (byte) (gridW >> 8);
        out[jpeg.length + 1] = (byte) gridW;
        out[jpeg.length + 2] = (byte) (gridH >> 8);
        out[jpeg.length + 3] = (byte) gridH;
        out[jpeg.length + 4] = 'G';
        out[jpeg.length + 5] = 'R';
        return out;
    }

    private static int @Nullable [] gridOf(byte[] data) {
        int n = data.length;
        if (n < 8 || data[n - 2] != 'G' || data[n - 1] != 'R') return null;
        int w = (data[n - 6] & 0xFF) << 8 | data[n - 5] & 0xFF, h = (data[n - 4] & 0xFF) << 8 | data[n - 3] & 0xFF;
        return w >= 20 && h >= 20 && w < 4096 && h < 4096 ? new int[] {w, h} : null;
    }

    /** The grid a terminal's picture was laid out in (null: unknown, an old picture). */
    static int @Nullable [] pictureGrid(BlockPos pos) {
        Shown shown = SHOWN.get(pos);
        return shown == null ? null : shown.grid;
    }
    private static final Map<BlockPos, Base> BASES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Takes a picture right now (the frame just drawn), e.g. when leaving: it stays on the screen. */
    static void captureNow(BlockPos at) {
        if (!TerminalView.arrived()) return;
        capture(at.immutable(), true, true);
    }

    @SubscribeEvent
    public static void onFrameDone(RenderFrameEvent.Post event) {
        BlockPos at = TerminalView.pos();
        if (at == null) {
            idleCapture();
            return;
        }
        float captureRate = rate(at);
        if (!TerminalView.arrived() || captureRate < 0.0F) {
            lastWindow = Integer.MIN_VALUE;
            return;
        }
        long now = System.currentTimeMillis();
        int window = window();
        if (window != lastWindow) {
            lastWindow = window;
            switchAt = now + 300; // let the new window draw first
        }
        boolean switched = switchAt > 0 && now >= switchAt;
        boolean due = switched || (captureRate > 0.0F && now - lastCapture >= (long) (1000.0F / captureRate));
        if (!due || capturing || (BUSY.get() >= 2 && !switched)) return;
        if (switched) switchAt = 0;
        lastCapture = now;
        capture(at, switched, false);
    }

    /**
     * The browser left open on a screen nobody is at: its page is still running, so at the rate the
     * viewers want, the page's newest picture is put into the last screen picture and sent.
     */
    private static void idleCapture() {
        if (capturing || BUSY.get() >= 2 || RATES.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (BlockPos at : WebBrowser.parked()) {
            float captureRate = rate(at);
            Base b = BASES.get(at);
            if (captureRate <= 0.0F || b == null || b.page() == null || !WebBrowser.parkedForeground(at)) continue;
            if (now - LAST_CAPTURE.getOrDefault(at, 0L) < (long) (1000.0F / captureRate)) continue;
            Object[] frame = WebBrowser.latestFrame(at);
            if (frame == null) continue;
            LAST_CAPTURE.put(at, now);
            idleCapture(at, b, frame);
            return; // one picture per frame at most
        }
    }

    private static void idleCapture(BlockPos at, Base b0, Object[] frame) {
        // Watchers now want larger pictures than the last screen picture: it is enlarged once (the
        // page, the part that moves, is then put in sharp at the new size).
        int[] want = shotSize(at, Integer.MAX_VALUE, false);
        boolean grow = want[0] > b0.w();
        long seq = ++nextSeq;
        BUSY.incrementAndGet();
        ENCODER.execute(() -> {
            try {
                Base b = b0;
                if (grow) {
                    b = new Base(enlarge(b0.rgb(), b0.w(), b0.h(), want[0], want[1]), want[0], want[1], b0.page(), b0.gridW(), b0.gridH());
                    BASES.put(at, b);
                }
                float[] page = b.page();
                final int SHOT_W = b.w(), SHOT_H = b.h();
                byte[] rgba = (byte[]) frame[0];
                int fw = (int) frame[1], fh = (int) frame[2];
                int[] rgb = b.rgb().clone();
                int px0 = Math.round(page[0] * SHOT_W), py0 = Math.round(page[1] * SHOT_H);
                int px1 = Math.round(page[2] * SHOT_W), py1 = Math.round(page[3] * SHOT_H);
                int pw = Math.max(1, px1 - px0), ph = Math.max(1, py1 - py0);
                for (int y = Math.max(0, py0); y < Math.min(SHOT_H, py1); y++) {
                    for (int x = Math.max(0, px0); x < Math.min(SHOT_W, px1); x++) {
                        int red = 0, green = 0, blue = 0;
                        for (int sy = 0; sy < 2; sy++) {
                            for (int sx = 0; sx < 2; sx++) {
                                int fx = Math.min(fw - 1, (int) ((x - px0 + 0.25 + sx * 0.5) * fw / pw));
                                int fy = Math.min(fh - 1, (int) ((y - py0 + 0.25 + sy * 0.5) * fh / ph));
                                int i = (fx + fy * fw) * 4;
                                red += rgba[i] & 0xFF;
                                green += rgba[i + 1] & 0xFF;
                                blue += rgba[i + 2] & 0xFF;
                            }
                        }
                        rgb[x + y * SHOT_W] = (red / 4) << 16 | (green / 4) << 8 | (blue / 4);
                    }
                }
                byte[] jpeg = encode(rgb, SHOT_W, SHOT_H);
                if (jpeg != null) {
                    Base done = b;
                    byte[] data = withGrid(jpeg, done.gridW(), done.gridH());
                    Minecraft.getInstance().execute(() -> deliver(at, seq, data, false));
                }
            } catch (RuntimeException e) {
                LOGGER.debug("EnPlotify: couldn't make a terminal picture", e);
            } finally {
                BUSY.decrementAndGet();
            }
        });
    }

    /** A picture (0xRRGGBB) scaled up smoothly (bilinear). */
    private static int[] enlarge(int[] rgb, int w, int h, int nw, int nh) {
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            float fy = Math.max(0.0F, (y + 0.5F) * h / nh - 0.5F);
            int y0 = Math.min(h - 1, (int) fy), y1 = Math.min(h - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < nw; x++) {
                float fx = Math.max(0.0F, (x + 0.5F) * w / nw - 0.5F);
                int x0 = Math.min(w - 1, (int) fx), x1 = Math.min(w - 1, x0 + 1);
                float tx = fx - x0;
                int a = rgb[x0 + y0 * w], b = rgb[x1 + y0 * w], c = rgb[x0 + y1 * w], d = rgb[x1 + y1 * w];
                int v = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    float top = (a >> shift & 0xFF) * (1 - tx) + (b >> shift & 0xFF) * tx;
                    float bottom = (c >> shift & 0xFF) * (1 - tx) + (d >> shift & 0xFF) * tx;
                    v |= Math.round(top * (1 - ty) + bottom * ty) << shift;
                }
                out[x + y * nw] = v;
            }
        }
        return out;
    }

    /**
     * Reads the screen area out of the finished frame (on the GPU's schedule) and sends it.
     * {@code now}: taken outside the usual beat (leaving), in a buffer of its own.
     */
    private static void capture(BlockPos at, boolean switched, boolean now) {
        if (capturing && !now) return;
        final int gridW = TerminalScreen.lastGridW, gridH = TerminalScreen.lastGridH;
        float[] page = TerminalView.app == io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity.APP_BROWSER
                && Minecraft.getInstance().screen instanceof TerminalScreen ? pageArea : null;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft.getMainRenderTarget();
        GpuTexture colour = target.getColorTexture();
        if (colour == null) return;
        int width = target.width, height = target.height;
        int pixelSize = colour.getFormat().pixelSize();
        long size = (long) width * height * pixelSize;
        GpuBuffer own = null;
        if (now) {
            own = RenderSystem.getDevice().createBuffer(() -> "EnPlotify terminal picture (leaving)",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
        } else if (buffer == null || bufferSize != size) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> "EnPlotify terminal picture",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
            bufferSize = size;
        }
        // The screen area in window pixels, rounded like the terminal screen rounds it.
        double gs = minecraft.getWindow().getGuiScale();
        float[] r = TerminalView.guiRect(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
        int x0 = (int) Math.round(Math.round(r[0]) * gs), y0 = (int) Math.round(Math.round(r[1]) * gs);
        int w = (int) Math.round(Math.round(r[2]) * gs), h = (int) Math.round(Math.round(r[3]) * gs);
        if (w <= 0 || h <= 0) {
            if (own != null) own.close();
            return;
        }
        int[] shot = shotSize(at, w, now);
        final int SHOT_W = shot[0], SHOT_H = shot[1];
        GpuBuffer target2 = own != null ? own : buffer;
        boolean local = localWatch(at);
        // Others get pictures when they asked for them (or the window changed, or it is the last one).
        boolean send = now || switched || RATES.getOrDefault(at, -1.0F) > 0.0F;
        if (!send && !local) {
            if (own != null) own.close();
            return;
        }
        GpuBuffer closeAfter = own;
        if (!now) capturing = true;
        long seq = ++nextSeq;
        CommandEncoder reader = RenderSystem.getDevice().createCommandEncoder();
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(colour, target2, 0L, () -> {
            // Here (the render thread) only the rows with the screen are copied out, in one go.
            int rowBytes = width * pixelSize;
            int rLo = Math.max(0, height - y0 - h), rHi = Math.min(height - 1, height - 1 - y0);
            byte[] rows;
            try (GpuBuffer.MappedView view = reader.mapBuffer(target2, true, false)) {
                if (rHi < rLo) throw new IllegalStateException("the screen is outside the window");
                rows = new byte[(rHi - rLo + 1) * rowBytes];
                view.data().get(rLo * rowBytes, rows, 0, rows.length);
            } catch (RuntimeException e) {
                LOGGER.debug("EnPlotify: couldn't read the terminal screen", e);
                if (!now) capturing = false;
                if (closeAfter != null) Minecraft.getInstance().execute(closeAfter::close);
                return;
            }
            if (!now) capturing = false;
            if (closeAfter != null) Minecraft.getInstance().execute(closeAfter::close);
            BUSY.incrementAndGet();
            ENCODER.execute(() -> {
                try {
                    int[] rgb = new int[SHOT_W * SHOT_H];
                    int yMin = Math.max(0, y0), yMax = Math.min(height - 1, y0 + h - 1);
                    // 2 x 2 samples per picture pixel; rows in the buffer go bottom up.
                    for (int oy = 0; oy < SHOT_H; oy++) {
                        for (int ox = 0; ox < SHOT_W; ox++) {
                            int red = 0, green = 0, blue = 0;
                            for (int sy = 0; sy < 2; sy++) {
                                int y = Math.min(yMax, Math.max(yMin, y0 + (int) ((oy + 0.25 + sy * 0.5) * h / SHOT_H)));
                                int row = (height - 1 - y - rLo) * width;
                                for (int sx = 0; sx < 2; sx++) {
                                    int x = Math.min(width - 1, Math.max(0, x0 + (int) ((ox + 0.25 + sx * 0.5) * w / SHOT_W)));
                                    int i = (row + x) * pixelSize;
                                    red += rows[i] & 0xFF;
                                    green += rows[i + 1] & 0xFF;
                                    blue += rows[i + 2] & 0xFF;
                                }
                            }
                            rgb[ox + oy * SHOT_W] = (red / 4) << 16 | (green / 4) << 8 | (blue / 4);
                        }
                    }
                    BASES.put(at, new Base(rgb, SHOT_W, SHOT_H, page, gridW, gridH));
                    // The picture taken on leaving is also what this player sees as the camera glides back;
                    // and a display of this terminal in sight shows it too: straight away, no JPEG.
                    if (now || local) {
                        NativeImage image = imageOf(rgb, SHOT_W, SHOT_H);
                        Minecraft.getInstance().execute(() -> showOwn(at, seq, image, new int[] {gridW, gridH}));
                    }
                    if (send) {
                        byte[] jpeg = encode(rgb, SHOT_W, SHOT_H);
                        if (jpeg != null) {
                            byte[] data = withGrid(jpeg, gridW, gridH);
                            Minecraft.getInstance().execute(() -> deliver(at, seq, data, switched));
                        }
                    }
                } catch (RuntimeException e) {
                    LOGGER.debug("EnPlotify: couldn't make a terminal picture", e);
                } finally {
                    BUSY.decrementAndGet();
                }
            });
        }, 0);
    }

    /** Sends a finished picture, unless a newer one of the same terminal already went. */
    private static void deliver(BlockPos at, long seq, byte[] data, boolean switched) {
        if (seq <= SENT_SEQ.getOrDefault(at, 0L) && !switched) return;
        SENT_SEQ.put(at, Math.max(seq, SENT_SEQ.getOrDefault(at, 0L)));
        sendShot(at, data, switched);
    }

    /** Shows a picture this player took, unless a newer one is already shown. */
    private static void showOwn(BlockPos at, long seq, NativeImage image, int[] grid) {
        if (seq <= SHOWN_SEQ.getOrDefault(at, 0L)) {
            image.close();
            return;
        }
        SHOWN_SEQ.put(at, seq);
        show(at, image);
        Shown shown = SHOWN.get(at);
        if (shown != null) shown.grid = grid;
    }

    /** A picture (0xRRGGBB pixels) as an image for a texture, written in one go. */
    private static NativeImage imageOf(int[] rgb, int w, int h) {
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = rgb[i];
            px[i] = 0xFF000000 | (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16) & 0xFF; // → the image's ABGR
        }
        NativeImage image = new NativeImage(w, h, false);
        org.lwjgl.system.MemoryUtil.memIntBuffer(image.getPointer(), px.length).put(px);
        return image;
    }

    /** Sends a picture to the server, in as many pieces as it takes. */
    private static void sendShot(BlockPos at, byte[] data, boolean switched) {
        PaperNetwork.inPieces(data, (part, index, total) ->
                ClientPacketDistributor.sendToServer(new PaperNetwork.TerminalShot(at, part, switched, index, total)));
    }

    /** A JPEG no larger than a picture may be (lower quality until it fits), or null. */
    private static final ThreadLocal<@Nullable ImageWriter> WRITER = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        return writers.hasNext() ? writers.next() : null;
    });

    private static byte @Nullable [] encode(int[] rgb, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        System.arraycopy(rgb, 0, ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData(), 0, width * height);
        // Big pictures start a little lower (smaller, so quicker to send); still sharp.
        float[] qualities = width >= 1280 ? new float[] {0.7F, 0.5F, 0.35F, 0.25F} : new float[] {0.8F, 0.6F, 0.4F, 0.25F};
        for (float quality : qualities) {
            ImageWriter writer = WRITER.get();
            if (writer == null) return null;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 4);
                try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                    writer.setOutput(stream);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality);
                    writer.write(null, new IIOImage(image, null, null), param);
                } finally {
                    writer.setOutput(null);
                }
                if (out.size() <= PaperNetwork.MAX_PICTURE_BYTES - 16) return out.toByteArray();
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.debug("EnPlotify: couldn't make a terminal picture", e);
                writer.dispose();
                WRITER.remove();
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ showing pictures (others)

    private static final class Shown {
        @Nullable DynamicTexture texture;
        int @Nullable [] grid;
        Identifier id;
        long lastUsed;

        Shown(Identifier id) {
            this.id = id;
        }
    }

    private static final Map<BlockPos, Shown> SHOWN = new HashMap<>();
    /** Terminals drawn lately that we want pictures of: when each was last drawn. */
    private static final Map<BlockPos, Long> WATCHED = new HashMap<>();
    private static int ticks;

    /** The latest picture of a terminal's screen (null if none yet); also asks for pictures of it. */
    static @Nullable Identifier picture(BlockPos pos) {
        long now = System.currentTimeMillis();
        BlockPos key = pos.immutable();
        if (!WATCHED.containsKey(key)) {
            WATCHED.put(key, now);
            sendWatch(key);
        } else {
            WATCHED.put(key, now);
        }
        Shown shown = SHOWN.get(key);
        if (shown == null || shown.texture == null) return null;
        shown.lastUsed = now;
        return shown.id;
    }

    /**
     * The latest picture; new ones are only asked for while the screen can really be seen
     * ({@code seen}: in front of it, not a shader's shadow pass).
     */
    static @Nullable Identifier picture(BlockPos pos, boolean seen) {
        return seen ? picture(pos) : cachedPicture(pos);
    }

    /** {@link #ownPicture}, only while the display can really be seen. */
    static @Nullable Identifier ownPicture(BlockPos pos, boolean seen) {
        return seen ? ownPicture(pos) : cachedPicture(pos);
    }

    /** The latest picture already here, without asking for more. */
    static @Nullable Identifier cachedPicture(BlockPos pos) {
        Shown shown = SHOWN.get(pos);
        if (shown == null || shown.texture == null) return null;
        shown.lastUsed = System.currentTimeMillis();
        return shown.id;
    }

    /** Drops the picture of a terminal (it was switched off: after starting again it shows its desktop). */
    static void forget(BlockPos pos) {
        Shown shown = SHOWN.remove(pos);
        if (shown != null && shown.texture != null) Minecraft.getInstance().getTextureManager().release(shown.id);
        BASES.remove(pos);
    }

    private static void sendWatch(BlockPos pos) {
        ClientPacketDistributor.sendToServer(new PaperNetwork.TerminalWatch(pos, (float) EnPlotifyConfig.terminalFps(),
                EnPlotifyConfig.terminalResolution().ordinal()));
    }

    private static final PaperNetwork.Pieces<BlockPos> PIECES = new PaperNetwork.Pieces<>();

    /** A piece of a picture from the server; the whole picture is shown once all pieces are here. */
    public static void onShotPiece(PaperNetwork.TerminalShotData piece) {
        byte[] whole = PIECES.add(piece.pos().immutable(), piece.jpeg(), piece.index(), piece.total());
        if (whole != null) onShot(piece.pos(), whole);
    }

    /** A picture arrived: decoded off the main thread (JPEG, which Minecraft's own reader refuses), then shown. */
    /** Pictures waiting to be read, per terminal: only the newest is kept (older ones would be shown for nothing). */
    private static final Map<BlockPos, byte[]> TO_DECODE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void onShot(BlockPos pos, byte[] jpeg) {
        BlockPos key = pos.immutable();
        if (TO_DECODE.put(key, jpeg) != null) return; // one is already waiting: it takes this newer one
        DECODER.execute(() -> {
            byte[] data = TO_DECODE.remove(key);
            if (data == null) return;
            int[] grid = gridOf(data);
            NativeImage image;
            try {
                image = decode(data);
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.debug("EnPlotify: couldn't read a terminal picture", e);
                return;
            }
            if (image == null) return;
            Minecraft.getInstance().execute(() -> {
                show(key, image);
                Shown shown = SHOWN.get(key);
                if (shown != null) shown.grid = grid;
            });
        });
    }

    private static @Nullable NativeImage decode(byte[] jpeg) throws java.io.IOException {
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        if (decoded == null) return null;
        int w = decoded.getWidth(), h = decoded.getHeight();
        if (decoded.getType() == BufferedImage.TYPE_3BYTE_BGR
                && decoded.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferByte bytes
                && bytes.getData().length >= w * h * 3) {
            byte[] d = bytes.getData();
            int[] px = new int[w * h];
            for (int i = 0, j = 0; i < px.length; i++, j += 3) {
                // B, G, R → the image's ABGR
                px[i] = 0xFF000000 | (d[j] & 0xFF) << 16 | (d[j + 1] & 0xFF) << 8 | (d[j + 2] & 0xFF);
            }
            NativeImage image = new NativeImage(w, h, false);
            org.lwjgl.system.MemoryUtil.memIntBuffer(image.getPointer(), px.length).put(px);
            return image;
        }
        int[] rgb = decoded.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < rgb.length; i++) rgb[i] &= 0xFFFFFF;
        return imageOf(rgb, w, h);
    }

    private static void show(BlockPos key, NativeImage image) {
        Shown shown = SHOWN.computeIfAbsent(key, p -> new Shown(Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID,
                "terminal_picture/" + p.getX() + "_" + p.getY() + "_" + p.getZ()).withPath(path -> path.replace('-', 'm'))));
        shown.lastUsed = System.currentTimeMillis();
        if (shown.texture != null && shown.texture.getPixels() != null
                && shown.texture.getPixels().getWidth() == image.getWidth() && shown.texture.getPixels().getHeight() == image.getHeight()) {
            shown.texture.setPixels(image); // the old one is closed by it
            shown.texture.upload();
            return;
        }
        if (shown.texture != null) Minecraft.getInstance().getTextureManager().release(shown.id);
        shown.texture = new DynamicTexture(() -> "enplotify terminal picture", image);
        Minecraft.getInstance().getTextureManager().register(shown.id, shown.texture);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (++ticks % 20 != 0) return;
        long now = System.currentTimeMillis();
        // Renew the watches of terminals still being drawn; forget the rest.
        WATCHED.entrySet().removeIf(e -> now - e.getValue() > 1500);
        for (BlockPos pos : WATCHED.keySet()) sendWatch(pos);
        SHOWN.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUsed < 10000) return false;
            if (e.getValue().texture != null) Minecraft.getInstance().getTextureManager().release(e.getValue().id);
            return true;
        });
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (Shown shown : SHOWN.values()) {
            if (shown.texture != null) Minecraft.getInstance().getTextureManager().release(shown.id);
        }
        SHOWN.clear();
        WATCHED.clear();
        RATES.clear();
        RESOLUTIONS.clear();
        PIECES.clear();
        LAST_CAPTURE.clear();
        BASES.clear();
        SENT_SEQ.clear();
        SHOWN_SEQ.clear();
        TO_DECODE.clear();
        LOCAL.clear();
    }

    private TerminalShots() {}
}
