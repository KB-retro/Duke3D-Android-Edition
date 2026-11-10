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

    /** true en plena partida (no en los menús del juego, el título, demos o fin de nivel). */
    static native boolean nativeInGame();

    private static boolean inGame() {
        try {
            return nativeInGame();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static final int[] FPS_VALUES = {60, 90, 120, 0};
    private static final int[] SCALE_VALUES = {100, 75, 50};

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
        command("pausegame 1");
    }

    private void closeMenu() {
        if (quickMenu != null) quickMenu.setVisibility(View.GONE);
        command("pausegame 0");
    }

    private DukeMenuView.Page rootPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.add(I18n.t("continue"), null, this::closeMenu);
        p.add(I18n.t("quicksave"), null, () -> {
            closeMenu();
            command("quicksave");
        });
        p.add(I18n.t("quickload"), null, () -> {
            closeMenu();
            command("quickload");
        });
        p.add(I18n.t("cheats"), null, () -> quickMenu.push(cheatsPage()));
        p.add(I18n.t("graphics"), null, () -> quickMenu.push(graphicsPage()));
        p.addValue("FPS", () -> fpsLabel(fps()), () -> {
            int i = indexOf(FPS_VALUES, fps());
            int next = FPS_VALUES[(i + 1) % FPS_VALUES.length];
            prefs().edit().putInt("fps", next).apply();
            applyRefreshRate(next);
            command("r_maxfps " + next);
        });
        p.add(I18n.t("game_menu"), null, () -> {
            closeMenu();
            sendKey(KeyEvent.KEYCODE_ESCAPE);
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
            });
        }
        p.add(I18n.t("back"), null, () -> quickMenu.pop());
        p.footer = () -> status != null ? status : "";
        return p;
    }

    private DukeMenuView.Page graphicsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.addValue(I18n.t("resolution"), () -> scale() + "%", () -> {
            int i = indexOf(SCALE_VALUES, scale());
            int next = SCALE_VALUES[(i + 1) % SCALE_VALUES.length];
            prefs().edit().putInt("escala", next).apply();
            applyScale(next);
        });
        p.add(I18n.t("enhanced_soon"), null, null);
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
        super.onDestroy();
        // EDuke32 no está pensado para arrancar dos veces en el mismo proceso.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
