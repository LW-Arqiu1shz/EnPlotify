package io.github.lwarqiu1shz.enplotify.client.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terminal browser tabs remembered on this computer: one file per world (save or server) in
 * config/enplotify/terminal_tabs/, holding per terminal the tabs' addresses and which one was shown.
 * Only addresses: the pages themselves never outlive the game; next time the tabs open them again.
 */
final class TabStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("enplotify").resolve("terminal_tabs");
    }

    /**
     * The world's file: a readable name (the save folder or server address, made safe for a file
     * name) and a short code from the full key, so two worlds with the same name don't share a file.
     */
    private static Path file(String world) {
        String readable = world;
        int colon = world.indexOf(':');
        String kind = colon > 0 ? world.substring(0, colon) : "world";
        String rest = colon > 0 ? world.substring(colon + 1) : world;
        if (kind.equals("save")) {
            Path p = Path.of(rest);
            // .../saves/<name>/. → <name>
            Path name = p.getFileName() != null && p.getFileName().toString().equals(".") ? p.getParent() : p;
            readable = name != null && name.getFileName() != null ? name.getFileName().toString() : rest;
        } else {
            readable = rest;
        }
        readable = readable.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (readable.length() > 48) readable = readable.substring(0, 48);
        String code = Integer.toHexString(world.hashCode());
        return folder().resolve(kind + "-" + readable + "-" + code + ".json");
    }

    private static JsonObject read(Path file) {
        try {
            if (!Files.exists(file)) return new JsonObject();
            JsonElement all = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
            return all != null && all.isJsonObject() ? all.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOGGER.warn("EnPlotify: couldn't read the remembered terminal tabs", e);
            return new JsonObject();
        }
    }

    static void load(String world, Map<BlockPos, List<String>> urls, Map<BlockPos, Integer> active) {
        moveOldFile();
        JsonObject mine = read(file(world));
        JsonElement terminals = mine.get("terminals");
        if (terminals == null || !terminals.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : terminals.getAsJsonObject().entrySet()) {
            try {
                String[] xyz = e.getKey().split(",");
                BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                JsonObject o = e.getValue().getAsJsonObject();
                List<String> list = new ArrayList<>();
                for (JsonElement u : o.getAsJsonArray("urls")) list.add(u.getAsString());
                if (list.isEmpty()) continue;
                urls.put(pos, list);
                active.put(pos, o.has("active") ? o.get("active").getAsInt() : 0);
            } catch (RuntimeException ignored) {
                // a broken entry is skipped
            }
        }
    }

    static void save(String world, Map<BlockPos, List<String>> urls, Map<BlockPos, Integer> active) {
        JsonObject terminals = new JsonObject();
        for (Map.Entry<BlockPos, List<String>> e : urls.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            JsonObject o = new JsonObject();
            JsonArray list = new JsonArray();
            for (String u : e.getValue()) list.add(u);
            o.add("urls", list);
            o.addProperty("active", active.getOrDefault(e.getKey(), 0));
            BlockPos p = e.getKey();
            terminals.add(p.getX() + "," + p.getY() + "," + p.getZ(), o);
        }
        Path file = file(world);
        try {
            if (terminals.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            JsonObject all = new JsonObject();
            all.addProperty("world", world);
            all.add("terminals", terminals);
            Files.createDirectories(folder());
            Files.writeString(file, GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("EnPlotify: couldn't remember the terminal tabs", e);
        }
    }

    /** The first version kept every world in one file right in config/: split it into the folder. */
    private static void moveOldFile() {
        Path old = FMLPaths.CONFIGDIR.get().resolve("enplotify-terminal-tabs.json");
        if (!Files.exists(old)) return;
        JsonObject all = read(old);
        for (Map.Entry<String, JsonElement> world : all.entrySet()) {
            if (!world.getValue().isJsonObject()) continue;
            JsonObject one = new JsonObject();
            one.addProperty("world", world.getKey());
            one.add("terminals", world.getValue());
            try {
                Files.createDirectories(folder());
                Path target = file(world.getKey());
                if (!Files.exists(target)) Files.writeString(target, GSON.toJson(one), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOGGER.warn("EnPlotify: couldn't move the remembered terminal tabs", e);
                return;
            }
        }
        try {
            Files.deleteIfExists(old);
        } catch (Exception ignored) {
            // left for next time
        }
    }

    private TabStore() {}
}
