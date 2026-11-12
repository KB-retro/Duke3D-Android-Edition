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
import java.util.Arrays;

/**
 * Juegos de MS-DOS de los extras (Duke Nukem 1 y 2), con DOSBox Pure dentro (libdos.so, ver
 * cpp/dos/dos_main.cpp). Va en su propio proceso (":dos"), aparte del Duke 3D.
 *
 * El juego arranca directamente. El mando y los controles táctiles llegan al juego como
 * teclado. Atrás abre el menú rápido: guardado y carga rápidos, gráficos, controles y teclas.
 */
public class DosActivity extends SDLActivity {
    static final String EXTRA_EXE = "exe";
    static final String EXTRA_SAVE_DIR = "save_dir";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_BASE_GRP = "base_grp";

    static native void nativeKey(int key, boolean down);

    /** 1 pausa, 2 seguir, 3 guardado rápido, 4 carga rápida, 5 salir. 0 = bien, 1 = no hay guardado. */
    static native int nativeCommand(int cmd);

    static native void nativePostFx(int scaler, int fxaa, int sharp, int crt, int color, int bloom);

    private static final int CMD_PAUSE = 1, CMD_RESUME = 2, CMD_SAVE = 3, CMD_LOAD = 4, CMD_QUIT = 5;

    // Teclas de libretro (RETROK_*).
    static final int K_BACKSPACE = 8, K_TAB = 9, K_ENTER = 13, K_ESC = 27, K_SPACE = 32;
    static final int K_UP = 273, K_DOWN = 274, K_RIGHT = 275, K_LEFT = 276;
    static final int K_F1 = 282, K_SHIFT = 304, K_CTRL = 306, K_ALT = 308;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DukeMenuView quickMenu;
    private DukeArt art;
    private DosTouchView touch;
    private String status;

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "dos"};
    }

    @Override
    protected String[] getArguments() {
        Intent i = getIntent();
        return new String[]{i.getStringExtra(EXTRA_EXE), i.getStringExtra(EXTRA_SAVE_DIR), i.getStringExtra(EXTRA_STATE)};
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

        touch = new DosTouchView(this, new DosTouchView.Host() {
            @Override
            public void key(int k, boolean down) {
                DosActivity.key(k, down);
            }

            @Override
            public void menu() {
                openMenu();
            }
        });
        addContentView(touch, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateTouch(false);

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

    private static void key(int k, boolean down) {
        try {
            nativeKey(k, down);
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

    /** Ajuste gráfico propio de los juegos de MS-DOS (no los del Duke 3D). */
    private int gfx(String key, int def, int n) {
        int v = prefs().getInt("dos_" + key, def);
        return v >= 0 && v < n ? v : def;
    }

    private void cycleGfx(String key, int def, int n) {
        prefs().edit().putInt("dos_" + key, (gfx(key, def, n) + 1) % n).apply();
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

    /** Táctiles: automático (sin mando, o al tocar la pantalla), siempre o nunca. */
    private void updateTouch(boolean touched) {
        if (touch == null) return;
        int mode = ControlSettings.touchMode(this);
        boolean show;
        if (mode == 1) show = true;
        else if (mode == 2) show = false;
        else show = touched || (touch.getVisibility() == View.VISIBLE && !padUsed) || !ControlSettings.hasGamepad();
        if (menuOpen()) show = false;
        touch.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) touch.releaseAll();
    }

    private boolean padUsed;

    // ---- Menú rápido ----

    private boolean menuOpen() {
        return quickMenu != null && quickMenu.getVisibility() == View.VISIBLE;
    }

    private void openMenu() {
        if (art == null || menuOpen()) return;
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
        updateTouch(false);
        hideSystemBars();
    }

    private void closeMenu() {
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        if (mSurface != null) mSurface.requestFocus();
        hideSystemBars();
        command(CMD_RESUME);
        updateTouch(false);
    }

    private DukeMenuView.Page rootPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.add(I18n.t("continue"), null, this::closeMenu);
        p.add(I18n.t("quicksave"), null, () -> stateCommand(CMD_SAVE));
        p.add(I18n.t("quickload"), null, () -> stateCommand(CMD_LOAD));
        p.add(I18n.t("graphics"), null, () -> quickMenu.push(graphicsPage()));
        p.add(I18n.t("controls"), null, () -> quickMenu.push(controlsPage()));
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
        }, "EstadoDOS").start();
    }

    private DukeMenuView.Page graphicsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
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
        return p;
    }

    private DukeMenuView.Page controlsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.addValue(I18n.t("touch_controls"), () -> ControlSettings.touchModeLabel(this),
                () -> ControlSettings.cycleTouchMode(this));
        p.add(I18n.t("gamepad_config"), null, () -> quickMenu.push(gamepadPage()));
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        return p;
    }

    // ---- Configurar mando ----

    /** Botones que se pueden cambiar (la cruceta y el stick siempre son las flechas). */
    private static final int[] PAD_BUTTONS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    };
    private static final String[] PAD_NAMES = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "Start", "Select"};

    /** Lo que puede hacer un botón: tecla del juego y su nombre (clave de I18n o texto). */
    private static final int[] ACTION_KEYS = {K_CTRL, K_ALT, K_UP, K_DOWN, K_ENTER, K_ESC, K_SPACE,
            'y', 'n', K_F1, K_F1 + 1, K_F1 + 2, K_F1 + 9, K_SHIFT, K_TAB, -1};
    private static final String[] ACTION_NAMES = {"dos_act_jump", "dos_act_fire", "dos_act_use", "dos_act_down",
            "Intro", "Esc", "dos_space", "Y", "N", "F1", "F2", "F3", "F10", "Shift", "Tab", "dos_act_none"};

    private static String actionName(int key) {
        for (int i = 0; i < ACTION_KEYS.length; i++)
            if (ACTION_KEYS[i] == key) return I18n.t(ACTION_NAMES[i]);
        return "?";
    }

    /** Tecla del botón: la elegida en "Configurar mando" o la de serie. */
    private int padAction(int code) {
        return prefs().getInt("dos_boton_" + code, padKey(code));
    }

    private void cyclePadAction(int code) {
        int cur = padAction(code), i = 0;
        for (int k = 0; k < ACTION_KEYS.length; k++) if (ACTION_KEYS[k] == cur) i = k;
        int next = ACTION_KEYS[(i + 1) % ACTION_KEYS.length];
        if (next == padKey(code)) prefs().edit().remove("dos_boton_" + code).apply();
        else prefs().edit().putInt("dos_boton_" + code, next).apply();
    }

    private DukeMenuView.Page gamepadPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        for (int i = 0; i < PAD_BUTTONS.length; i++) {
            int code = PAD_BUTTONS[i];
            p.addValue(PAD_NAMES[i], () -> actionName(padAction(code)), () -> cyclePadAction(code));
        }
        p.add(I18n.t("controls_reset"), null, () -> {
            SharedPreferences.Editor ed = prefs().edit();
            for (int code : PAD_BUTTONS) ed.remove("dos_boton_" + code);
            ed.apply();
            status = I18n.t("controls_reset_done");
        });
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        p.footer = () -> status != null ? status : I18n.t("dos_pad_help");
        return p;
    }

    /** Sale del juego (DOSBox Pure guarda antes lo que haya escrito el juego). */
    private void quit(boolean all) {
        if (all) prefs().edit().putBoolean("salir_todo", true).commit();
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        command(CMD_QUIT);
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 3000);
    }

    @Override
    protected void onDestroy() {
        // Como en el Duke 3D: el proceso entero se cierra (DOSBox no puede arrancar dos veces).
        android.os.Process.killProcess(android.os.Process.myPid());
        super.onDestroy();
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

    // ---- Mando y teclado ----

    private static boolean isPad(InputDevice d, int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /**
     * Botón del mando → tecla del juego. En los dos juegos: flechas para moverse (arriba = usar
     * / entrar en puertas), Ctrl = saltar, Alt = disparar.
     */
    private static int padKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return K_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return K_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return K_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return K_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A: return K_CTRL;    // saltar
            case KeyEvent.KEYCODE_BUTTON_B: return K_ALT;     // disparar
            case KeyEvent.KEYCODE_BUTTON_X: return K_ALT;     // disparar
            case KeyEvent.KEYCODE_BUTTON_Y: return K_UP;      // usar / entrar
            case KeyEvent.KEYCODE_BUTTON_L2: return K_CTRL;
            case KeyEvent.KEYCODE_BUTTON_R2: return K_ALT;
            case KeyEvent.KEYCODE_BUTTON_L1: return K_ESC;
            case KeyEvent.KEYCODE_BUTTON_R1: return K_ENTER;
            case KeyEvent.KEYCODE_BUTTON_START: return K_ENTER;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return K_ESC;
            case KeyEvent.KEYCODE_DPAD_CENTER: return K_ENTER;
            default: return -1;
        }
    }

    /** Teclado físico → tecla del juego. */
    private static int keyboardKey(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return 'a' + (code - KeyEvent.KEYCODE_A);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return '0' + (code - KeyEvent.KEYCODE_0);
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return K_F1 + (code - KeyEvent.KEYCODE_F1);
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: return K_ENTER;
            case KeyEvent.KEYCODE_ESCAPE: return K_ESC;
            case KeyEvent.KEYCODE_SPACE: return K_SPACE;
            case KeyEvent.KEYCODE_TAB: return K_TAB;
            case KeyEvent.KEYCODE_DEL: return K_BACKSPACE;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return K_CTRL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return K_ALT;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return K_SHIFT;
            case KeyEvent.KEYCODE_MINUS: return '-';
            case KeyEvent.KEYCODE_EQUALS: return '=';
            case KeyEvent.KEYCODE_PERIOD: return '.';
            case KeyEvent.KEYCODE_COMMA: return ',';
            default: return padKey(code);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (menuOpen()) {
            if (down) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_SELECT) quickMenu.pop();
                else quickMenu.onKeyDown(code, e);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) openMenu();
            return true;
        }
        boolean pad = isPad(e.getDevice(), e.getSource()) || KeyEvent.isGamepadButton(code);
        if (pad && !padUsed) {
            padUsed = true;
            updateTouch(false);
        }
        int k = pad ? padAction(code) : keyboardKey(code);
        if (k < 0) return true;
        if (e.getRepeatCount() == 0 || !down) key(k, down);
        return true;
    }

    // Stick izquierdo, cruceta como ejes y gatillos analógicos.
    private int stickH = -1, stickV = -1;
    private boolean trigL, trigR;
    private long lastAxisMove;

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK)
            return super.dispatchGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f) x = e.getAxisValue(MotionEvent.AXIS_X);
        if (Math.abs(y) < 0.5f) y = e.getAxisValue(MotionEvent.AXIS_Y);
        if (menuOpen()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (Math.abs(y) >= 0.5f && now - lastAxisMove > 180) {
                quickMenu.moveSelection(y > 0 ? 1 : -1);
                lastAxisMove = now;
            } else if (Math.abs(y) < 0.3f) {
                lastAxisMove = 0;
            }
            return true;
        }
        if (!padUsed) {
            padUsed = true;
            updateTouch(false);
        }
        int h = x < -0.5f ? K_LEFT : x > 0.5f ? K_RIGHT : -1;
        int v = y < -0.5f ? K_UP : y > 0.5f ? K_DOWN : -1;
        if (h != stickH) {
            if (stickH >= 0) key(stickH, false);
            if (h >= 0) key(h, true);
            stickH = h;
        }
        if (v != stickV) {
            if (stickV >= 0) key(stickV, false);
            if (v >= 0) key(v, true);
            stickV = v;
        }
        boolean l = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f;
        boolean r = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f;
        if (l != trigL) {
            trigL = l;
            int k = padAction(KeyEvent.KEYCODE_BUTTON_L2);
            if (k >= 0) key(k, l);
        }
        if (r != trigR) {
            trigR = r;
            int k = padAction(KeyEvent.KEYCODE_BUTTON_R2);
            if (k >= 0) key(k, r);
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!menuOpen() && e.getActionMasked() == MotionEvent.ACTION_DOWN && touch != null
                && touch.getVisibility() != View.VISIBLE && ControlSettings.touchMode(this) == 0) {
            padUsed = false;
            updateTouch(true);
        }
        return super.dispatchTouchEvent(e);
    }
}
