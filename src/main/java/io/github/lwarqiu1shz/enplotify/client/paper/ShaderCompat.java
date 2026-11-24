package io.github.lwarqiu1shz.enplotify.client.paper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Knows when a shader pack (Iris and its ports) is drawing its shadow map. Posters and boards lie
 * flat on walls, so their shadows add nothing: they are skipped there, which roughly halves their
 * cost with shaders on. Found by name at run time, so no shader mod is needed to build or play.
 */
final class ShaderCompat {
    private static final Object API;
    private static final MethodHandle SHADOW_PASS;

    static {
        Object api = null;
        MethodHandle shadow = null;
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            api = lookup.findStatic(type, "getInstance", MethodType.methodType(type)).invoke();
            shadow = lookup.findVirtual(type, "isRenderingShadowPass", MethodType.methodType(boolean.class));
        } catch (Throwable ignored) {
            // no shader mod
        }
        API = api;
        SHADOW_PASS = shadow;
    }

    static boolean inShadowPass() {
        if (SHADOW_PASS == null) return false;
        try {
            return (boolean) SHADOW_PASS.invoke(API);
        } catch (Throwable e) {
            return false;
        }
    }

    private ShaderCompat() {}
}
