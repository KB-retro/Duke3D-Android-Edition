package com.kbretro.duke3d;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** Menú inicial: pide permiso de archivos, busca los datos de Steam y lanza el juego elegido. */
public class LauncherActivity extends Activity {
    private static final int REQUEST_STORAGE = 1;
    private static final int COLOR_BG = 0xFF101010;
    private static final int COLOR_ACCENT = 0xFFFFC21A;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        addTitle("DUKE NUKEM 3D");
        addText("Android Edition", 16, COLOR_ACCENT);
        addSpace(16);

        if (!hasStoragePermission()) {
            addText("La app necesita permiso para leer los archivos del juego en la memoria.", 16, Color.WHITE);
            addButton("Dar permiso de archivos", v -> requestStoragePermission()).requestFocus();
            return;
        }

        File gameDir = GameData.findGameDir();
        if (gameDir == null) {
            File base = GameData.baseDir();
            if (!base.exists()) base.mkdirs();
            addText("No se encuentra DUKE3D.GRP.", 18, COLOR_ACCENT);
            addSpace(8);
            addText("Copia la carpeta \"gameroot\" de Duke Nukem 3D: Megaton Edition (Steam) a:\n\n"
                    + base.getAbsolutePath() + "/gameroot\n\n"
                    + "En Steam: clic derecho en el juego → Administrar → Ver archivos locales.\n"
                    + "Las expansiones (Duke It Out in D.C., Nuclear Winter y Caribbean) están dentro, "
                    + "en gameroot/addons.", 15, Color.WHITE);
            addSpace(16);
            addButton("Volver a buscar", v -> refresh()).requestFocus();
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
        addText("Atrás o Start: menú del juego", 13, Color.GRAY);
    }

    private void launch(File gameDir, GameData.Game game) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_DIR, gameDir.getAbsolutePath());
        intent.putExtra(GameActivity.EXTRA_ARGS, GameData.arguments(gameDir, game));
        startActivity(intent);
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

    private void addTitle(String text) {
        TextView t = addText(text, 34, COLOR_ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
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
