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
        if (getPreferences(MODE_PRIVATE).getBoolean("jugando", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("jugando", false).apply();
            lastLog = saveLog();
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
        for (GameData.Game g : assets.games) {
            Bitmap cover = cover(g);
            if (cover != null) root.addGame(g.menuLabel(), cover, () -> launch(gameDir, g));
            else root.add(g.menuLabel(), assets.icons.get(g.title), () -> launch(gameDir, g));
        }
        root.add(I18n.t("multiplayer"), null, () -> menuView.push(netPage(gameDir)));
        root.add(I18n.t("mods"), null, () -> menuView.push(modsPage(gameDir)));
        root.add(I18n.t("options"), null, () -> menuView.push(optionsPage(gameDir)));
        root.add(I18n.t("extras"), null, () -> menuView.push(extrasPage(gameDir)));
        root.add(I18n.t("quit"), null, this::finishAffinity);
        return root;
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
        p.add(I18n.t("music"), null, () -> menuView.push(musicPage(gameDir)));
        p.add(I18n.t("achievements"), null, () -> menuView.push(achievementsPage()));
        p.add(I18n.t("back"), null, () -> menuView.pop());
        return p;
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
        p.add(I18n.t("graphics"), null, () -> {
            DukeMenuView.Page g = new DukeMenuView.Page();
            GraphicsSettings.addTo(this, g, null, null);
            g.add(I18n.t("back"), null, () -> menuView.pop());
            g.footer = () -> I18n.t("gfx_soft_note");
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
        if (!error) addBanner();
        addSpace(12);
        showCrash();

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

    private void setModEnabled(String name, boolean on) {
        java.util.Set<String> set = enabledMods();
        if (on) set.add(name);
        else set.remove(name);
        getSharedPreferences("ajustes", MODE_MULTI_PROCESS).edit().putStringSet("mods_activos", set).apply();
    }

    /** Lista de los mods de la carpeta "mods" con un interruptor para cada uno. */
    private DukeMenuView.Page modsPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        List<File> mods = GameData.mods(gameDir);
        for (File f : mods) {
            String name = f.getName();
            String label = name.replaceAll("(?i)\\.(zip|pk3|grp)$", "").replace('_', ' ');
            p.addValue(label, () -> I18n.t(enabledMods().contains(name) ? "yes" : "no"),
                    () -> setModEnabled(name, !enabledMods().contains(name)));
            DukeMenuView.Entry e = p.entries.get(p.entries.size() - 1);
            e.active = () -> enabledMods().contains(name);
        }
        p.add(I18n.t("back"), null, () -> menuView.pop());
        File dir = new File(gameDir, "mods");
        if (!dir.exists()) dir.mkdirs();
        p.footer = () -> mods.isEmpty()
                ? I18n.t("mods_empty") + dir.getAbsolutePath()
                : (p.selected < mods.size() ? mods.get(p.selected).getName() + " - " : "") + I18n.t("mods_note");
        return p;
    }

    // ---- Multijugador (red local o internet, por IP; sin servidores de Steam) ----

    static final int NET_PORT = 23513;

    private SharedPreferences netPrefs() {
        return getSharedPreferences("ajustes", MODE_PRIVATE);
    }

    private String playerName() {
        return netPrefs().getString("nombre_red", "Duke");
    }

    private GameData.Game netGame() {
        int i = netPrefs().getInt("juego_red", 0);
        if (assets.games.isEmpty()) return null;
        return assets.games.get(Math.max(0, Math.min(i, assets.games.size() - 1)));
    }

    private DukeMenuView.Page netPage(File gameDir) {
        DukeMenuView.Page p = new DukeMenuView.Page();
        p.addValue(I18n.t("net_game"), () -> {
            GameData.Game g = netGame();
            return g != null ? g.menuLabel() : "-";
        }, () -> {
            int n = assets.games.size();
            if (n == 0) return;
            netPrefs().edit().putInt("juego_red", (netPrefs().getInt("juego_red", 0) + 1) % n).apply();
        });
        p.addValue(I18n.t("net_name"), this::playerName, () ->
                askText(I18n.t("net_name"), playerName(), android.text.InputType.TYPE_CLASS_TEXT, name -> {
                    name = name.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
                    if (name.length() > 16) name = name.substring(0, 16);
                    if (!name.isEmpty()) netPrefs().edit().putString("nombre_red", name).apply();
                }));
        p.add(I18n.t("net_host"), null, () -> {
            GameData.Game g = netGame();
            if (g != null) launch(gameDir, g, new String[]{"-server", "-name", playerName()});
        });
        p.add(I18n.t("net_join"), null, () ->
                askText(I18n.t("net_join_ip"), netPrefs().getString("ip_red", ""),
                        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, ip -> {
                            ip = ip.trim();
                            if (ip.isEmpty()) return;
                            netPrefs().edit().putString("ip_red", ip).apply();
                            GameData.Game g = netGame();
                            if (g != null) launch(gameDir, g, new String[]{"-connect", ip, "-name", playerName()});
                        }));
        p.add(I18n.t("net_internet"), null, () -> checkInternet(true));
        p.add(I18n.t("back"), null, () -> menuView.pop());
        p.footer = () -> {
            String lan = localIp(false), vpn = localIp(true);
            StringBuilder sb = new StringBuilder(I18n.t("net_lan")).append(lan != null ? lan : "-");
            if (vpn != null) sb.append("  VPN: ").append(vpn);
            String inet = internetStatus;
            if (inet != null) sb.append("  ").append(inet);
            return sb.toString();
        };
        checkInternet(false);
        return p;
    }

    /** Lo que dice la comprobación de internet (UPnP) para el pie de Multijugador. */
    private volatile String internetStatus;
    private volatile boolean checkingInternet;

    /** Abre el puerto en el router (UPnP) y averigua la IP pública, en segundo plano. */
    private void checkInternet(boolean again) {
        if (checkingInternet || (!again && internetStatus != null)) return;
        String lan = localIp(false);
        if (lan == null) {
            internetStatus = I18n.t("net_no_wifi");
            return;
        }
        checkingInternet = true;
        internetStatus = I18n.t("net_checking");
        new Thread(() -> {
            Upnp.Result r = Upnp.openPort(lan, NET_PORT);
            String s;
            if (r.publicIp == null) s = I18n.t("net_no_upnp");
            else if (r.cgnat) s = I18n.t("net_cgnat");
            else if (r.opened) s = I18n.t("net_public") + r.publicIp;
            else s = I18n.t("net_public") + r.publicIp + " " + I18n.t("net_closed");
            internetStatus = s;
            checkingInternet = false;
        }, "UPnP").start();
    }

    /** La IP de este aparato en la red (wifi), para que los demás se unan. */
    /**
     * La IP de este aparato: la de la red local (wifi/ethernet) o, con vpn = true, la de una red
     * privada virtual (Tailscale, ZeroTier...), que sirve para jugar por internet sin abrir puertos.
     */
    private static String localIp(boolean vpn) {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(java.util.Locale.ROOT);
                boolean isVpn = n.startsWith("tun") || n.startsWith("zt") || n.startsWith("tailscale") || n.startsWith("ppp");
                if (isVpn != vpn) continue;
                if (!vpn && n.startsWith("rmnet")) continue; // datos móviles: no sirven para alojar
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Cuadro para escribir un texto (nombre, IP). */
    private void askText(String title, String value, int inputType, java.util.function.Consumer<String> done) {
        android.widget.EditText edit = new android.widget.EditText(this);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        edit.setText(value);
        edit.setSelection(edit.getText().length());
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton(android.R.string.ok, (d, w) -> done.accept(edit.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> hideSystemBars())
                .show();
        edit.requestFocus();
    }

    private void launch(File gameDir, GameData.Game game, String[] extraArgs) {
        if (launching) return;
        launching = true;
        music.stop();
        boolean spanish = I18n.ES.equals(I18n.lang());
        // La traducción se prepara aparte (lee los .CON y hace las letras con tilde).
        new Thread(() -> {
            String def = null;
            if (spanish) {
                try {
                    List<GameData.Game> games = GameData.games(gameDir);
                    def = Translator.prepare(this, gameDir, games.get(0), game);
                } catch (Exception e) {
                    android.util.Log.e("Duke3D", "No se pudo preparar la traducción", e);
                }
            }
            String defFile = def;
            List<String> modArgs = GameData.modArguments(gameDir, enabledMods());
            runOnUiThread(() -> {
                launching = false;
                if (isFinishing() || isDestroyed()) return;
                getPreferences(MODE_PRIVATE).edit().putBoolean("jugando", true).apply();
                Intent intent = new Intent(this, GameActivity.class);
                intent.putExtra(GameActivity.EXTRA_DIR, gameDir.getAbsolutePath());
                List<String> args = new ArrayList<>(Arrays.asList(GameData.arguments(gameDir, game, defFile)));
                args.addAll(Arrays.asList(extraArgs));
                args.addAll(modArgs);
                intent.putExtra(GameActivity.EXTRA_ARGS, args.toArray(new String[0]));
                intent.putExtra(GameActivity.EXTRA_LANG, defFile != null ? "es" : "en");
                List<GameData.Game> all = GameData.games(gameDir);
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
                    if (line.contains("EDuke32") || line.contains("Duke3D") || line.contains("SDL")
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
        addText("El juego se ha cerrado por un fallo:", 15, 0xFFFF5040);
        TextView t = addText(sb.toString(), 11, Color.WHITE);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.START);
        t.setTextIsSelectable(true);
        addButton("Continuar", v -> {
            lastLog = null;
            refresh();
        }).requestFocus();
        addSpace(12);
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
