package com.kbretro.duke3d;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ajustes de imagen de Raze (menú rápido → Ajustes → Imagen): campo de visión, tamaño de pantalla
 * y estilo del HUD, su escala, la retícula, la emulación de paleta y el contador de FPS. Izquierda/derecha cambian el valor; se
 * guardan en "ajustes" y se aplican al momento y en cada arranque.
 */
final class ImageSettings {

    private static final class Opt {
        final String label, pref;
        final int def;
        final String[] labels, commands;

        Opt(String label, String pref, int def, String[] labels, String[] commands) {
            this.label = label;
            this.pref = pref;
            this.def = def;
            this.labels = labels;
            this.commands = commands;
        }
    }

    private static final Opt[] OPTS = {
            // Campo de visión (antes en Gráficos; misma clave): de serie 90°.
            new Opt("gfx_fov", "raze_campo", 2,
                    new String[]{"70°", "80°", "90°", "100°", "110°", "120°"},
                    new String[]{"r_fov 70", "r_fov 80", "r_fov 90", "r_fov 100", "r_fov 110", "r_fov 120"}),
            // hud_size de Raze: 0-4 marco (pantalla reducida), 5 barra de estado, ... 10 sin HUD.
            new Opt("img_screen", "img_pantalla", 5,
                    new String[]{"img_frame50", "img_frame60", "img_frame70", "img_frame80", "img_frame90",
                            "img_stbar", "img_stbar_overlay", "img_mini", "img_full", "img_althud", "img_nothing"},
                    new String[]{"hud_size 0", "hud_size 1", "hud_size 2", "hud_size 3", "hud_size 4",
                            "hud_size 5", "hud_size 6", "hud_size 7", "hud_size 8", "hud_size 9", "hud_size 10"}),
            new Opt("img_hud_scale", "img_escala_hud", 4,
                    new String[]{"60%", "70%", "80%", "90%", "100%"},
                    new String[]{"hud_scalefactor 0.6", "hud_scalefactor 0.7", "hud_scalefactor 0.8",
                            "hud_scalefactor 0.9", "hud_scalefactor 1"}),
            new Opt("img_crosshair", "img_reticula", 1,
                    new String[]{"no", "yes"}, new String[]{"cl_crosshair 0", "cl_crosshair 1"}),
            new Opt("img_crosshair_type", "img_reticula_tipo", 0,
                    new String[]{"img_xh_standard", "img_xh_cross1", "img_xh_cross2", "X", "img_xh_circle",
                            "img_xh_angle", "img_xh_triangle", "img_xh_dot"},
                    new String[]{"crosshair 0", "crosshair 1", "crosshair 2", "crosshair 3", "crosshair 4",
                            "crosshair 5", "crosshair 6", "crosshair 7"}),
            new Opt("img_crosshair_size", "img_reticula_tam", 1,
                    new String[]{"img_small", "gfx_mid", "img_big", "img_huge"},
                    new String[]{"crosshairscale 0.35", "crosshairscale 0.5", "crosshairscale 0.75", "crosshairscale 1"}),
            // Colores como el original de 8 bits (paleta de Build). Apagada de serie.
            new Opt("img_palette", "img_paleta", 0,
                    new String[]{"no", "yes"},
                    new String[]{"hw_useindexedcolortextures 0", "hw_useindexedcolortextures 1"}),
            new Opt("gfx_fps_counter", "raze_contador", 0,
                    new String[]{"no", "yes"}, new String[]{"vid_fps 0", "vid_fps 1"}),
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_MULTI_PROCESS);
    }

    private static int index(Context c, Opt o) {
        int i = prefs(c).getInt(o.pref, o.def);
        return i >= 0 && i < o.labels.length ? i : o.def;
    }

    /** Las órdenes de consola de Raze para los ajustes guardados. */
    static java.util.List<String> commands(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Opt o : OPTS) out.add(o.commands[index(c, o)]);
        return out;
    }

    /**
     * Añade las opciones a una página (izquierda/derecha bajan y suben; A pasa al siguiente) y
     * el botón de restablecer.
     * @param apply manda una orden de consola al juego
     */
    static void addTo(Context c, DukeMenuView.Page p, java.util.function.Consumer<String> apply, Runnable onReset) {
        for (Opt o : OPTS) {
            p.addValue(I18n.t(o.label), () -> I18n.t(o.labels[index(c, o)]), () -> {
                int next = (index(c, o) + 1) % o.labels.length;
                prefs(c).edit().putInt(o.pref, next).apply();
                apply.accept(o.commands[next]);
            });
            p.entries.get(p.entries.size() - 1).step = d -> {
                int cur = index(c, o);
                int next = Math.max(0, Math.min(o.labels.length - 1, cur + d));
                if (next == cur) return;
                prefs(c).edit().putInt(o.pref, next).apply();
                apply.accept(o.commands[next]);
            };
        }
        p.add(I18n.t("img_reset"), null, () -> {
            SharedPreferences.Editor e = prefs(c).edit();
            for (Opt o : OPTS) e.remove(o.pref);
            e.apply();
            for (Opt o : OPTS) apply.accept(o.commands[o.def]);
            if (onReset != null) onReset.run();
        });
    }

    private ImageSettings() {
    }
}
