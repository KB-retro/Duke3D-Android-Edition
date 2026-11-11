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
            copyAssets(c, "raze", dir);
            try (OutputStream out = new FileOutputStream(stamp)) {
                out.write(version.getBytes());
            }
            Log.i("Duke3D", "Datos de Raze copiados en " + dir);
        } catch (Exception e) {
            Log.e("Duke3D", "No se pudieron copiar los datos de Raze", e);
        }
        return dir;
    }

    /** Copia una carpeta de los assets (con sus subcarpetas, p. ej. soundfonts/). */
    private static void copyAssets(Context c, String path, File to) throws java.io.IOException {
        String[] files = c.getAssets().list(path);
        if (files == null) return;
        to.mkdirs();
        for (String f : files) {
            String sub = path + "/" + f;
            String[] inner = c.getAssets().list(sub);
            if (inner != null && inner.length > 0) {
                copyAssets(c, sub, new File(to, f));
                continue;
            }
            try (InputStream in = c.getAssets().open(sub);
                 OutputStream out = new FileOutputStream(new File(to, f))) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    // ---- Driver Vulkan propio (Opciones → Gráficos) ----
    // Se copia a la carpeta privada de la app (libadrenotools no puede cargarlo desde la
    // memoria compartida) y se usa al empezar el juego.

    static File driverDir(Context c) {
        return new File(c.getFilesDir(), "drivers");
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_MULTI_PROCESS);
    }

    /** Nombre del .so del driver cargado, o null si se usa el del sistema. */
    static String vkDriver(Context c) {
        String n = prefs(c).getString("vk_driver", null);
        return n != null && new File(driverDir(c), n).isFile() ? n : null;
    }

    static void clearVkDriver(Context c) {
        prefs(c).edit().remove("vk_driver").apply();
    }

    /**
     * Copia el driver elegido: un .so o un .zip de driver (pack de adrenotools, con meta.json).
     * @return el nombre del .so instalado
     */
    static String installVkDriver(Context c, android.net.Uri uri) throws java.io.IOException {
        File dir = driverDir(c);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        dir.mkdirs();
        File tmp = new File(dir, "descarga.tmp");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new java.io.IOException("no se puede leer");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        String soName = null;
        byte[] head = new byte[4];
        try (InputStream in = new java.io.FileInputStream(tmp)) {
            if (in.read(head) != 4) throw new java.io.IOException("archivo vacío");
        }
        if (head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
            String name = displayName(c, uri);
            soName = name != null && name.endsWith(".so") ? name : "libvulkan_custom.so";
            if (!tmp.renameTo(new File(dir, soName))) throw new java.io.IOException("no se pudo guardar");
        } else if (head[0] == 'P' && head[1] == 'K') {
            String meta = null;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(tmp)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = e.nextElement();
                    String name = new File(entry.getName()).getName();
                    if (entry.isDirectory() || name.isEmpty()) continue;
                    if (name.equals("meta.json")) {
                        meta = new String(readAll(z.getInputStream(entry)), "UTF-8");
                        continue;
                    }
                    if (!name.endsWith(".so")) continue;
                    try (InputStream in = z.getInputStream(entry);
                         OutputStream out = new FileOutputStream(new File(dir, name))) {
                        out.write(readAll(in));
                    }
                    if (soName == null || name.startsWith("libvulkan")) soName = name;
                }
            }
            if (meta != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"libraryName\"\\s*:\\s*\"([^\"]+)\"").matcher(meta);
                if (m.find() && new File(dir, m.group(1)).isFile()) soName = m.group(1);
            }
            tmp.delete();
            if (soName == null) throw new java.io.IOException("el zip no trae ningún .so");
        } else {
            tmp.delete();
            throw new java.io.IOException("no es un .so ni un .zip");
        }
        prefs(c).edit().putString("vk_driver", soName).apply();
        Log.i("Duke3D", "Driver Vulkan instalado: " + soName);
        return soName;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static String displayName(Context c, android.net.Uri uri) {
        try (android.database.Cursor cur = c.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    private Motor() {
    }
}
