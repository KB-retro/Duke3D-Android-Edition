package com.kbretro.duke3d;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ajustes de las mejoras gráficas (ver postfx.c): los mismos en el lanzador y en el menú rápido.
 * Se guardan en "ajustes" y el juego los aplica al momento.
 */
final class GraphicsSettings {
    static final int[] SCALE_VALUES = {100, 75, 50, 150, 125};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_MULTI_PROCESS);
    }

    static int scale(Context c) {
        return prefs(c).getInt("escala", 100);
    }

    static int scaler(Context c) {
        return prefs(c).getInt("escalado", 1);
    }

    static int fxaa(Context c) {
        return prefs(c).getInt("fxaa", 0);
    }

    static int sharp(Context c) {
        return prefs(c).getInt("nitidez", 0);
    }

    static int crt(Context c) {
        return prefs(c).getInt("crt", 0);
    }

    static int color(Context c) {
        return prefs(c).getInt("color", 0);
    }

    private static final String[] SCALER_KEYS = {"gfx_pixel", "gfx_sharp", "gfx_smooth", "gfx_fsr"};
    private static final String[] SHARP_KEYS = {"no", "gfx_low", "gfx_mid", "gfx_high"};
    private static final String[] CRT_KEYS = {"no", "gfx_lines", "gfx_crt"};
    private static final String[] COLOR_KEYS = {"gfx_normal", "gfx_vivid"};

    private static String scaleLabel(int s) {
        return s > 100 ? s + "% SSAA" : s + "%";
    }

    /**
     * Añade las opciones a una página.
     * @param onScale    al cambiar la resolución interna (o null)
     * @param onChange   al cambiar cualquier otra (o null)
     */
    static void addTo(Context c, DukeMenuView.Page p, IntConsumer onScale, Runnable onChange) {
        p.addValue(I18n.t("resolution"), () -> scaleLabel(scale(c)), () -> {
            int next = next(SCALE_VALUES, scale(c));
            prefs(c).edit().putInt("escala", next).apply();
            if (onScale != null) onScale.accept(next);
        });
        p.addValue(I18n.t("gfx_scaler"), () -> I18n.t(SCALER_KEYS[clamp(scaler(c), 4)]),
                () -> cycle(c, "escalado", 4, onChange));
        p.addValue("FXAA", () -> I18n.t(fxaa(c) != 0 ? "yes" : "no"), () -> cycle(c, "fxaa", 2, onChange));
        p.addValue(I18n.t("gfx_sharpness"), () -> I18n.t(SHARP_KEYS[clamp(sharp(c), 4)]),
                () -> cycle(c, "nitidez", 4, onChange));
        p.addValue(I18n.t("gfx_effect"), () -> I18n.t(CRT_KEYS[clamp(crt(c), 3)]), () -> cycle(c, "crt", 3, onChange));
        p.addValue(I18n.t("gfx_color"), () -> I18n.t(COLOR_KEYS[clamp(color(c), 2)]), () -> cycle(c, "color", 2, onChange));
    }

    interface IntConsumer {
        void accept(int v);
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }

    private static void cycle(Context c, String key, int n, Runnable onChange) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(key, (clamp(p.getInt(key, key.equals("escalado") ? 1 : 0), n) + 1) % n).apply();
        if (onChange != null) onChange.run();
    }

    private static int next(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return values[(i + 1) % values.length];
        return values[0];
    }

    private GraphicsSettings() {
    }
}
