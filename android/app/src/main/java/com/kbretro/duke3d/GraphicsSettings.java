package com.kbretro.duke3d;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ajustes de las mejoras gráficas (ver postfx.c): los mismos en el lanzador y en el menú rápido.
 * Se guardan en "ajustes" y el juego los aplica al momento.
 */
final class GraphicsSettings {
    static final int[] SCALE_VALUES = {100, 75, 50};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_MULTI_PROCESS);
    }

    static int scale(Context c) {
        int s = prefs(c).getInt("escala", 100);
        return s == 75 || s == 50 ? s : 100; // 125/150 % (antes) cerraban el juego
    }

    static int scaler(Context c) {
        return clamp(prefs(c).getInt("escalado", 1), SCALER_KEYS.length);
    }

    static int fxaa(Context c) {
        return prefs(c).getInt("fxaa", 0);
    }

    static int sharp(Context c) {
        return clamp(prefs(c).getInt("nitidez", 0), SHARP_KEYS.length);
    }

    static int crt(Context c) {
        return clamp(prefs(c).getInt("crt", 0), CRT_KEYS.length);
    }

    static int color(Context c) {
        return clamp(prefs(c).getInt("color", 0), COLOR_KEYS.length);
    }

    static int bloom(Context c) {
        return clamp(prefs(c).getInt("resplandor", 0), BLOOM_KEYS.length);
    }

    private static final String[] SCALER_KEYS = {"gfx_pixel", "gfx_sharp", "gfx_smooth", "gfx_bicubic", "gfx_fsr"};
    private static final String[] SHARP_KEYS = {"no", "gfx_low", "gfx_mid", "gfx_high"};
    private static final String[] CRT_KEYS = {"no", "gfx_lines", "gfx_crt", "gfx_lcd"};
    private static final String[] COLOR_KEYS = {"gfx_normal", "gfx_vivid", "gfx_warm", "gfx_cold", "gfx_cinema"};
    private static final String[] BLOOM_KEYS = {"no", "gfx_soft", "gfx_strong"};

    private static String scaleLabel(int s) {
        return s + "%";
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
        if (Motor.RAZE) {
            // Raze dibuja con Vulkan: en vez de los filtros de EDuke32, sus propias opciones.
            for (RazeOpt o : RAZE_OPTS) {
                p.addValue(I18n.t(o.label), () -> I18n.t(o.labels[razeIndex(c, o)]), () -> {
                    int next = (razeIndex(c, o) + 1) % o.labels.length;
                    prefs(c).edit().putInt(o.pref, next).apply();
                    if (onChange != null) onChange.run();
                });
            }
            return;
        }
        p.addValue(I18n.t("gfx_scaler"), () -> I18n.t(SCALER_KEYS[scaler(c)]),
                () -> cycle(c, "escalado", scaler(c), SCALER_KEYS.length, onChange));
        p.addValue("FXAA", () -> I18n.t(fxaa(c) != 0 ? "yes" : "no"),
                () -> cycle(c, "fxaa", fxaa(c) != 0 ? 1 : 0, 2, onChange));
        p.addValue(I18n.t("gfx_sharpness"), () -> I18n.t(SHARP_KEYS[sharp(c)]),
                () -> cycle(c, "nitidez", sharp(c), SHARP_KEYS.length, onChange));
        p.addValue(I18n.t("gfx_bloom"), () -> I18n.t(BLOOM_KEYS[bloom(c)]),
                () -> cycle(c, "resplandor", bloom(c), BLOOM_KEYS.length, onChange));
        p.addValue(I18n.t("gfx_color"), () -> I18n.t(COLOR_KEYS[color(c)]),
                () -> cycle(c, "color", color(c), COLOR_KEYS.length, onChange));
        p.addValue(I18n.t("gfx_effect"), () -> I18n.t(CRT_KEYS[crt(c)]),
                () -> cycle(c, "crt", crt(c), CRT_KEYS.length, onChange));
    }

    // ---- Raze (Vulkan) ----

    private static final class RazeOpt {
        final String label, pref;
        final String[] labels, commands; // orden(es) de consola de cada valor (varias con ';')
        final int def;

        RazeOpt(String label, String pref, int def, String[] labels, String[] commands) {
            this.label = label;
            this.pref = pref;
            this.def = def;
            this.labels = labels;
            this.commands = commands;
        }
    }

    private static final RazeOpt[] RAZE_OPTS = {
            new RazeOpt("gfx_filter", "raze_filtro", 0,
                    new String[]{"gfx_pixel", "gfx_pixel_smooth", "gfx_bilinear", "gfx_trilinear"},
                    new String[]{"gl_texture_filter 0", "gl_texture_filter 5", "gl_texture_filter 3", "gl_texture_filter 4"}),
            new RazeOpt("gfx_aniso", "raze_aniso", 2,
                    new String[]{"no", "4x", "8x", "16x"},
                    new String[]{"gl_texture_filter_anisotropic 1", "gl_texture_filter_anisotropic 4",
                            "gl_texture_filter_anisotropic 8", "gl_texture_filter_anisotropic 16"}),
            new RazeOpt("FXAA", "raze_fxaa", 0,
                    new String[]{"no", "gfx_low", "gfx_mid", "gfx_high"},
                    new String[]{"gl_fxaa 0", "gl_fxaa 1", "gl_fxaa 2", "gl_fxaa 3"}),
            new RazeOpt("gfx_bloom", "raze_bloom", 0,
                    new String[]{"no", "yes"},
                    new String[]{"gl_bloom 0", "gl_bloom 1"}),
            new RazeOpt("gfx_color", "raze_color", 0,
                    new String[]{"gfx_normal", "gfx_cinema", "gfx_vivid", "gfx_palette"},
                    new String[]{"gl_tonemap 0", "gl_tonemap 1", "gl_tonemap 2", "gl_tonemap 5"}),
            new RazeOpt("gfx_ssao", "raze_ssao", 0,
                    new String[]{"no", "gfx_low", "gfx_mid", "gfx_high"},
                    new String[]{"gl_ssao 0", "gl_ssao 1", "gl_ssao 2", "gl_ssao 3"}),
            new RazeOpt("gfx_hq", "raze_hq", 0,
                    new String[]{"no", "xBRZ 2x", "xBRZ 3x"},
                    new String[]{"gl_texture_hqresizemode 0",
                            "gl_texture_hqresizemult 2;gl_texture_hqresizemode 4",
                            "gl_texture_hqresizemult 3;gl_texture_hqresizemode 4"}),
            new RazeOpt("gfx_lens", "raze_lens", 0,
                    new String[]{"no", "yes"},
                    new String[]{"gl_lens 0", "gl_lens 1"}),
    };

    private static int razeIndex(Context c, RazeOpt o) {
        int i = prefs(c).getInt(o.pref, o.def);
        return i >= 0 && i < o.labels.length ? i : o.def;
    }

    /** Las órdenes de consola de Raze para los ajustes guardados. */
    static java.util.List<String> razeCommands(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (RazeOpt o : RAZE_OPTS) {
            for (String cmd : o.commands[razeIndex(c, o)].split(";")) out.add(cmd.trim());
        }
        return out;
    }

    interface IntConsumer {
        void accept(int v);
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }

    private static void cycle(Context c, String key, int cur, int n, Runnable onChange) {
        prefs(c).edit().putInt(key, (cur + 1) % n).apply();
        if (onChange != null) onChange.run();
    }

    private static int next(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return values[(i + 1) % values.length];
        return values[0];
    }

    private GraphicsSettings() {
    }
}
