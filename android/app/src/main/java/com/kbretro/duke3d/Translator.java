package com.kbretro.duke3d;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepara el juego en castellano: escribe duke3d_android/es.def en la carpeta del juego con
 *
 * - las traducciones (bloque "localization" de EDuke32): las del motor tal cual y las de los
 *   .CON del usuario (mensajes, niveles, dificultades) emparejadas con el diccionario;
 * - las letras con tilde, la ñ, ¿ y ¡ para las tres fuentes del juego, hechas a partir de sus
 *   propias letras (no se mete ningún gráfico del juego en el APK).
 *
 * El motor aplica el idioma de la variable DUKE3D_LANG. Lo que se pinte sin traducir se apunta
 * en duke3d_android/sin_traducir.txt, y los textos de los .CON que no están en el diccionario,
 * en duke3d_android/textos_sin_traducir_con.txt.
 */
final class Translator {
    static final String DIR = "duke3d_android";
    static final String DEF = DIR + "/es.def";

    private static final Locale ES = new Locale("es", "ES");
    private static final int FIRST_TILE = 29600;

    private static Map<String, String> dictionary;

    /** Diccionario del APK: clave en inglés tal cual → castellano. */
    private static synchronized Map<String, String> dictionary(Context c) throws IOException {
        if (dictionary != null) return dictionary;
        Map<String, String> d = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("traduccion_es.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf(" = ");
                if (eq <= 0) continue;
                d.put(line.substring(0, eq), line.substring(eq + 3));
            }
        }
        dictionary = d;
        return d;
    }

    /** Clave para comparar textos de los .CON: mayúsculas, sin espacios de más ni signos finales. */
    static String norm(String s) {
        String n = s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        while (!n.isEmpty() && "!.?:".indexOf(n.charAt(n.length() - 1)) >= 0) n = n.substring(0, n.length() - 1).trim();
        return n;
    }

    /**
     * Escribe es.def para lanzar el juego (el base o una expansión). Devuelve la ruta relativa
     * a la carpeta del juego para "-mh".
     */
    static String prepare(Context c, File gameDir, GameData.Game base, GameData.Game game) throws IOException {
        File dir = new File(gameDir, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No se pudo crear " + dir);

        Map<String, String> dict = dictionary(c);
        Map<String, String> byNorm = new HashMap<>();
        for (Map.Entry<String, String> e : dict.entrySet()) byNorm.putIfAbsent(norm(e.getKey()), e.getValue());

        // Textos de los .CON del juego base y de la expansión (y sueltos en la carpeta, si los hay).
        List<GrpArchive> grps = new ArrayList<>();
        try {
            grps.add(new GrpArchive(base.grp));
            if (game.grp != null && !game.grp.equals(base.grp)) grps.add(new GrpArchive(game.grp));

            Set<String> conTexts = new LinkedHashSet<>();
            for (GrpArchive g : grps) {
                for (String name : g.names()) {
                    if (name.endsWith(".CON")) collectConTexts(new String(g.read(name), StandardCharsets.ISO_8859_1), conTexts);
                }
            }
            for (File d : Arrays.asList(gameDir, game.grp != null ? game.grp.getParentFile() : null)) {
                File[] files = d != null ? d.listFiles() : null;
                if (files == null) continue;
                for (File f : files) {
                    if (f.isFile() && f.getName().toUpperCase(Locale.ROOT).endsWith(".CON") && f.length() < 2_000_000) {
                        collectConTexts(new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1), conTexts);
                    }
                }
            }

            // Traducciones: primero las del motor tal cual, luego las de los .CON emparejadas.
            Map<String, String> out = new LinkedHashMap<>(dict);
            List<String> missing = new ArrayList<>();
            for (String t : conTexts) {
                if (out.containsKey(t)) continue;
                String es = byNorm.get(norm(t));
                if (es == null) {
                    missing.add(t);
                    continue;
                }
                out.put(t, adaptCase(t, es));
            }
            writeLines(new File(dir, "textos_sin_traducir_con.txt"), missing);

            // Sin tildes: las fuentes del juego no las tienen y las letras fabricadas salían raras.
            StringBuilder def = new StringBuilder();
            def.append("// Generado por Duke3D Android Edition: castellano (sin tildes).\n\n");

            def.append("localization \"es\"\n{\n");
            for (Map.Entry<String, String> e : out.entrySet()) {
                String k = e.getKey(), v = plain(e.getValue());
                // Las claves son bytes del juego: con caracteres raros no coincidirían.
                if (!defSafe(k) || !defSafe(v) || !k.matches("[\\x20-\\x7E]*")) continue;
                def.append("    string \"").append(escape(k)).append("\" \"").append(escape(v)).append("\"\n");
            }
            def.append("}\n");

            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(gameDir, DEF)), StandardCharsets.UTF_8)) {
                w.write(def.toString());
            }
            return DEF;
        } finally {
            for (GrpArchive g : grps) {
                try {
                    g.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Un nombre del juego (p. ej. de un nivel) en el idioma del lanzador. */
    static String lookup(Context c, String text) {
        if (!I18n.ES.equals(I18n.lang())) return text;
        try {
            Map<String, String> dict = dictionary(c);
            String es = dict.get(text);
            if (es == null) {
                String n = norm(text);
                for (Map.Entry<String, String> e : dict.entrySet()) {
                    if (norm(e.getKey()).equals(n)) {
                        es = e.getValue();
                        break;
                    }
                }
            }
            return es != null ? adaptCase(text, es) : text;
        } catch (IOException e) {
            return text;
        }
    }

    // ---- Textos de los .CON ----

    private static final Pattern QUOTE = Pattern.compile("^\\s*(?:re)?definequote\\s+\\d+\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME = Pattern.compile("^\\s*definevolumename\\s+\\d+\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL = Pattern.compile("^\\s*defineskillname\\s+\\d+\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GAMETYPE = Pattern.compile("^\\s*definegametype\\s+\\d+\\s+\\d+\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL = Pattern.compile(
            "^\\s*definelevelname\\s+\\d+\\s+\\d+\\s+\\S+\\s+\\d+:\\d+(?:\\s+\\d+:\\d+)?\\s+(.*)$", Pattern.CASE_INSENSITIVE);

    /** El texto como lo guarda el motor: todo lo que queda hasta el final de la línea. */
    private static void collectConTexts(String con, Set<String> out) {
        for (String raw : con.split("\r\n|\r|\n")) {
            for (Pattern p : new Pattern[]{QUOTE, VOLUME, SKILL, GAMETYPE, LEVEL}) {
                Matcher m = p.matcher(raw);
                if (m.matches()) {
                    String t = m.group(1);
                    if (!t.trim().isEmpty() && t.matches(".*[A-Za-z]{2}.*")) out.add(t);
                    break;
                }
            }
        }
    }

    /** Si el original va en mayúsculas, la traducción también; y si acaba en "!", con ¡...! */
    private static String adaptCase(String original, String es) {
        String r = original.matches(".*[a-z].*") ? es : es.toUpperCase(ES);
        if (original.trim().endsWith("!") && !r.endsWith("!")) r = r + "!";
        return r;
    }

    /**
     * El texto con las letras que tiene el juego: sin tildes ni diéresis, ñ como n y sin ¿ ni ¡
     * (las fuentes originales no los tienen).
     */
    static String plain(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace("¿", "").replace("¡", "").replace('º', 'o').replace('ª', 'a');
    }

    private static boolean defSafe(String s) {
        // El lector de .def corta en "//" aunque esté entre comillas, y los textos con bytes
        // que no son UTF-8 no coincidirían.
        if (s.contains("//")) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 32) return false;
        return true;
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }

    private static void writeLines(File f, List<String> lines) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (String l : lines) {
                w.write(l);
                w.write('\n');
            }
        }
    }

    // ---- Letras con tilde ----

    private static final int ACUTE = 0, DIAERESIS = 1, TILDE = 2;

    /** Las tres fuentes del juego: letras rojas grandes, la de los mensajes y la diminuta. */
    private static final int[] FONTS = {DukeArt.BIGALPHANUM, DukeArt.STARTALPHANUM, DukeArt.MINIFONT};

    private static int baseTile(DukeArt art, int font, char c) {
        if (font == DukeArt.BIGALPHANUM) {
            char u = Character.toUpperCase(c);
            if (u >= 'A' && u <= 'Z') return DukeArt.BIGALPHANUM + (u - 'A');
            if (c == '?') return DukeArt.BIGQ;
            if (c == '!') return DukeArt.BIGX_;
            return -1;
        }
        if (c < '!' || c > '~') return -1;
        int t = font + (c - '!');
        if (!art.has(t) && Character.isLowerCase(c)) t = font + (Character.toUpperCase(c) - '!');
        return art.has(t) ? t : -1;
    }

    private static void writeGlyphs(DukeArt art, File dir, StringBuilder def) throws IOException {
        String accented = "ÁÉÍÓÚÜÑáéíóúüñ";
        String plain = "AEIOUUNaeiouun";
        int[] marks = {ACUTE, ACUTE, ACUTE, ACUTE, ACUTE, DIAERESIS, TILDE, ACUTE, ACUTE, ACUTE, ACUTE, ACUTE, DIAERESIS, TILDE};
        int next = FIRST_TILE;

        for (int font : FONTS) {
            if (!art.has(font) && !art.has(font + ('A' - '!'))) continue;
            Map<String, Integer> mapping = new LinkedHashMap<>();
            Map<Integer, Integer> made = new HashMap<>(); // tile base + marca → tile nueva

            for (int i = 0; i < accented.length(); i++) {
                int base = baseTile(art, font, plain.charAt(i));
                if (base < 0) continue;
                int key = base * 4 + marks[i];
                Integer tile = made.get(key);
                if (tile == null) {
                    Bitmap b = art.tile(base);
                    if (b == null) continue;
                    tile = next++;
                    savePng(accent(b, marks[i]), new File(dir, "g" + tile + ".png"));
                    def.append("tilefromtexture ").append(tile).append(" { file \"").append(DIR)
                            .append("/g").append(tile).append(".png\" }\n");
                    made.put(key, tile);
                }
                mapping.put(String.valueOf(accented.charAt(i)), tile);
            }
            // ¿ y ¡: ? y ! al revés.
            for (char[] pair : new char[][]{{'¿', '?'}, {'¡', '!'}}) {
                int base = baseTile(art, font, pair[1]);
                Bitmap b = base >= 0 ? art.tile(base) : null;
                if (b == null) continue;
                int tile = next++;
                savePng(rotate180(b), new File(dir, "g" + tile + ".png"));
                def.append("tilefromtexture ").append(tile).append(" { file \"").append(DIR)
                        .append("/g").append(tile).append(".png\" }\n");
                mapping.put(String.valueOf(pair[0]), tile);
            }

            if (mapping.isEmpty()) continue;
            def.append("tilefont ").append(font).append("\n{\n");
            for (Map.Entry<String, Integer> e : mapping.entrySet()) {
                def.append("    char \"").append(e.getKey()).append("\" ").append(e.getValue()).append("\n");
            }
            def.append("}\n\n");
        }
    }

    /**
     * La letra con su acento encima. Si la letra tiene hueco arriba (minúsculas) el acento va
     * ahí; si no, la letra se encoge un poco en vertical para dejarle sitio, sin cambiar la
     * altura del gráfico (así no se descuadra la línea).
     */
    static Bitmap accent(Bitmap base, int mark) {
        int w = base.getWidth(), h = base.getHeight();
        int[] src = new int[w * h];
        base.getPixels(src, 0, w, 0, 0, w, h);

        int free = 0;
        rows:
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) if ((src[y * w + x] >>> 24) != 0) break rows;
            free++;
        }
        int minX = w, maxX = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((src[y * w + x] >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
        if (maxX < 0) return base;
        int cx = (minX + maxX) / 2;
        int color = mainColor(src, w, h);

        int rows = h >= 12 ? 2 : 1;
        int need = rows + 1;
        int[] dst = new int[w * h];
        int top;
        if (free >= need) {
            System.arraycopy(src, 0, dst, 0, src.length);
            top = free - need;
        } else {
            int body = h - need;
            for (int y = 0; y < body; y++) {
                int sy = body > 1 ? Math.round(y * (h - 1) / (float) (body - 1)) : 0;
                System.arraycopy(src, sy * w, dst, (y + need) * w, w);
            }
            top = 0;
        }

        switch (mark) {
            case ACUTE:
                if (rows == 2) {
                    put(dst, w, h, cx + 1, top, color);
                    put(dst, w, h, cx + 2, top, color);
                    put(dst, w, h, cx, top + 1, color);
                    put(dst, w, h, cx + 1, top + 1, color);
                } else {
                    put(dst, w, h, cx, top, color);
                    put(dst, w, h, cx + 1, top, color);
                }
                break;
            case DIAERESIS:
                for (int r = 0; r < rows; r++) {
                    put(dst, w, h, cx - 2, top + r, color);
                    put(dst, w, h, cx + 2, top + r, color);
                    if (rows == 2) {
                        put(dst, w, h, cx - 1, top + r, color);
                        put(dst, w, h, cx + 3, top + r, color);
                    }
                }
                break;
            default: // TILDE
                if (rows == 2) {
                    for (int x : new int[]{cx - 2, cx - 1, cx + 2, cx + 3}) put(dst, w, h, x, top, color);
                    for (int x : new int[]{cx - 3, cx, cx + 1}) put(dst, w, h, x, top + 1, color);
                } else {
                    for (int x = cx - 2; x <= cx + 2; x++) put(dst, w, h, x, top, color);
                }
                break;
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void put(int[] px, int w, int h, int x, int y, int color) {
        if (x >= 0 && x < w && y >= 0 && y < h) px[y * w + x] = color;
    }

    /** El color más repetido de la letra (el de la cara de la letra, no el del borde). */
    private static int mainColor(int[] px, int w, int h) {
        Map<Integer, Integer> count = new HashMap<>();
        int best = 0xFFFFFFFF, bestN = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                if ((c >>> 24) == 0) continue;
                int n = count.merge(c, 1, Integer::sum);
                if (n > bestN) {
                    bestN = n;
                    best = c;
                }
            }
        return best;
    }

    static Bitmap rotate180(Bitmap base) {
        int w = base.getWidth(), h = base.getHeight();
        int[] src = new int[w * h];
        base.getPixels(src, 0, w, 0, 0, w, h);
        int[] dst = new int[w * h];
        for (int i = 0; i < src.length; i++) dst[i] = src[src.length - 1 - i];
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void savePng(Bitmap b, File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG: " + f);
        }
    }

    private Translator() {
    }
}
