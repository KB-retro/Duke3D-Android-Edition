package com.kbretro.duke3d;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Los logros de Duke Nukem 3D: Megaton Edition (los de Steam), adaptados al APK: se cumplen
 * jugando aquí y se guardan en el propio teléfono (preferencias "logros"), sin Steam.
 *
 * El motor avisa de lo que pasa (ver G_AndroidEvent en game.cpp) y aquí se decide qué logro
 * se desbloquea. Los tres de Dukematch necesitan partidas en red, que todavía no hay.
 */
final class Achievements {
    // Lo que manda el motor (el mismo orden que ANDROID_EV_* en player.h).
    static final int EV_KILL = 1, EV_TIP = 2, EV_SECRET = 3, EV_SQUISH = 4, EV_CHEAT = 5,
            EV_POOP = 6, EV_LUKE = 7, EV_DOOMGUY = 8, EV_LEVEL_END = 9;

    static final class Def {
        final String id, name, es, en;
        /** Objetivo de un contador (0 si no lleva). */
        final int goal;
        final String counter;
        final boolean online;

        Def(String id, String name, String es, String en, String counter, int goal, boolean online) {
            this.id = id;
            this.name = name;
            this.es = es;
            this.en = en;
            this.counter = counter;
            this.goal = goal;
            this.online = online;
        }

        String description() {
            return I18n.EN.equals(I18n.lang()) ? en : es;
        }
    }

    static final Def[] ALL = {
            new Def("shake", "Shake It Baby", "Dale una propina a una bailarina", "Tip an exotic dancer", null, 0, false),
            new Def("prisoners", "Take No Prisoners", "Mata a todos los enemigos de un nivel", "Kill every enemy on a level", null, 0, false),
            new Def("doomed", "Doomed Marine", "Encuentra al marine de Doom en Death Row", "Find the Doomguy on Death Row", null, 0, false),
            new Def("ep1", "Piece of Cake", "Completa L.A. Meltdown", "Complete L.A. Meltdown", null, 0, false),
            new Def("poop", "Shit Happens", "Pisa una mierda", "Step in a pile of excrement", null, 0, false),
            new Def("kills", "Invaders Must Die!", "Mata a 1000 enemigos", "Kill 1000 enemies", "c_kills", 1000, false),
            new Def("shame", "Shame", "Usa un truco", "Use a cheat code", null, 0, false),
            new Def("foot", "Duke's Mighty Foot", "Pisa a 40 enemigos encogidos", "Step on 40 shrunken enemies", "c_squish", 40, false),
            new Def("secrets", "Oomph, Uugh, Where is it?", "Encuentra 70 zonas secretas", "Find 70 secret areas", "c_secrets", 70, false),
            new Def("ep2", "Let's Rock", "Completa Lunar Apocalypse", "Complete Lunar Apocalypse", null, 0, false),
            new Def("hollywood", "Alien Bastards are Gonna Pay", "Acaba Hollywood Holocaust en menos de 3 minutos", "Finish Hollywood Holocaust in under 3 minutes", null, 0, false),
            new Def("ep3", "Come Get Some", "Completa Shrapnel City", "Complete Shrapnel City", null, 0, false),
            new Def("ep4", "Damn I'm Good", "Completa The Birth", "Complete The Birth", null, 0, false),
            new Def("dc", "The Patriot Act", "Completa Duke It Out In D.C.", "Complete Duke It Out In D.C.", null, 0, false),
            new Def("luke", "Duke, Use The Force!", "Encuentra a Luke Skywalker en Lunar Reactor", "Find Luke Skywalker on Lunar Reactor", null, 0, false),
            new Def("nw", "I've Got Balls of Snow", "Completa Duke: Nuclear Winter", "Complete Duke: Nuclear Winter", null, 0, false),
            new Def("vacation", "Limbo time!", "Completa Duke Caribbean: Life's A Beach", "Complete Duke Caribbean: Life's A Beach", null, 0, false),
            new Def("dm100", "Gonna Rip Em' a New One", "100 bajas en Dukematch (en red, aún no disponible)", "100 Dukematch kills (online, not available yet)", null, 0, true),
            new Def("dm250", "See You in Hell", "250 bajas en Dukematch (en red, aún no disponible)", "250 Dukematch kills (online, not available yet)", null, 0, true),
            new Def("dm500", "Hail to the King, Baby", "500 bajas en Dukematch (en red, aún no disponible)", "500 Dukematch kills (online, not available yet)", null, 0, true),
    };

