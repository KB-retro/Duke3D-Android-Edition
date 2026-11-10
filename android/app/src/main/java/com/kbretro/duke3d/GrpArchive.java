package com.kbretro.duke3d;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lee archivos de un .GRP del motor BUILD (formato "KenSilverman"): una cabecera, un índice de
 * nombres y tamaños, y los archivos uno detrás de otro. De aquí salen los gráficos, la paleta y
 * la música que usa el menú, siempre de la copia del juego del usuario.
 */
final class GrpArchive implements Closeable {
    final File file;
    private final RandomAccessFile raf;
    /** Nombre en mayúsculas → { posición, tamaño }. */
    private final Map<String, long[]> entries = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    GrpArchive(File file) throws IOException {
        this.file = file;
        raf = new RandomAccessFile(file, "r");
        byte[] head = new byte[16];
        raf.readFully(head);
        if (!"KenSilverman".equals(new String(head, 0, 12, StandardCharsets.US_ASCII))) {
            raf.close();
            throw new IOException("No es un GRP: " + file);
        }
        int count = le32(head, 12);
        if (count < 0 || count > 100000) {
            raf.close();
            throw new IOException("GRP dañado: " + file);
        }
        byte[] dir = new byte[count * 16];
        raf.readFully(dir);
        long offset = 16L + count * 16L;
        for (int i = 0; i < count; i++) {
            int p = i * 16;
            int len = 0;
            while (len < 12 && dir[p + len] != 0) len++;
            String name = new String(dir, p, len, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.ROOT);
            long size = le32(dir, p + 12) & 0xFFFFFFFFL;
            entries.put(name, new long[]{offset, size});
            names.add(name);
            offset += size;
        }
    }

    boolean has(String name) {
        return entries.containsKey(name.toUpperCase(Locale.ROOT));
    }

    long size(String name) {
        long[] e = entries.get(name.toUpperCase(Locale.ROOT));
        return e != null ? e[1] : -1;
    }

    /** Nombres de los archivos que hay dentro, en mayúsculas y en orden. */
    List<String> names() {
        return names;
    }

    byte[] read(String name) throws IOException {
        long[] e = entries.get(name.toUpperCase(Locale.ROOT));
        if (e == null) throw new IOException("No está en el GRP: " + name);
        return read(name, 0, (int) e[1]);
    }

    /** Lee un trozo de un archivo del GRP. */
    synchronized byte[] read(String name, long pos, int len) throws IOException {
        long[] e = entries.get(name.toUpperCase(Locale.ROOT));
        if (e == null) throw new IOException("No está en el GRP: " + name);
        if (pos < 0 || pos + len > e[1]) throw new IOException("Fuera de " + name);
        byte[] b = new byte[len];
        raf.seek(e[0] + pos);
        raf.readFully(b);
        return b;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
    }

    static int le16(byte[] b, int p) {
        return (short) ((b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8);
    }
}
