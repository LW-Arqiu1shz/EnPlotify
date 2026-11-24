package io.github.lwarqiu1shz.enplotify.paper;

import org.jspecify.annotations.Nullable;

/**
 * A quick look through a GIF file's blocks (no decoding): its size, number of frames and how long
 * one loop lasts. Used by the server to check uploads and by clients to tell animations from pictures.
 */
public record GifInfo(int width, int height, int frames, int centiseconds) {
    public static boolean isGif(byte[] data) {
        return data != null && data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
    }

    /** Frame delays below 2 cs are shown as 10 cs (what browsers do too). */
    public static int effectiveDelay(int centiseconds) {
        return centiseconds < 2 ? 10 : centiseconds;
    }

    public static @Nullable GifInfo read(byte[] d) {
        if (!isGif(d) || d.length < 13) return null;
        try {
            int width = u16(d, 6), height = u16(d, 8);
            int packed = d[10] & 0xFF;
            int at = 13;
            if ((packed & 0x80) != 0) at += 3 * (1 << ((packed & 7) + 1));
            int frames = 0, total = 0, delay = 0;
            while (at < d.length) {
                int block = d[at++] & 0xFF;
                if (block == 0x3B) break;
                if (block == 0x21) {
                    int label = d[at++] & 0xFF;
                    if (label == 0xF9 && (d[at] & 0xFF) >= 4) delay = u16(d, at + 2);
                    at = skipSubBlocks(d, at);
                } else if (block == 0x2C) {
                    int localPacked = d[at + 8] & 0xFF;
                    at += 9;
                    if ((localPacked & 0x80) != 0) at += 3 * (1 << ((localPacked & 7) + 1));
                    at++; // LZW minimum code size
                    at = skipSubBlocks(d, at);
                    frames++;
                    total += effectiveDelay(delay);
                    delay = 0;
                } else {
                    return null;
                }
            }
            return frames > 0 ? new GifInfo(width, height, frames, total) : null;
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static int skipSubBlocks(byte[] d, int at) {
        while (true) {
            int size = d[at++] & 0xFF;
            if (size == 0) return at;
            at += size;
        }
    }

    private static int u16(byte[] d, int at) {
        return (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8);
    }
}
