package io.github.lwarqiu1shz.enplotify.client.paper;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * IMBlocker (an optional mod) keeps the system's input method off unless a text box it knows has
 * the keyboard. A web page in a terminal is no such box, so while the page has the keyboard it is
 * told about one: a stand-in text box over the page, which wants the input method on. Everything
 * goes through reflection; without IMBlocker nothing here does anything.
 */
final class ImBlockerBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnPlotify");
    private static final String GUI = "io.github.reserveword.imblocker.common.gui.";

    private static boolean tried;
    private static @Nullable Object container, widget;
    private static @Nullable Method requestFocus, removeFocus, switchFocus;
    private static @Nullable Constructor<?> rectangle, point;
    private static java.lang.reflect.@Nullable Field tracking, located;
    private static boolean focused;
    /** The page's place on the screen and where its caret probably is (GUI pixels). */
    private static int x, y, w, h, caretX, caretY;

    private static boolean ready() {
        if (!tried) {
            tried = true;
            try {
                if (!ModList.get().isLoaded("imblocker")) return false;
                ClassLoader loader = ImBlockerBridge.class.getClassLoader();
                Class<?> widgetType = Class.forName(GUI + "FocusableWidget", true, loader);
                Class<?> containerType = Class.forName(GUI + "FocusContainer", true, loader);
                Class<?> manager = Class.forName(GUI + "FocusManager", true, loader);
                container = containerType.getField("MINECRAFT").get(null);
                requestFocus = containerType.getMethod("requestFocus", widgetType);
                removeFocus = containerType.getMethod("removeFocus", widgetType);
                switchFocus = containerType.getMethod("switchFocus", widgetType);
                rectangle = Class.forName(GUI + "Rectangle", true, loader).getConstructor(int.class, int.class, int.class, int.class);
                point = Class.forName(GUI + "Point", true, loader).getConstructor(int.class, int.class);
                tracking = manager.getField("isTrackingFocus");
                located = manager.getField("isFocusLocated");
                Object holder = container;
                InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                    case "getFocusContainer" -> holder;
                    case "getPreferredState", "isRenderable" -> true;
                    case "getPreferredEnglishState" -> false;
                    case "getBoundsAbs" -> rectangle.newInstance(x, y, w, h);
                    case "getCaretPos" -> point.newInstance(caretX, caretY);
                    case "getGuiScale" -> (double) Minecraft.getInstance().getWindow().getGuiScale();
                    case "getFontHeight" -> Minecraft.getInstance().font.lineHeight;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "EnPlotify terminal page";
                    default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
                };
                widget = Proxy.newProxyInstance(loader, new Class<?>[] {widgetType}, handler);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.warn("EnPlotify: IMBlocker is installed but can't be told about terminal pages", e);
                container = null;
                widget = null;
            }
        }
        return widget != null;
    }

    /** The page has (or lost) the keyboard; where it is, in GUI pixels. */
    static void pageFocus(boolean on, int px, int py, int pw, int ph, int cx, int cy) {
        x = px;
        y = py;
        w = pw;
        h = ph;
        caretX = cx;
        caretY = cy;
        if (on == focused || !ready()) return;
        focused = on;
        try {
            if (on) requestFocus.invoke(container, widget);
            else removeFocus.invoke(container, widget);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("EnPlotify: IMBlocker focus change failed", e);
        }
    }

    /**
     * IMBlocker looks for the text box that has the keyboard by sending a character no one types:
     * when it reaches the page, the page says it is the one. True if this was such a probe.
     */
    static boolean probe(int codepoint) {
        if (!focused || !ready()) return false;
        try {
            if (!tracking.getBoolean(null)) return false;
            switchFocus.invoke(container, widget);
            located.setBoolean(null, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private ImBlockerBridge() {}
}
