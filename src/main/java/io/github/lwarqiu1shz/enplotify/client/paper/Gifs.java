package io.github.lwarqiu1shz.enplotify.client.paper;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleConsumer;

/**
 * Animated poster pictures. Whatever comes in (an animated GIF, a video) is turned into a GIF of at
 * most {@link #FPS} frames per second, at most {@link #MAX_SIDE} pixels wide or high, cut after the
 * server's time limit. Only the part of each frame that changed is stored, which keeps videos small.
 * <p>
 * {@link Decoder} also plays GIFs back, frame by frame, without holding the whole animation in memory.
 */
final class Gifs {
    static final int FPS = 24;
    static final int MAX_SIDE = 320;

    /** The conversion went past the server's size limit. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("animation too large");
        }
    }

    /** What a conversion produced. */
    record Result(byte[] gif, boolean cut) {}

    // ------------------------------------------------------------------ reading GIFs

    /** One composited frame: the whole picture (ARGB, reused between frames) and how long it shows. */
    record Frame(BufferedImage image, int delayMs) {}

    /** Reads a GIF one full frame at a time (applying each frame's disposal), looping when asked. */
    static final class Decoder implements Closeable {
        private final byte[] data;
        private @Nullable ImageReader reader;
        private @Nullable ImageInputStream in;
        private final BufferedImage canvas;
        private @Nullable BufferedImage saved;
        private int index;
        private String lastDisposal = "none";
        private int lastX, lastY, lastW, lastH;

        Decoder(byte[] data) throws IOException {
            this.data = data;
            open();
            Node stream = reader.getStreamMetadata() == null ? null
                    : reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
            int w = 0, h = 0;
            Node lsd = child(stream, "LogicalScreenDescriptor");
            if (lsd != null) {
                w = intAttr(lsd, "logicalScreenWidth", 0);
                h = intAttr(lsd, "logicalScreenHeight", 0);
            }
            if (w <= 0 || h <= 0) {
                w = reader.getWidth(0);
                h = reader.getHeight(0);
            }
            canvas = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        }

        private void open() throws IOException {
            close();
            in = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("no GIF reader");
            reader = readers.next();
            reader.setInput(in, false);
            index = 0;
            lastDisposal = "none";
            saved = null;
        }

        int width() {
            return canvas.getWidth();
        }

        int height() {
            return canvas.getHeight();
        }

        /** The next frame, or null at the end. The image is shared: copy it to keep it. */
        @Nullable Frame next() throws IOException {
            BufferedImage part;
            IIOMetadata meta;
            try {
                part = reader.read(index);
                meta = reader.getImageMetadata(index);
            } catch (IndexOutOfBoundsException e) {
                return null;
            } catch (IOException | RuntimeException e) {
                // A damaged frame ends the animation early rather than failing it.
                if (index == 0) throw e instanceof IOException io ? io : new IOException(e);
                return null;
            }
            index++;
            Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
            Node descriptor = child(root, "ImageDescriptor");
            Node control = child(root, "GraphicControlExtension");
            int x = intAttr(descriptor, "imageLeftPosition", 0), y = intAttr(descriptor, "imageTopPosition", 0);
            String disposal = control == null ? "none" : attr(control, "disposalMethod", "none");
            int delay = control == null ? 0 : intAttr(control, "delayTime", 0);

            Graphics2D g = canvas.createGraphics();
            try {
                if (lastDisposal.equals("restoreToBackgroundColor")) {
                    g.setComposite(AlphaComposite.Clear);
                    g.fillRect(lastX, lastY, lastW, lastH);
                    g.setComposite(AlphaComposite.SrcOver);
                } else if (lastDisposal.equals("restoreToPrevious") && saved != null) {
                    g.setComposite(AlphaComposite.Src);
                    g.drawImage(saved, 0, 0, null);
                    g.setComposite(AlphaComposite.SrcOver);
                }
                if (disposal.equals("restoreToPrevious")) {
                    if (saved == null) saved = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D s = saved.createGraphics();
                    s.setComposite(AlphaComposite.Src);
                    s.drawImage(canvas, 0, 0, null);
                    s.dispose();
                }
                g.drawImage(part, x, y, null);
            } finally {
                g.dispose();
            }
            lastDisposal = disposal;
            lastX = x;
            lastY = y;
            lastW = part.getWidth();
            lastH = part.getHeight();
            return new Frame(canvas, io.github.lwarqiu1shz.enplotify.paper.GifInfo.effectiveDelay(delay) * 10);
        }

        /** Starts over from the first frame. */
        void restart() throws IOException {
            open();
            Graphics2D g = canvas.createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.dispose();
        }

