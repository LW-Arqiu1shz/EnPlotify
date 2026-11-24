package io.github.lwarqiu1shz.enplotify.paper;

import io.github.lwarqiu1shz.enplotify.EnPlotify;
import io.github.lwarqiu1shz.enplotify.EnPlotifyServerConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Pictures players upload, kept with the world in {@code <world>/enplotify/images/<sha256>.png}.
 * Items only store the hash; clients download a picture the first time they need to draw it.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID)
public final class ImageStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static final LevelResource IMAGES = new LevelResource("enplotify/images");
    /** Largest picture side accepted (8 cells of 256 pixels). */
    public static final int MAX_SIDE = 2048;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

    private static final class Upload {
        final int id;
        final byte[][] chunks;
        int received;

        Upload(int id, int total) {
            this.id = id;
            this.chunks = new byte[total][];
        }
    }

    static void receiveChunk(ServerPlayer player, PaperNetwork.UploadChunk chunk) {
        if (!EnPlotifyServerConfig.allowUploads()) {
            refuse(player, chunk.uploadId(), "message.enplotify.upload.disabled");
            return;
        }
        int maxBytes = Math.max(EnPlotifyServerConfig.maxUploadBytes(), EnPlotifyServerConfig.maxAnimationBytes());
        int maxChunks = (int) Math.ceil(maxBytes / (double) PaperNetwork.UPLOAD_CHUNK);
        if (chunk.total() <= 0 || chunk.total() > maxChunks) {
            UPLOADS.remove(player.getUUID());
            refuse(player, chunk.uploadId(), "message.enplotify.upload.too_large");
            return;
        }
        Upload upload = UPLOADS.get(player.getUUID());
        if (upload == null || upload.id != chunk.uploadId() || upload.chunks.length != chunk.total()) {
            upload = new Upload(chunk.uploadId(), chunk.total());
            UPLOADS.put(player.getUUID(), upload);
        }
        if (chunk.index() < 0 || chunk.index() >= chunk.total() || upload.chunks[chunk.index()] != null) return;
        upload.chunks[chunk.index()] = chunk.data();
        upload.received++;
        if (upload.received < upload.chunks.length) return;

        UPLOADS.remove(player.getUUID());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : upload.chunks) joined.writeBytes(part);
        byte[] png = joined.toByteArray();
        boolean gif = GifInfo.isGif(png);
        if (png.length > (gif ? EnPlotifyServerConfig.maxAnimationBytes() : EnPlotifyServerConfig.maxUploadBytes())) {
            refuse(player, upload.id, "message.enplotify.upload.too_large");
            return;
        }
        String problem = gif ? checkGif(png) : isAcceptablePng(png) ? null : "message.enplotify.upload.invalid";
        if (problem != null) {
            refuse(player, upload.id, problem);
            return;
        }
        String hash = sha256(png);
        try {
            Path file = imagePath(player.level().getServer(), hash, gif);
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.write(file, png);
            }
        } catch (IOException e) {
            LOGGER.warn("EnPlotify: could not save an uploaded picture", e);
            refuse(player, upload.id, "message.enplotify.upload.failed");
            return;
        }
        PacketDistributor.sendToPlayer(player, new PaperNetwork.UploadResult(upload.id, hash, ""));
    }

    private static void refuse(ServerPlayer player, int uploadId, String reason) {
        PacketDistributor.sendToPlayer(player, new PaperNetwork.UploadResult(uploadId, "", reason));
    }

    static void sendImage(ServerPlayer player, String hash) {
        if (!PaperContents.isImageHash(hash)) return;
        byte[] png;
        try {
            Path file = imagePath(player.level().getServer(), hash, false);
            if (!Files.exists(file)) file = imagePath(player.level().getServer(), hash, true);
            png = Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            png = null;
        }
        if (png == null) {
            PacketDistributor.sendToPlayer(player, new PaperNetwork.ImageData(hash, 0, 0, new byte[0]));
            return;
        }
        int total = Math.max(1, (png.length + PaperNetwork.DOWNLOAD_CHUNK - 1) / PaperNetwork.DOWNLOAD_CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * PaperNetwork.DOWNLOAD_CHUNK;
            byte[] part = Arrays.copyOfRange(png, from, Math.min(png.length, from + PaperNetwork.DOWNLOAD_CHUNK));
            PacketDistributor.sendToPlayer(player, new PaperNetwork.ImageData(hash, i, total, part));
        }
    }

    private static Path imagePath(MinecraftServer server, String hash, boolean gif) {
        return server.getWorldPath(IMAGES).resolve(hash + (gif ? ".gif" : ".png"));
    }

    /** Animated poster pictures: a GIF within the size and length limits. Null when fine. */
    private static String checkGif(byte[] data) {
        GifInfo info = GifInfo.read(data);
        if (info == null || info.width() <= 0 || info.height() <= 0) return "message.enplotify.upload.invalid";
        if (info.width() > MAX_SIDE || info.height() > MAX_SIDE) return "message.enplotify.upload.too_large";
        int seconds = EnPlotifyServerConfig.maxAnimationSeconds();
        if (info.centiseconds() > seconds * 100 + 100 || info.frames() > seconds * 25 + 25) return "message.enplotify.upload.too_long";
        return null;
    }

    /** PNG signature, then the IHDR size: both sides within {@link #MAX_SIDE}. */
    private static boolean isAcceptablePng(byte[] png) {
        if (png.length < 24) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) return false;
        }
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return false;
        int width = readInt(png, 16);
        int height = readInt(png, 20);
        return width > 0 && height > 0 && width <= MAX_SIDE && height <= MAX_SIDE;
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UPLOADS.remove(event.getEntity().getUUID());
        PaperNetwork.forget(event.getEntity().getUUID());
    }

    private ImageStore() {}
}
