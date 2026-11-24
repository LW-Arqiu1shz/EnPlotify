package io.github.lwarqiu1shz.enplotify;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world server settings (serverconfig/enplotify-server.toml). */
public final class EnPlotifyServerConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ALLOW_UPLOADS;
    public static final ModConfigSpec.IntValue MAX_UPLOAD_KB;
    public static final ModConfigSpec.IntValue MAX_ANIMATION_SECONDS;
    public static final ModConfigSpec.IntValue MAX_ANIMATION_KB;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ALLOW_UPLOADS = builder
                .comment("Whether players may upload pictures for posters, newspapers and documents.")
                .translation("config.enplotify.allow_uploads")
                .define("allow_uploads", true);
        MAX_UPLOAD_KB = builder
                .comment("Largest picture a player may upload, in KB (after the game shrinks and converts it to PNG).")
                .translation("config.enplotify.max_upload_kb")
                .defineInRange("max_upload_kb", 2048, 64, 16384);
        MAX_ANIMATION_SECONDS = builder
                .comment("Longest GIF / video on a poster, in seconds (longer videos are cut).")
                .translation("config.enplotify.max_animation_seconds")
                .defineInRange("max_animation_seconds", 60, 1, 600);
        MAX_ANIMATION_KB = builder
                .comment("Largest animated poster picture a player may upload, in KB.")
                .translation("config.enplotify.max_animation_kb")
                .defineInRange("max_animation_kb", 32768, 256, 262144);
        SPEC = builder.build();
    }

    public static boolean allowUploads() {
        try {
            return ALLOW_UPLOADS.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static int maxUploadBytes() {
        try {
            return MAX_UPLOAD_KB.get() * 1024;
        } catch (IllegalStateException e) {
            return 2048 * 1024;
        }
    }

    public static int maxAnimationSeconds() {
        try {
            return MAX_ANIMATION_SECONDS.get();
        } catch (IllegalStateException e) {
            return 60;
        }
    }

    public static int maxAnimationBytes() {
        try {
            return MAX_ANIMATION_KB.get() * 1024;
        } catch (IllegalStateException e) {
            return 32768 * 1024;
        }
    }

    private EnPlotifyServerConfig() {}
}
