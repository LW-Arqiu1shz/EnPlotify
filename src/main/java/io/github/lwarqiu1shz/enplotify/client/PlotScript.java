package io.github.lwarqiu1shz.enplotify.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A story loaded from resources: {@code assets/<ns>/plots/<path>/<language>.txt} (falling back to
 * en_us, then to the language key {@code <ns>.plot.<path>}) plus the optional {@code plot.json}.
 *
 * @param pages        page texts; a page may contain {@code \n} for a manual line break
 * @param logo         texture shown after the last page, or null
 * @param logoWidth    pixel size of the logo texture
 * @param zoomLastPage the last page rushes towards the screen before the logo
 */
record PlotScript(List<String> pages, Identifier logo, int logoWidth, int logoHeight, boolean zoomLastPage) {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    /** An empty line (possibly holding spaces) separates two pages. */
    private static final Pattern PAGE_BREAK = Pattern.compile("\\n[ \\t\\u3000]*\\n");

    static PlotScript load(Identifier plot, java.util.Map<String, net.minecraft.network.chat.Component> vars) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resources = minecraft.getResourceManager();
        String base = "plots/" + plot.getPath() + "/";

        String text = null;
        // Every Chinese variant (zh_tw, zh_hk, …) falls back to Simplified Chinese before English.
        String selected = minecraft.getLanguageManager().getSelected();
        List<String> languages = new ArrayList<>();
        languages.add(selected);
        if (selected.startsWith("zh_") && !selected.equals("zh_cn")) languages.add("zh_cn");
        if (!selected.equals("en_us")) languages.add("en_us");
        for (String language : languages) {
            text = readText(resources, Identifier.fromNamespaceAndPath(plot.getNamespace(), base + language + ".txt"));
            if (text != null) break;
        }
        if (text == null) {
            String key = plot.getNamespace() + ".plot." + plot.getPath().replace('/', '.');
            if (I18n.exists(key)) text = I18n.get(key);
        }
        if (text == null) {
            LOGGER.warn("EnPlotify: no text found for story {} (looked for assets/{}/{}<language>.txt)",
                    plot, plot.getNamespace(), base);
            return null;
        }
        // {name} → its value, in this player's language.
        for (var entry : vars.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue().getString());
        List<String> pages = splitPages(text);
        if (pages.isEmpty()) return null;

        Identifier logo = null;
        int logoWidth = 0;
        int logoHeight = 0;
        boolean zoom = false;
        JsonObject settings = readJson(resources, Identifier.fromNamespaceAndPath(plot.getNamespace(), base + "plot.json"));
        if (settings != null) {
            JsonElement zoomElement = settings.get("zoom_last_page");
            if (zoomElement != null && zoomElement.isJsonPrimitive()) zoom = zoomElement.getAsBoolean();
            JsonElement logoElement = settings.get("logo");
            if (logoElement != null && logoElement.isJsonPrimitive()) {
                Identifier candidate = Identifier.tryParse(logoElement.getAsString());
                if (candidate == null) {
                    LOGGER.warn("EnPlotify: story {} has an invalid logo id '{}'", plot, logoElement.getAsString());
                } else {
                    Optional<Resource> image = resources.getResource(candidate);
                    if (image.isEmpty()) {
                        LOGGER.warn("EnPlotify: logo {} of story {} not found", candidate, plot);
                    } else {
                        try (InputStream in = image.get().open(); NativeImage pixels = NativeImage.read(in)) {
                            logo = candidate;
                            logoWidth = pixels.getWidth();
                            logoHeight = pixels.getHeight();
                        } catch (IOException e) {
                            LOGGER.warn("EnPlotify: could not read logo {} of story {}", candidate, plot, e);
                        }
                    }
                }
            }
        }
        return new PlotScript(List.copyOf(pages), logo, logoWidth, logoHeight, zoom);
    }

    /** Two line breaks start a new page; a single one stays inside the page as a manual line break. */
    static List<String> splitPages(String text) {
        String normalized = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        List<String> pages = new ArrayList<>();
        for (String page : PAGE_BREAK.split(normalized)) {
            String trimmed = page.strip();
            if (!trimmed.isEmpty()) pages.add(trimmed);
        }
        return pages;
    }

    private static String readText(ResourceManager resources, Identifier file) {
        Optional<Resource> resource = resources.getResource(file);
        if (resource.isEmpty()) return null;
        try (BufferedReader reader = resource.get().openAsReader()) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) out.append(buffer, 0, read);
            return out.toString();
        } catch (IOException e) {
            LOGGER.warn("EnPlotify: could not read {}", file, e);
            return null;
        }
    }

    private static JsonObject readJson(ResourceManager resources, Identifier file) {
        Optional<Resource> resource = resources.getResource(file);
        if (resource.isEmpty()) return null;
        try (BufferedReader reader = resource.get().openAsReader()) {
            JsonElement json = JsonParser.parseReader(reader);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (Exception e) {
            LOGGER.warn("EnPlotify: could not read {}", file, e);
            return null;
        }
    }
}
