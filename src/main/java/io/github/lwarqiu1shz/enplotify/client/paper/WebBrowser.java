package io.github.lwarqiu1shz.enplotify.client.paper;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.lwarqiu1shz.enplotify.EnPlotify;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The terminal's web browser. Only there when the optional mod FerricOxide (mod id
 * {@code ferric_oxide}) is installed: it drives the system's own web view (WebView2 on Windows,
 * WKWebView on macOS, WebKitGTK on Linux) off screen and hands over the picture. Reached through
 * reflection, so EnPlotify neither needs nor bundles it.
 * <p>
 * A browser window has tabs. Every open tab owns its own web view (its own native process), so
 * closing a tab really ends that process: the view is destroyed instead of being left running.
 * Tabs that were restored from a previous visit only start their view once they are shown.
 */
@EventBusSubscriber(modid = EnPlotify.MOD_ID, value = Dist.CLIENT)
final class WebBrowser {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static final String VIEW = "dev.anvilcraft.oxide.ferric.webui.OffscreenWebView";
    static final String HOME = "https://cn.bing.com";
    /** Largest page side in px. */
    private static final int MAX_SIDE = 4096;
    /** Rows at the very bottom of the view that carry the address (drawn twice, so one bad row is survivable). */
    private static final int STRIP_ROWS = 2;
    /** Rows above the address strip that carry the page's sound (see the sound script). */
    private static final int AUDIO_ROWS = 6;
    /** Rows below the page in every view. */
    private static final int EXTRA_ROWS = STRIP_ROWS + AUDIO_ROWS;
    /** Most tabs one window may keep. */
    static final int MAX_TABS = 8;
    /**
     * Browser windows of terminals the player is not at, still running: each terminal keeps its own
     * tabs (and page processes) until they are closed (Ctrl+W, the window's x) or let go of.
     */
    private static final java.util.Map<net.minecraft.core.BlockPos, Parked> PARKED = new java.util.HashMap<>();

    private static final class Parked {
        final List<OpenTab> tabs;
        final int active;
        /** Whether the window was the one shown on the terminal when last seen (not minimised). */
        boolean foreground;

        Parked(List<OpenTab> tabs, int active) {
            this.tabs = tabs;
            this.active = active;
        }
    }

    /** Puts the window aside for its terminal (pages keep running). */
    static void park(net.minecraft.core.BlockPos pos, boolean foreground) {
        if (TABS.isEmpty()) return;
        hoverOffAll();
        closeParked(pos);
        Parked parked = new Parked(new ArrayList<>(TABS), activeIndex());
        parked.foreground = foreground;
        PARKED.put(pos.immutable(), parked);
        TABS.clear();
        active = 0;
    }

    /** Takes back a terminal's window put aside earlier; false if there is none. */
    static boolean unpark(net.minecraft.core.BlockPos pos) {
        Parked parked = PARKED.remove(pos);
        if (parked == null) return false;
        closeAll();
        TABS.addAll(parked.tabs);
        active = Math.clamp(parked.active, 0, Math.max(0, TABS.size() - 1));
        return !TABS.isEmpty();
    }

    /** Closes a put-aside window for good (its page processes end). */
    static void closeParked(net.minecraft.core.BlockPos pos) {
        Parked parked = PARKED.remove(pos);
        if (parked == null) return;
        for (OpenTab tab : parked.tabs) {
            if (tab.page != null) {
                tab.page.shutdown();
                tab.page = null;
            }
        }
    }

    static java.util.Set<net.minecraft.core.BlockPos> parked() {
        return java.util.Set.copyOf(PARKED.keySet());
    }

    static boolean parkedForeground(net.minecraft.core.BlockPos pos) {
        Parked parked = PARKED.get(pos);
        return parked != null && parked.foreground;
    }

    static void setParkedForeground(net.minecraft.core.BlockPos pos, boolean foreground) {
        Parked parked = PARKED.get(pos);
        if (parked != null) parked.foreground = foreground;
    }

    /** Every window, shown, put aside or asleep (the game is closing). */
    static void closeEverything() {
        closeAll();
        for (net.minecraft.core.BlockPos pos : parked()) closeParked(pos);
        for (java.util.Map<net.minecraft.core.BlockPos, Parked> world : SLEEPING.values()) {
            for (Parked parked : world.values()) shutdownAll(parked.tabs);
        }
        SLEEPING.clear();
    }

    private static void shutdownAll(List<OpenTab> tabs) {
        for (OpenTab tab : tabs) {
            if (tab.page != null) {
                tab.page.shutdown();
                tab.page = null;
            }
        }
    }

    /**
     * Windows of a world the player left, still running (silent) while the game runs: coming back
     * to that world brings them back as they were. Only one world sleeps at a time.
     */
    private static final java.util.Map<String, java.util.Map<net.minecraft.core.BlockPos, Parked>> SLEEPING = new java.util.HashMap<>();

    /** Leaving a world: its windows go to sleep, muted. */
    static void sleep(String world) {
        if (PARKED.isEmpty()) return;
        for (Parked parked : PARKED.values()) {
            for (OpenTab tab : parked.tabs) {
                if (tab.page != null) {
                    tab.page.call(tab.page.eval, "window.__epGain=0;");
                    tab.page.sentGain = 0.0F;
                    tab.page.hoverOff();
                }
            }
        }
        SLEEPING.computeIfAbsent(world, w -> new java.util.HashMap<>()).putAll(PARKED);
        PARKED.clear();
    }

