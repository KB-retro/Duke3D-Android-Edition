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

    /** Empieza (o sigue) con la música de esta carpeta. */
    void play(Context c, File gameDir, GrpArchive base) {
        File src = find(gameDir);
        if (src == null) src = extract(c, base);
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
            p.setLooping(true);
            p.prepare();
            p.start();
            player = p;
            playing = src.getAbsolutePath();
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
    }

    /** GRABBAG.ogg (u otro formato) suelto en la carpeta del juego o en music/, sin mirar mayúsculas. */
    private static File find(File gameDir) {
        File hit = findIn(gameDir);
        if (hit != null) return hit;
        File music = GameData.findChild(gameDir, "music");
        if (music != null && music.isDirectory()) {
            hit = findIn(music);
            if (hit != null) return hit;
            File[] subs = music.listFiles();
            if (subs != null) {
                for (File d : subs) {
                    if (d.isDirectory() && (hit = findIn(d)) != null) return hit;
                }
            }
        }
        return null;
    }

    private static File findIn(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toUpperCase(Locale.ROOT);
            if (!f.isFile() || !n.startsWith(NAME + ".")) continue;
            for (String ext : EXTS) if (n.endsWith(ext.toUpperCase(Locale.ROOT))) return f;
        }
        return null;
    }

    /** El MIDI del GRP, copiado a la caché de la app (Android no lo puede leer de dentro del GRP). */
    private static File extract(Context c, GrpArchive base) {
        if (base == null || !base.has(NAME + ".MID")) return null;
        File out = new File(c.getCacheDir(), "grabbag.mid");
        try {
            byte[] data = base.read(NAME + ".MID");
            if (!out.isFile() || out.length() != data.length) {
                try (FileOutputStream o = new FileOutputStream(out)) {
                    o.write(data);
                }
            }
            return out;
        } catch (Exception e) {
            Log.w("Duke3D", "No se pudo sacar " + NAME + ".MID del GRP", e);
            return null;
        }
    }
}
