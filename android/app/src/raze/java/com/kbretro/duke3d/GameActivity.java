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
import android.view.InputEvent;
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
        for (android.os.Vibrator v : a.vibrators()) {
            try {
                android.os.VibrationEffect fx = android.os.VibrationEffect.createOneShot(ms,
                        v.hasAmplitudeControl() ? amp : android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // Como vibración de juego/multimedia: el ajuste de "respuesta táctil" no la anula.
                    v.vibrate(fx, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_MEDIA));
                } else {
                    v.vibrate(fx, new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_GAME).build());
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Juego lanzado ("base", "dc", "nw" o "vacation") para los logros de cada uno. */
    private static volatile String gameId = "base";

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

    // ---- Vigilante: si el hilo principal se queda bloqueado (el "no responde" de Android),
    // apunta en el registro qué estaba haciendo, para encontrar la causa. ----

    private volatile boolean watchdogRunning;
    private volatile long lastTick;

    private void startWatchdog() {
        watchdogRunning = true;
        lastTick = android.os.SystemClock.uptimeMillis();
        Thread t = new Thread(() -> {
            long reported = 0;
            while (watchdogRunning) {
                ui.post(() -> lastTick = android.os.SystemClock.uptimeMillis());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long blocked = android.os.SystemClock.uptimeMillis() - lastTick;
                if (blocked > 3000 && lastTick != reported) {
                    reported = lastTick;
                    StringBuilder sb = new StringBuilder("Hilo principal bloqueado " + blocked + " ms en:");
                    for (StackTraceElement e : Looper.getMainLooper().getThread().getStackTrace())
                        sb.append("\n    ").append(e);
                    Log.e("Duke3D", sb.toString());
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> en : Thread.getAllStackTraces().entrySet()) {
                        Thread th = en.getKey();
                        if (th == Thread.currentThread() || th == Looper.getMainLooper().getThread()) continue;
                        String n = th.getName();
                        if (!n.contains("SDL") && !n.contains("main") && !n.contains("Arte")) continue;
                        StringBuilder o = new StringBuilder("  hilo " + n + ":");
                        for (StackTraceElement e : en.getValue()) o.append("\n    ").append(e);
                        Log.e("Duke3D", o.toString());
                    }
                }
            }
        }, "Vigilante");
        t.setDaemon(true);
        t.start();
    }


    /** El motor de la consola; si no tiene, el del mando conectado. */
    private java.util.List<android.os.Vibrator> vibratorList;

    /**
     * Todos los motores de vibración: el de la consola (o móvil) y los de cada mando. Algunas
     * consolas (RG477M) lo tienen en el mando integrado y no como vibrador del sistema; vibran
     * todos a la vez. Se apuntan en el registro.
     */
    private java.util.List<android.os.Vibrator> vibrators() {
        if (vibratorList != null) return vibratorList;
        java.util.List<android.os.Vibrator> list = new java.util.ArrayList<>();
        StringBuilder names = new StringBuilder();
        android.os.Vibrator sys = null;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.VibratorManager vm = (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            if (vm != null) sys = vm.getDefaultVibrator();
        }
        if (sys == null) sys = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (sys != null && sys.hasVibrator()) {
            list.add(sys);
            names.append(" sistema");
        }
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            android.os.Vibrator v = null;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm = d.getVibratorManager();
                if (vm != null && vm.getVibratorIds().length > 0) v = vm.getDefaultVibrator();
            }
            if (v == null || !v.hasVibrator()) v = d.getVibrator();
            if (v != null && v.hasVibrator()) {
                list.add(v);
                names.append(" mando:").append(d.getName());
            }
        }
        Log.i("Duke3D", "Vibración:" + (names.length() > 0 ? names : " ninguna"));
        vibratorList = list;
        return list;
    }

    private boolean vibrationPref() {
        return prefs().getBoolean("vibracion", true);
    }

    /** Mejoras gráficas de postfx.c: escalado, FXAA, nitidez, CRT y color. */
    static native void nativePostFx(int scaler, int fxaa, int sharp, int crt, int color, int bloom);

    // ---- Controles táctiles y botones reasignados (android_main.c) ----

    static native void nativeTouchMove(float x, float y);

    static native void nativeTouchLook(float dx, float dy, float sens);

    static native void nativeButton(int gamefunc, boolean down);

    /** Solo en Raze: stick derecho del mando (-1..1), giro continuo. */
    static native void nativeStickLook(float x, float y);

    private TouchControls touch;
    /** En modo automático: se ven si no hay mando, o al tocar la pantalla; se ocultan al usar el mando. */
    private boolean touchActive;

    private void setupTouch() {
        touchActive = !ControlSettings.hasGamepad();
        touch = new TouchControls(this, new TouchControls.Host() {
            @Override
            public boolean inGame() {
                return !menuOpen() && GameActivity.inGame();
            }

            @Override
            public void move(float x, float y) {
                try {
                    nativeTouchMove(x, y);
                } catch (UnsatisfiedLinkError ignored) {
                }
            }

            @Override
            public void look(float dx, float dy) {
                try {
                    nativeTouchLook(dx, dy, 1f);
                } catch (UnsatisfiedLinkError ignored) {
                }
            }

            @Override
            public void button(int gamefunc, boolean down) {
                pressFunc(gamefunc, down);
            }

            @Override
            public void openMenu() {
                if (art != null) GameActivity.this.openMenu();
            }
        });
        addContentView(touch, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateTouch();
    }

    private void updateTouch() {
        if (touch == null) return;
        int mode = ControlSettings.touchMode(this);
        touch.setControlsEnabled(mode == 1 || (mode == 0 && touchActive));
        touch.setSensitivity(ControlSettings.sensitivity(this));
    }

    private static void pressFunc(int gamefunc, boolean down) {
        try {
            nativeButton(gamefunc, down);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    /** Suelta todo lo que estuviera pulsado (al abrir el menú, por ejemplo). */
    private static void releaseAllFuncs() {
        for (int i = 0; i < 64; i++) pressFunc(i, false);
        try {
            nativeTouchMove(0, 0);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        // Modo automático: tocar la pantalla en plena partida enseña los controles táctiles.
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !touchActive && !menuOpen()
                && ControlSettings.touchMode(this) == 0 && inGame()) {
            touchActive = true;
            updateTouch();
        }
        return super.dispatchTouchEvent(e);
    }

    /** Se ha usado el mando: en modo automático se ocultan los controles táctiles. */
    private void gamepadUsed(InputEvent e) {
        int s = e.getSource();
        boolean pad = (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (pad && touchActive && ControlSettings.touchMode(this) == 0) {
            touchActive = false;
            updateTouch();
        }
    }

    private void applyPostFx() {
        try {
            nativePostFx(GraphicsSettings.scaler(this), GraphicsSettings.fxaa(this), GraphicsSettings.sharp(this),
                    GraphicsSettings.crt(this), GraphicsSettings.color(this), GraphicsSettings.bloom(this));
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
            if (Motor.RAZE) {
                Os.setenv("RAZE_PROGDIR", Motor.prepareRazeData(this).getAbsolutePath(), true);
                // Carpetas donde Raze busca los juegos: las expansiones de la Megaton (addons/dc...).
                if (dir != null) {
                    StringBuilder sb = new StringBuilder(dir);
                    for (File d : GameData.addonDirs(new File(dir))) sb.append(':').append(d.getAbsolutePath());
                    Os.setenv("RAZE_SEARCH", sb.toString(), true);
                }
            }
        } catch (ErrnoException ignored) {
        }
        // Pantalla completa desde el principio (antes de que SDL cree su superficie): si la
        // superficie nace con la barra de navegación, el juego coge esa altura y en 16:9 quedaba
        // una franja negra abajo.
        android.view.Window win = getWindow();
        win.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            win.setAttributes(lp);
        }
        super.onCreate(savedInstanceState);
        hideSystemBars();
        if (mSurface != null) {
            mSurface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) {
                    android.util.DisplayMetrics real = new android.util.DisplayMetrics();
                    getWindowManager().getDefaultDisplay().getRealMetrics(real);
                    Log.i("Duke3D", "Superficie del juego: " + (r - l) + "x" + (b - t) + " (pantalla "
                            + real.widthPixels + "x" + real.heightPixels + ")");
                }
            });
        }
        startWatchdog();
        loadStickSens();
        setupTouch();
        I18n.load(this);
        instance = this;
        gameId = Achievements.gameId(gameGrp(getArguments()));
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
            if (scale() != 100) applyScaleWhenReady(scale(), 0);
        }, 4000);

        // Gráficos del juego para el menú rápido (letras, átomo...), en segundo plano.
        String grp = getIntent().getStringExtra(EXTRA_BASE_GRP);
        if (grp != null) {
            new Thread(() -> {
                try {
                    baseGrp = new GrpArchive(new File(grp));
                    art = new DukeArt(Arrays.asList(baseGrp));
                    ui.post(() -> {
                        if (touch != null) touch.setArt(art);
                    });
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
        return GraphicsSettings.scale(this);
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
                android.view.Surface surf = mSurface.getHolder().getSurface();
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // "Siempre": aunque el cambio no sea instantáneo (algunos MediaTek lo ignoraban).
                    surf.setFrameRate(best.getRefreshRate(), android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            android.view.Surface.CHANGE_FRAME_RATE_ALWAYS);
                } else {
                    surf.setFrameRate(best.getRefreshRate(), android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                }
            }
            Log.i("Duke3D", "Pantalla a " + best.getRefreshRate() + " Hz (pedido: " + fpsLabel(fps) + ")");
            // Lo que de verdad queda al cabo de un momento (si el sistema lo limita, se verá aquí).
            ui.postDelayed(() -> {
                try {
                    Display dd = getWindowManager().getDefaultDisplay();
                    Log.i("Duke3D", "Frecuencia real de la pantalla: " + dd.getRefreshRate() + " Hz");
                } catch (Exception ignored) {
                }
            }, 2500);
        } catch (Exception e) {
            Log.w("Duke3D", "No se pudo cambiar la frecuencia de pantalla", e);
        }
    }

    /**
     * La resolución guardada, cuando el juego ya está en marcha (con mods tarda más en arrancar
     * y la orden llegaba antes de que existiera la consola).
     */
    private void applyScaleWhenReady(int percent, int tries) {
        if (inGame() || tries > 120) {
            applyScale(percent);
            return;
        }
        ui.postDelayed(() -> applyScaleWhenReady(percent, tries + 1), 500);
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

    /**
     * Pantalla completa de verdad: sin barra de estado ni de navegación. Al abrir el menú rápido
     * Android volvía a enseñar la barra de navegación y tapaba la parte de abajo.
     */
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

    /** Pausa o reanuda el sonido de Raze (OpenAL), que Android no para solo. */
    static native void nativeAudioPause(boolean pause);

    @Override
    protected void onPause() {
        super.onPause();
        try {
            nativeAudioPause(true);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            nativeAudioPause(false);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

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
        releaseAllFuncs();
        hideSystemBars();
        command("pausegame 1");
    }

    private void closeMenu() {
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        // El foco vuelve al juego (si no, el mando dejaba de llegarle tras usar el menú).
        if (mSurface != null) mSurface.requestFocus();
        hideSystemBars();
        command("pausegame 0");
    }

    private DukeMenuView.Page rootPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.add(I18n.t("continue"), null, this::closeMenu);
        p.add(I18n.t("cheats"), null, () -> quickMenu.push(cheatsPage()));
        p.add(I18n.t("graphics"), null, () -> quickMenu.push(graphicsPage()));
        p.add(I18n.t("controls"), null, () -> quickMenu.push(controlsPage()));
        p.addValue(I18n.t("vibration"), () -> I18n.t(vibrationOn ? "yes" : "no"), () -> {
            vibrationOn = !vibrationOn;
            prefs().edit().putBoolean("vibracion", vibrationOn).apply();
            if (vibrationOn) vibrate(200, 120); // para notar que está activa
        });
        p.add(I18n.t("to_launcher"), null, () -> quitGame(false));
        p.add(I18n.t("quit_game"), null, () -> quitGame(true));
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

    /** Controles: los táctiles y la reasignación de los botones del mando. */
    private DukeMenuView.Page controlsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.addValue(I18n.t("touch_controls"), () -> ControlSettings.touchModeLabel(this), () -> {
            ControlSettings.cycleTouchMode(this);
            touchActive = !ControlSettings.hasGamepad();
            updateTouch();
        });
        p.addValue(I18n.t("touch_sens"), () -> ControlSettings.sensLabel(this), () -> {
            ControlSettings.cycleSens(this);
            updateTouch();
        });
        p.add(I18n.t("gamepad_config"), null, () -> quickMenu.push(gamepadPage()));
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        return p;
    }

    /** Configurar mando: la acción de cada botón (A: cambiar, Start: por defecto). */
    private DukeMenuView.Page gamepadPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        status = null;
        // Sensibilidad del stick derecho (1-10): izquierda/derecha bajan y suben.
        for (boolean vertical : new boolean[]{false, true}) {
            p.addValue(I18n.t(vertical ? "pad_sens_v" : "pad_sens_h"),
                    () -> ControlSettings.padSensLabel(this, vertical), () -> {
                        ControlSettings.stepPadSens(this, vertical, 0);
                        loadStickSens();
                    });
            p.entries.get(p.entries.size() - 1).step = d -> {
                ControlSettings.stepPadSens(this, vertical, d);
                loadStickSens();
            };
        }
        p.addValue(I18n.t("stick_move"), () -> ControlSettings.stickMoveLabel(this), () -> {
            ControlSettings.cycleStickMove(this);
            loadStickSens();
        });
        p.entries.get(p.entries.size() - 1).step = d -> {
            ControlSettings.stepStickMove(this, d);
            loadStickSens();
        };
        for (int i = 0; i < ControlSettings.KEYS.length; i++) {
            int key = ControlSettings.KEYS[i];
            String name = ControlSettings.keyName(i);
            p.addValue(name, () -> ControlSettings.actionName(ControlSettings.remap(this, key)),
                    () -> ControlSettings.cycleRemap(this, key));
            p.entries.get(p.entries.size() - 1).step = d -> ControlSettings.cycleRemap(this, key, d);
            p.entries.get(p.entries.size() - 1).reset = () -> {
                ControlSettings.resetRemap(this, key);
                status = name + ": " + ControlSettings.actionName(ControlSettings.DEFAULT);
            };
        }
        p.add(I18n.t("controls_reset"), null, () -> {
            ControlSettings.resetRemaps(this);
            status = I18n.t("controls_reset_done");
        });
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        p.footer = () -> status != null ? status : I18n.t("controls_hint");
        return p;
    }

    private DukeMenuView.Page graphicsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        GraphicsSettings.addTo(this, p, this::applyScale, Motor.RAZE ? this::applyRazeGraphics : this::applyPostFx);
        if (!Motor.RAZE) p.footer = () -> I18n.t("gfx_soft_note");
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

    /** Raze: aplica al momento las opciones gráficas guardadas. */
    private void applyRazeGraphics() {
        for (String cmd : GraphicsSettings.razeCommands(this)) command(cmd);
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
        gamepadUsed(e);
        if (menuOpen()) {
            // Con el menú abierto, el mando y las teclas son para el menú, no para el juego.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_SELECT) closeMenu();
                else quickMenu.onKeyDown(key, e);
            }
            return true;
        }
        // Botones reasignados (solo en plena partida; en los menús del juego, como siempre).
        if (key != KeyEvent.KEYCODE_BACK && inGame()) {
            int act = ControlSettings.remap(this, key);
            if (act != ControlSettings.DEFAULT) {
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                if (act >= 0) {
                    if (e.getRepeatCount() == 0 || !down) pressFunc(act, down);
                } else if (act == ControlSettings.QUICK_MENU && down && e.getRepeatCount() == 0 && art != null) {
                    openMenu();
                }
                return true;
            }
        }
        if (isPadKey(e) && razePadKey(e)) return true;
        if (key == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                // En plena partida, el menú rápido; en los menús del juego, el título, etc., Escape.
                // En Raze, Atrás abre siempre el menú rápido (su menú se maneja con B).
                if (art != null && (inGame() || Motor.RAZE)) openMenu();
                else sendKey(KeyEvent.KEYCODE_ESCAPE);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private long lastAxisMove;

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        gamepadUsed(e);
        if (!menuOpen() && (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && (Motor.RAZE || inGame())) {
            razePadMotion(e);
            return true;
        }
        if (!menuOpen()) return super.dispatchGenericMotionEvent(e);
        // Crucetas que llegan como ejes (HAT) y el stick izquierdo mueven el menú.
        quickMenu.onAxis(e);
        return true;
    }

    // ---- Mando (las dos versiones, la misma disposición) ----
    // El mando pasa por la app como los táctiles (y respeta "Configurar mando"), en Raze y en
    // EDuke32. En los menús del juego hace de teclado: cruceta/stick = flechas, A = Intro,
    // B/Select = Escape; Start abre y cierra el menú del juego.

    private static boolean isPadKey(KeyEvent e) {
        int src = e.getSource();
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || KeyEvent.isGamepadButton(e.getKeyCode());
    }

    /** Lo que hace cada botón en plena partida si no se ha cambiado (gamefunc_* de EDuke32). */
    private static int razeDefault(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return 9;      // saltar
            case KeyEvent.KEYCODE_BUTTON_B: return 10;     // agacharse
            case KeyEvent.KEYCODE_BUTTON_X: return 6;      // usar / abrir
            case KeyEvent.KEYCODE_BUTTON_Y: return 29;     // usar objeto
            case KeyEvent.KEYCODE_BUTTON_L1: return 51;    // arma anterior
            case KeyEvent.KEYCODE_BUTTON_R1: return 50;    // arma siguiente
            case KeyEvent.KEYCODE_BUTTON_L2: return 49;    // patada
            case KeyEvent.KEYCODE_BUTTON_R2: return 5;     // disparar
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 7; // correr
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 41; // centrar vista
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 38; // mapa
            case KeyEvent.KEYCODE_DPAD_UP: return 35;      // botiquín
            case KeyEvent.KEYCODE_DPAD_DOWN: return 33;    // jetpack
            case KeyEvent.KEYCODE_DPAD_LEFT: return 30;    // objeto anterior
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 31;   // objeto siguiente
            default: return -1;
        }
    }

    /** Tecla equivalente en los menús de Raze. */
    private static int razeMenuKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return key;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return KeyEvent.KEYCODE_ENTER;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return KeyEvent.KEYCODE_ESCAPE;
            default:
                return -1;
        }
    }

    private boolean razePadKey(KeyEvent e) {
        int key = e.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) return false; // Atrás: el menú rápido (más abajo)
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        // Classic (EDuke32): fuera de la partida y con Start, el mando va directo al juego como
        // siempre (sus menús lo manejan bien); en plena partida, la misma disposición que Raze.
        if (!Motor.RAZE && (!inGame() || key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_MENU)) {
            // Al ir al menú con Start, que no se quede nada pulsado (ni el stick andando).
            if (key == KeyEvent.KEYCODE_BUTTON_START && e.getAction() == KeyEvent.ACTION_DOWN) releaseAllFuncs();
            return false;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_START || key == KeyEvent.KEYCODE_MENU) {
            // Start: abre y cierra el menú del juego de Raze (sin sus opciones: eso va en el
            // menú rápido, que se abre con Atrás o el botón táctil).
            if (first) {
                releaseAllFuncs(); // que no se quede nada pulsado al pasar al menú
                if (Motor.RAZE) command("android_start");
                else sendKey(KeyEvent.KEYCODE_ESCAPE); // EDuke32: Escape abre y cierra su menú
            }
            return true;
        }
        if (inGame()) {
            int f = razeDefault(key);
            if (f >= 0 && (e.getRepeatCount() == 0 || !down)) pressFunc(f, down);
            return true; // ningún botón del mando llega a Raze por su cuenta (lo haría dos veces)
        }
        // Fuera de la partida: si se suelta un botón que se pulsó jugando, se suelta también en el juego.
        if (!down) {
            int f = remapOr(key);
            if (f >= 0) pressFunc(f, false);
        }
        int k = razeMenuKey(key);
        if (k < 0) return true;
        if (Motor.RAZE) {
            // Raze: la acción va directa a su menú (con teclas simuladas, B había que pulsarlo dos veces).
            if (down) {
                int mkey = menuKeyFor(k);
                if (mkey >= 0) command("android_menukey " + mkey);
            }
        } else {
            super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k,
                    e.getRepeatCount(), 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
        }
        return true;
    }

    /** Tecla → acción de menú de Raze (MKEY_*: arriba 0, abajo 1, izquierda 2, derecha 3, aceptar 6, atrás 7). */
    private static int menuKeyFor(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return 0;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 1;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 2;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 3;
            case KeyEvent.KEYCODE_ENTER: return 6;
            case KeyEvent.KEYCODE_ESCAPE: return 7;
            default: return -1;
        }
    }

    private boolean triggerL, triggerR;
    private float stickH = 1f, stickV = 1f, stickMoveSens = 1f;

    private void loadStickSens() {
        stickH = ControlSettings.padSensFactor(this, false);
        stickV = ControlSettings.padSensFactor(this, true);
        stickMoveSens = ControlSettings.stickMove(this);
    }

    private static float clampAxis(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
    private int stickMenuKey = -1;

    private static float dead(float v) {
        return Math.abs(v) < 0.15f ? 0f : v;
    }

    private void razePadMotion(MotionEvent e) {
        float lx = dead(e.getAxisValue(MotionEvent.AXIS_X)), ly = dead(e.getAxisValue(MotionEvent.AXIS_Y));
        float rx = dead(e.getAxisValue(MotionEvent.AXIS_Z)), ry = dead(e.getAxisValue(MotionEvent.AXIS_RZ));
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        if (inGame()) {
            try {
                nativeTouchMove(clampAxis(lx * stickMoveSens), clampAxis(ly * stickMoveSens));
                nativeStickLook(rx * stickH, ry * stickV);
            } catch (UnsatisfiedLinkError ignored) {
            }
            boolean l = lt > 0.5f, r = rt > 0.5f;
            if (l != triggerL) {
                triggerL = l;
                int f = remapOr(KeyEvent.KEYCODE_BUTTON_L2);
                if (f >= 0) pressFunc(f, l);
            }
            if (r != triggerR) {
                triggerR = r;
                int f = remapOr(KeyEvent.KEYCODE_BUTTON_R2);
                if (f >= 0) pressFunc(f, r);
            }
            razeHat(hx, hy, true);
            return;
        }
        // Menús: stick izquierdo y cruceta (como ejes) = flechas.
        int k = -1;
        float x = Math.abs(hx) > 0.5f ? hx : lx, y = Math.abs(hy) > 0.5f ? hy : ly;
        if (y < -0.5f) k = KeyEvent.KEYCODE_DPAD_UP;
        else if (y > 0.5f) k = KeyEvent.KEYCODE_DPAD_DOWN;
        else if (x < -0.5f) k = KeyEvent.KEYCODE_DPAD_LEFT;
        else if (x > 0.5f) k = KeyEvent.KEYCODE_DPAD_RIGHT;
        long now = android.os.SystemClock.uptimeMillis();
        if (k >= 0 && (k != stickMenuKey || now - lastAxisMove > 220)) {
            if (Motor.RAZE) command("android_menukey " + menuKeyFor(k));
            else sendKey(k);
            lastAxisMove = now;
        }
        stickMenuKey = k;
    }

    private int hatKey = -1;

    /** Cruceta que llega como ejes (HAT) en plena partida: como sus botones. */
    private void razeHat(float hx, float hy, boolean game) {
        int k = -1;
        if (hy < -0.5f) k = KeyEvent.KEYCODE_DPAD_UP;
        else if (hy > 0.5f) k = KeyEvent.KEYCODE_DPAD_DOWN;
        else if (hx < -0.5f) k = KeyEvent.KEYCODE_DPAD_LEFT;
        else if (hx > 0.5f) k = KeyEvent.KEYCODE_DPAD_RIGHT;
        if (k == hatKey) return;
        if (hatKey >= 0) {
            int f = remapOr(hatKey);
            if (f >= 0) pressFunc(f, false);
        }
        hatKey = k;
        if (k >= 0) {
            int f = remapOr(k);
            if (f >= 0) pressFunc(f, true);
        }
    }

    /** La acción del botón: la de "Configurar mando" o la de serie. */
    private int remapOr(int key) {
        int a = ControlSettings.remap(this, key);
        if (a == ControlSettings.DEFAULT) return razeDefault(key);
        return a >= 0 ? a : -1;
    }

    @Override
    protected void onDestroy() {
        watchdogRunning = false;
        instance = null;
        // Se cierra el proceso ANTES de super.onDestroy(): SDL esperaría a que el juego termine
        // y EDuke32 no termina con el aviso de SDL (pide confirmación). Esa espera bloqueaba la
        // app y salía el aviso de "no responde". Además EDuke32 no puede arrancar dos veces en
        // el mismo proceso.
        android.os.Process.killProcess(android.os.Process.myPid());
        super.onDestroy();
    }

    /**
     * Sale del juego: con "quit" EDuke32 guarda su configuración y termina el proceso; el
     * lanzador (que va en otro proceso) queda a la vista. Si no termina en 2 s, se cierra igual.
     * @param all también cierra el lanzador
     */
    private void quitGame(boolean all) {
        // Primero termina el juego; el lanzador se cierra después al volver a él (si se cerraba
        // antes, la ventana del juego desaparecía con el juego aún dibujando y Raze fallaba).
        if (all) prefs().edit().putBoolean("salir_todo", true).commit();
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        command("pausegame 0");
        command("quit");
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 2000);
    }
}
