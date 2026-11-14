package com.kbretro.duke3d;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Ajustes de controles: los táctiles (cuándo se ven y su sensibilidad) y la reasignación de
 * los botones del mando a acciones del juego, hecha aquí y no con el menú del motor.
 */
final class ControlSettings {
    static final int DEFAULT = -1, NONE = -2, QUICK_MENU = -3;

    /** Acciones que se pueden poner en un botón: gamefunc_* (function.h) o especiales. */
    static final int[] ACTIONS = {DEFAULT, 5, 6, 9, 10, 7, 49, 50, 51, 57, 29, 30, 31, 35, 33, 48, 34, 32, 38, 36,
            41, 62, 56, QUICK_MENU, NONE};
    private static final String[][] ACTION_NAMES = {
            {"Por defecto", "Default"}, {"Disparar", "Fire"}, {"Usar / abrir", "Use / open"},
            {"Saltar", "Jump"}, {"Agacharse", "Crouch"}, {"Correr", "Run"}, {"Patada", "Kick"},
            {"Arma siguiente", "Next weapon"}, {"Arma anterior", "Previous weapon"},
            {"Última arma", "Last weapon"}, {"Usar objeto", "Use item"}, {"Objeto anterior", "Previous item"},
            {"Objeto siguiente", "Next item"}, {"Botiquín", "Medkit"}, {"Jetpack", "Jetpack"},
            {"Esteroides", "Steroids"}, {"Visión nocturna", "Night vision"}, {"Holoduke", "Holoduke"},
            {"Mapa", "Map"}, {"Media vuelta", "Turn around"}, {"Centrar vista", "Center view"},
            {"Agacharse (fijo)", "Toggle crouch"}, {"Correr siempre", "Auto run"},
            {"Menú rápido", "Quick menu"}, {"Nada", "Nothing"},
    };

