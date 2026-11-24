package io.github.lwarqiu1shz.enplotify.client.paper;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rect;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.DoubleConsumer;

/**
 * Videos to animated GIFs, decoded with JCodec (pure Java; H.264 in MP4 / MOV / MKV). Kept apart
 * from {@link Gifs}, so playing GIFs never loads the video decoder.
 */
final class GifVideo {
    /** A video as an animated GIF, without sound. */
    static Gifs.Result fromVideo(File file, double maxSeconds, long maxBytes, int background, DoubleConsumer progress) throws IOException {
        try (FileChannelWrapper channel = NIOUtils.readableChannel(file);
             Gifs.Encoder encoder = new Gifs.Encoder(maxBytes)) {
            FrameGrab grab;
            try {
                grab = FrameGrab.createFrameGrab(channel);
            } catch (JCodecException e) {
                throw new IOException(e);
            }
            DemuxerTrackMeta.Orientation orientation = null;
            try {
                DemuxerTrackMeta meta = grab.getVideoTrack().getMeta();
                if (meta != null) orientation = meta.getOrientation();
            } catch (RuntimeException ignored) {
                // no orientation: keep as stored
            }
            Gifs.Resampler resampler = new Gifs.Resampler(encoder, maxSeconds, background, progress);
            PictureWithMetadata picture;
            int decoded = 0;
            while ((picture = grab.getNativeFrameWithMetadata()) != null) {
                decoded++;
                double start = picture.getTimestamp();
                double duration = picture.getDuration() > 0 ? picture.getDuration() : 1.0 / 30.0;
                if (resampler.wants(start, start + duration)) {
                    resampler.add(rotate(toImage(picture.getPicture()), orientation), start, start + duration);
                }
                // Past the limit (with some room for frames that arrive out of order): stop decoding.
                if (resampler.cut && start * Gifs.FPS > resampler.maxTicks + 2 * Gifs.FPS) break;
            }
            if (decoded == 0) throw new IOException("no video frames");
            resampler.finish();
            return new Gifs.Result(encoder.finish(), resampler.cut);
        }
    }

    /** A decoded frame (any of JCodec's colour spaces) as an RGB picture, cropped to its visible part. */
    private static BufferedImage toImage(Picture source) {
        Picture rgb = source;
        if (source.getColor() != ColorSpace.RGB) {
            Transform transform = ColorUtil.getTransform(source.getColor(), ColorSpace.RGB);
            rgb = Picture.createCropped(source.getWidth(), source.getHeight(), ColorSpace.RGB, source.getCrop());
            transform.transform(source, rgb);
        }
        Rect crop = rgb.getCrop();
        int cx = crop == null ? 0 : crop.getX(), cy = crop == null ? 0 : crop.getY();
        int w = rgb.getCroppedWidth(), h = rgb.getCroppedHeight(), stride = rgb.getWidth();
        byte[] data = rgb.getPlaneData(0);
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int from = ((y + cy) * stride + cx) * 3;
            for (int x = 0; x < w; x++, from += 3) {
                // JCodec keeps samples as signed bytes centred on 0.
                int r = data[from] + 128, g = data[from + 1] + 128, b = data[from + 2] + 128;
                pixels[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        return image;
    }

    /** Turns phone videos filmed upright the right way round. */
    private static BufferedImage rotate(BufferedImage image, DemuxerTrackMeta.Orientation orientation) {
        if (orientation == null || orientation == DemuxerTrackMeta.Orientation.D_0) return image;
        int w = image.getWidth(), h = image.getHeight();
        boolean quarter = orientation != DemuxerTrackMeta.Orientation.D_180;
        BufferedImage out = new BufferedImage(quarter ? h : w, quarter ? w : h, BufferedImage.TYPE_INT_RGB);
        AffineTransform t = new AffineTransform();
        if (orientation == DemuxerTrackMeta.Orientation.D_90) {
            t.translate(h, 0);
            t.rotate(Math.PI / 2);
        } else if (orientation == DemuxerTrackMeta.Orientation.D_180) {
            t.translate(w, h);
            t.rotate(Math.PI);
        } else {
            t.translate(0, w);
            t.rotate(-Math.PI / 2);
        }
        Graphics2D g = out.createGraphics();
        g.drawImage(image, t, null);
        g.dispose();
        return out;
    }

    private GifVideo() {}
}