    /** Se llama al desbloquear uno (en el hilo del juego). */
    interface Listener {
        void unlocked(Def def);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("logros", Context.MODE_MULTI_PROCESS);
    }

    static boolean unlocked(Context c, Def d) {
        return prefs(c).getBoolean(d.id, false);
    }

    static int count(Context c, Def d) {
        return d.counter != null ? prefs(c).getInt(d.counter, 0) : 0;
    }

    static int unlockedCount(Context c) {
        int n = 0;
        for (Def d : ALL) if (unlocked(c, d)) n++;
        return n;
    }

    static Def byId(String id) {
        for (Def d : ALL) if (d.id.equals(id)) return d;
        return null;
    }

    /** "base", "dc", "nw" o "vacation", según el GRP que se lanza. */
    static String gameId(String grpName) {
        if (grpName == null) return "base";
        String g = grpName.toUpperCase(java.util.Locale.ROOT);
        if (g.contains("DUKEDC")) return "dc";
        if (g.contains("NWINTER")) return "nw";
        if (g.contains("VACATION")) return "vacation";
        return "base";
    }

    /** Un aviso del motor. */
    static synchronized void onEvent(Context c, String game, int type, int a, int b, int flags, Listener l) {
        switch (type) {
            case EV_KILL:
                add(c, "kills", a, l);
                break;
            case EV_TIP:
                unlock(c, "shake", l);
                break;
            case EV_SECRET:
                add(c, "secrets", 1, l);
                break;
            case EV_SQUISH:
                add(c, "foot", 1, l);
                break;
            case EV_CHEAT:
                unlock(c, "shame", l);
                break;
            case EV_POOP:
                unlock(c, "poop", l);
                break;
            case EV_LUKE:
                unlock(c, "luke", l);
                break;
            case EV_DOOMGUY:
                unlock(c, "doomed", l);
                break;
            case EV_LEVEL_END:
                levelEnd(c, game, a, b, flags, l);
                break;
            default:
                break;
        }
    }

    private static void levelEnd(Context c, String game, int volume, int level, int flags, Listener l) {
        int seconds = flags >>> 8;
        if ((flags & 1) != 0) unlock(c, "prisoners", l);
        boolean base = "base".equals(game);
        if (base && volume == 0 && level == 0 && seconds < 180) unlock(c, "hollywood", l);
        if ((flags & 2) == 0) return;
        if (base) {
            if (volume >= 0 && volume <= 3) unlock(c, "ep" + (volume + 1), l);
        } else {
            unlock(c, game, l); // dc, nw o vacation
        }
    }

    private static void add(Context c, String id, int n, Listener l) {
        Def d = byId(id);
        if (d == null || d.counter == null) return;
        SharedPreferences p = prefs(c);
        int v = p.getInt(d.counter, 0);
        if (v >= d.goal) return;
        v = Math.min(d.goal, v + n);
        p.edit().putInt(d.counter, v).apply();
        if (v >= d.goal) unlock(c, id, l);
    }

    private static void unlock(Context c, String id, Listener l) {
        Def d = byId(id);
        if (d == null || d.online) return;
        SharedPreferences p = prefs(c);
        if (p.getBoolean(id, false)) return;
        p.edit().putBoolean(id, true).putLong(id + "_t", System.currentTimeMillis()).apply();
        if (l != null) l.unlocked(d);
    }

    private Achievements() {
    }
}