    /** Entering a world: its sleeping windows wake up; windows of any other world end. */
    static void wake(String world) {
        for (java.util.Map.Entry<String, java.util.Map<net.minecraft.core.BlockPos, Parked>> e : SLEEPING.entrySet()) {
            if (e.getKey().equals(world)) continue;
            for (Parked parked : e.getValue().values()) shutdownAll(parked.tabs);
        }
        java.util.Map<net.minecraft.core.BlockPos, Parked> mine = SLEEPING.remove(world);
        SLEEPING.clear();
        if (mine != null) PARKED.putAll(mine);
    }

    /** The addresses and shown tab of each window put aside, for remembering them on disk. */
    static java.util.Map<net.minecraft.core.BlockPos, Object[]> parkedAddresses() {
        java.util.Map<net.minecraft.core.BlockPos, Object[]> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<net.minecraft.core.BlockPos, Parked> e : PARKED.entrySet()) {
            List<String> urls = new ArrayList<>();
            for (OpenTab tab : e.getValue().tabs) urls.add(tab.url);
            if (!urls.isEmpty()) out.put(e.getKey(), new Object[] {urls, e.getValue().active});
        }
        return out;
    }

    /** The size the terminal wants the page to be: its on-screen size in window pixels. */
    private static int wantW = 960, wantH = 540;

    private static @Nullable Boolean available;
    /** The window's tabs, in order. A tab without a page has not been shown yet. */
    private static final List<OpenTab> TABS = new ArrayList<>();
    private static int active;
    private static int nextId;

    /** One tab: the address it shows last, and its web view once it has been shown. */
    static final class OpenTab {
        String url;
        @Nullable WebBrowser page;
        /**
         * The pages this tab went through, kept here (the web view's own history can't be reached
         * through FerricOxide): "<" and ">" walk this list.
         */
        final List<String> history = new ArrayList<>();
        int at = -1;
        /** A step back or forward under way: the address left, and since when (a redirect may follow). */
        @Nullable String leaving;
        long jumpAt;

        OpenTab(String url) {
            this.url = url;
        }

        /** The page now shows {@code address}. */
        void visited(String address) {
            if (leaving != null) {
                // Our own step: the old page may still report for a moment; the first new address
                // is where the step landed (perhaps redirected), in the entry already there.
                if (address.equals(leaving) && System.currentTimeMillis() - jumpAt < 8000) return;
                leaving = null;
                if (at >= 0 && at < history.size()) history.set(at, address);
                return;
            }
            if (at >= 0 && at < history.size() && history.get(at).equals(address)) return;
            while (history.size() > at + 1) history.removeLast();
            history.add(address);
            if (history.size() > 100) history.removeFirst();
            at = history.size() - 1;
        }

        boolean canBack() {
            return at > 0;
        }

        boolean canForward() {
            return at >= 0 && at < history.size() - 1;
        }

        /** The address one step back (-1) or forward (+1), now the current entry; null if none. */
        @Nullable String step(int by) {
            int to = at + by;
            if (at < 0 || to < 0 || to >= history.size()) return null;
            leaving = history.get(at);
            jumpAt = System.currentTimeMillis();
            at = to;
            return history.get(to);
        }
    }

    // ------------------------------------------------------------------ window state

    static boolean available() {
        if (available == null) {
            available = false;
            try {
                if (ModList.get().isLoaded("ferric_oxide")) {
                    Class<?> view = Class.forName(VIEW, true, WebBrowser.class.getClassLoader());
                    available = (Boolean) view.getMethod("isAvailable").invoke(null);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("EnPlotify: FerricOxide is installed but its web view can't be used", e);
            }
        }
        return available;
    }

    /** Replaces the window's tab list; only the shown tab starts its web view (the others wait for a click). */
    static void prepare(List<String> urls, int activeIndex) {
        closeAll();
        for (String url : urls) TABS.add(new OpenTab(url));
        if (TABS.isEmpty()) TABS.add(new OpenTab(HOME));
        active = Math.clamp(activeIndex, 0, TABS.size() - 1);
    }

    static int tabCount() {
        return TABS.size();
    }

    static int activeIndex() {
        return TABS.isEmpty() ? -1 : Math.clamp(active, 0, TABS.size() - 1);
    }

    static String activeUrl() {
        return TABS.isEmpty() ? HOME : TABS.get(activeIndex()).url;
    }

    static String urlAt(int index) {
        return index < 0 || index >= TABS.size() ? HOME : TABS.get(index).url;
    }

    /** What a tab shows in the tab strip: the site's host, or "New tab" before anything is loaded. */
    static String titleAt(int index) {
        String host = hostOf(urlAt(index));
        return host.isEmpty() ? I18n.get("terminal.enplotify.browser_new_tab") : host;
    }

    private static String hostOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return "";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The addresses of every tab, in order (used to reopen the same tabs later). */
    static List<String> urls() {
        List<String> out = new ArrayList<>(TABS.size());
        for (OpenTab tab : TABS) out.add(tab.url);
        return out;
    }

    /** Makes another tab the shown one; its view is started the first time it is shown. */
    static void select(int index) {
        if (index < 0 || index >= TABS.size() || index == active) return;
        WebBrowser old = TABS.get(active).page;
        if (old != null) old.hoverOff();
        active = index;
        TerminalView.browserUrl = TABS.get(index).url;
    }

    /** Closes one tab for good: its web view is closed, so the native process ends. */
    static void closeTab(int index) {
        if (index < 0 || index >= TABS.size()) return;
        OpenTab tab = TABS.remove(index);
        if (tab.page != null) {
            tab.page.shutdown();
            tab.page = null;
        }
        if (TABS.isEmpty()) {
            active = 0;
            TerminalView.browserUrl = HOME;
            return;
        }
        if (active > index) active--;
        else if (active >= TABS.size()) active = TABS.size() - 1;
        TerminalView.browserUrl = TABS.get(active).url;
    }

    /** A fresh tab; false when there are already {@link #MAX_TABS}. */
    static boolean newTab() {
        if (TABS.size() >= MAX_TABS || !available()) return false;
        TABS.add(new OpenTab(HOME));
        active = TABS.size() - 1;
        TerminalView.browserUrl = HOME;
        return true;
    }

    /** The page of the shown tab, made on first use (null if it can't be made). */
    static @Nullable WebBrowser get() {
        if (!available()) return null;
        if (TABS.isEmpty()) {
            TABS.add(new OpenTab(TerminalView.browserUrl));
            active = 0;
        }
        OpenTab tab = TABS.get(Math.clamp(active, 0, TABS.size() - 1));
        if (tab.page == null) tab.page = create(tab);
        return tab.page;
    }

    private static @Nullable WebBrowser create(OpenTab tab) {
        try {
            return new WebBrowser(tab);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("EnPlotify: couldn't open a web view", e);
            available = false;
            return null;
        }
    }

    /** Closes every tab and its web view (leaving the terminal, closing the window, logging out). */
    static void closeAll() {
        for (OpenTab tab : TABS) {
            if (tab.page != null) {
                tab.page.shutdown();
                tab.page = null;
            }
        }
        TABS.clear();
        active = 0;
    }

    /** The page should be {@code w} x {@code h} px; every open page is resized to it. */
    static void want(int w, int h) {
        wantW = Math.clamp(w, 16, MAX_SIDE);
        wantH = Math.clamp(h, 16, MAX_SIDE);
        for (OpenTab tab : TABS) {
            WebBrowser page = tab.page;
            if (page != null && (page.width != wantW || page.height != wantH + EXTRA_ROWS)) {
                page.resize(wantW, wantH + EXTRA_ROWS);
            }
        }
    }

    // ------------------------------------------------------------------ the web view

    private final Object view;
    private final OpenTab tab;
    private final Identifier textureId;
    private final Method resize, cdp;
    /** Mouse buttons held on the page (1 left, 2 right, 4 middle). */
    private int held;
    /** What the page said last (see the address script). */
    private boolean locked, cursorHidden;
    /** Where the page thinks the mouse is; while locked it moves by the mouse's movement, unbounded. */
    private double mouseX, mouseY;
    private final Method getFrame, mouseMove, mouseButton, mouseWheel, keyEvent, insertText, loadUrl, eval, close;
    private @Nullable DynamicTexture texture;
    private int textureW, textureH;
    private int width, height;
    private boolean hasFrame;
    private long lastStripAt, lastInjectAt;

    /**
     * Run in every page: keeps a two pixel high strip at the very bottom of the view (below what
     * the terminal shows) holding, three bytes per pixel: "EP", flags (1 = the page locked the
     * mouse, 2 = it hides the cursor where the mouse is), length, checksum, then the address in
     * UTF-8. FerricOxide has no way to tell these back, so they are read out of the picture
     * ({@link #readStrip}). Both rows carry the same bytes, so one clipped or shifted row still works.
     */
    private static final String ADDRESS_SCRIPT = """
            (function () {
              if (window.top !== window || window.__epAddress) return;
              window.__epAddress = 1;
              var strip = null, last = null, hidden = false, lastAt = 0;
              function put() {
                try {
                  var root = document.body || document.documentElement;
                  if (!root) return;
                  var w = Math.max(1, Math.min(window.innerWidth, 4096));
                  if (!strip || !strip.isConnected || strip.width !== w) {
                    strip = document.createElement('canvas');
                    strip.width = w;
                    strip.height = 2;
                    strip.style.cssText = 'position:fixed!important;left:0!important;bottom:0!important;width:' + w
                      + 'px!important;height:2px!important;margin:0!important;padding:0!important;border:0!important;'
                      + 'z-index:2147483647!important;pointer-events:none!important;display:block!important;'
                      + 'opacity:1!important;transform:none!important;filter:none!important;';
                    root.appendChild(strip);
                    last = null;
                  }
                  var href = location.href.slice(0, 1400);
                  var flags = (document.pointerLockElement ? 1 : 0) | (hidden ? 2 : 0);
                  var now = Date.now();
                  if (href + '|' + flags === last && now - lastAt < 1200) return;
                  last = href + '|' + flags;
                  lastAt = now;
                  var bytes = new TextEncoder().encode(href), sum = flags;
                  for (var i = 0; i < bytes.length; i++) sum = (sum + bytes[i]) & 65535;
                  var all = [69, 80, flags, bytes.length >> 8, bytes.length & 255, sum >> 8, sum & 255];
                  for (var j = 0; j < bytes.length; j++) all.push(bytes[j]);
                  var ctx = strip.getContext('2d');
                  var img = ctx.createImageData(w, 2);
                  for (var p = 0; p * 3 < all.length && p < w; p++) {
                    var r = all[p * 3] || 0, g = all[p * 3 + 1] || 0, b = all[p * 3 + 2] || 0;
                    for (var row = 0; row < 2; row++) {
                      var o = (row * w + p) * 4;
                      img.data[o] = r;
                      img.data[o + 1] = g;
                      img.data[o + 2] = b;
                      img.data[o + 3] = 255;
                    }
                  }
                  ctx.putImageData(img, 0, 0);
                } catch (e) {}
              }
              document.addEventListener('DOMContentLoaded', put);
              document.addEventListener('pointerlockchange', put);
              document.addEventListener('pageshow', put);
              window.addEventListener('popstate', put);
              window.addEventListener('hashchange', put);
              document.addEventListener('click', function () { setTimeout(put, 250); setTimeout(put, 900); }, true);
              document.addEventListener('mousemove', function (e) {
                try {
                  var t = e.target;
                  var now = !!(t && t.nodeType === 1 && getComputedStyle(t).cursor === 'none');
                  if (now !== hidden) { hidden = now; put(); }
                } catch (err) {}
              }, true);
              setInterval(put, 300);
            })();
            """;

    /**
     * Run in every page: the page's sound goes out through the picture instead of the speakers, so
     * the game can play it at the terminal for everyone near. Web Audio connected to the speakers,
     * and audio / video elements (once the player has clicked or typed on the page), are led into a
     * tap that mixes to mono, resamples to 24000 a second and keeps the last 150 ms as µ-law bytes.
     * While there is sound, those are painted every 20 ms into rows above the address strip:
     * "EA", a running sample count (4 bytes), the byte count (2), a check sum (2), then the bytes,
     * three per pixel. Beside the tap, the sound goes to the speakers through a gain node at
     * {@code window.__epGain} (set by the game from distance and volume): the player whose page it
     * is hears it at once, untouched by any script.
     * Media from other sites that don't allow it (no CORS) can't be tapped and plays as before.
     */
    private static final String AUDIO_SCRIPT = """
            (function () {
              if (window.top !== window || window.__epAudio || !window.AudioContext) return;
              window.__epAudio = 1;
              var RATE = 24000, KEEP = 3600, ROWS = %d;
              var ring = new Uint8Array(KEEP), total = 0, loudAt = 0, cleared = true;
              function mulaw(f) {
                var x = Math.round(Math.max(-1, Math.min(1, f)) * 32635), sign = 0;
                if (x < 0) { sign = 128; x = -x; }
                x += 132;
                var exp = 7;
                for (var m = 0x4000; (x & m) === 0 && exp > 0; m >>= 1) exp--;
                return ~(sign | (exp << 4) | ((x >> (exp + 3)) & 15)) & 255;
              }
              var taps = new WeakMap(), connect = AudioNode.prototype.connect, disconnect = AudioNode.prototype.disconnect;
              // How loud the page is for this player (distance and the game's volume; set by the game).
              // Silent until the game says how loud (a page that just loaded hasn't been told yet).
              if (typeof window.__epGain !== 'number') window.__epGain = 0;
              // Per context: a gain node on the way to the speakers (the player's own ears, no delay, no
              // script in the way), and beside it a tap that only listens (for everybody else).
              function route(ctx) {
                var r = taps.get(ctx);
                if (r) return r;
                var gain = ctx.createGain();
                gain.gain.value = Math.max(0, Math.min(1, +window.__epGain || 0));
                connect.call(gain, ctx.destination);
                var t = ctx.createScriptProcessor(2048, 2, 1);
                var step = ctx.sampleRate / RATE, phase = 0, acc = 0, n = 0;
                t.onaudioprocess = function (e) {
                  var ib = e.inputBuffer, a = ib.getChannelData(0), b = ib.numberOfChannels > 1 ? ib.getChannelData(1) : a, loud = false;
                  for (var i = 0; i < a.length; i++) {
                    acc += (a[i] + b[i]) * 0.5; n++; phase += 1;
                    if (phase >= step) {
                      phase -= step;
                      var v = acc / n; acc = 0; n = 0;
                      if (v > 0.003 || v < -0.003) loud = true;
                      ring[total %% KEEP] = mulaw(v); total++;
                    }
                  }
                  if (loud) loudAt = Date.now();
                };
                connect.call(t, ctx.destination); // its output stays silent; it only has to run
                r = { gain: gain, tap: t, set: -1 };
                taps.set(ctx, r);
                return r;
              }
              setInterval(function () {
                var g = Math.max(0, Math.min(1, +window.__epGain || 0));
                routes.forEach(function (r) {
                  if (Math.abs(r.set - g) < 0.005) return;
                  r.set = g;
                  try { r.gain.gain.setTargetAtTime(g, r.gain.context.currentTime, 0.04); } catch (e) { r.gain.gain.value = g; }
                });
              }, 50);
              var routes = [];
              var plainRoute = route;
              route = function (ctx) {
                var had = taps.get(ctx), r = plainRoute(ctx);
                if (!had) routes.push(r);
                return r;
              };
              AudioNode.prototype.connect = function (dest) {
                if (dest instanceof AudioDestinationNode && dest.context) {
                  var r = route(dest.context), rest = Array.prototype.slice.call(arguments, 1);
                  connect.apply(this, [r.tap].concat(rest));
                  return connect.apply(this, [r.gain].concat(rest));
                }
                return connect.apply(this, arguments);
              };
              AudioNode.prototype.disconnect = function (dest) {
                if (dest instanceof AudioDestinationNode && dest.context && taps.get(dest.context)) {
                  var r = taps.get(dest.context);
                  try { disconnect.call(this, r.tap); } catch (e) {}
                  try { return disconnect.call(this, r.gain); } catch (e) { return; }
                }
                return disconnect.apply(this, arguments);
              };
              var media = null, hooked = new WeakSet();
              function wake() {
                try {
                  if (!media) media = new AudioContext();
                  if (media.state !== 'running') media.resume();
                } catch (e) {}
              }
              document.addEventListener('pointerdown', wake, true);
              document.addEventListener('keydown', wake, true);
              function hook(el) {
                if (hooked.has(el) || !media || media.state !== 'running') return;
                var src = el.currentSrc || el.src || '';
                var same = !src || /^(blob|data):/.test(src) || el.crossOrigin != null;
                try { if (!same) same = new URL(src, location.href).origin === location.origin; } catch (e) {}
                if (!same) return;
                hooked.add(el);
                try { media.createMediaElementSource(el).connect(media.destination); } catch (e) {}
              }
              document.addEventListener('play', function (e) {
                var el = e.target;
                if (el && (el.tagName === 'AUDIO' || el.tagName === 'VIDEO')) hook(el);
              }, true);
              var canvas = null;
              setInterval(function () {
                try {
                  var root = document.body || document.documentElement;
                  if (!root) return;
                  var w = Math.max(1, Math.min(window.innerWidth, 4096));
                  if (!canvas || !canvas.isConnected || canvas.width !== w) {
                    canvas = document.createElement('canvas');
                    canvas.width = w;
                    canvas.height = ROWS;
                    canvas.style.cssText = 'position:fixed!important;left:0!important;bottom:2px!important;width:' + w
                      + 'px!important;height:' + ROWS + 'px!important;margin:0!important;padding:0!important;border:0!important;'
                      + 'z-index:2147483647!important;pointer-events:none!important;display:block!important;'
                      + 'opacity:1!important;transform:none!important;filter:none!important;background:#000!important;';
                    root.appendChild(canvas);
                    cleared = false;
                  }
                  var ctx = canvas.getContext('2d');
                  if (Date.now() - loudAt > 1500) {
                    if (!cleared) { ctx.clearRect(0, 0, w, ROWS); cleared = true; }
                    return;
                  }
                  cleared = false;
                  var room = w * ROWS * 3 - 10, count = Math.min(KEEP, total, room);
                  var bytes = new Uint8Array(10 + count), sum = 0;
                  bytes[0] = 69; bytes[1] = 65;
                  bytes[2] = (total >>> 24) & 255; bytes[3] = (total >>> 16) & 255; bytes[4] = (total >>> 8) & 255; bytes[5] = total & 255;
                  bytes[6] = count >> 8; bytes[7] = count & 255;
                  for (var i = 0; i < count; i++) { var u = ring[(total - count + i) %% KEEP]; bytes[10 + i] = u; sum = (sum * 31 + u) & 65535; }
                  for (var k = 2; k < 8; k++) sum = (sum * 31 + bytes[k]) & 65535;
                  bytes[8] = sum >> 8; bytes[9] = sum & 255;
                  var img = ctx.createImageData(w, ROWS);
                  for (var p = 0; p * 3 < bytes.length; p++) {
                    var o = p * 4;
                    img.data[o] = bytes[p * 3]; img.data[o + 1] = bytes[p * 3 + 1] || 0; img.data[o + 2] = bytes[p * 3 + 2] || 0; img.data[o + 3] = 255;
                  }
                  ctx.putImageData(img, 0, 0);
                } catch (e) {}
              }, 20);
            })();
            """.formatted(AUDIO_ROWS);

    private WebBrowser(OpenTab tab) throws ReflectiveOperationException {
        ClassLoader loader = WebBrowser.class.getClassLoader();
        Class<?> viewClass = Class.forName(VIEW, true, loader);
        Class<?> builderClass = Class.forName(VIEW + "$Builder", true, loader);
        Object builder = builderClass.getConstructor().newInstance();
        // Two extra rows at the bottom carry the address.
        builderClass.getMethod("size", int.class, int.class).invoke(builder, wantW, wantH + EXTRA_ROWS);
        builderClass.getMethod("initScript", String.class).invoke(builder, ADDRESS_SCRIPT + AUDIO_SCRIPT);
        this.width = wantW;
        this.height = wantH + EXTRA_ROWS;
        this.audioId = nextId;
        builderClass.getMethod("url", String.class).invoke(builder, tab.url);
        this.view = builderClass.getMethod("build").invoke(builder);
        this.tab = tab;
        this.textureId = Identifier.fromNamespaceAndPath(EnPlotify.MOD_ID, "terminal_browser_" + (nextId++));
        getFrame = viewClass.getMethod("getFrame");
        mouseMove = viewClass.getMethod("mouseMove", int.class, int.class);
        mouseButton = viewClass.getMethod("mouseButton", int.class, int.class, int.class, boolean.class);
        mouseWheel = viewClass.getMethod("mouseWheel", int.class, int.class, double.class, double.class);
        keyEvent = viewClass.getMethod("keyEvent", String.class, int.class, String.class, String.class, String.class, int.class);
        insertText = viewClass.getMethod("insertText", String.class);
        loadUrl = viewClass.getMethod("loadUrl", String.class);
        eval = viewClass.getMethod("eval", String.class);
        close = viewClass.getMethod("close");
        resize = viewClass.getMethod("resize", int.class, int.class);
        cdp = viewClass.getMethod("cdp", String.class, String.class);
    }

    Identifier texture() {
        return textureId;
    }

    private boolean isActive() {
        int index = activeIndex();
        return index >= 0 && TABS.get(index) == tab;
    }

    private void resize(int w, int h) {
        width = w;
        height = h;
        call(resize, w, h);
    }

    private void call(Method method, Object... args) {
        try {
            method.invoke(view, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("EnPlotify: web view call failed", e);
        }
    }

    // ------------------------------------------------------------------ page

    void load(String address) {
        String a = address.strip();
        if (a.isEmpty()) return;
        if (!a.contains("://")) {
            // Looks like an address → https; otherwise search for it.
            a = a.contains(".") && !a.contains(" ")
                    ? "https://" + a
                    : HOME + "/search?q=" + java.net.URLEncoder.encode(a, java.nio.charset.StandardCharsets.UTF_8);
        }
        tab.url = a;
        if (isActive()) TerminalView.browserUrl = a;
        lastStripAt = 0L;
        call(loadUrl, a);
    }

    void back() {
        String to = tab.step(-1);
        if (to != null) open(to);
    }

    void forward() {
        String to = tab.step(1);
        if (to != null) open(to);
    }

    boolean canBack() {
        return tab.canBack();
    }

    boolean canForward() {
        return tab.canForward();
    }

    /** Goes to an address as it is (no search, no "https://" added). */
    private void open(String address) {
        tab.url = address;
        if (isActive()) TerminalView.browserUrl = address;
        lastStripAt = 0L;
        call(loadUrl, address);
    }

    void reload() {
        call(eval, "location.reload()");
    }

    // Mouse events go out with the held buttons and the click count: many pages ignore a press
    // that says no button is down (FerricOxide's own helpers leave those out).

    boolean locked() {
        return locked;
    }

    boolean cursorHidden() {
        return cursorHidden;
    }

    int mouseX() {
        return (int) Math.round(mouseX);
    }

    int mouseY() {
        return (int) Math.round(mouseY);
    }

    /** While the page holds the mouse: move by (dx, dy) page px. */
    void lockedMove(double dx, double dy) {
        mouseX += dx;
        mouseY += dy;
        mouseMove(mouseX(), mouseY());
    }

    /** Whether the page thinks the mouse is over it. */
    private boolean hovering;

    /**
     * Moves the page's mouse off the page, so nothing stays hovered: otherwise the link preview
     * (a little window of the system's web view) stays on the game window after leaving.
     */
    void hoverOff() {
        if (!hovering || locked) return;
        hovering = false;
        mouse("mouseMoved", -1000, -1000, "none", 0);
    }

    /** {@link #hoverOff} for every open page. */
    static void hoverOffAll() {
        for (OpenTab tab : TABS) {
            if (tab.page != null) tab.page.hoverOff();
        }
    }

    void unlock() {
        call(eval, "document.exitPointerLock && document.exitPointerLock()");
    }

    void mouseMove(int x, int y) {
        mouseX = x;
        mouseY = y;
        hovering = true;
        mouse("mouseMoved", x, y, held == 0 ? "none" : (held & 1) != 0 ? "left" : (held & 2) != 0 ? "right" : "middle", 0);
    }

    /** {@code button}: 0 left, 1 middle, 2 right; {@code clicks}: 1, or 2 for a double click. */
    void mouseButton(int x, int y, int button, boolean down, int clicks) {
        int bit = button == 0 ? 1 : button == 2 ? 2 : 4;
        if (down) held |= bit;
        else held &= ~bit;
        mouse(down ? "mousePressed" : "mouseReleased", x, y, button == 0 ? "left" : button == 2 ? "right" : "middle", clicks);
    }

    private void mouse(String type, int x, int y, String button, int clicks) {
        call(cdp, "Input.dispatchMouseEvent", "{\"type\":\"" + type + "\",\"x\":" + x + ",\"y\":" + y
                + ",\"button\":\"" + button + "\",\"buttons\":" + held + ",\"clickCount\":" + clicks
                + ",\"pointerType\":\"mouse\"}");
    }

    void wheel(int x, int y, double dy) {
        call(mouseWheel, x, y, 0.0, -dy * 100.0);
    }

    /** The input method's text not yet chosen, shown in the page where it will go (empty: none). */
    void compose(String text, int caret) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("text", text);
        o.addProperty("selectionStart", Math.clamp(caret, 0, text.length()));
        o.addProperty("selectionEnd", Math.clamp(caret, 0, text.length()));
        call(cdp, "Input.imeSetComposition", o.toString());
    }

    void key(String type, int vk, String key, String code, @Nullable String text, int modifiers) {
        call(keyEvent, type, vk, key, code, text, modifiers);
    }

    void type(String text) {
        call(insertText, text);
    }

    boolean hasFrame() {
        return hasFrame;
    }

    // ------------------------------------------------------------------ the page's sound

    /** This page's sound stream number. */
    private final int audioId;
    /** The page's sample count last read (-1: none yet), when it last had sound, when it was last read. */
    private long audioTotal = -1;
    private long audibleAt, audioPolledAt;

    /** Takes the new sound out of a frame (the rows above the address strip) and plays it at {@code pos}. */
    private void readAudio(byte[] rgba, net.minecraft.core.BlockPos pos) {
        audioPolledAt = Util.getMillis();
        int top = height - EXTRA_ROWS;
        if (top < 0 || width <= 0) return;
        int base = top * width * 4, cap = width * AUDIO_ROWS * 3;
        java.util.function.IntUnaryOperator at = i -> {
            int pixel = i / 3, channel = i % 3;
            return rgba[base + pixel * 4 + channel] & 0xFF;
        };
        if (cap < 10 || at.applyAsInt(0) != 'E' || at.applyAsInt(1) != 'A') {
            audioTotal = -1;
            return;
        }
        long total = (long) at.applyAsInt(2) << 24 | at.applyAsInt(3) << 16 | at.applyAsInt(4) << 8 | at.applyAsInt(5);
        int count = at.applyAsInt(6) << 8 | at.applyAsInt(7);
        if (count <= 0 || count > cap - 10) return;
        int sum = 0;
        for (int i = 0; i < count; i++) sum = (sum * 31 + at.applyAsInt(10 + i)) & 0xFFFF;
        for (int k = 2; k < 8; k++) sum = (sum * 31 + at.applyAsInt(k)) & 0xFFFF;
        if (sum != (at.applyAsInt(8) << 8 | at.applyAsInt(9))) return; // half-drawn or misread: wait for the next
        audibleAt = audioPolledAt;
        long fresh = audioTotal < 0 ? Math.min(count, StreamSound.RATE / 20) : total - audioTotal;
        audioTotal = total;
        if (fresh <= 0) return;
        if (fresh > count) fresh = count; // missed some: take what is there
        byte[] out = new byte[(int) fresh];
        for (int i = 0; i < out.length; i++) out[i] = (byte) at.applyAsInt(10 + count - out.length + i);
        TerminalAudio.fromPage(pos, audioId, out);
    }

    float sentGain = -1.0F;
    private long gainAt, gainSentAt;

    /** How loud this page plays on this player's speakers: the game's own volume, fading out by 32 blocks. */
    private void updateGain(net.minecraft.core.BlockPos pos) {
        long now = Util.getMillis();
        if (now - gainAt < 150) return;
        gainAt = now;
        Minecraft minecraft = Minecraft.getInstance();
        float gain = 0.0F;
        if (minecraft.level != null && minecraft.player != null) {
            // From the terminal or the nearest display connected to it, whichever is closer.
            double distance = TerminalAudio.nearestDistance(pos, minecraft.gameRenderer.getMainCamera().position());
            float fade = (float) Math.max(0.0, 1.0 - distance / 32.0);
            gain = fade * minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)
                    * minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.RECORDS);
        }
        // Said again every second even if unchanged: a page that loaded anew has forgotten it.
        if (Math.abs(gain - sentGain) < 0.01F && now - gainSentAt < 1000) return;
        sentGain = gain;
        gainSentAt = now;
        call(eval, "window.__epGain=" + gain + ";");
    }

    /** Reads only the sound (pages not shown): often while they have sound, now and then otherwise. */
    private void pollAudio(net.minecraft.core.BlockPos pos) {
        updateGain(pos);
        long now = Util.getMillis();
        boolean audible = now - audibleAt < 3000;
        if (now - audioPolledAt < (audible ? 40 : 500)) return;
        audioPolledAt = now;
        try {
            Object frame = getFrame.invoke(view);
            if (frame == null) return;
            byte[] rgba = (byte[]) frame.getClass().getMethod("rgba").invoke(frame);
            int size = width * height * 4;
            if (rgba.length < size || rgba.length > size + size / 16) return;
            readAudio(rgba, pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // next time
        }
    }

    /** Takes the newest picture into the texture (between frames, never mid-draw). */
    private void pull() {
        Object frame;
        try {
            frame = getFrame.invoke(view);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        if (frame == null) return;
        byte[] rgba;
        try {
            rgba = (byte[]) frame.getClass().getMethod("rgba").invoke(frame);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        int size = width * height * 4;
        // Like FerricOxide's own display, a frame may carry a few extra bytes at the end. Too short,
        // or much longer (a frame from before a resize), and it is not this size: wait for the next.
        if (rgba.length < size || rgba.length > size + size / 16) return;
        if (texture == null || textureW != width || textureH != height) {
            if (texture != null) Minecraft.getInstance().getTextureManager().release(textureId);
            texture = new DynamicTexture(() -> "enplotify terminal browser", new NativeImage(width, height, false));
            textureW = width;
            textureH = height;
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
        }
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        // RGBA bytes are the image's own layout (ABGR ints, little end first): one copy, one upload.
        MemoryUtil.memByteBuffer(pixels.getPointer(), size).put(rgba, 0, size);
        texture.upload();
        hasFrame = true;
        net.minecraft.core.BlockPos shownAt = TerminalView.pos();
        if (shownAt != null) {
            updateGain(shownAt);
            readAudio(rgba, shownAt);
        }
        long now = Util.getMillis();
        Object[] strip = readStrip(rgba, width, height);
        if (strip == null) {
            // A page that never got the address script (or lost it): put it back, now and then.
            if (now - lastStripAt > 1500L && now - lastInjectAt > 2000L) {
                lastInjectAt = now;
                call(eval, ADDRESS_SCRIPT + AUDIO_SCRIPT);
            }
            return;
        }
        lastStripAt = now;
        int flags = (int) strip[0];
        locked = (flags & 1) != 0;
        cursorHidden = (flags & 2) != 0;
        String address = (String) strip[1];
        tab.visited(address);
        if (!address.equals(tab.url)) tab.url = address;
        if (isActive()) TerminalView.browserUrl = address;
    }

    /** {flags, address} from the bottom rows of the picture (null if it isn't there or doesn't check out). */
    private static Object @Nullable [] readStrip(byte[] rgba, int width, int height) {
        for (int row = height - 1; row >= height - STRIP_ROWS && row >= 0; row--) {
            Object[] got = readStripRow(rgba, width, row);
            if (got != null) return got;
        }
        return null;
    }

    private static Object @Nullable [] readStripRow(byte[] rgba, int width, int row) {
        int base = row * width * 4;
        java.util.function.IntUnaryOperator at = i -> {
            int pixel = i / 3, channel = i % 3;
            if (pixel >= width) return -1;
            return rgba[base + pixel * 4 + channel] & 0xFF;
        };
        if (at.applyAsInt(0) != 'E' || at.applyAsInt(1) != 'P') return null;
        int flags = at.applyAsInt(2);
        int length = at.applyAsInt(3) << 8 | at.applyAsInt(4);
        int sum = at.applyAsInt(5) << 8 | at.applyAsInt(6);
        if (length <= 0 || length > 1400 || (7 + length + 2) / 3 > width) return null;
        byte[] bytes = new byte[length];
        int check = flags;
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) at.applyAsInt(7 + i);
            check = (check + (bytes[i] & 0xFF)) & 0xFFFF;
        }
        return check == sum ? new Object[] {flags, new String(bytes, java.nio.charset.StandardCharsets.UTF_8)} : null;
    }

    /**
     * The shown tab's newest picture as {rgba bytes, width, page height} (the address strip left
     * out), without touching the texture: for pictures of a screen nobody is at. Null if none.
     */
    static Object @Nullable [] latestFrame(net.minecraft.core.BlockPos pos) {
        Parked parked = PARKED.get(pos);
        if (parked == null || parked.tabs.isEmpty()) return null;
        WebBrowser page = parked.tabs.get(Math.clamp(parked.active, 0, parked.tabs.size() - 1)).page;
        if (page == null) return null;
        try {
            Object frame = page.getFrame.invoke(page.view);
            if (frame == null) return null;
            byte[] rgba = (byte[]) frame.getClass().getMethod("rgba").invoke(frame);
            int size = page.width * page.height * 4;
            if (rgba.length < size || rgba.length > size + size / 16) return null;
            return new Object[] {rgba, page.width, Math.max(1, page.height - EXTRA_ROWS)};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** How much of the texture's height is page (the bottom rows are the address strip). */
    float pageV() {
        return textureH <= EXTRA_ROWS ? 1.0F : (textureH - EXTRA_ROWS) / (float) textureH;
    }

    private void shutdown() {
        call(close);
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture = null;
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        // The shown tab's picture goes into its texture; every other running page is only listened to.
        WebBrowser shown = null;
        net.minecraft.core.BlockPos here = TerminalView.pos();
        if (TerminalView.active() && TerminalView.app == io.github.lwarqiu1shz.enplotify.paper.TerminalBlockEntity.APP_BROWSER) {
            int index = activeIndex();
            if (index >= 0) shown = TABS.get(index).page;
            if (shown != null) shown.pull();
        }
        if (here != null) {
            for (OpenTab tab : TABS) if (tab.page != null && tab.page != shown) tab.page.pollAudio(here);
        }
        for (java.util.Map.Entry<net.minecraft.core.BlockPos, Parked> e : PARKED.entrySet()) {
            for (OpenTab tab : e.getValue().tabs) if (tab.page != null) tab.page.pollAudio(e.getKey());
        }
    }
}
