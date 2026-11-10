package com.kbretro.duke3d;

import android.graphics.Bitmap;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gráficos del juego (archivos TILESnnn.ART de los GRP) convertidos a Bitmap con la paleta del
 * juego (PALETTE.DAT). Si varios GRP traen la misma imagen, gana el último de la lista: así una
 * expansión puede cambiar, por ejemplo, su pantalla de carga.
 */
final class DukeArt {
    // Números de las imágenes del juego (source/duke3d/src/names.h).
    static final int APLAYER = 1405;
    static final int MENUSCREEN = 2456;
    static final int BETASCREEN = 2493;
    static final int DUKENUKEM = 2497;
    static final int THREEDEE = 2498;
    static final int INGAMEDUKETHREEDEE = 2499;
    static final int PLUTOPAKSPRITE = 2501;
    static final int SPINNINGNUKEICON = 2813;
    static final int STARTALPHANUM = 2822;
    static final int BIGALPHANUM = 2940;
    static final int BIGPERIOD = 3002;
    static final int BIGCOMMA = 3003;
    static final int BIGX_ = 3004;
    static final int BIGQ = 3005;
    static final int BIGSEMI = 3006;
    static final int BIGCOLIN = 3007;
    static final int BIGAPPOS = 3022;
    static final int MINIFONT = 3072;
    static final int LOADSCREEN = 3281;

    private static final class TileRef {
        GrpArchive grp;
        String file;
        long offset;
        int w, h, xoff, yoff;
    }

    private final int[] palette = new int[256];
    private final Map<Integer, TileRef> tiles = new HashMap<>();
    private final Map<Integer, Bitmap> cache = new HashMap<>();

    /** @param grps de menos a más prioridad (el juego base primero, la expansión después). */
    DukeArt(List<GrpArchive> grps) throws IOException {
        boolean havePalette = false;
        for (GrpArchive grp : grps) {
            if (grp.has("PALETTE.DAT")) {
                byte[] pal = grp.read("PALETTE.DAT", 0, 768);
                for (int i = 0; i < 256; i++) {
                    int r = (pal[i * 3] & 63) * 255 / 63;
                    int g = (pal[i * 3 + 1] & 63) * 255 / 63;
                    int b = (pal[i * 3 + 2] & 63) * 255 / 63;
                    palette[i] = 0xFF000000 | r << 16 | g << 8 | b;
                }
                havePalette = true;
            }
            for (int n = 0; n < 64; n++) {
                String name = String.format(Locale.ROOT, "TILES%03d.ART", n);
                if (grp.has(name)) indexArt(grp, name);
            }
        }
        if (!havePalette) throw new IOException("No hay PALETTE.DAT");
        if (tiles.isEmpty()) throw new IOException("No hay gráficos (TILES*.ART)");
        palette[255] = 0; // transparente
    }

    private void indexArt(GrpArchive grp, String name) throws IOException {
        byte[] head = grp.read(name, 0, 16);
        int start = GrpArchive.le32(head, 8);
        int end = GrpArchive.le32(head, 12);
        int n = end - start + 1;
        if (n <= 0 || n > 30000) return;
        byte[] info = grp.read(name, 16, n * 8);
        long offset = 16L + n * 8L;
        for (int i = 0; i < n; i++) {
            int w = GrpArchive.le16(info, i * 2) & 0xFFFF;
            int h = GrpArchive.le16(info, n * 2 + i * 2) & 0xFFFF;
            int picanm = GrpArchive.le32(info, n * 4 + i * 4);
            if (w > 0 && h > 0) {
                TileRef t = new TileRef();
                t.grp = grp;
                t.file = name;
                t.offset = offset;
                t.w = w;
                t.h = h;
                t.xoff = (byte) ((picanm >> 8) & 0xFF);
                t.yoff = (byte) ((picanm >> 16) & 0xFF);
                tiles.put(start + i, t);
            }
            offset += (long) w * h;
        }
    }

    boolean has(int tile) {
        return tiles.containsKey(tile);
    }

    int width(int tile) {
        TileRef t = tiles.get(tile);
        return t != null ? t.w : 0;
    }

    int height(int tile) {
        TileRef t = tiles.get(tile);
        return t != null ? t.h : 0;
    }

    int xoff(int tile) {
        TileRef t = tiles.get(tile);
        return t != null ? t.xoff : 0;
    }

    int yoff(int tile) {
        TileRef t = tiles.get(tile);
        return t != null ? t.yoff : 0;
    }

    /** El gráfico como Bitmap (con transparencia), o null si no existe. */
    synchronized Bitmap tile(int tile) {
        Bitmap b = cache.get(tile);
        if (b != null || cache.containsKey(tile)) return b;
        TileRef t = tiles.get(tile);
        if (t != null) {
            try {
                byte[] data = t.grp.read(t.file, t.offset, t.w * t.h);
                int[] px = new int[t.w * t.h];
                // En los ART los píxeles van por columnas.
                for (int x = 0; x < t.w; x++) {
                    int col = x * t.h;
                    for (int y = 0; y < t.h; y++) px[y * t.w + x] = palette[data[col + y] & 0xFF];
                }
                b = Bitmap.createBitmap(px, t.w, t.h, Bitmap.Config.ARGB_8888);
            } catch (IOException | RuntimeException e) {
                b = null;
            }
        }
        cache.put(tile, b);
        return b;
    }
}
