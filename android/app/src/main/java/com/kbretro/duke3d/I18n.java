package com.kbretro.duke3d;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Textos del lanzador en español e inglés. El idioma se guarda en las preferencias. */
final class I18n {
    static final String ES = "es";
    static final String EN = "en";

    private static String lang = ES;
    private static final Map<String, String[]> TEXTS = new HashMap<>();

    private static void put(String key, String es, String en) {
        TEXTS.put(key, new String[]{es, en});
    }

    static {
        put("options", "Opciones", "Options");
        put("quit", "Salir", "Quit");
        put("back", "Volver", "Back");
        put("language", "Idioma", "Language");
        put("language_value", "Español", "English");
        put("folder", "Carpeta del juego", "Game folder");
        put("hint", "A: aceptar   B: volver", "A: select   B: back");
        put("hint_value", "A: cambiar   B: volver", "A: change   B: back");
        put("loading", "Cargando...", "Loading...");
        put("data", "Datos: ", "Data: ");
        put("caribbean", "Caribbean", "Caribbean");
        put("menu_music", "Música del menú", "Menu music");
        put("extras", "Extras", "Extras");
        put("music", "Música", "Music");
        put("playing", "Sonando: ", "Now playing: ");
        put("no_tracks", "No hay pistas", "No tracks");
        // Menú rápido (durante la partida)
        put("continue", "Continuar", "Continue");
        put("quicksave", "Guardado rápido", "Quick save");
        put("quickload", "Carga rápida", "Quick load");
        put("cheats", "Trucos", "Cheats");
        put("graphics", "Gráficos", "Graphics");
        put("game_menu", "Menú del juego", "Game menu");
        put("to_launcher", "Volver al lanzador", "Back to launcher");
        put("quit_game", "Salir del juego", "Quit game");
        put("unlimited", "Sin límite", "Unlimited");
        put("resolution", "Resolución", "Resolution");
        put("enhanced_soon", "Modo mejorado: pronto", "Enhanced mode: soon");
        put("cheat_god", "Modo dios", "God mode");
        put("cheat_noclip", "Atravesar paredes", "No clipping");
        put("cheat_weapons", "Todas las armas", "All weapons");
        put("cheat_ammo", "Munición", "Ammo");
        put("cheat_keys", "Todas las llaves", "All keys");
        put("cheat_health", "Salud", "Health");
        put("cheat_armor", "Blindaje", "Armor");
        put("cheat_inventory", "Inventario", "Inventory");
        put("cheat_all", "Todo", "Everything");
        put("cheat_done", "Hecho: ", "Done: ");
        put("yes", "Sí", "On");
        put("no", "No", "Off");
    }

    static void load(Context c) {
        String saved = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getString("idioma", null);
        if (saved == null) saved = Locale.getDefault().getLanguage().equals("es") ? ES : EN;
        lang = saved;
    }

    static void set(Context c, String l) {
        lang = l;
        c.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putString("idioma", l).apply();
    }

    static String lang() {
        return lang;
    }

    static String t(String key) {
        String[] v = TEXTS.get(key);
        if (v == null) return key;
        return EN.equals(lang) ? v[1] : v[0];
    }

    private I18n() {
    }
}
