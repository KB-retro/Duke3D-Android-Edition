package com.kbretro.duke3d;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Juegos de PlayStation de los extras (Duke Nukem: Time to Kill...), con SwanStation (libpsxcore.so)
 * y nuestro frontend (libpsx.so, ver cpp/psx/psx_main.cpp). Va en su propio proceso (":psx").
 *
 * El juego arranca directamente. El mando va como un DualShock (los dos sticks). Atrás abre el
 * menú rápido: guardado y carga rápidos, gráficos (pantalla 4:3/panorámica, resolución...) y
 * configurar mando.
 */
public class PsxActivity extends SDLActivity {
    static final String EXTRA_DISC = "disc";
    static final String EXTRA_SYSTEM_DIR = "system_dir";
    static final String EXTRA_SAVE_DIR = "save_dir";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_BASE_GRP = "base_grp";

    static native void nativeButton(int id, boolean down);

    static native void nativeAnalog(int stick, int x, int y);

    static native void nativeOption(String key, String value);

    /** 1 pausa, 2 seguir, 3 guardado rápido, 4 carga rápida, 5 salir. 0 = bien, 1 = no hay guardado. */
    static native int nativeCommand(int cmd);

    static native void nativePostFx(int scaler, int fxaa, int sharp, int crt, int color, int bloom);

    private static final int CMD_PAUSE = 1, CMD_RESUME = 2, CMD_SAVE = 3, CMD_LOAD = 4, CMD_QUIT = 5;

