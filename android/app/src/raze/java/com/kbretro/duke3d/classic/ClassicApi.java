package com.kbretro.duke3d.classic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Duke Nukem 3D Classic (EDuke32 por software) dentro de Duke Legacy: lo que necesita el
 * lanzador de la Legacy (juegos, mods, campañas, opciones y el lanzamiento). El resto del código
 * de la Classic va en este paquete tal cual, con sus ajustes aparte ("classic_ajustes").
 */
public final class ClassicApi {
    private static final String PREFS = "classic_ajustes";

    /** Una opción con valor (izquierda/derecha la cambian). */
    public static final class Option {
        public final String label;
        public final Supplier<String> value;
        public final Runnable action;
        public final IntConsumer step;

        Option(String label, Supplier<String> value, Runnable action, IntConsumer step) {
            this.label = label;
            this.value = value;
            this.action = action;
            this.step = step;
        }
    }

    /** Un juego o expansión. */
    public static final class Game {
        public final String title;
        public final String label;
        final GameData.Game game;

        Game(GameData.Game g) {
            game = g;
            title = g.title;
            label = g.menuLabel();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    public static List<Game> games(Context c, File gameDir) {
        I18n.load(c);
        List<Game> out = new ArrayList<>();
        for (GameData.Game g : GameData.games(gameDir)) out.add(new Game(g));
        return out;
    }

    // ---- Mods ----

    public static final String CAMPAIGN_DIR = GameData.CAMPAIGN_DIR, GFX_DIR = GameData.GFX_DIR;

    public static void makeCategoryDirs(File gameDir) {
        GameData.makeCategoryDirs(gameDir);
    }

    /**
     * Los mods que sirven en la Classic: sin el pack HD (HRP). El motor por software no usa sus
     * texturas ni sus modelos, y leer sus definiciones dejaba la pantalla en negro casi un minuto.
     */
    public static List<File> mods(File gameDir) {
        List<File> out = new ArrayList<>();
        for (File f : GameData.mods(gameDir)) if (!isHrp(f)) out.add(f);
        return out;
    }

    static boolean isHrp(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.contains("hrp") || n.contains("texturas hd") || n.contains("texturas_hd")) return true;
        return f.isDirectory() && (new File(f, "duke3d_hrp.def").exists() || new File(f, "duke3d_hrp_megaton.def").exists());
    }

    public static boolean isCampaign(File mod) {
        return GameData.isCampaign(mod);
    }

    public static File findChild(File dir, String name) {
        return GameData.findChild(dir, name);
    }

    public static Set<String> enabledMods(Context c) {
        return new HashSet<>(prefs(c).getStringSet("mods_activos", new HashSet<>()));
    }

    public static void setEnabledMods(Context c, Set<String> set) {
        prefs(c).edit().putStringSet("mods_activos", set).apply();
    }

    /** La campaña activada (o null). */
    public static File activeCampaign(Context c, File gameDir) {
        Set<String> enabled = enabledMods(c);
        if (enabled.isEmpty()) return null;
        for (File f : mods(gameDir)) if (enabled.contains(f.getName()) && GameData.isCampaign(f)) return f;
        return null;
    }

    /** Activa o apaga un mod: una campaña va sola y un mod de gráficos apaga la campaña. */
    public static void toggleMod(Context c, File gameDir, String name, boolean campaign) {
        Set<String> set = enabledMods(c);
        boolean on = !set.contains(name);
        if (on && campaign) set.clear();
        else if (on) {
            File camp = activeCampaign(c, gameDir);
            if (camp != null) set.remove(camp.getName());
        }
        if (on) set.add(name);
        else set.remove(name);
        setEnabledMods(c, set);
    }

    public static String progress() {
        return GameData.progress;
    }

    public static String progressValue() {
        return GameData.progressValue;
    }

    /**
     * Prepara una partida (traducción, mods y argumentos) y devuelve la actividad que la lanza.
     * Tarda (la primera vez se descomprimen los mods): fuera del hilo de la interfaz.
     */
    public static Intent prepareLaunch(Context c, File gameDir, Game game, boolean spanish) throws Exception {
        I18n.load(c);
        String def = null;
        if (spanish) {
            try {
                List<GameData.Game> games = GameData.games(gameDir);
                def = Translator.prepare(c, gameDir, games.get(0), game.game);
            } catch (Throwable e) {
                android.util.Log.e("Duke3D", "Classic: no se pudo preparar la traducción", e);
            }
        }
        File camp = activeCampaign(c, gameDir);
        Set<String> enabled = camp != null ? java.util.Collections.singleton(camp.getName()) : enabledMods(c);
        Set<String> usable = new HashSet<>();
        for (File f : mods(gameDir)) if (enabled.contains(f.getName())) usable.add(f.getName());
        enabled = usable;
        List<String> modArgs = GameData.modArguments(gameDir, enabled);
        List<String> args = new ArrayList<>(Arrays.asList(GameData.arguments(c, gameDir, game.game, def)));
        GameData.chainModCons(c, args, modArgs);
        args.addAll(modArgs);
        List<GameData.Game> all = GameData.games(gameDir);
        Intent intent = new Intent(c, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_DIR, gameDir.getAbsolutePath());
        intent.putExtra(GameActivity.EXTRA_ARGS, args.toArray(new String[0]));
        intent.putExtra(GameActivity.EXTRA_LANG, def != null ? "es" : "en");
        if (!all.isEmpty()) intent.putExtra(GameActivity.EXTRA_BASE_GRP, all.get(0).grp.getAbsolutePath());
        return intent;
    }

    // ---- Opciones (independientes de las de la Legacy) ----

    /** FPS, vibración y controles táctiles. */
    public static List<Option> options(Context c) {
        I18n.load(c);
        List<Option> out = new ArrayList<>();
        out.add(new Option("FPS", () -> {
            int f = prefs(c).getInt("fps", 60);
            return f <= 0 ? I18n.t("unlimited") : f + " FPS";
        }, () -> {
            int[] values = {60, 120, 0};
            int cur = prefs(c).getInt("fps", 60), i = 0;
            for (int k = 0; k < values.length; k++) if (values[k] == cur) i = k;
            prefs(c).edit().putInt("fps", values[(i + 1) % values.length]).apply();
        }, null));
        out.add(new Option(I18n.t("vibration"), () -> I18n.t(prefs(c).getBoolean("vibracion", true) ? "yes" : "no"),
                () -> prefs(c).edit().putBoolean("vibracion", !prefs(c).getBoolean("vibracion", true)).apply(), null));
        out.add(new Option(I18n.t("touch_controls"), () -> ControlSettings.touchModeLabel(c),
                () -> ControlSettings.cycleTouchMode(c), null));
        return out;
    }

    /** Gráficos de la Classic: resolución y filtros de imagen. */
    public static List<Option> graphics(Context c) {
        I18n.load(c);
        DukeMenuView.Page p = new DukeMenuView.Page();
        GraphicsSettings.addTo(c, p, null, null);
        List<Option> out = new ArrayList<>();
        for (DukeMenuView.Entry e : p.entries) out.add(new Option(e.label, e.value, e.action, e.step));
        return out;
    }

    public static String graphicsNote() {
        return I18n.t("gfx_soft_note");
    }

    /** Todos los ajustes de la Classic a los de serie (sin tocar los mods activados). */
    public static void resetSettings(Context c) {
        SharedPreferences sp = prefs(c);
        Set<String> mods = enabledMods(c);
        sp.edit().clear().putStringSet("mods_activos", mods).apply();
    }

    private ClassicApi() {
    }
}
