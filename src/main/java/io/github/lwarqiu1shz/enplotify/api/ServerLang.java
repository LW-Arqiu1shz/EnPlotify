package io.github.lwarqiu1shz.enplotify.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every mod's language files read on the server ({@code assets/<mod id>/lang/<lang>.json}), so text
 * can be measured in the language each player plays in. Selected language, then zh_cn for any
 * zh_*, then en_us.
 */
public final class ServerLang {
    private static final Map<String, Map<String, String>> FILES = new HashMap<>();

    /** The player's language, as the client reported it ("zh_cn", "en_us", ...). */
    public static String language(ServerPlayer player) {
        String lang = player.clientInformation().language();
        return lang == null ? "en_us" : lang.toLowerCase(Locale.ROOT);
    }

    public static boolean chinese(ServerPlayer player) {
        return language(player).startsWith("zh");
    }

    /** The text as this player would read it (plain translatable keys are looked up; anything else as is). */
    public static String resolve(ServerPlayer player, Component component) {
        if (component.getContents() instanceof TranslatableContents tr && tr.getArgs().length == 0 && component.getSiblings().isEmpty()) {
            String lang = language(player);
            String found = file(lang).get(tr.getKey());
            if (found == null && lang.startsWith("zh")) found = file("zh_cn").get(tr.getKey());
            if (found == null) found = file("en_us").get(tr.getKey());
            if (found == null) found = tr.getFallback() != null ? tr.getFallback() : tr.getKey();
            return found;
        }
        return component.getString();
    }

    private static synchronized Map<String, String> file(String lang) {
        return FILES.computeIfAbsent(lang, ServerLang::load);
    }

    private static Map<String, String> load(String lang) {
        Map<String, String> out = new HashMap<>();
        for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
            for (IModInfo mod : fileInfo.getMods()) {
                String path = "assets/" + mod.getModId() + "/lang/" + lang + ".json";
                try {
                    var contents = fileInfo.getFile().getContents();
                    if (!contents.containsFile(path)) continue;
                    try (InputStream in = contents.openFile(path)) {
                        JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                        for (var entry : json.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) out.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger("EnPlotify").warn("EnPlotify: could not read {}", path, e);
                }
            }
        }
        return out;
    }

    private ServerLang() {}
}
