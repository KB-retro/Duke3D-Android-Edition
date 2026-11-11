package com.kbretro.duke3d;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.Arrays;

/**
 * Pantalla del juego: EDuke32 dentro de SDLActivity. Va en su propio proceso (":game"), así
 * cada partida arranca limpia y, al salir del juego, se cierra el proceso entero.
 *
 * El botón Atrás abre el menú rápido (encima de la partida, en pausa): guardar y cargar,
 * trucos, gráficos, FPS, el menú del juego, volver al lanzador y salir.
 */
public class GameActivity extends SDLActivity {
    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_LANG = "lang";
    public static final String EXTRA_BASE_GRP = "base_grp";

    /** Orden de consola para el juego (la ejecuta en su hilo; ver android_main.c). */
    static native void nativeCommand(String cmd);

    /** Guarda la clase para que el hilo del juego pueda llamar a vibrate(). */
    static native void nativeInit();

    private static GameActivity instance;
    private static volatile boolean vibrationOn = true;
    private static long lastVibration;

    /**
     * Lo llama el juego (desde su hilo) al recibir daño, disparar, en explosiones...
     * @param strength 0..255
     */
    static void vibrate(int strength, int ms) {
        GameActivity a = instance;
        if (a == null || !vibrationOn || strength <= 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastVibration < 40) return; // sin saturar el motor
        lastVibration = now;
        ms = Math.max(20, Math.min(ms, 500));
        int amp = Math.max(40, Math.min(255, strength));
        try {
            android.os.Vibrator v = a.vibrator();
            if (v == null) return;
            if (v.hasAmplitudeControl()) v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp));
            else v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    /** Juego lanzado ("base", "dc", "nw" o "vacation") para los logros de cada uno. */
    private static volatile String gameId = "base";
    /** Partida en red: el menú rápido no pausa (los demás siguen jugando) y no hay trucos. */
    private boolean netGame;

    /**
     * Lo llama el juego (desde su hilo) cuando pasa algo que cuenta para los logros.
     * Ver Achievements y G_AndroidEvent en game.cpp.
     */
    static void achievementEvent(int type, int a, int b, int c) {
        GameActivity act = instance;
        if (act == null) return;
        Achievements.onEvent(act.getApplicationContext(), gameId, type, a, b, c,
                def -> act.ui.post(() -> act.showAchievement(def)));
    }

    private android.widget.TextView banner;

    /** Cartel arriba durante unos segundos: "Logro desbloqueado" y su nombre. */
    private void showAchievement(Achievements.Def def) {
        if (banner == null) {
            banner = new android.widget.TextView(this);
            banner.setTextColor(android.graphics.Color.rgb(255, 210, 60));
            banner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            banner.setGravity(android.view.Gravity.CENTER);
            banner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            banner.setShadowLayer(3, 1, 1, android.graphics.Color.BLACK);
            float dp = getResources().getDisplayMetrics().density;
            int pad = (int) (10 * dp);
            banner.setPadding(pad * 2, pad, pad * 2, pad);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(android.graphics.Color.argb(215, 25, 10, 5));
            bg.setStroke((int) (2 * dp), android.graphics.Color.rgb(200, 60, 20));
            bg.setCornerRadius(6 * dp);
            banner.setBackground(bg);
            banner.setFocusable(false);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (16 * dp);
            addContentView(banner, lp);
        }
        banner.setText(I18n.t("achievement_unlocked") + "\n" + def.name);
        banner.animate().cancel();
        banner.setAlpha(1f);
        banner.setVisibility(View.VISIBLE);
        banner.bringToFront();
        vibrate(150, 150);
        banner.animate().alpha(0f).setStartDelay(4000).setDuration(600)
                .withEndAction(() -> banner.setVisibility(View.GONE)).start();
    }

    private android.os.Vibrator vibratorCache;

