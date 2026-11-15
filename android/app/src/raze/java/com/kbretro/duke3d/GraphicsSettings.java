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
        // Izquierda/derecha: baja o sube (antes cualquier lado pasaba al siguiente).
        lastEntry(p).step = d -> {
            int i = 0;
            for (int k = 0; k < SCALE_VALUES.length; k++) if (SCALE_VALUES[k] == scale(c)) i = k;
            // SCALE_VALUES va de mayor a menor: derecha = más resolución.
            int j = Math.max(0, Math.min(SCALE_VALUES.length - 1, i - d));
            if (j == i) return;
            prefs(c).edit().putInt("escala", SCALE_VALUES[j]).apply();
            if (onScale != null) onScale.accept(SCALE_VALUES[j]);
        };
        if (Motor.RAZE) {
            p.addValue(I18n.t("gfx_preset"), () -> {
                int cur = currentPreset(c);
                return I18n.t(cur >= 0 ? PRESET_KEYS[cur] : "gfx_preset_custom");
            }, () -> {
                int next = (currentPreset(c) + 1) % PRESETS.length;
                applyPreset(c, next);
                if (onScale != null) onScale.accept(PRESETS[next][0]);
                if (onChange != null) onChange.run();
            });
            lastEntry(p).step = d -> {
                int cur = currentPreset(c);
                int next = cur < 0 ? (d > 0 ? 0 : PRESETS.length - 1) : Math.max(0, Math.min(PRESETS.length - 1, cur + d));
                if (next == cur) return;
                applyPreset(c, next);
                if (onScale != null) onScale.accept(PRESETS[next][0]);
                if (onChange != null) onChange.run();
            };
            // Raze dibuja con OpenGL ES: en vez de los filtros de EDuke32, sus propias opciones.
            for (RazeOpt o : RAZE_OPTS) {
                p.addValue(I18n.t(o.label), () -> I18n.t(o.labels[razeIndex(c, o)]), () -> {
                    int next = (razeIndex(c, o) + 1) % o.labels.length;
                    prefs(c).edit().putInt(o.pref, next).apply();
                    if (onChange != null) onChange.run();
                });
                lastEntry(p).step = d -> {
                    int cur = razeIndex(c, o);
                    int next = Math.max(0, Math.min(o.labels.length - 1, cur + d));
                    if (next == cur) return;
                    prefs(c).edit().putInt(o.pref, next).apply();
                    if (onChange != null) onChange.run();
                };
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

    // ---- Raze (OpenGL ES) ----

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
            new RazeOpt("gfx_colors", "raze_paleta", 0,
                    new String[]{"gfx_truecolor", "gfx_palette"},
                    new String[]{"hw_useindexedcolortextures 0", "hw_useindexedcolortextures 1"}),
            new RazeOpt("gfx_hq", "raze_hq", 0,
                    new String[]{"no", "xBRZ 2x", "xBRZ 3x"},
                    new String[]{"gl_texture_hqresizemode 0",
                            "gl_texture_hqresizemult 2;gl_texture_hqresizemode 4",
                            "gl_texture_hqresizemult 3;gl_texture_hqresizemode 4"}),
            // Raze trae los modelos apagados (hw_models false): con el HRP salían los sprites planos.
            new RazeOpt("gfx_models", "raze_modelos", 1,
                    new String[]{"no", "yes"}, new String[]{"hw_models 0", "hw_models 1"}),
            new RazeOpt("gfx_hightile", "raze_hightile", 1,
                    new String[]{"no", "yes"}, new String[]{"hw_hightile 0", "hw_hightile 1"}),
            new RazeOpt("gfx_voxels", "raze_voxels", 1,
                    new String[]{"no", "yes"}, new String[]{"r_voxels 0", "r_voxels 1"}),
            new RazeOpt("gfx_shadows", "raze_sombras", 1,
                    new String[]{"no", "yes"}, new String[]{"r_shadows 0", "r_shadows 1"}),
            // Luces de explosiones, disparos, fuego... (parche 31 de raze/parchear.py).
            new RazeOpt("gfx_lights", "raze_luces", 1,
                    new String[]{"no", "yes"}, new String[]{"hw_dynlights 0", "hw_dynlights 1"}),
            // Las paredes y columnas tapan las luces (sombras básicas, ver parche 31).
            new RazeOpt("gfx_light_shadows", "raze_sombras_luces", 1,
                    new String[]{"no", "yes"}, new String[]{"hw_lightshadows 0", "hw_lightshadows 1"}),
            // Más campo de visión en pantallas panorámicas (Raze lo corrige según la proporción).
            // (Clave nueva: en «raze_fov» el 0 era 90°.) De serie 90°, también con los perfiles.
            new RazeOpt("gfx_fov", "raze_campo", 2,
                    new String[]{"70°", "80°", "90°", "100°", "110°", "120°"},
                    new String[]{"r_fov 70", "r_fov 80", "r_fov 90", "r_fov 100", "r_fov 110", "r_fov 120"}),
            new RazeOpt("gfx_fps_counter", "raze_contador", 0,
                    new String[]{"no", "yes"}, new String[]{"vid_fps 0", "vid_fps 1"}),
    };

    // ---- Perfiles: cambian de una vez la resolución y las opciones que más pesan ----
    // Orden de los valores: escala, filtro, anisotrópico, suavizado xBRZ, modelos, sombras.
    private static final String[] PRESET_KEYS = {"gfx_preset_perf", "gfx_preset_bal", "gfx_preset_quality"};
    private static final int[][] PRESETS = {
            {75, 0, 0, 0, 1, 0},
            {100, 0, 2, 0, 1, 1},
            {100, 3, 3, 1, 1, 1},
    };
    private static final String[] PRESET_PREFS = {"raze_filtro", "raze_aniso", "raze_hq", "raze_modelos", "raze_sombras"};

    /** El perfil que coincide con los ajustes actuales, o -1 (personalizado). */
    static int currentPreset(Context c) {
        for (int i = 0; i < PRESETS.length; i++) {
            boolean ok = scale(c) == PRESETS[i][0];
            for (int k = 0; ok && k < PRESET_PREFS.length; k++) ok = razeIndex(c, opt(PRESET_PREFS[k])) == PRESETS[i][k + 1];
            if (ok) return i;
        }
        return -1;
    }

    private static RazeOpt opt(String pref) {
        for (RazeOpt o : RAZE_OPTS) if (o.pref.equals(pref)) return o;
        throw new IllegalArgumentException(pref);
    }

    private static void applyPreset(Context c, int i) {
        SharedPreferences.Editor e = prefs(c).edit().putInt("escala", PRESETS[i][0]);
        for (int k = 0; k < PRESET_PREFS.length; k++) e.putInt(PRESET_PREFS[k], PRESETS[i][k + 1]);
        e.apply();
    }

    private static int razeIndex(Context c, RazeOpt o) {
        int i = prefs(c).getInt(o.pref, o.def);
        return i >= 0 && i < o.labels.length ? i : o.def;
    }

    /** Las órdenes de consola de Raze para los ajustes guardados. */
    static java.util.List<String> razeCommands(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        // OpenGL ES no tiene posprocesado (FXAA, resplandor, tonos, oclusión, destellos): apagado.
        out.add("gl_fxaa 0");
        out.add("gl_bloom 0");
        out.add("gl_ssao 0");
        out.add("gl_lens 0");
        for (RazeOpt o : RAZE_OPTS) {
            for (String cmd : o.commands[razeIndex(c, o)].split(";")) out.add(cmd.trim());
        }
        return out;
    }

    private static DukeMenuView.Entry lastEntry(DukeMenuView.Page p) {
        return p.entries.get(p.entries.size() - 1);
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
