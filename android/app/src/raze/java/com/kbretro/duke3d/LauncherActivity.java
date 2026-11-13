package com.kbretro.duke3d;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menú inicial: pide permiso de archivos, busca los datos de Steam y lanza el juego elegido.
 *
 * Si encuentra los archivos del juego, enseña el menú con los gráficos del propio juego
 * (DukeMenuView). Si no (falta el permiso o la carpeta, o el juego se cerró por un fallo), usa
 * el menú sencillo de Android de siempre.
 */
public class LauncherActivity extends Activity {
    private static final int REQUEST_STORAGE = 1;
    /** Desde el menú rápido del juego: cerrar también el lanzador. */
    static final String EXTRA_QUIT = "salir";
    private static final int COLOR_BG = 0xFF101010;
    private static final int COLOR_ACCENT = 0xFFFFC21A;

    private LinearLayout list;
    private android.widget.FrameLayout classicRoot;
    /** Fondo "Game Over" cuando hay un fallo (el juego se cerró o faltan los datos). */
    private ImageView errorBackground;

    /** Gráficos del juego ya cargados para el menú (se cargan una vez por carpeta). */
    private static final class MenuAssets {
        String dir;
        DukeArt art;
        List<GameData.Game> games;
        final Map<String, Bitmap> icons = new HashMap<>();
        final List<GrpArchive> open = new ArrayList<>();
    }