        @Override
        public void close() {
            if (reader != null) reader.dispose();
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            reader = null;
            in = null;
        }
    }

    private static @Nullable Node child(@Nullable Node parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name)) return n;
        }
        return null;
    }

    private static String attr(Node node, String name, String fallback) {
        Node a = node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return a == null ? fallback : a.getNodeValue();
    }

    private static int intAttr(@Nullable Node node, String name, int fallback) {
        if (node == null) return fallback;
        try {
            return Integer.parseInt(attr(node, name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ writing GIFs

    /** Writes frames as a looping GIF; each frame after the first only stores the box that changed. */
    static final class Encoder implements Closeable {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ImageOutputStream out;
        private final ImageWriter writer;
        private final ImageWriteParam param;
        private final long maxBytes;
        private int @Nullable [] previous;
        private boolean first = true;

        Encoder(long maxBytes) throws IOException {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
            if (!writers.hasNext()) throw new IOException("no GIF writer");
            writer = writers.next();
            param = writer.getDefaultWriteParam();
            out = ImageIO.createImageOutputStream(bytes);
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            this.maxBytes = maxBytes;
        }

        /** Adds an RGB frame shown for {@code delayCs} hundredths of a second. */
        void add(BufferedImage frame, int delayCs) throws IOException {
            int w = frame.getWidth(), h = frame.getHeight();
            int[] pixels = frame.getRGB(0, 0, w, h, null, 0, w);
            int x0 = 0, y0 = 0, x1 = w - 1, y1 = h - 1;
            if (previous != null) {
                x0 = w; y0 = h; x1 = -1; y1 = -1;
                for (int y = 0; y < h; y++) {
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        if (pixels[row + x] != previous[row + x]) {
                            if (x < x0) x0 = x;
                            if (x > x1) x1 = x;
                            if (y < y0) y0 = y;
                            if (y > y1) y1 = y;
                        }
                    }
                }
                if (x1 < 0) {
                    // Nothing changed: a one-pixel frame keeps the timing.
                    x0 = x1 = 0;
                    y0 = y1 = 0;
                }
            }
            previous = pixels;
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            BufferedImage part = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
            part.setRGB(0, 0, bw, bh, pixels, y0 * w + x0, w);

            IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB), param);
            String format = meta.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
            IIOMetadataNode control = node(root, "GraphicControlExtension");
            control.setAttribute("disposalMethod", "doNotDispose");
            control.setAttribute("userInputFlag", "FALSE");
            control.setAttribute("transparentColorFlag", "FALSE");
            control.setAttribute("delayTime", String.valueOf(Math.max(2, delayCs)));
            control.setAttribute("transparentColorIndex", "0");
            IIOMetadataNode descriptor = node(root, "ImageDescriptor");
            descriptor.setAttribute("imageLeftPosition", String.valueOf(x0));
            descriptor.setAttribute("imageTopPosition", String.valueOf(y0));
            descriptor.setAttribute("imageWidth", String.valueOf(bw));
            descriptor.setAttribute("imageHeight", String.valueOf(bh));
            descriptor.setAttribute("interlaceFlag", "FALSE");
            if (first) {
                IIOMetadataNode apps = node(root, "ApplicationExtensions");
                IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
                loop.setAttribute("applicationID", "NETSCAPE");
                loop.setAttribute("authenticationCode", "2.0");
                loop.setUserObject(new byte[] {1, 0, 0});
                apps.appendChild(loop);
            }
            meta.setFromTree(format, root);
            if (first) {
                // The first frame is the full picture, so it also sets the animation's size.
                writer.writeToSequence(new IIOImage(part, null, meta), param);
                first = false;
            } else {
                writer.writeToSequence(new IIOImage(part, null, meta), param);
            }
            out.flush();
            if (bytes.size() > maxBytes) throw new TooLarge();
        }

        byte[] finish() throws IOException {
            writer.endWriteSequence();
            out.flush();
            return bytes.toByteArray();
        }

        @Override
        public void close() {
            writer.dispose();
            try {
                out.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }

        private static IIOMetadataNode node(IIOMetadataNode root, String name) {
            for (int i = 0; i < root.getLength(); i++) {
                if (root.item(i).getNodeName().equals(name)) return (IIOMetadataNode) root.item(i);
            }
            IIOMetadataNode n = new IIOMetadataNode(name);
            root.appendChild(n);
            return n;
        }
    }

    // ------------------------------------------------------------------ resampling to 24 frames a second

    /**
     * Takes frames with their times (in any order, as videos may deliver them) and writes one frame
     * per 1/24 s tick to the encoder, merging ticks that show the same picture into one longer frame.
     */
    static final class Resampler {
        private final Encoder encoder;
        final int maxTicks;
        private final int background;
        private final DoubleConsumer progress;
        private int width, height;
        /** Frames waiting for the ticks before them, by first tick: {image, tick count}. */
        private final TreeMap<Integer, Object[]> waiting = new TreeMap<>();
        private int nextTick;
        private @Nullable BufferedImage pending;
        private int @Nullable [] pendingPixels;
        private int pendingStart, pendingTicks;
        boolean cut;

        Resampler(Encoder encoder, double maxSeconds, int background, DoubleConsumer progress) {
            this.encoder = encoder;
            this.maxTicks = (int) Math.floor(maxSeconds * FPS);
            this.background = background;
            this.progress = progress;
        }

        private int firstTick(double start) {
            return (int) Math.ceil(start * FPS - 1.0E-6);
        }

        private int endTick(double end) {
            return Math.min(maxTicks, (int) Math.ceil(end * FPS - 1.0E-6));
        }

        /** True if a frame shown from {@code start} to {@code end} seconds would be used. */
        boolean wants(double start, double end) {
            if (start * FPS >= maxTicks) {
                cut = true;
                return false;
            }
            return endTick(end) > Math.max(firstTick(start), 0) && firstTick(start) >= nextTick;
        }

        boolean full() {
            return nextTick >= maxTicks;
        }

        /** Adds a frame (any size; it is scaled down and flattened onto the poster colour). */
        void add(BufferedImage source, double start, double end) throws IOException {
            if (!wants(start, end)) return;
            int a = Math.max(firstTick(start), 0), b = endTick(end);
            if (width == 0) {
                double scale = Math.min(1.0, MAX_SIDE / (double) Math.max(source.getWidth(), source.getHeight()));
                width = Math.max(1, (int) Math.round(source.getWidth() * scale));
                height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            }
            BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = frame.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new Color(background));
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
            g.dispose();
            waiting.put(a, new Object[] {frame, b - a});
            drain(false);
        }

        private void drain(boolean all) throws IOException {
            while (!waiting.isEmpty()) {
                Map.Entry<Integer, Object[]> first = waiting.firstEntry();
                int start = first.getKey();
                if (start > nextTick && !all && waiting.size() < 24) return;
                waiting.pollFirstEntry();
                // A gap before this frame: the previous picture stays up longer.
                if (start > nextTick && pending != null) pendingTicks += start - nextTick;
                emit((BufferedImage) first.getValue()[0], Math.max(start, nextTick), (Integer) first.getValue()[1] - Math.max(0, nextTick - start));
            }
        }

        private void emit(BufferedImage frame, int start, int ticks) throws IOException {
            if (ticks <= 0) return;
            int[] pixels = frame.getRGB(0, 0, frame.getWidth(), frame.getHeight(), null, 0, frame.getWidth());
            if (pending != null && Arrays.equals(pixels, pendingPixels)) {
                pendingTicks += ticks;
            } else {
                flushPending();
                pending = frame;
                pendingPixels = pixels;
                pendingStart = start;
                pendingTicks = ticks;
            }
            nextTick = start + ticks;
            progress.accept(nextTick / (double) FPS);
        }

        private void flushPending() throws IOException {
            if (pending == null) return;
            int a = pendingStart, b = pendingStart + pendingTicks;
            // Centiseconds from tick a to tick b, rounded so the whole animation keeps exact time.
            int delay = Math.round(b * 100.0F / FPS) - Math.round(a * 100.0F / FPS);
            encoder.add(pending, delay);
            pending = null;
        }

        void finish() throws IOException {
            drain(true);
            flushPending();
        }
    }

    // ------------------------------------------------------------------ converters

    /** An animated GIF made to fit the limits. */
    static Result fromGif(byte[] data, double maxSeconds, long maxBytes, int background, DoubleConsumer progress) throws IOException {
        try (Decoder decoder = new Decoder(data); Encoder encoder = new Encoder(maxBytes)) {
            Resampler resampler = new Resampler(encoder, maxSeconds, background, progress);
            double time = 0;
            Frame frame;
            while ((frame = decoder.next()) != null) {
                double end = time + frame.delayMs() / 1000.0;
                if (resampler.wants(time, end)) resampler.add(frame.image(), time, end);
                time = end;
                if (resampler.full()) {
                    if (decoder.next() != null) resampler.cut = true;
                    break;
                }
            }
            resampler.finish();
            return new Result(encoder.finish(), resampler.cut);
        }
    }

    private Gifs() {}
}
