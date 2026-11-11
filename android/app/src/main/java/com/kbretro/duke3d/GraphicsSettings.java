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

    /** 0 software (con las mejoras de postfx.c), 1 OpenGL (Polymost). */
    static int renderer(Context c) {
        return prefs(c).getInt("renderizador", 0);
    }

    static boolean opengl(Context c) {
        return renderer(c) == 1;
    }

    static int texFilter(Context c) {
        return prefs(c).getInt("filtrado", 2);
    }

    static int anisotropy(Context c) {
        return prefs(c).getInt("anisotropico", 8);
    }

    static boolean hdTextures(Context c) {
        return prefs(c).getBoolean("texturas_hd", true);
    }

    static boolean models(Context c) {
        return prefs(c).getBoolean("modelos", true);
    }

    static boolean voxels(Context c) {
        return prefs(c).getBoolean("voxeles", true);
    }

    private static final int[] ANISO = {1, 2, 4, 8, 16};
    private static final String[] FILTER_KEYS = {"gfx_classic", "gfx_bilinear", "gfx_trilinear"};

    /** Órdenes de consola para Polymost con los ajustes guardados. */
    static String[] polymostCommands(Context c) {
        int f = texFilter(c);
        java.util.List<String> cmds = new java.util.ArrayList<>();
        if (f == 0) {
            cmds.add("r_useindexedcolortextures 1");
        } else {
            cmds.add("r_useindexedcolortextures 0");
            cmds.add("r_texfilter " + (f == 1 ? 3 : 5));
            cmds.add("r_anisotropy " + anisotropy(c));
        }
        cmds.add("r_hightile " + (hdTextures(c) ? 1 : 0));
        cmds.add("r_models " + (models(c) ? 1 : 0));
        cmds.add("r_voxels " + (voxels(c) ? 1 : 0));
        cmds.add("r_glowmapping 1");
        cmds.add("r_detailmapping 1");
        return cmds.toArray(new String[0]);
    }

    /** Elegir renderizador (solo en el lanzador: se aplica al empezar la partida). */
    static void addRenderer(Context c, DukeMenuView.Page p) {
        p.addValue(I18n.t("gfx_renderer"), () -> I18n.t(opengl(c) ? "gfx_opengl" : "gfx_software"), () ->
                prefs(c).edit().putInt("renderizador", opengl(c) ? 0 : 1).apply());
    }

    /** Opciones de Polymost: filtrado de texturas, anisotrópico, texturas HD, modelos y vóxeles. */
    static void addPolymost(Context c, DukeMenuView.Page p, Runnable onChange) {
        p.addValue(I18n.t("gfx_filter"), () -> I18n.t(FILTER_KEYS[clamp(texFilter(c), 3)]), () -> {
            prefs(c).edit().putInt("filtrado", (clamp(texFilter(c), 3) + 1) % 3).apply();
            if (onChange != null) onChange.run();
        });
        p.addValue(I18n.t("gfx_aniso"), () -> anisotropy(c) + "x", () -> {
            prefs(c).edit().putInt("anisotropico", next(ANISO, anisotropy(c))).apply();
            if (onChange != null) onChange.run();
        });
        p.addValue(I18n.t("gfx_hd"), () -> I18n.t(hdTextures(c) ? "yes" : "no"), () -> {
            prefs(c).edit().putBoolean("texturas_hd", !hdTextures(c)).apply();
            if (onChange != null) onChange.run();
        });
        p.addValue(I18n.t("gfx_models"), () -> I18n.t(models(c) ? "yes" : "no"), () -> {
            prefs(c).edit().putBoolean("modelos", !models(c)).apply();
            if (onChange != null) onChange.run();
        });
        p.addValue(I18n.t("gfx_voxels"), () -> I18n.t(voxels(c) ? "yes" : "no"), () -> {
            prefs(c).edit().putBoolean("voxeles", !voxels(c)).apply();
            if (onChange != null) onChange.run();
        });
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
