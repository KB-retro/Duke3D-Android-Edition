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
        put("vibration", "Vibración", "Vibration");
        put("yes", "Sí", "On");
        put("no", "No", "Off");
        put("achievements", "Logros", "Achievements");
        put("gfx_scaler", "Escalado", "Scaling");
        put("gfx_pixel", "Píxel", "Pixel");
        put("gfx_sharp", "Nítido", "Sharp");
        put("gfx_smooth", "Suave", "Smooth");
        put("gfx_fsr", "FSR 1.0", "FSR 1.0");
        put("gfx_bicubic", "Bicúbico", "Bicubic");
        put("gfx_lcd", "LCD", "LCD");
        put("gfx_warm", "Cálido", "Warm");
        put("gfx_cold", "Frío", "Cold");
        put("gfx_cinema", "Cine", "Cinema");
        put("gfx_bloom", "Resplandor", "Bloom");
        put("gfx_soft", "Suave", "Soft");
        put("gfx_strong", "Fuerte", "Strong");
        put("gfx_sharpness", "Nitidez", "Sharpness");
        put("gfx_low", "Baja", "Low");
        put("gfx_mid", "Media", "Medium");
        put("gfx_high", "Alta", "High");
        put("gfx_effect", "Efecto", "Effect");
        put("gfx_lines", "Líneas", "Scanlines");
        put("gfx_crt", "CRT", "CRT");
        put("gfx_color", "Color", "Color");
        put("gfx_normal", "Normal", "Normal");
        put("gfx_vivid", "Vivo", "Vivid");
        put("gfx_soft_note", "Filtros por GPU sobre el renderizado por software", "GPU filters over the software renderer");
        put("make_log", "Generar registro", "Save log");
        put("making_log", "Guardando registro...", "Saving log...");
        put("log_saved", "Registro guardado: ", "Log saved: ");
        put("mods", "Mods", "Mods");
        put("mods_empty", "Pon los mods (.zip, .pk3, .grp) en ", "Put mods (.zip, .pk3, .grp) in ");
        put("mods_note", "vóxeles y mapas sí; texturas y modelos HD no", "voxels and maps work; HD textures and models do not");
        put("multiplayer", "Multijugador", "Multiplayer");
        put("net_game", "Juego", "Game");
        put("net_name", "Nombre", "Name");
        put("net_host", "Crear partida", "Host game");
        put("net_join", "Unirse a partida", "Join game");
        put("net_join_ip", "IP del anfitrión", "Host IP address");
        put("net_your_ip", "Tu IP: ", "Your IP: ");
        put("net_port", "Puerto ", "Port ");
        put("achievement_unlocked", "LOGRO DESBLOQUEADO", "ACHIEVEMENT UNLOCKED");
        put("unlocked_short", "OK", "OK");
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