    /** El motor de la consola; si no tiene, el del mando conectado. */
    private android.os.Vibrator vibrator() {
        if (vibratorCache != null) return vibratorCache;
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) {
            v = null;
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && d.getVibrator() != null && d.getVibrator().hasVibrator()) {
                    v = d.getVibrator();
                    break;
                }
            }
        }
        vibratorCache = v;
        return v;
    }

    private boolean vibrationPref() {
        return prefs().getBoolean("vibracion", true);
    }

    /** Mejoras gráficas de postfx.c: escalado, FXAA, nitidez, CRT y color. */
    static native void nativePostFx(int scaler, int fxaa, int sharp, int crt, int color);

    private void applyPostFx() {
        try {
            nativePostFx(GraphicsSettings.scaler(this), GraphicsSettings.fxaa(this), GraphicsSettings.sharp(this),
                    GraphicsSettings.crt(this), GraphicsSettings.color(this));
        } catch (UnsatisfiedLinkError e) {
            Log.w("Duke3D", "Sin mejoras gráficas", e);
        }
    }

    /** true en plena partida (no en los menús del juego, el título, demos o fin de nivel). */
    static native boolean nativeInGame();

    private static boolean inGame() {
        try {
            return nativeInGame();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static final int[] FPS_VALUES = {60, 120, 0};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DukeMenuView quickMenu;
    private DukeArt art;
    private GrpArchive baseGrp;
    private String status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // android_main.c entra en esta carpeta antes de arrancar el juego.
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        try {
            if (dir != null) Os.setenv("DUKE3D_DIR", dir, true);
            // Idioma del juego (el motor lo aplica tras cargar las traducciones).
            String lang = getIntent().getStringExtra(EXTRA_LANG);
            Os.setenv("DUKE3D_LANG", lang != null ? lang : "en", true);
        } catch (ErrnoException ignored) {
        }
        super.onCreate(savedInstanceState);
        I18n.load(this);
        instance = this;
        gameId = Achievements.gameId(gameGrp(getArguments()));
        netGame = Arrays.asList(getArguments()).contains("-server") || Arrays.asList(getArguments()).contains("-connect");
        vibrationOn = vibrationPref();
        applyPostFx();
        try {
            nativeInit();
        } catch (UnsatisfiedLinkError e) {
            Log.w("Duke3D", "Sin vibración", e);
        }
        // Sin el resalte de foco de Android (salía un contorno verde alrededor del juego).
        if (mSurface != null) mSurface.setDefaultFocusHighlightEnabled(false);
        getWindow().getDecorView().setDefaultFocusHighlightEnabled(false);

        applyRefreshRate(fps());
        // Los ajustes guardados, cuando el juego ya ha arrancado (si no, su configuración los pisa).
        ui.postDelayed(() -> {
            applyRefreshRate(fps()); // otra vez, ya con la superficie del juego creada
            command("r_maxfps " + fps());
            if (scale() != 100) applyScale(scale());
        }, 4000);

        // Gráficos del juego para el menú rápido (letras, átomo...), en segundo plano.
        String grp = getIntent().getStringExtra(EXTRA_BASE_GRP);
        if (grp != null) {
            new Thread(() -> {
                try {
                    baseGrp = new GrpArchive(new File(grp));
                    art = new DukeArt(Arrays.asList(baseGrp));
                } catch (Exception e) {
                    Log.w("Duke3D", "Menú rápido sin gráficos del juego", e);
                }
            }, "ArteMenuRapido").start();
        }
    }

    @Override
    protected String[] getArguments() {
        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return args != null ? args : new String[0];
    }

    /** El GRP que va tras "-gamegrp" en los argumentos. */
    private static String gameGrp(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) if ("-gamegrp".equals(args[i])) return args[i + 1];
        return null;
    }

    private static void command(String cmd) {
        try {
            nativeCommand(cmd);
        } catch (UnsatisfiedLinkError e) {
            Log.w("Duke3D", "Orden no enviada: " + cmd, e);
        }
    }

    // ---- Ajustes (compartidos con el lanzador) ----

    private SharedPreferences prefs() {
        return getSharedPreferences("ajustes", MODE_MULTI_PROCESS);
    }

    private int fps() {
        return prefs().getInt("fps", 60);
    }

    private int scale() {
        return prefs().getInt("escala", 100);
    }

    private static String fpsLabel(int fps) {
        return fps <= 0 ? I18n.t("unlimited") : fps + " FPS";
    }

    /** Pide a Android la frecuencia de pantalla más cercana (sin esto se queda en 60 Hz). */
    private void applyRefreshRate(int fps) {
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode cur = d.getMode();
            Display.Mode best = cur;
            float want = fps <= 0 ? 1000f : fps;
            float bestDiff = Float.MAX_VALUE;
            StringBuilder modes = new StringBuilder();
            for (Display.Mode m : d.getSupportedModes()) {
                modes.append(' ').append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight())
                        .append('@').append(Math.round(m.getRefreshRate()));
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                float diff = Math.abs(m.getRefreshRate() - want);
                if (fps <= 0) diff = -m.getRefreshRate(); // sin límite: la más alta
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = m;
                }
            }
            Log.i("Duke3D", "Modos de pantalla:" + modes);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            getWindow().setAttributes(lp);
            // Y a la superficie del juego: algunos sistemas solo hacen caso a esto.
            if (android.os.Build.VERSION.SDK_INT >= 30 && mSurface != null
                    && mSurface.getHolder().getSurface() != null && mSurface.getHolder().getSurface().isValid()) {
                mSurface.getHolder().getSurface().setFrameRate(best.getRefreshRate(),
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            Log.i("Duke3D", "Pantalla a " + best.getRefreshRate() + " Hz (pedido: " + fpsLabel(fps) + ")");
        } catch (Exception e) {
            Log.w("Duke3D", "No se pudo cambiar la frecuencia de pantalla", e);
        }
    }

    /** Resolución interna del juego (la GPU la escala a la pantalla). */
    private void applyScale(int percent) {
        View v = mSurface != null ? mSurface : getWindow().getDecorView();
        int w = v.getWidth(), h = v.getHeight();
        if (w == 0 || h == 0) return;
        int sw = (w * percent / 100) & ~1, sh = (h * percent / 100) & ~1;
        command("vidmode " + sw + " " + sh + " 8 1");
    }

    // ---- Menú rápido ----

    private boolean menuOpen() {
        return quickMenu != null && quickMenu.getVisibility() == View.VISIBLE;
    }

    private void openMenu() {
        if (art == null) return;
        if (quickMenu == null) {
            quickMenu = new DukeMenuView(this, art);
            quickMenu.setOverlay(true);
            addContentView(quickMenu, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        status = null;
        quickMenu.setRoot(rootPage(), this::closeMenu);
        quickMenu.setVisibility(View.VISIBLE);
        quickMenu.bringToFront();
        quickMenu.requestFocus();
        if (!netGame) command("pausegame 1");
    }

    private void closeMenu() {
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        if (!netGame) command("pausegame 0");
    }

    private DukeMenuView.Page rootPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.add(I18n.t("continue"), null, this::closeMenu);
        if (!netGame) {
            p.add(I18n.t("quicksave"), null, () -> {
                closeMenu();
                command("quicksave");
            });
            p.add(I18n.t("quickload"), null, () -> {
                closeMenu();
                command("quickload");
            });
            p.add(I18n.t("cheats"), null, () -> quickMenu.push(cheatsPage()));
        }
        p.add(I18n.t("graphics"), null, () -> quickMenu.push(graphicsPage()));
        p.addValue(I18n.t("vibration"), () -> I18n.t(vibrationOn ? "yes" : "no"), () -> {
            vibrationOn = !vibrationOn;
            prefs().edit().putBoolean("vibracion", vibrationOn).apply();
            if (vibrationOn) vibrate(200, 120); // para notar que está activa
        });
        p.add(I18n.t("to_launcher"), null, this::finish);
        p.add(I18n.t("quit_game"), null, () -> {
            Intent i = new Intent(this, LauncherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(LauncherActivity.EXTRA_QUIT, true);
            startActivity(i);
            finish();
        });
        return p;
    }

    private DukeMenuView.Page cheatsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        String[][] cheats = {
                {"cheat_god", "god"},
                {"cheat_noclip", "noclip"},
                {"cheat_weapons", "give weapons"},
                {"cheat_ammo", "give ammo"},
                {"cheat_keys", "give keys"},
                {"cheat_health", "give health"},
                {"cheat_armor", "give armor"},
                {"cheat_inventory", "give inventory"},
                {"cheat_all", "give all"},
        };
        for (String[] c : cheats) {
            p.add(I18n.t(c[0]), null, () -> {
                command(c[1]);
                status = I18n.t("cheat_done") + I18n.t(c[0]);
                achievementEvent(Achievements.EV_CHEAT, 0, 0, 0);
            });
        }
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        p.footer = () -> status != null ? status : "";
        return p;
    }

    private DukeMenuView.Page graphicsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        GraphicsSettings.addTo(this, p, this::applyScale, this::applyPostFx);
        p.addValue("FPS", () -> fpsLabel(fps()), () -> {
            int i = indexOf(FPS_VALUES, fps());
            int next = FPS_VALUES[(i + 1) % FPS_VALUES.length];
            prefs().edit().putInt("fps", next).apply();
            applyRefreshRate(next);
            command("r_maxfps " + next);
        });
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        return p;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private void sendKey(int keyCode) {
        // Llega al juego como si fuera un teclado (SDL la convierte en su tecla).
        long t = android.os.SystemClock.uptimeMillis();
        super.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
        super.dispatchKeyEvent(new KeyEvent(t, t + 50, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    // ---- Mando y teclas ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        if (menuOpen()) {
            // Con el menú abierto, el mando y las teclas son para el menú, no para el juego.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_SELECT) closeMenu();
                else quickMenu.onKeyDown(key, e);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                // En plena partida, el menú rápido; en los menús del juego, el título, etc., Escape.
                if (art != null && inGame()) openMenu();
                else sendKey(KeyEvent.KEYCODE_ESCAPE);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private long lastAxisMove;

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!menuOpen()) return super.dispatchGenericMotionEvent(e);
        // Crucetas que llegan como ejes (HAT) y el stick izquierdo mueven el menú.
        float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(y) < 0.5f) y = e.getAxisValue(MotionEvent.AXIS_Y);
        long now = android.os.SystemClock.uptimeMillis();
        if (Math.abs(y) >= 0.5f && now - lastAxisMove > 180) {
            quickMenu.moveSelection(y > 0 ? 1 : -1);
            lastAxisMove = now;
        } else if (Math.abs(y) < 0.3f) {
            lastAxisMove = 0;
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        instance = null;
        super.onDestroy();
        // EDuke32 no está pensado para arrancar dos veces en el mismo proceso.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