    /** Botones del mando que se pueden reasignar. */
    static final int[] KEYS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    };
    private static final String[] KEY_NAMES = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "Start",
            "Cruceta arriba", "Cruceta abajo", "Cruceta izquierda", "Cruceta derecha"};
    private static final String[] KEY_NAMES_EN = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "Start",
            "D-pad up", "D-pad down", "D-pad left", "D-pad right"};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_MULTI_PROCESS);
    }

    /** 0 automático (ocultos si hay mando), 1 siempre, 2 nunca. */
    static int touchMode(Context c) {
        int m = prefs(c).getInt("tactil", 0);
        return m >= 0 && m <= 2 ? m : 0;
    }

    static String touchModeLabel(Context c) {
        switch (touchMode(c)) {
            case 1:
                return I18n.t("yes");
            case 2:
                return I18n.t("no");
            default:
                return I18n.t("touch_auto");
        }
    }

    // ---- Táctiles de los juegos de MS-DOS (sección Classic): ajuste propio ----

    static int dosTouchMode(Context c) {
        int m = prefs(c).getInt("dos_tactil", 0);
        return m >= 0 && m <= 2 ? m : 0;
    }

    static String dosTouchModeLabel(Context c) {
        int m = dosTouchMode(c);
        return I18n.t(m == 1 ? "yes" : m == 2 ? "no" : "touch_auto");
    }

    static void cycleDosTouchMode(Context c) {
        prefs(c).edit().putInt("dos_tactil", (dosTouchMode(c) + 1) % 3).apply();
    }

    static void cycleTouchMode(Context c) {
        prefs(c).edit().putInt("tactil", (touchMode(c) + 1) % 3).apply();
    }

    private static final float[] SENS = {0.6f, 1f, 1.6f};
    private static final String[] SENS_KEYS = {"gfx_low", "gfx_mid", "gfx_high"};

    static int sensIndex(Context c) {
        int s = prefs(c).getInt("tactil_sens", 1);
        return s >= 0 && s < SENS.length ? s : 1;
    }

    static float sensitivity(Context c) {
        return SENS[sensIndex(c)];
    }

    static String sensLabel(Context c) {
        return I18n.t(SENS_KEYS[sensIndex(c)]);
    }

    static void cycleSens(Context c) {
        prefs(c).edit().putInt("tactil_sens", (sensIndex(c) + 1) % SENS.length).apply();
    }

    // ---- Sensibilidad de los sticks del mando ----

    private static final float[] LOOK = {0.5f, 0.75f, 1f, 1.35f, 1.75f, 2.25f};
    private static final float[] MOVE = {0.75f, 1f, 1.25f, 1.5f};
    private static final String[] LOOK_LABELS = {"1", "2", "3", "4", "5", "6"};
    private static final String[] MOVE_LABELS = {"1", "2", "3", "4"};

    private static int stickIndex(Context c, String key, int def, int n) {
        int v = prefs(c).getInt(key, def);
        return v >= 0 && v < n ? v : def;
    }

    /** Stick derecho (mirar/girar). */
    static float stickLook(Context c) {
        return LOOK[stickIndex(c, "stick_mirar", 2, LOOK.length)];
    }

    /** Stick izquierdo (andar): con más sensibilidad se llega antes a la velocidad máxima. */
    static float stickMove(Context c) {
        return MOVE[stickIndex(c, "stick_andar", 1, MOVE.length)];
    }

    static String stickLookLabel(Context c) {
        return LOOK_LABELS[stickIndex(c, "stick_mirar", 2, LOOK.length)] + "/" + LOOK.length;
    }

    static String stickMoveLabel(Context c) {
        return MOVE_LABELS[stickIndex(c, "stick_andar", 1, MOVE.length)] + "/" + MOVE.length;
    }

    static void cycleStickLook(Context c) {
        prefs(c).edit().putInt("stick_mirar", (stickIndex(c, "stick_mirar", 2, LOOK.length) + 1) % LOOK.length).apply();
    }

    static void cycleStickMove(Context c) {
        prefs(c).edit().putInt("stick_andar", (stickIndex(c, "stick_andar", 1, MOVE.length) + 1) % MOVE.length).apply();
    }

    static void stepStickMove(Context c, int dir) {
        int i = stickIndex(c, "stick_andar", 1, MOVE.length);
        prefs(c).edit().putInt("stick_andar", Math.max(0, Math.min(MOVE.length - 1, i + dir))).apply();
    }

    // ---- Sensibilidad H y V del stick derecho (1-10; 5 = la de siempre) ----

    static int padSens(Context c, boolean vertical) {
        int v = prefs(c).getInt(vertical ? "mando_sens_v" : "mando_sens_h", 5);
        return v >= 1 && v <= 10 ? v : 5;
    }

    /** Multiplicador del giro (H) o de la mirada arriba/abajo (V): 5 = 1. */
    static float padSensFactor(Context c, boolean vertical) {
        return padSens(c, vertical) / 5f;
    }

    static String padSensLabel(Context c, boolean vertical) {
        return padSens(c, vertical) + "/10";
    }

    /** Suma delta (izquierda/derecha, sin pasar de 1 ni de 10) o, con delta 0, da la vuelta (A). */
    static void stepPadSens(Context c, boolean vertical, int delta) {
        int v = padSens(c, vertical);
        v = delta == 0 ? (v % 10) + 1 : Math.max(1, Math.min(10, v + delta));
        prefs(c).edit().putInt(vertical ? "mando_sens_v" : "mando_sens_h", v).apply();
    }

    /** La acción puesta en un botón del mando (DEFAULT si no se ha cambiado). */
    static int remap(Context c, int keyCode) {
        return prefs(c).getInt("boton_" + keyCode, DEFAULT);
    }

    static String actionName(int action) {
        for (int i = 0; i < ACTIONS.length; i++) {
            if (ACTIONS[i] == action) return ACTION_NAMES[i][I18n.EN.equals(I18n.lang()) ? 1 : 0];
        }
        return "?";
    }

    static void cycleRemap(Context c, int keyCode) {
        cycleRemap(c, keyCode, 1);
    }

    /** Pasa a la acción siguiente (dir 1) o a la anterior (dir -1). */
    static void cycleRemap(Context c, int keyCode, int dir) {
        int cur = remap(c, keyCode), i = 0;
        for (int k = 0; k < ACTIONS.length; k++) if (ACTIONS[k] == cur) i = k;
        int next = ACTIONS[((i + dir) % ACTIONS.length + ACTIONS.length) % ACTIONS.length];
        if (next == DEFAULT) prefs(c).edit().remove("boton_" + keyCode).apply();
        else prefs(c).edit().putInt("boton_" + keyCode, next).apply();
    }

    static void resetRemap(Context c, int keyCode) {
        prefs(c).edit().remove("boton_" + keyCode).apply();
    }

    static void resetRemaps(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int k : KEYS) e.remove("boton_" + k);
        e.apply();
    }

    static String keyName(int i) {
        return I18n.EN.equals(I18n.lang()) ? KEY_NAMES_EN[i] : KEY_NAMES[i];
    }

    /** ¿Hay algún mando o consola con botones conectado? */
    static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    private ControlSettings() {
    }
}