    private MenuAssets assets;
    private final MenuMusic music = new MenuMusic();
    private DukeMenuView menuView;
    private boolean loading;
    /** Últimas líneas del registro de la última partida (para ver por qué se cerró). */
    private List<String> lastLog;
    /** Aviso en Opciones al generar el registro a mano. */
    private volatile String logStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        I18n.load(this);
        ScrollView scroll = new ScrollView(this);
        classicRoot = new android.widget.FrameLayout(this);
        classicRoot.setBackgroundColor(COLOR_BG);
        errorBackground = new ImageView(this);
        errorBackground.setImageResource(R.drawable.fondo_error);
        errorBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        errorBackground.setVisibility(View.GONE);
        classicRoot.addView(errorBackground, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        classicRoot.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(classicRoot);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences aj = getSharedPreferences("ajustes", MODE_MULTI_PROCESS);
        if (aj.getBoolean("salir_todo", false)) {
            // «Salir del juego» desde el menú rápido: el juego ya ha terminado; ahora el lanzador.
            aj.edit().putBoolean("salir_todo", false).apply();
            getPreferences(MODE_PRIVATE).edit().putBoolean("jugando", false).apply();
            finishAffinity();
            return;
        }
        if (getPreferences(MODE_PRIVATE).getBoolean("jugando", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("jugando", false).apply();
            // El registro entero tarda (logcat): en otro hilo, para no congelar el lanzador al
            // volver del juego. Si hubo un fallo, se enseña cuando está listo.
            new Thread(() -> {
                List<String> log = saveLog();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lastLog = log;
                    if (hasCrash()) refresh();
                });
            }, "Registro").start();
        }
        refresh();
    }

    private void refresh() {
        // Con permiso, datos y sin fallo que enseñar: el menú con los gráficos del juego.
        if (hasStoragePermission() && !hasCrash()) {
            File gameDir = GameData.findGameDir(baseDir());
            if (gameDir != null) {
                showDukeMenu(gameDir);
                return;
            }
        }
        showClassic();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_QUIT, false)) finishAffinity();
    }

    @Override
    protected void onPause() {
        super.onPause();
        music.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        music.stop();
        closeAssets();
    }

    private boolean musicOn() {
        return getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean("musica_menu", true);
    }

    private void startMusic(File gameDir) {
        if (musicOn() && assets != null && !assets.open.isEmpty()) music.play(this, gameDir, assets.open.get(0));
        else music.stop();
    }

    // ---- Menú con los gráficos del juego ----

    private void showDukeMenu(File gameDir) {
        if (assets != null && assets.dir.equals(gameDir.getAbsolutePath())) {
            buildMenu(gameDir, false);
            return;
        }
        if (loading) return;
        loading = true;
        TextView wait = new TextView(this);
        wait.setText(I18n.t("loading"));
        wait.setTextColor(Color.WHITE);
        wait.setGravity(Gravity.CENTER);
        wait.setBackgroundColor(Color.BLACK);
        setContentView(wait);
        new Thread(() -> {
            MenuAssets a = null;
            try {
                a = loadAssets(gameDir);
            } catch (Exception e) {
                android.util.Log.e("Duke3D", "No se pudieron leer los gráficos del juego", e);
            }
            MenuAssets result = a;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    showClassic();
                    return;
                }
                closeAssets();
                assets = result;
                buildMenu(gameDir, true);
            });
        }, "CargaMenu").start();
    }

    /** Lee los gráficos del juego base y el icono de cada expansión (su pantalla de carga). */
    private static MenuAssets loadAssets(File gameDir) throws Exception {
        MenuAssets a = new MenuAssets();
        a.dir = gameDir.getAbsolutePath();
        a.games = GameData.games(gameDir);
        if (a.games.isEmpty()) throw new Exception("Sin juegos");
        GrpArchive base = new GrpArchive(a.games.get(0).grp);
        a.open.add(base);
        a.art = new DukeArt(Arrays.asList(base));
        Bitmap baseIcon = icon(a.art, null);
        a.icons.put(a.games.get(0).title, baseIcon);
        for (int i = 1; i < a.games.size(); i++) {
            GameData.Game g = a.games.get(i);
            try {
                GrpArchive addon = new GrpArchive(g.grp);
                a.open.add(addon);
                DukeArt art = new DukeArt(Arrays.asList(base, addon));
                a.icons.put(g.title, icon(art, baseIcon));
            } catch (Exception e) {
                a.icons.put(g.title, baseIcon);
            }
        }
        return a;
    }

    /** La pantalla de carga del juego; si una expansión no trae la suya, su pantalla de título. */
    private static Bitmap icon(DukeArt art, Bitmap baseIcon) {
        Bitmap b = art.tile(DukeArt.LOADSCREEN);
        if (b == null || (baseIcon != null && b.sameAs(baseIcon))) {
            Bitmap t = art.tile(DukeArt.BETASCREEN);
            if (t != null) b = t;
        }
        return b;
    }

    private void closeAssets() {
        if (assets == null) return;
        for (GrpArchive g : assets.open) {
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
        assets = null;
    }

    private void buildMenu(File gameDir, boolean fresh) {
        if (menuView == null || fresh) {
            menuView = new DukeMenuView(this, assets.art);
            menuView.versionText = (Motor.RAZE ? "Duke3D Raze Edition " : "Duke Nukem 3D Classic Edition ")
                    + BuildConfig.VERSION_NAME.replace("-raze", "");
            menuView.setBackground(android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.fondo_menu));
        }
        menuView.setRoot(rootPage(gameDir), this::finish);
        setContentView(menuView);
        menuView.requestFocus();
        hideSystemBars();
        startMusic(gameDir);
    }

    private DukeMenuView.Page rootPage(File gameDir) {
        DukeMenuView.Page root = new DukeMenuView.Page();
        // Con una campaña activada solo se puede jugar al juego base (es sobre el que corre).
        boolean campaign = activeCampaign(gameDir) != null;
        for (GameData.Game g : assets.games) {
            if (campaign && g != assets.games.get(0)) continue;
            Bitmap cover = cover(g);
            if (cover != null) root.addGame(g.menuLabel(), cover, () -> launch(gameDir, g));
            else root.add(g.menuLabel(), assets.icons.get(g.title), () -> launch(gameDir, g));
        }
        // Penthouse Paradise (DLC gratuito de 1997), si está en extras/; como las expansiones,
        // no sale con una campaña activada.
        File pent = campaign ? null : findPenthouse(gameDir);
        if (pent != null) {
            root.addGame("Penthouse", box(R.drawable.caja_penthouse), () -> launchPenthouse(gameDir, pent));
            DukeMenuView.Entry e = root.entries.get(root.entries.size() - 1);
            e.coverTap = () -> penthouseTap(e);
        }
        root.add(I18n.t("mods"), null, () -> menuView.push(modsPage(gameDir)));
        root.add(I18n.t("options"), null, () -> menuView.push(optionsPage(gameDir)));
        root.add(I18n.t("quit"), null, this::finishAffinity);

        // El otro lado: los clásicos y demás juegos (por orden de salida) y los extras, con la
        // caja a la izquierda. Se pasa de un lado a otro con izquierda/derecha, L1/R1 o tocando
        // las pestañas de arriba.
        DukeMenuView.Page classics = classicsPage(gameDir);
        String[] tabs = {"Duke Nukem 3D", I18n.t("dos_games")};
        root.tabs = tabs;
        classics.tabs = tabs;
        classics.rightTab = true;
        classics.coverLeft = true;
        root.otherSide = classics;
        classics.otherSide = root;
        return showClassics ? classics : root;
    }

    /** El menú principal vuelve al lado en el que se lanzó el último juego. */
    private boolean showClassics;

    /** Lado de los clásicos: Duke Nukem (1991), Duke Nukem II (1993), Time to Kill (1998), Land of the Babes (2000). */
    private DukeMenuView.Page classicsPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        List<File> dirs = extrasDirs(gameDir);
        String where = (dirs.isEmpty() ? extrasDir() : dirs.get(0)).getAbsolutePath();
        String[] note = {null};
        // Duke Nukem: un archivo por episodio; al elegirlo, sus tres episodios.
        p.addGame("Duke Nukem", box(R.drawable.caja_duke1), () -> {
            DukeMenuView.Page eps = new DukeMenuView.Page();
            for (DosGame g : DOS_GAMES) {
                if (!g.folder.equals("duke1")) continue;
                String title = g.title.substring(g.title.indexOf(" - ") + 3);
                eps.add(title, null, () -> {
                    File exe = findDosExe(extrasDirs(gameDir), g);
                    if (exe != null) launchDos(gameDir, g, exe);
                });
            }
            eps.add(I18n.t("back"), null, () -> menuView.pop());
            File any = null;
            for (DosGame g : DOS_GAMES) if (g.folder.equals("duke1") && any == null) any = findDosExe(dirs, g);
            boolean has = any != null;
            eps.footer = () -> has ? "" : I18n.t("dos_missing") + where + "/duke1";
            menuView.push(eps);
        });
        for (DosGame g : DOS_GAMES) {
            if (!g.folder.equals("duke2")) continue;
            p.addGame("Duke Nukem II", box(R.drawable.caja_duke2), () -> {
                File exe = findDosExe(extrasDirs(gameDir), g);
                if (exe != null) launchDos(gameDir, g, exe);
                else note[0] = I18n.t("dos_missing") + where + "/duke2";
            });
        }
        int[] psxBoxes = {R.drawable.caja_ttk, R.drawable.caja_lotb};
        for (int i = 0; i < PSX_GAMES.length; i++) {
            String[] pg = PSX_GAMES[i];
            p.addGame(pg[0].replace("Duke Nukem: ", ""), box(psxBoxes[i]), () -> {
                List<File> ds = extrasDirs(gameDir);
                File disc = null;
                for (File extras : ds) {
                    disc = findPsxDisc(findIgnoreCase(extras, pg[1]), 2);
                    if (disc != null) break;
                }
                if (disc == null) {
                    note[0] = I18n.t("dos_missing") + where + "/" + pg[1];
                    return;
                }
                File b = findPsxBios(disc.getParentFile(), ds);
                if (b == null) {
                    note[0] = I18n.t("psx_no_bios") + disc.getParentFile().getAbsolutePath();
                    return;
                }
                launchPsx(gameDir, disc, b, pg[2]);
            });
        }
        p.add(I18n.t("extras"), null, () -> menuView.push(extrasPage(gameDir)));
        p.add(I18n.t("quit"), null, this::finishAffinity);
        int[] lastSel = {-1};
        p.footer = () -> {
            if (lastSel[0] != p.selected) {
                note[0] = null; // el aviso es del juego que se intentó lanzar
                lastSel[0] = p.selected;
            }
            return note[0] != null ? note[0] : "";
        };
        return p;
    }

    /** El ejecutable de un juego de MS-DOS en las carpetas extras (o null). */
    private static File findDosExe(List<File> dirs, DosGame g) {
        for (File extras : dirs) {
            File exe = findExe(findIgnoreCase(extras, g.folder), g.exe, 3);
            if (exe == null) exe = findExe(extras, g.exe, 4);
            if (exe != null) return exe;
        }
        return null;
    }

    private final Map<Integer, Bitmap> covers = new HashMap<>();

    /** La caja de cada juego (imágenes del APK). */
    private Bitmap cover(GameData.Game g) {
        String t = g.title.toLowerCase(java.util.Locale.ROOT);
        int res = t.contains("d.c.") ? R.drawable.caja_dc
                : t.contains("winter") ? R.drawable.caja_nw
                : t.contains("caribbean") ? R.drawable.caja_caribbean
                : R.drawable.caja_duke3d;
        Bitmap b = covers.get(res);
        if (b == null) {
            b = android.graphics.BitmapFactory.decodeResource(getResources(), res);
            covers.put(res, b);
        }
        return b;
    }

    private DukeMenuView.Page extrasPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.add(I18n.t("guide"), null, () -> menuView.push(guidePage()));
        p.add(I18n.t("music"), null, () -> menuView.push(musicPage(gameDir)));
        p.add(I18n.t("achievements"), null, () -> menuView.push(achievementsPage()));
        p.add(I18n.t("back"), null, () -> menuView.pop());
        return p;
    }

    // ---- Penthouse Paradise ----

    // Huevo de pascua: 3 toques seguidos en su caja enseñan una portada de la revista de 1997
    // al azar durante 10 segundos; luego vuelve la caja del juego.
    private static final int[] PENT_EGGS = {
            R.drawable.pent_egg_01, R.drawable.pent_egg_02, R.drawable.pent_egg_03, R.drawable.pent_egg_04,
            R.drawable.pent_egg_05, R.drawable.pent_egg_06, R.drawable.pent_egg_07, R.drawable.pent_egg_08,
            R.drawable.pent_egg_09, R.drawable.pent_egg_10,
    };
    private int pentTaps;
    private long pentLastTap;
    private int pentLastEgg = -1;
    private final android.os.Handler eggHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void penthouseTap(DukeMenuView.Entry e) {
        long now = android.os.SystemClock.uptimeMillis();
        pentTaps = now - pentLastTap < 700 ? pentTaps + 1 : 1;
        pentLastTap = now;
        if (pentTaps < 3) return;
        pentTaps = 0;
        int i;
        do {
            i = new java.util.Random().nextInt(PENT_EGGS.length);
        } while (i == pentLastEgg && PENT_EGGS.length > 1);
        pentLastEgg = i;
        e.cover = box(PENT_EGGS[i]);
        menuView.invalidate();
        eggHandler.removeCallbacksAndMessages(null);
        eggHandler.postDelayed(() -> {
            e.cover = box(R.drawable.caja_penthouse);
            if (menuView != null) menuView.invalidate();
        }, 10000);
    }

    /** La carpeta con ppakpent.map (versión Atomic del DLC) dentro de extras/, o null. */
    private File findPenthouse(File gameDir) {
        for (File extras : extrasDirs(gameDir)) {
            File map = findExe(extras, "ppakpent.map", 5);
            if (map != null) return map.getParentFile();
        }
        return null;
    }

    /**
     * El paquete es una instalación entera del Duke 3D de 1997: solo se usan los archivos del DLC
     * para la versión Atomic (la de la Megaton), copiados a una carpeta aparte con los nombres
     * que espera el juego (GAME.CON, DEFS.CON, USER.CON). Así no se mezcla con nada más.
     */
    private void launchPenthouse(File gameDir, File src) {
        File prep = new File(getFilesDir(), "penthouse");
        try {
            File[] old = prep.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            //noinspection ResultOfMethodCallIgnored
            prep.mkdirs();
            String[][] files = {
                    {"ppakgame.con", "GAME.CON"}, {"ppakdefs.con", "DEFS.CON"}, {"ppakuser.con", "USER.CON"},
                    {"ppakgame.con", null}, {"ppakdefs.con", null}, {"ppakuser.con", null},
                    {"ppakpent.map", null}, {"needle.wav", null}, {"needle2.wav", null}, {"pob.mid", null},
            };
            // Además: sus texturas (TILES014.ART) y todos sus sonidos y músicas.
            File[] all = src.listFiles();
            if (all != null) {
                for (File in : all) {
                    String n = in.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!in.isFile() || !(n.endsWith(".art") || n.endsWith(".voc") || n.endsWith(".wav")
                            || n.endsWith(".mid"))) continue;
                    copyFile(in, new File(prep, n));
                    copyFile(in, new File(prep, n.toUpperCase(java.util.Locale.ROOT)));
                    copyFile(in, new File(prep, in.getName()));
                }
            }
            for (String[] f : files) {
                File in = findIgnoreCase(src, f[0]);
                if (in == null) continue;
                if (f[1] != null) {
                    copyFile(in, new File(prep, f[1]));
                } else {
                    // Con el nombre en minúsculas y en mayúsculas (los .CON los nombran de las dos formas).
                    copyFile(in, new File(prep, f[0].toLowerCase(java.util.Locale.ROOT)));
                    copyFile(in, new File(prep, f[0].toUpperCase(java.util.Locale.ROOT)));
                    copyFile(in, new File(prep, in.getName()));
                }
            }
        } catch (java.io.IOException e) {
            android.widget.Toast.makeText(this, "Penthouse Paradise: " + e, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        List<GameData.Game> all = GameData.games(gameDir);
        if (all.isEmpty()) return;
        launchAlone = true;
        launch(gameDir, all.get(0), new String[]{"-j", prep.getAbsolutePath(), "-x", "GAME.CON", "-map", "ppakpent.map"});
    }

    private static void copyFile(File in, File out) throws java.io.IOException {
        if (out.exists()) return;
        try (java.io.InputStream is = new java.io.FileInputStream(in);
             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    // ---- Guía de uso ----

    private DukeMenuView.Page guidePage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        for (int i = 0; i < Guide.TOPICS.length; i++) {
            int n = i;
            p.add(Guide.TOPICS[i].title(), null, () -> menuView.push(guideTopic(n)));
        }
        p.add(I18n.t("back"), null, () -> menuView.pop());
        return p;
    }

    private DukeMenuView.Page guideTopic(int i) {
        Guide.Topic t = Guide.TOPICS[i];
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.title = t.title();
        p.body = t.lines();
        if (i + 1 < Guide.TOPICS.length) {
            p.add(I18n.t("next"), null, () -> {
                menuView.pop();
                menuView.push(guideTopic(i + 1));
            });
        }
        p.add(I18n.t("back"), null, () -> menuView.pop());
        return p;
    }

    // ---- Juegos de MS-DOS (DosActivity) ----

    /** Un juego de MS-DOS: su carpeta dentro de extras/ y el ejecutable que arranca. */
    private static final class DosGame {
        final String title, folder, exe, id;

        DosGame(String title, String folder, String exe, String id) {
            this.title = title;
            this.folder = folder;
            this.exe = exe;
            this.id = id;
        }
    }

    private static final DosGame[] DOS_GAMES = {
            new DosGame("Duke Nukem - Shrapnel City", "duke1", "DN1.EXE", "duke1_ep1"),
            new DosGame("Duke Nukem - Mission: Moonbase", "duke1", "DN2.EXE", "duke1_ep2"),
            new DosGame("Duke Nukem - Trapped in the Future", "duke1", "DN3.EXE", "duke1_ep3"),
            new DosGame("Duke Nukem II", "duke2", "NUKEM2.EXE", "duke2"),
    };

    /** Archivo o carpeta dentro de dir sin mirar mayúsculas (o null). */
    private static File findIgnoreCase(File dir, String name) {
        File[] list = dir != null ? dir.listFiles() : null;
        if (list == null) return null;
        for (File f : list) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    /** El ejecutable en la carpeta o en sus subcarpetas (zips descomprimidos tal cual). */
    private static File findExe(File dir, String name, int depth) {
        File f = findIgnoreCase(dir, name);
        if (f != null && f.isFile()) return f;
        File[] list = depth > 0 && dir != null ? dir.listFiles() : null;
        if (list == null) return null;
        for (File d : list) {
            if (!d.isDirectory()) continue;
            f = findExe(d, name, depth - 1);
            if (f != null) return f;
        }
        return null;
    }

    /** Carpetas "extras" posibles: en la carpeta elegida, junto al juego o encima de él. */
    private List<File> extrasDirs(File gameDir) {
        List<File> out = new ArrayList<>();
        File[] bases = {baseDir(), gameDir, gameDir != null ? gameDir.getParentFile() : null};
        for (File b : bases) {
            File d = findIgnoreCase(b, "extras");
            if (d != null && d.isDirectory() && !out.contains(d)) out.add(d);
        }
        return out;
    }

    private File extrasDir() {
        File d = findIgnoreCase(baseDir(), "extras");
        return d != null ? d : new File(baseDir(), "extras");
    }

    private DukeMenuView.Page dosPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        List<File> dirs = extrasDirs(gameDir);
        int found = 0;
        for (DosGame g : DOS_GAMES) {
            File exe = null;
            for (File extras : dirs) {
                // Primero en su carpeta (duke1 / duke2); si no, en cualquier sitio dentro de extras.
                exe = findExe(findIgnoreCase(extras, g.folder), g.exe, 3);
                if (exe == null) exe = findExe(extras, g.exe, 4);
                if (exe != null) break;
            }
            if (exe == null) continue;
            found++;
            File f = exe;
            p.add(g.title, null, () -> launchDos(gameDir, g, f));
        }
        // Juegos de PlayStation: Time to Kill (extras/timetokill) y Land of the Babes
        // (extras/landofthebabes).
        String[] psxNote = {null};
        for (String[] pg : PSX_GAMES) {
            File disc = null;
            for (File extras : dirs) {
                disc = findPsxDisc(findIgnoreCase(extras, pg[1]), 2);
                if (disc != null) break;
            }
            android.util.Log.i("Duke3D", pg[0] + ": disco " + disc);
            if (disc == null) continue;
            found++;
            File d = disc;
            if (findPsxBios(d.getParentFile(), dirs) == null)
                psxNote[0] = I18n.t("psx_no_bios") + d.getParentFile().getAbsolutePath();
            p.add(pg[0], null, () -> {
                File b = findPsxBios(d.getParentFile(), extrasDirs(gameDir));
                if (b == null) {
                    psxNote[0] = I18n.t("psx_no_bios") + d.getParentFile().getAbsolutePath();
                    menuView.invalidate();
                    return;
                }
                launchPsx(gameDir, d, b, pg[2]);
            });
        }
        p.add(I18n.t("back"), null, () -> menuView.pop());
        String where = (dirs.isEmpty() ? extrasDir() : dirs.get(0)).getAbsolutePath();
        android.util.Log.i("Duke3D", "Juegos de MS-DOS y PlayStation: " + found + " en " + dirs);
        int total = found;
        p.footer = () -> psxNote[0] != null ? psxNote[0]
                : total == 0 ? I18n.t("dos_none") + where + "/duke1 - duke2 - timetokill - landofthebabes" : "";
        return p;
    }

    // ---- Juegos de PlayStation (PsxActivity) ----

    /** Juegos de PlayStation: nombre, carpeta dentro de extras/ y nombre de su guardado rápido. */
    private static final String[][] PSX_GAMES = {
            {"Duke Nukem: Time to Kill", "timetokill", "timetokill"},
            {"Duke Nukem: Land of the Babes", "landofthebabes", "landofthebabes"},
    };

    private static final String[] PSX_DISC_EXT = {".m3u", ".cue", ".chd", ".pbp", ".iso"};

    /** El disco del juego en la carpeta o en sus subcarpetas (.m3u, .cue, .chd, .pbp o .iso). */
    private static File findPsxDisc(File dir, int depth) {
        File[] list = dir != null ? dir.listFiles() : null;
        if (list == null) return null;
        for (String ext : PSX_DISC_EXT)
            for (File f : list)
                if (f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(ext)) return f;
        if (depth <= 0) return null;
        for (File d : list) {
            if (!d.isDirectory()) continue;
            File f = findPsxDisc(d, depth - 1);
            if (f != null) return f;
        }
        return null;
    }

    /** La BIOS de PlayStation (un archivo de 512 KB): junto al disco, en extras/bios o en extras. */
    private static File findPsxBios(File discDir, List<File> extras) {
        List<File> where = new ArrayList<>();
        where.add(discDir);
        if (discDir != null) where.add(discDir.getParentFile());
        for (File e : extras) {
            where.add(findIgnoreCase(e, "bios"));
            where.add(e);
        }
        for (File d : where) {
            File[] list = d != null ? d.listFiles() : null;
            if (list == null) continue;
            for (File f : list) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && f.length() == 524288 && (n.endsWith(".bin") || n.endsWith(".rom")))
                    return f;
            }
        }
        return null;
    }

    private void launchPsx(File gameDir, File disc, File bios, String id) {
        showClassics = true;
        // Beetle PSX busca la BIOS por el nombre de su región (scph5500/5501/5502): se copia
        // con los tres a una carpeta de la app, sea cual sea el nombre del archivo del usuario.
        File system = new File(getFilesDir(), "psx_bios");
        //noinspection ResultOfMethodCallIgnored
        system.mkdirs();
        try {
            for (String n : new String[]{"scph5500.bin", "scph5501.bin", "scph5502.bin"}) {
                File out = new File(system, n);
                if (out.length() == bios.length() && out.lastModified() >= bios.lastModified()) continue;
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                copyFile(bios, out);
            }
        } catch (java.io.IOException e) {
            android.widget.Toast.makeText(this, "BIOS: " + e, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        File saves = new File(extrasDir(), "partidas");
        //noinspection ResultOfMethodCallIgnored
        saves.mkdirs();
        Intent intent = new Intent(this, PsxActivity.class);
        intent.putExtra(PsxActivity.EXTRA_DISC, disc.getAbsolutePath());
        intent.putExtra(PsxActivity.EXTRA_SYSTEM_DIR, system.getAbsolutePath());
        intent.putExtra(PsxActivity.EXTRA_SAVE_DIR, saves.getAbsolutePath());
        intent.putExtra(PsxActivity.EXTRA_STATE, new File(saves, id + ".estado").getAbsolutePath());
        List<GameData.Game> all = GameData.games(gameDir);
        if (!all.isEmpty()) intent.putExtra(PsxActivity.EXTRA_BASE_GRP, all.get(0).grp.getAbsolutePath());
        android.util.Log.i("Duke3D", "PlayStation: disco " + disc + ", BIOS " + bios);
        music.stop();
        startActivity(intent);
    }

    private void launchDos(File gameDir, DosGame g, File exe) {
        showClassics = true;
        File saves = new File(extrasDir(), "partidas");
        //noinspection ResultOfMethodCallIgnored
        saves.mkdirs();
        Intent intent = new Intent(this, DosActivity.class);
        intent.putExtra(DosActivity.EXTRA_EXE, exe.getAbsolutePath());
        intent.putExtra(DosActivity.EXTRA_SAVE_DIR, saves.getAbsolutePath());
        intent.putExtra(DosActivity.EXTRA_STATE, new File(saves, g.id + ".estado").getAbsolutePath());
        List<GameData.Game> all = GameData.games(gameDir);
        if (!all.isEmpty()) intent.putExtra(DosActivity.EXTRA_BASE_GRP, all.get(0).grp.getAbsolutePath());
        music.stop();
        startActivity(intent);
    }

    /** Los 20 logros: los conseguidos en amarillo; abajo, qué hay que hacer para el elegido. */
    private DukeMenuView.Page achievementsPage() {
        DukeMenuView.Page p = new DukeMenuView.Page();
        for (Achievements.Def d : Achievements.ALL) {
            p.addValue(d.name, () -> {
                if (Achievements.unlocked(this, d)) return I18n.t("unlocked_short");
                if (d.goal > 0) return Achievements.count(this, d) + "/" + d.goal;
                return "-";
            }, null);
            DukeMenuView.Entry e = p.entries.get(p.entries.size() - 1);
            e.active = () -> Achievements.unlocked(this, d);
        }
        p.add(I18n.t("back"), null, () -> menuView.pop());
        p.footer = () -> {
            String head = I18n.t("achievements") + " " + Achievements.unlockedCount(this) + "/"
                    + Achievements.ALL.length;
            if (p.selected < Achievements.ALL.length) return head + " - " + Achievements.ALL[p.selected].description();
            return head;
        };
        return p;
    }

    private List<MusicTracks.Track> tracks;
    private String nowPlaying;

    /** Reproductor: todas las pistas del juego y de las expansiones, con el nombre de su nivel. */
    private DukeMenuView.Page musicPage(File gameDir) {
        if (tracks == null) {
            List<GrpArchive> grps = new ArrayList<>();
            List<GameData.Game> games = new ArrayList<>();
            for (GameData.Game g : assets.games) {
                for (GrpArchive a : assets.open) {
                    if (a.file.equals(g.grp)) {
                        grps.add(a);
                        games.add(g);
                        break;
                    }
                }
            }
            tracks = MusicTracks.load(this, games, grps);
        }
        DukeMenuView.Page p = new DukeMenuView.Page();
        for (MusicTracks.Track t : tracks) {
            p.add(t.label, null, () -> {
                music.playFile(this, gameDir, t.grp, t.file, true);
                nowPlaying = t.label;
            });
            p.entries.get(p.entries.size() - 1).active = () -> t.file.equals(music.current());
        }
        if (tracks.isEmpty()) p.add(I18n.t("no_tracks"), null, null);
        p.add(I18n.t("back"), null, () -> menuView.pop());
        p.footer = () -> nowPlaying != null && music.current() != null ? I18n.t("playing") + nowPlaying : "";
        // Al salir del reproductor vuelve el tema del menú.
        p.onLeave = () -> {
            nowPlaying = null;
            music.stop();
            startMusic(gameDir);
        };
        return p;
    }

    private DukeMenuView.Page optionsPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.addValue(I18n.t("language"), () -> I18n.t("language_value"), () -> {
            I18n.set(this, I18n.EN.equals(I18n.lang()) ? I18n.ES : I18n.EN);
            tracks = null; // los nombres de las pistas, en el idioma nuevo
            // Los textos del menú se rehacen en el idioma nuevo.
            menuView.setRoot(rootPage(gameDir), this::finish);
            menuView.push(optionsPage(gameDir));
        });
        p.addValue(I18n.t("menu_music"), () -> I18n.t(musicOn() ? "yes" : "no"), () -> {
            getSharedPreferences("ajustes", MODE_PRIVATE).edit().putBoolean("musica_menu", !musicOn()).apply();
            startMusic(gameDir);
        });
        p.addValue("FPS", () -> {
            int f = getSharedPreferences("ajustes", MODE_MULTI_PROCESS).getInt("fps", 60);
            return f <= 0 ? I18n.t("unlimited") : f + " FPS";
        }, () -> {
            SharedPreferences sp = getSharedPreferences("ajustes", MODE_MULTI_PROCESS);
            int[] values = {60, 120, 0};
            int cur = sp.getInt("fps", 60), i = 0;
            for (int k = 0; k < values.length; k++) if (values[k] == cur) i = k;
            sp.edit().putInt("fps", values[(i + 1) % values.length]).apply();
        });
        p.addValue(I18n.t("vibration"), () -> I18n.t(
                getSharedPreferences("ajustes", MODE_MULTI_PROCESS).getBoolean("vibracion", true) ? "yes" : "no"), () -> {
            SharedPreferences sp = getSharedPreferences("ajustes", MODE_MULTI_PROCESS);
            sp.edit().putBoolean("vibracion", !sp.getBoolean("vibracion", true)).apply();
        });
        p.addValue(I18n.t("touch_controls"), () -> ControlSettings.touchModeLabel(this),
                () -> ControlSettings.cycleTouchMode(this));
        p.add(I18n.t("graphics"), null, () -> {
            DukeMenuView.Page g = new DukeMenuView.Page();
            GraphicsSettings.addTo(this, g, null, null);
            g.add(I18n.t("back"), null, () -> menuView.pop());
            if (!Motor.RAZE) g.footer = () -> I18n.t("gfx_soft_note");
            menuView.push(g);
        });
        p.add(I18n.t("folder"), null, this::pickFolder);
        p.add(I18n.t("make_log"), null, () -> {
            logStatus = I18n.t("making_log");
            new Thread(() -> {
                saveLog();
                logStatus = I18n.t("log_saved") + new File(baseDir(), "registro.txt").getAbsolutePath();
            }, "Registro").start();
        });
        p.add(I18n.t("back"), null, () -> menuView.pop());
        p.onLeave = () -> logStatus = null;
        p.footer = () -> logStatus != null ? logStatus : I18n.t("data") + gameDir.getAbsolutePath();
        return p;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @SuppressWarnings("deprecation")
    private void showSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ---- Menú sencillo (sin permiso, sin datos o tras un fallo) ----

    /** Con fallo, la imagen de Game Over de fondo y el texto sobre un panel oscuro para leerlo. */
    private void setErrorBackground(boolean on) {
        errorBackground.setVisibility(on ? View.VISIBLE : View.GONE);
        list.setBackgroundColor(on ? Color.argb(185, 0, 0, 0) : Color.TRANSPARENT);
    }

    private boolean hasCrash() {
        if (lastLog == null) return false;
        for (String line : lastLog) if (line.contains("F/Duke3D")) return true;
        return false;
    }

    private void showClassic() {
        music.stop();
        if (classicRoot.getParent() == null) setContentView(classicRoot);
        showSystemBars();
        list.removeAllViews();
        // Con fallo (o sin datos) solo el fondo de Game Over, sin la carátula encima.
        boolean error = hasCrash() || (hasStoragePermission() && GameData.findGameDir(baseDir()) == null);
        setErrorBackground(error);
        if (hasCrash()) {
            // Pantalla del fallo: arriba solo Continuar y Salir, y debajo el fallo.
            showCrash();
            return;
        }
        if (!error) addBanner();
        addSpace(12);

        if (!hasStoragePermission()) {
            addText("La app necesita permiso para leer los archivos del juego en la memoria.", 16, Color.WHITE);
            addButton("Dar permiso de archivos", v -> requestStoragePermission()).requestFocus();
            return;
        }

        File base = baseDir();
        File gameDir = GameData.findGameDir(base);
        if (gameDir == null) {
            setErrorBackground(true);
            if (base.equals(GameData.defaultDir()) && !base.exists()) base.mkdirs();
            addText("No se encuentra DUKE3D.GRP en:\n" + base.getAbsolutePath(), 18, COLOR_ACCENT);
            addSpace(8);
            addText("Copia la carpeta \"gameroot\" de Duke Nukem 3D: Megaton Edition (Steam) en esa "
                    + "carpeta, o elige con el botón la carpeta donde la tengas (puede llamarse como "
                    + "quieras y estar en la tarjeta SD).\n\n"
                    + "En Steam: clic derecho en el juego → Administrar → Ver archivos locales.\n"
                    + "Las expansiones (Duke It Out in D.C., Nuclear Winter y Caribbean) están dentro, "
                    + "en gameroot/addons.", 15, Color.WHITE);
            addSpace(16);
            addButton("Elegir carpeta", v -> pickFolder()).requestFocus();
            addButton("Volver a buscar", v -> refresh());
            if (!base.equals(GameData.defaultDir())) {
                addButton("Usar la carpeta por defecto (" + GameData.FOLDER + ")", v -> setBaseDir(null));
            }
            showLastLog();
            return;
        }

        List<GameData.Game> games = GameData.games(gameDir);
        View first = null;
        for (GameData.Game game : games) {
            View b = addButton(game.title, v -> launch(gameDir, game));
            if (first == null) first = b;
        }
        if (first != null) first.requestFocus();
        addSpace(16);
        addText("Datos: " + gameDir.getAbsolutePath(), 13, Color.GRAY);
        addButton("Elegir otra carpeta", v -> pickFolder());
        addText("Atrás o Start: menú del juego", 13, Color.GRAY);
        showLastLog();
    }

    // ---- Carpeta del juego ----

    private static final int REQUEST_FOLDER = 2;

    /** Carpeta elegida por el usuario, o /Duke3D si no ha elegido ninguna. */
    private File baseDir() {
        String path = getPreferences(MODE_PRIVATE).getString("carpeta", null);
        return path != null ? new File(path) : GameData.defaultDir();
    }

    private void setBaseDir(File dir) {
        getPreferences(MODE_PRIVATE).edit()
                .putString("carpeta", dir != null ? dir.getAbsolutePath() : null).apply();
        refresh();
    }

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER);
        } catch (Exception e) {
            addText("Este dispositivo no tiene selector de carpetas.", 14, COLOR_ACCENT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                setBaseDir(GameData.treeUriToFile(data.getData()));
            } catch (Exception e) {
                refresh();
            }
        }
    }

    private boolean launching;

    private void launch(File gameDir, GameData.Game game) {
        launch(gameDir, game, new String[0]);
    }

    // ---- Mods ----

    /** Nombres de los mods activados (se guardan en los ajustes). */
    private java.util.Set<String> enabledMods() {
        return new java.util.HashSet<>(getSharedPreferences("ajustes", MODE_MULTI_PROCESS)
                .getStringSet("mods_activos", new java.util.HashSet<>()));
    }

    /** La campaña activada (o null). */
    private File activeCampaign(File gameDir) {
        java.util.Set<String> enabled = enabledMods();
        if (enabled.isEmpty()) return null;
        for (File f : GameData.mods(gameDir)) {
            if (enabled.contains(f.getName()) && GameData.isCampaign(f)) return f;
        }
        return null;
    }

    private void setModEnabled(String name, boolean on) {
        java.util.Set<String> set = enabledMods();
        if (on) set.add(name);
        else set.remove(name);
        getSharedPreferences("ajustes", MODE_MULTI_PROCESS).edit().putStringSet("mods_activos", set).apply();
    }

    /**
     * Mods: dos cajas, Campañas (solo una activada a la vez) y Gráficos y sonido (varios).
     * Cada mod va en una u otra según su subcarpeta o, si no está en ninguna, según lo que trae.
     */
    private DukeMenuView.Page modsPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        GameData.makeCategoryDirs(gameDir);
        List<File> campaigns = new ArrayList<>(), others = new ArrayList<>();
        for (File f : GameData.mods(gameDir)) (GameData.isCampaign(f) ? campaigns : others).add(f);
        p.addGame(I18n.t("mods_campaigns"), box(R.drawable.caja_campanas),
                () -> menuView.push(modListPage(gameDir, campaigns, true)));
        p.addGame(I18n.t("mods_gfx"), box(R.drawable.caja_graficos),
                () -> menuView.push(modListPage(gameDir, others, false)));
        p.add(I18n.t("back"), null, () -> menuView.pop());
        // Al volver, el menú principal se rehace (con campaña solo se ve el juego base).
        p.onLeave = () -> menuView.post(() -> {
            DukeMenuView.Page root = rootPage(gameDir);
            root.selected = Math.max(0, root.entries.size() - 4); // sigue en "Mods"
            menuView.setRoot(root, this::finish);
        });
        File dir = GameData.findChild(gameDir, "mods");
        String path = (dir != null ? dir : new File(gameDir, "mods")).getAbsolutePath();
        p.footer = () -> p.selected == 0 ? countLabel(campaigns) + " - " + I18n.t("mods_one_campaign")
                : p.selected == 1 ? countLabel(others) : I18n.t("mods_where") + path;
        return p;
    }

    private String countLabel(List<File> mods) {
        int on = 0;
        java.util.Set<String> enabled = enabledMods();
        for (File f : mods) if (enabled.contains(f.getName())) on++;
        return mods.size() + " " + I18n.t("mods_found") + ", " + on + " " + I18n.t("mods_active");
    }

    private final java.util.Map<Integer, Bitmap> boxes = new java.util.HashMap<>();

    private Bitmap box(int res) {
        Bitmap b = boxes.get(res);
        if (b == null) {
            try {
                b = android.graphics.BitmapFactory.decodeResource(getResources(), res);
                boxes.put(res, b);
            } catch (Throwable ignored) {
            }
        }
        return b;
    }

    /** La lista de una de las dos secciones, con un interruptor por mod. */
    private DukeMenuView.Page modListPage(File gameDir, List<File> mods, boolean campaigns) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        for (File f : mods) {
            String name = f.getName();
            String label = name.replaceAll("(?i)\\.(zip|pk3|grp)$", "").replace('_', ' ');
            p.addValue(label, () -> I18n.t(enabledMods().contains(name) ? "yes" : "no"), () -> {
                boolean on = !enabledMods().contains(name);
                java.util.Set<String> set = enabledMods();
                if (on && campaigns) {
                    // Una campaña va sola: no es compatible con ningún otro mod.
                    set.clear();
                } else if (on) {
                    // Un mod de gráficos o sonido apaga la campaña que hubiera.
                    File camp = activeCampaign(gameDir);
                    if (camp != null) set.remove(camp.getName());
                }
                if (on) set.add(name);
                else set.remove(name);
                getSharedPreferences("ajustes", MODE_MULTI_PROCESS).edit().putStringSet("mods_activos", set).apply();
            });
            DukeMenuView.Entry e = p.entries.get(p.entries.size() - 1);
            e.active = () -> enabledMods().contains(name);
        }
        p.add(I18n.t("back"), null, () -> menuView.pop());
        File base = GameData.findChild(gameDir, "mods");
        File dir = new File(base != null ? base : new File(gameDir, "mods"),
                campaigns ? GameData.CAMPAIGN_DIR : GameData.GFX_DIR);
        p.footer = () -> mods.isEmpty()
                ? I18n.t("mods_empty") + dir.getAbsolutePath()
                : (p.selected < mods.size() ? mods.get(p.selected).getName() + " - " : "")
                + I18n.t(campaigns ? "mods_campaign_alone" : "mods_note");
        return p;
    }

    /** El próximo lanzamiento va solo: sin mods ni traducción (Penthouse Paradise trae sus .CON). */
    private boolean launchAlone;

    private void launch(File gameDir, GameData.Game game, String[] extraArgs) {
        showClassics = false;
        if (launching) return;
        launching = true;
        music.stop();
        boolean alone = launchAlone;
        launchAlone = false;
        boolean spanish = I18n.ES.equals(I18n.lang()) && !alone;
        // Mientras se preparan (la primera vez se descomprimen y tarda), se enseña el progreso.
        DukeMenuView.Page wait = new DukeMenuView.Page();
        if (!alone && !enabledMods().isEmpty()) {
            GameData.progress = "";
            GameData.progressValue = "";
            wait.addValue(I18n.t("mods_preparing"), () -> GameData.progressValue, null);
            wait.footer = () -> GameData.progress + " - " + I18n.t("mods_wait");
            menuView.push(wait);
        }
        // La traducción se prepara aparte (lee los .CON y hace las letras con tilde).
        new Thread(() -> {
            String def = null;
            if (spanish && !Motor.RAZE) {
                try {
                    List<GameData.Game> games = GameData.games(gameDir);
                    def = Translator.prepare(this, gameDir, games.get(0), game);
                } catch (Throwable e) {
                    android.util.Log.e("Duke3D", "No se pudo preparar la traducción", e);
                }
            }
            String defFile = def;
            List<String> modArgs;
            try {
                // Una campaña va sola, aunque hubiera otros mods activados de antes.
                File camp = activeCampaign(gameDir);
                java.util.Set<String> enabled = camp != null
                        ? java.util.Collections.singleton(camp.getName()) : enabledMods();
                modArgs = alone ? new ArrayList<>() : GameData.modArguments(gameDir, enabled);
            } catch (Throwable t) {
                android.util.Log.e("Duke3D", "No se pudieron preparar los mods", t);
                runOnUiThread(() -> {
                    launching = false;
                    if (menuView.top() == wait) menuView.pop();
                    android.widget.Toast.makeText(this, I18n.t("mods_error") + t, android.widget.Toast.LENGTH_LONG).show();
                });
                return;
            }
            // Fuera del hilo de la interfaz (lee archivos).
            List<String> args = new ArrayList<>();
            List<GameData.Game> all;
            try {
                args.addAll(Arrays.asList(GameData.arguments(this, gameDir, game, defFile)));
                args.addAll(Arrays.asList(extraArgs));
                args.addAll(modArgs);
                all = GameData.games(gameDir);
            } catch (Throwable t) {
                // Sin esto, launching se quedaba a true y ya no se podía lanzar nada.
                android.util.Log.e("Duke3D", "No se pudo preparar el juego", t);
                runOnUiThread(() -> {
                    launching = false;
                    if (menuView.top() == wait) menuView.pop();
                    android.widget.Toast.makeText(this, String.valueOf(t), android.widget.Toast.LENGTH_LONG).show();
                });
                return;
            }
            runOnUiThread(() -> {
                launching = false;
                if (menuView.top() == wait) menuView.pop();
                if (isFinishing() || isDestroyed()) return;
                getPreferences(MODE_PRIVATE).edit().putBoolean("jugando", true).apply();
                Intent intent = new Intent(this, GameActivity.class);
                intent.putExtra(GameActivity.EXTRA_DIR, gameDir.getAbsolutePath());
                intent.putExtra(GameActivity.EXTRA_ARGS, args.toArray(new String[0]));
                intent.putExtra(GameActivity.EXTRA_LANG, defFile != null || (Motor.RAZE && spanish) ? "es" : "en");
                if (!all.isEmpty()) intent.putExtra(GameActivity.EXTRA_BASE_GRP, all.get(0).grp.getAbsolutePath());
                startActivity(intent);
            });
        }, "Traduccion").start();
    }

    // ---- Registro ----

    /**
     * Guarda el registro del sistema de esta app (juego incluido) en /Duke3D/registro.txt y
     * devuelve las líneas del juego y de los errores, para enseñar las últimas en pantalla.
     */
    private List<String> saveLog() {
        List<String> important = new ArrayList<>();
        File out = new File(baseDir(), "registro.txt");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-b", "main,system,crash"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 FileWriter w = new FileWriter(out)) {
                String line;
                while ((line = r.readLine()) != null) {
                    w.write(line);
                    w.write('\n');
                    if (line.contains("EDuke32") || line.contains("Duke3D") || line.contains("Raze") || line.contains("SDL")
                            || line.contains("AndroidRuntime") || line.contains("DEBUG")
                            || line.contains(" F/") || line.contains(" E/")) {
                        important.add(line);
                    }
                }
            }
        } catch (Exception e) {
            important.add("No se pudo leer el registro: " + e);
        }
        return important;
    }

    /** Si el juego se ha roto, las líneas del fallo (función y cadena de llamadas). */
    private void showCrash() {
        if (lastLog == null) return;
        StringBuilder sb = new StringBuilder();
        for (String line : lastLog) {
            if (line.contains("F/Duke3D")) {
                int i = line.indexOf("): ");
                sb.append(i >= 0 ? line.substring(i + 3) : line).append('\n');
            }
        }
        if (sb.length() == 0) return;
        addButton("Continuar", v -> {
            lastLog = null;
            refresh();
        }).requestFocus();
        addButton("Salir", v -> finishAffinity());
        addSpace(12);
        addText("El juego se ha cerrado por un fallo (registro.txt guardado):", 15, 0xFFFF5040);
        TextView t = addText(sb.toString(), 11, Color.WHITE);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.START);
        t.setTextIsSelectable(true);
    }

    private void showLastLog() {
        if (lastLog == null || lastLog.isEmpty()) return;
        addSpace(16);
        addText("Registro de la última partida (completo en " + new File(baseDir(), "registro.txt") + "):", 13, COLOR_ACCENT);
        StringBuilder sb = new StringBuilder();
        for (String line : lastLog.subList(Math.max(0, lastLog.size() - 40), lastLog.size())) {
            sb.append(line).append('\n');
        }
        TextView t = addText(sb.toString(), 10, Color.LTGRAY);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.START);
        t.setTextIsSelectable(true);
    }

    // ---- Permiso de archivos ----

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ---- Vistas ----

    /** Portada arriba del menú, a lo ancho y sin pasarse de 600 dp. */
    private void addBanner() {
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.portada);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setContentDescription("Duke Nukem 3D: Megaton Edition");
        int w = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(600));
        list.addView(img, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView addText(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private Button addButton(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(onClick);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        list.addView(b, lp);
        return b;
    }

    private void addSpace(int dp) {
        View v = new View(this);
        list.addView(v, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
