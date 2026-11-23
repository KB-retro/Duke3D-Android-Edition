package com.kbretro.duke3d;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Sonido (menú rápido → Ajustes → Sonido): volumen general, de la música y de los efectos, y
 * música sí/no. Las opciones de Raze ya no se ven (su «Opciones» abre los Ajustes de la app), así
 * que el volumen va aquí. Izquierda/derecha cambian el valor; se aplican al momento y al arrancar.
 */
final class SoundSettings {

    private static final class Opt {
        final String label, pref, cvar;
        final int def;
        final boolean onOff;

        Opt(String label, String pref, String cvar, int def, boolean onOff) {
            this.label = label;
            this.pref = pref;
            this.cvar = cvar;
            this.def = def;
            this.onOff = onOff;
        }

        int max() {
            return onOff ? 1 : 10;
        }

        String command(int v) {
            return onOff ? cvar + " " + v : cvar + " " + (v / 10f);
        }

        String label(int v) {
            return onOff ? I18n.t(v != 0 ? "yes" : "no") : (v * 10) + "%";
        }
    }

    private static final Opt[] OPTS = {
            new Opt("snd_master", "snd_general", "snd_mastervolume", 10, false),
            new Opt("snd_music", "snd_musica", "snd_musicvolume", 5, false),
            new Opt("snd_sfx", "snd_efectos", "snd_sfxvolume", 10, false),
            new Opt("snd_music_on", "snd_musica_si", "mus_enabled", 1, true),
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_MULTI_PROCESS);
    }

    private static int value(Context c, Opt o) {
        int v = prefs(c).getInt(o.pref, o.def);
        return v >= 0 && v <= o.max() ? v : o.def;
    }

    /** Las órdenes de consola de Raze para los ajustes guardados. */
    static java.util.List<String> commands(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Opt o : OPTS) out.add(o.command(value(c, o)));
        return out;
    }

    static void addTo(Context c, DukeMenuView.Page p, java.util.function.Consumer<String> apply) {
        for (Opt o : OPTS) {
            p.addValue(I18n.t(o.label), () -> o.label(value(c, o)), () -> {
                int next = (value(c, o) + 1) % (o.max() + 1);
                prefs(c).edit().putInt(o.pref, next).apply();
                apply.accept(o.command(next));
            });
            p.entries.get(p.entries.size() - 1).step = d -> {
                int cur = value(c, o);
                int next = Math.max(0, Math.min(o.max(), cur + d));
                if (next == cur) return;
                prefs(c).edit().putInt(o.pref, next).apply();
                apply.accept(o.command(next));
            };
        }
    }

    private SoundSettings() {
    }
}
