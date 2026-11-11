package com.kbretro.duke3d;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lo que cambia entre los dos APK: EDuke32 (software) y Raze (OpenGL ES). El lanzador, los
 * controles y el menú rápido son los mismos; Raze acepta casi las mismas opciones y su puente
 * nativo (raze/android/android_raze.cpp) traduce las órdenes de EDuke32.
 */
final class Motor {
    /** ¿Es el APK de Raze? */
    static final boolean RAZE = "raze".equals(BuildConfig.FLAVOR);

    /**
     * Raze necesita sus datos (raze.pk3 y el soundfont) en una carpeta: se copian de los assets
     * del APK la primera vez y tras cada actualización. Devuelve esa carpeta.
     */
    static File prepareRazeData(Context c) {
        File dir = new File(c.getFilesDir(), "raze");
        File stamp = new File(dir, ".version");
        String version = String.valueOf(BuildConfig.VERSION_CODE);
        try {
            if (stamp.exists() && version.equals(new String(java.nio.file.Files.readAllBytes(stamp.toPath())))) {
                return dir;
            }
            dir.mkdirs();
            String[] files = c.getAssets().list("raze");
            if (files != null) {
                for (String f : files) {
                    try (InputStream in = c.getAssets().open("raze/" + f);
                         OutputStream out = new FileOutputStream(new File(dir, f))) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
            try (OutputStream out = new FileOutputStream(stamp)) {
                out.write(version.getBytes());
            }
            Log.i("Duke3D", "Datos de Raze copiados en " + dir);
        } catch (Exception e) {
            Log.e("Duke3D", "No se pudieron copiar los datos de Raze", e);
        }
        return dir;
    }

    private Motor() {
    }
}