    // Botones de la RetroPad (RETRO_DEVICE_ID_JOYPAD_*). En la PlayStation: B = Cruz, A = Círculo,
    // Y = Cuadrado, X = Triángulo.
    static final int P_CROSS = 0, P_SQUARE = 1, P_SELECT = 2, P_START = 3, P_UP = 4, P_DOWN = 5, P_LEFT = 6,
            P_RIGHT = 7, P_CIRCLE = 8, P_TRIANGLE = 9, P_L1 = 10, P_R1 = 11, P_L2 = 12, P_R2 = 13, P_L3 = 14,
            P_R3 = 15, P_NONE = -1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DukeMenuView quickMenu;
    private DukeArt art;
    private String status;

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "psx"};
    }

    @Override
    protected String[] getArguments() {
        Intent i = getIntent();
        List<String> a = new ArrayList<>(Arrays.asList(i.getStringExtra(EXTRA_DISC), i.getStringExtra(EXTRA_SYSTEM_DIR),
                i.getStringExtra(EXTRA_SAVE_DIR), i.getStringExtra(EXTRA_STATE)));
        for (String[] o : coreOptions()) a.add(o[0] + "=" + o[1]);
        return a.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        android.view.Window win = getWindow();
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            win.setAttributes(lp);
        }
        String save = getIntent().getStringExtra(EXTRA_SAVE_DIR);
        if (save != null) //noinspection ResultOfMethodCallIgnored
            new File(save).mkdirs();
        super.onCreate(savedInstanceState);
        I18n.load(this);
        hideSystemBars();
        if (mSurface != null) mSurface.setDefaultFocusHighlightEnabled(false);
        getWindow().getDecorView().setDefaultFocusHighlightEnabled(false);
        applyPostFx();

        // Gráficos del Duke 3D (letras) para el menú rápido.
        String grp = getIntent().getStringExtra(EXTRA_BASE_GRP);
        if (grp != null) {
            new Thread(() -> {
                try {
                    art = new DukeArt(Arrays.asList(new GrpArchive(new File(grp))));
                } catch (Exception e) {
                    Log.w("Duke3D", "Menú rápido sin gráficos del juego", e);
                }
            }, "ArteMenuRapido").start();
        }
    }

    private static void button(int id, boolean down) {
        if (id < 0) return;
        try {
            nativeButton(id, down);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private static void analog(int stick, float x, float y) {
        try {
            nativeAnalog(stick, Math.round(x * 32767), Math.round(y * 32767));
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private static void option(String key, String value) {
        try {
            nativeOption(key, value);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private static int command(int cmd) {
        try {
            return nativeCommand(cmd);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    // ---- Ajustes ----

    private SharedPreferences prefs() {
        return getSharedPreferences("ajustes", MODE_MULTI_PROCESS);
    }

    private static final String[] SCALER_KEYS = {"gfx_pixel", "gfx_sharp", "gfx_smooth", "gfx_bicubic", "gfx_fsr"};
    private static final String[] SHARP_KEYS = {"no", "gfx_low", "gfx_mid", "gfx_high"};
    private static final String[] CRT_KEYS = {"no", "gfx_lines", "gfx_crt", "gfx_lcd"};
    private static final String[] COLOR_KEYS = {"gfx_normal", "gfx_vivid", "gfx_warm", "gfx_cold", "gfx_cinema"};
    private static final String[] BLOOM_KEYS = {"no", "gfx_soft", "gfx_strong"};
    /** Pantalla: automática (según la de la consola), 4:3 o panorámica. */
    /** Pantalla: automática, 4:3, panorámica (truco de pantalla ancha) o estirada (4:3 a pantalla completa). */
    private static final String[] SCREEN_KEYS = {"psx_auto", "4:3", "psx_wide", "psx_stretch"};
    // Resolución interna (con la GPU): de 1x a 6x.
    private static final String[] RES_LABELS = {"1x", "2x", "3x", "4x", "5x", "6x"};
    private static final String[] RES_VALUES = {"1", "2", "3", "4", "5", "6"};

    /** Ajuste propio de los juegos de PlayStation. */
    private int gfx(String key, int def, int n) {
        int v = prefs().getInt("psx_" + key, def);
        return v >= 0 && v < n ? v : def;
    }

    private void cycleGfx(String key, int def, int n) {
        prefs().edit().putInt("psx_" + key, (gfx(key, def, n) + 1) % n).apply();
        applyPostFx();
    }

    private void applyPostFx() {
        try {
            nativePostFx(gfx("escalado", 1, SCALER_KEYS.length), 0, gfx("nitidez", 0, SHARP_KEYS.length),
                    gfx("crt", 0, CRT_KEYS.length), gfx("color", 0, COLOR_KEYS.length),
                    gfx("resplandor", 0, BLOOM_KEYS.length));
        } catch (UnsatisfiedLinkError e) {
            Log.w("Duke3D", "Sin mejoras gráficas", e);
        }
    }

    /**
     * Proporción panorámica para el truco de pantalla ancha de SwanStation, la más cercana a la
     * de la pantalla (16:9, 19:9 o 20:9), o null si la pantalla es casi 4:3.
     */
    private String screenWide() {
        int mode = gfx("pantalla", 0, SCREEN_KEYS.length);
        if (mode == 1 || mode == 3) return null;
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        float r = (float) Math.max(m.widthPixels, m.heightPixels) / Math.max(1, Math.min(m.widthPixels, m.heightPixels));
        if (mode == 0 && r < 1.45f) return null; // pantalla 4:3 (o casi): sin panorámico
        String[] names = {"16:9", "19:9", "20:9"};
        float[] ratios = {16f / 9f, 19f / 9f, 20f / 9f};
        int best = 0;
        for (int i = 0; i < ratios.length; i++)
            if (Math.abs(ratios[i] - r) < Math.abs(ratios[best] - r)) best = i;
        return names[best];
    }

    /** La proporción de SwanStation más cercana a la de la pantalla (para estirar). */
    private String screenRatioName() {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        float r = (float) Math.max(m.widthPixels, m.heightPixels) / Math.max(1, Math.min(m.widthPixels, m.heightPixels));
        if (r < 1.45f) return "4:3";
        String[] names = {"16:9", "19:9", "20:9"};
        float[] ratios = {16f / 9f, 19f / 9f, 20f / 9f};
        int best = 0;
        for (int i = 0; i < ratios.length; i++)
            if (Math.abs(ratios[i] - r) < Math.abs(ratios[best] - r)) best = i;
        return names[best];
    }

    /** Opciones de SwanStation que dependen de los ajustes (las demás, en psx_main.cpp). */
    private List<String[]> coreOptions() {
        List<String[]> o = new ArrayList<>();
        String wide = screenWide();
        o.add(new String[]{"swanstation_GPU_WidescreenHack", wide != null ? "true" : "false"});
        // Estirada: sin el truco (el juego dibuja como siempre) y la imagen a la proporción de la pantalla.
        String aspect = wide != null ? wide : "4:3";
        if (gfx("pantalla", 0, SCREEN_KEYS.length) == 3) aspect = screenRatioName();
        o.add(new String[]{"swanstation_Display_AspectRatio", aspect});
        o.add(new String[]{"swanstation_GPU_ResolutionScale", RES_VALUES[gfx("resolucion3", 2, RES_VALUES.length)]});
        o.add(new String[]{"swanstation_GPU_PGXPEnable", gfx("pgxp2", 0, 2) == 1 ? "true" : "false"});
        // Dibujado: con la gráfica (OpenGL ES) o con el procesador (como la consola, a 1x).
        o.add(new String[]{"swanstation_GPU_Renderer", gfx("render", 0, 2) == 1 ? "Software" : "OpenGL"});
        // Versiones PAL (europeas, a 50 Hz) a 60 Hz, como la americana. Solo afecta a discos PAL.
        o.add(new String[]{"swanstation_GPU_ForceNTSCTimings", gfx("pal60", 0, 2) == 1 ? "true" : "false"});
        return o;
    }

    private void applyCoreOptions() {
        for (String[] o : coreOptions()) option(o[0], o[1]);
    }

    // ---- Menú rápido ----

    private boolean menuOpen() {
        return quickMenu != null && quickMenu.getVisibility() == View.VISIBLE;
    }

    private void openMenu() {
        if (art == null || menuOpen()) return;
        releaseAll();
        command(CMD_PAUSE);
        if (quickMenu == null) {
            quickMenu = new DukeMenuView(this, art);
            quickMenu.setOverlay(true);
            addContentView(quickMenu, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        status = null;
        quickMenu.setRoot(rootPage(), this::closeMenu);
        quickMenu.setVisibility(View.VISIBLE);
        quickMenu.bringToFront();
        quickMenu.requestFocus();
        hideSystemBars();
    }

    private void closeMenu() {
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        if (mSurface != null) mSurface.requestFocus();
        hideSystemBars();
        command(CMD_RESUME);
    }

    private DukeMenuView.Page rootPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.add(I18n.t("continue"), null, this::closeMenu);
        p.add(I18n.t("quicksave"), null, () -> stateCommand(CMD_SAVE));
        p.add(I18n.t("quickload"), null, () -> stateCommand(CMD_LOAD));
        p.add(I18n.t("graphics"), null, () -> quickMenu.push(graphicsPage()));
        p.add(I18n.t("gamepad_config"), null, () -> quickMenu.push(gamepadPage()));
        p.add(I18n.t("to_launcher"), null, () -> quit(false));
        p.add(I18n.t("quit_game"), null, () -> quit(true));
        p.footer = () -> status != null ? status : "";
        return p;
    }

    private boolean busy;

    /** Guardado o carga rápida (en otro hilo: el juego lo hace entre dos fotogramas). */
    private void stateCommand(int cmd) {
        if (busy) return;
        busy = true;
        status = I18n.t(cmd == CMD_SAVE ? "dos_saving" : "dos_loading");
        quickMenu.invalidate();
        new Thread(() -> {
            int r = command(cmd);
            ui.post(() -> {
                busy = false;
                if (r == 0) {
                    closeMenu();
                    android.widget.Toast.makeText(this, I18n.t(cmd == CMD_SAVE ? "dos_saved" : "dos_loaded"),
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    status = I18n.t(r == 1 ? "dos_no_save" : (cmd == CMD_SAVE ? "dos_save_error" : "dos_load_error"));
                    if (quickMenu != null) quickMenu.invalidate();
                }
            });
        }, "EstadoPSX").start();
    }

    private String screenLabel() {
        int mode = gfx("pantalla", 0, SCREEN_KEYS.length);
        String wide = screenWide();
        String label = I18n.t(SCREEN_KEYS[mode]);
        if (mode == 0) label += " (" + (wide != null ? wide : "4:3") + ")";
        return label;
    }

    private DukeMenuView.Page graphicsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        status = null;
        p.addValue(I18n.t("psx_screen"), this::screenLabel, () -> {
            cycleGfx("pantalla", 0, SCREEN_KEYS.length);
            applyCoreOptions();
        });
        p.addValue(I18n.t("psx_res"), () -> RES_LABELS[gfx("resolucion3", 2, RES_VALUES.length)], () -> {
            cycleGfx("resolucion3", 2, RES_VALUES.length);
            applyCoreOptions();
        });
        p.addValue(I18n.t("psx_render"), () -> I18n.t(gfx("render", 0, 2) == 1 ? "psx_render_cpu" : "psx_render_gpu"), () -> {
            cycleGfx("render", 0, 2);
            status = I18n.t("psx_restart");
        });
        p.addValue(I18n.t("psx_pgxp"), () -> I18n.t(gfx("pgxp2", 0, 2) == 1 ? "yes" : "no"), () -> {
            cycleGfx("pgxp2", 0, 2);
            applyCoreOptions();
        });
        p.addValue(I18n.t("psx_pal60"), () -> I18n.t(gfx("pal60", 0, 2) == 1 ? "yes" : "no"), () -> {
            cycleGfx("pal60", 0, 2);
            applyCoreOptions();
            status = I18n.t("psx_restart");
        });
        p.addValue(I18n.t("gfx_scaler"), () -> I18n.t(SCALER_KEYS[gfx("escalado", 1, SCALER_KEYS.length)]),
                () -> cycleGfx("escalado", 1, SCALER_KEYS.length));
        p.addValue(I18n.t("gfx_sharpness"), () -> I18n.t(SHARP_KEYS[gfx("nitidez", 0, SHARP_KEYS.length)]),
                () -> cycleGfx("nitidez", 0, SHARP_KEYS.length));
        p.addValue(I18n.t("gfx_bloom"), () -> I18n.t(BLOOM_KEYS[gfx("resplandor", 0, BLOOM_KEYS.length)]),
                () -> cycleGfx("resplandor", 0, BLOOM_KEYS.length));
        p.addValue(I18n.t("gfx_color"), () -> I18n.t(COLOR_KEYS[gfx("color", 0, COLOR_KEYS.length)]),
                () -> cycleGfx("color", 0, COLOR_KEYS.length));
        p.addValue(I18n.t("gfx_effect"), () -> I18n.t(CRT_KEYS[gfx("crt", 0, CRT_KEYS.length)]),
                () -> cycleGfx("crt", 0, CRT_KEYS.length));
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        p.footer = () -> status != null ? status : "";
        return p;
    }

    // ---- Configurar mando ----

    /** Botones que se pueden cambiar (la cruceta y los sticks van siempre igual). */
    private static final int[] PAD_BUTTONS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    };
    private static final String[] PAD_NAMES = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "Start", "Select"};

    /** Botones de la PlayStation que se pueden poner en cada uno. */
    private static final int[] ACTIONS = {P_CROSS, P_CIRCLE, P_SQUARE, P_TRIANGLE, P_L1, P_R1, P_L2, P_R2, P_L3, P_R3,
            P_START, P_SELECT, P_NONE};
    private static final String[] ACTION_NAMES = {"psx_cross", "psx_circle", "psx_square", "psx_triangle", "L1", "R1",
            "L2", "R2", "L3", "R3", "Start", "Select", "dos_act_none"};

    private static String actionName(int a) {
        for (int i = 0; i < ACTIONS.length; i++)
            if (ACTIONS[i] == a) return I18n.t(ACTION_NAMES[i]);
        return "?";
    }

    /** Lo de serie: cada botón en el sitio del de la PlayStation (A abajo = Cruz...). */
    private static int defaultAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return P_CROSS;
            case KeyEvent.KEYCODE_BUTTON_B: return P_CIRCLE;
            case KeyEvent.KEYCODE_BUTTON_X: return P_SQUARE;
            case KeyEvent.KEYCODE_BUTTON_Y: return P_TRIANGLE;
            case KeyEvent.KEYCODE_BUTTON_L1: return P_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return P_R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return P_L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return P_R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return P_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return P_R3;
            case KeyEvent.KEYCODE_BUTTON_START: return P_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return P_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return P_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return P_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return P_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return P_RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER: return P_CROSS;
            default: return P_NONE;
        }
    }

    private static String padPref(int code) {
        return "psx_boton_" + code;
    }

    private int padAction(int code) {
        return prefs().getInt(padPref(code), defaultAction(code));
    }

    private void cyclePadAction(int code, int dir) {
        int cur = padAction(code), i = 0;
        for (int k = 0; k < ACTIONS.length; k++) if (ACTIONS[k] == cur) i = k;
        int next = ACTIONS[((i + dir) % ACTIONS.length + ACTIONS.length) % ACTIONS.length];
        if (next == defaultAction(code)) prefs().edit().remove(padPref(code)).apply();
        else prefs().edit().putInt(padPref(code), next).apply();
    }

    private DukeMenuView.Page gamepadPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        status = null;
        for (int i = 0; i < PAD_BUTTONS.length; i++) {
            int code = PAD_BUTTONS[i];
            p.addValue(PAD_NAMES[i], () -> actionName(padAction(code)), () -> cyclePadAction(code, 1));
            p.entries.get(p.entries.size() - 1).step = d -> cyclePadAction(code, d);
        }
        p.add(I18n.t("controls_reset"), null, () -> {
            SharedPreferences.Editor ed = prefs().edit();
            for (int code : PAD_BUTTONS) ed.remove(padPref(code));
            ed.apply();
            status = I18n.t("controls_reset_done");
        });
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        p.footer = () -> status != null ? status : I18n.t("psx_pad_help");
        return p;
    }

    /** Sale del juego (antes se guarda la tarjeta de memoria). */
    private void quit(boolean all) {
        if (all) prefs().edit().putBoolean("salir_todo", true).commit();
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        command(CMD_QUIT);
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 3000);
    }

    @Override
    protected void onDestroy() {
        // Como en el Duke 3D: el proceso entero se cierra (el núcleo no puede arrancar dos veces).
        android.os.Process.killProcess(android.os.Process.myPid());
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Sin sonido ni juego en segundo plano (pantalla apagada, Inicio).
        if (!menuOpen()) command(CMD_PAUSE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!menuOpen()) command(CMD_RESUME);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ---- Mando ----

    private void releaseAll() {
        for (int i = 0; i < 16; i++) button(i, false);
        analog(0, 0, 0);
        analog(1, 0, 0);
        hatH = hatV = -1;
        trigL = trigR = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (menuOpen()) {
            if (down) {
                if (code == KeyEvent.KEYCODE_BACK) quickMenu.pop();
                else quickMenu.onKeyDown(code, e);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) openMenu();
            return true;
        }
        int id = padAction(code);
        if (id >= 0 && (e.getRepeatCount() == 0 || !down)) button(id, down);
        return true;
    }

    // Cruceta como ejes (HAT), gatillos analógicos y los dos sticks (analógicos del DualShock).
    private int hatH = -1, hatV = -1;
    private boolean trigL, trigR;

    private static float dead(float v) {
        return Math.abs(v) < 0.12f ? 0f : v;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK)
            return super.dispatchGenericMotionEvent(e);
        if (menuOpen()) {
            quickMenu.onAxis(e);
            return true;
        }
        analog(0, dead(e.getAxisValue(MotionEvent.AXIS_X)), dead(e.getAxisValue(MotionEvent.AXIS_Y)));
        analog(1, dead(e.getAxisValue(MotionEvent.AXIS_Z)), dead(e.getAxisValue(MotionEvent.AXIS_RZ)));
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int h = hx < -0.5f ? P_LEFT : hx > 0.5f ? P_RIGHT : -1;
        int v = hy < -0.5f ? P_UP : hy > 0.5f ? P_DOWN : -1;
        if (h != hatH) {
            button(hatH, false);
            button(h, true);
            hatH = h;
        }
        if (v != hatV) {
            button(hatV, false);
            button(v, true);
            hatV = v;
        }
        boolean l = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f;
        boolean r = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f;
        if (l != trigL) {
            trigL = l;
            button(padAction(KeyEvent.KEYCODE_BUTTON_L2), l);
        }
        if (r != trigR) {
            trigR = r;
            button(padAction(KeyEvent.KEYCODE_BUTTON_R2), r);
        }
        return true;
    }
}
