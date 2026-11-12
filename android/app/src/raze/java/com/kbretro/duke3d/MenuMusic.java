package com.kbretro.duke3d;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Música del menú: el tema principal de Duke Nukem 3D (GRABBAG). Primero busca una versión
 * suelta (OGG, MP3, FLAC o WAV) en la carpeta del juego o en su carpeta "music"; si no hay,
 * saca el MIDI original del DUKE3D.GRP del usuario y lo toca Android.
 */
final class MenuMusic {
    private static final String NAME = "GRABBAG";
    private static final String[] EXTS = {".ogg", ".mp3", ".flac", ".wav"};

    private MediaPlayer player;
    private String playing;

    /** Empieza (o sigue) con el tema del menú. */
    void play(Context c, File gameDir, GrpArchive base) {
        playFile(c, gameDir, base, NAME + ".MID", true);
    }

    /** Lo que suena ahora (nombre del archivo en el GRP), o null. */
    String current() {
        return player != null ? currentName : null;
    }

    private String currentName;

    /**
     * Toca una pista: la versión suelta si la hay (OGG, MP3...), si no la del GRP.
     * @param file nombre en el GRP, p. ej. STALKER.MID
     */
    void playFile(Context c, File gameDir, GrpArchive grp, String file, boolean loop) {
        String base = file.replaceAll("\\..*$", "").toUpperCase(Locale.ROOT);
        File src = find(gameDir, base);
        if (src == null) src = extract(c, grp, file);
        if (src == null) return;
        if (player != null && src.getAbsolutePath().equals(playing)) {
            if (!player.isPlaying()) player.start();
            return;
        }
        stop();
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(src.getAbsolutePath());
            p.setLooping(loop);
            p.prepare();
            p.start();
            player = p;
            playing = src.getAbsolutePath();
            currentName = file.toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            Log.w("Duke3D", "No se pudo tocar la música del menú: " + src, e);
            stop();
        }
    }

    void pause() {
        if (player != null && player.isPlaying()) player.pause();
    }

    void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
        }
        player = null;
        playing = null;
        currentName = null;
    }

    /** GRABBAG.ogg (u otro formato) suelto en la carpeta del juego o en music/, sin mirar mayúsculas. */
    private static File find(File gameDir, String name) {
        File hit = findIn(gameDir, name);
        if (hit != null) return hit;
        File music = GameData.findChild(gameDir, "music");
        if (music != null && music.isDirectory()) {
            hit = findIn(music, name);
            if (hit != null) return hit;
            File[] subs = music.listFiles();
            if (subs != null) {
                for (File d : subs) {
                    if (d.isDirectory() && (hit = findIn(d, name)) != null) return hit;
                }
            }
        }
        return null;
    }

    private static File findIn(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toUpperCase(Locale.ROOT);
            if (!f.isFile() || !n.startsWith(name + ".")) continue;
            for (String ext : EXTS) if (n.endsWith(ext.toUpperCase(Locale.ROOT))) return f;
        }
        return null;
    }

    /** La pista del GRP, copiada a la caché de la app (Android no la puede leer de dentro del GRP). */
    private static File extract(Context c, GrpArchive grp, String file) {
        if (grp == null || !grp.has(file)) return null;
        String ext = file.contains(".") ? file.substring(file.lastIndexOf('.')).toLowerCase(Locale.ROOT) : ".mid";
        File out = new File(c.getCacheDir(), "musica_" + file.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "_"));
        if (!out.getName().endsWith(ext)) out = new File(out.getPath() + ext);
        try {
            byte[] data = grp.read(file);
            if (!out.isFile() || out.length() != data.length) {
                try (FileOutputStream o = new FileOutputStream(out)) {
                    o.write(data);
                }
            }
            return out;
        } catch (Exception e) {
            Log.w("Duke3D", "No se pudo sacar " + file + " del GRP", e);
            return null;
        }
    }
}
