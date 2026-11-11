package com.kbretro.duke3d;

import android.os.Environment;

import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Busca los archivos de Duke Nukem 3D: Megaton Edition (Steam). Por defecto en /Duke3D; el
 * usuario puede elegir cualquier otra carpeta desde el menú.
 */
final class GameData {
    static final String FOLDER = "Duke3D";

    /** Un juego que se puede lanzar: el principal o una expansión. */
    static final class Game {
        final String title;
        final String grpName;   // nombre real del archivo (con sus mayúsculas)
        final File grp;

        Game(String title, File grp) {
            this.title = title;
            this.grpName = grp.getName();
            this.grp = grp;
        }

        /** Nombre corto para el menú (el de Caribbean no cabe entero con las letras del juego). */
        String menuLabel() {
            int colon = title.indexOf(':');
            return colon > 0 && title.startsWith("Caribbean") ? title.substring(0, colon) : title;
        }
    }

    /** Carpeta por defecto: /storage/emulated/0/Duke3D. */
    static File defaultDir() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER);
    }

    /**
     * La carpeta con DUKE3D.GRP. Vale copiar la carpeta "gameroot", su contenido o la carpeta
     * entera del juego de Steam ("Duke Nukem 3D").
     */
    static File findGameDir(File base) {
        File[] candidates = {
                base,
                new File(base, "gameroot"),
                new File(base, "Duke Nukem 3D/gameroot"),
        };
        for (File dir : candidates) {
            File d = resolve(base, dir);
            if (d != null && findChild(d, "DUKE3D.GRP") != null) return d;
        }
        return null;
    }

    /** Carpetas de las expansiones de Megaton (gameroot/addons/dc, nw y vacation). */
    static List<File> addonDirs(File gameDir) {
        List<File> dirs = new ArrayList<>();
        File addons = findChild(gameDir, "addons");
        if (addons != null && addons.isDirectory()) {
            for (String name : new String[]{"dc", "nw", "vacation"}) {
                File d = findChild(addons, name);
                if (d != null && d.isDirectory()) dirs.add(d);
            }
        }
        return dirs;
    }

    /** El juego principal y las expansiones que haya. */
    static List<Game> games(File gameDir) {
        List<Game> games = new ArrayList<>();
        File base = findChild(gameDir, "DUKE3D.GRP");
        if (base == null) return games;
        games.add(new Game("Duke Nukem 3D", base));

        List<File> dirs = new ArrayList<>(addonDirs(gameDir));
        dirs.add(gameDir); // también si las expansiones están sueltas junto a DUKE3D.GRP
        String[][] addons = {
                {"Duke It Out in D.C.", "DUKEDC.GRP"},
                {"Nuclear Winter", "NWINTER.GRP"},
                {"Caribbean: Life's a Beach", "VACATION.GRP"},
        };
        for (String[] addon : addons) {
            for (File d : dirs) {
                File grp = findChild(d, addon[1]);
                if (grp != null) {
                    games.add(new Game(addon[0], grp));
                    break;
                }
            }
        }
        return games;
    }

    /** Las carpetas donde se buscan mods: "mods" (y "hd", la de antes) dentro de la del juego. */
    static List<File> modDirs(File gameDir) {
        List<File> dirs = new ArrayList<>();
        for (String n : new String[]{"mods", "hd"}) {
            File d = findChild(gameDir, n);
            if (d != null && d.isDirectory()) dirs.add(d);
        }
        return dirs;
    }

    /** Todos los mods (.zip, .pk3, .grp o carpetas) que hay, ordenados por nombre. */
    static List<File> mods(File gameDir) {
        List<File> mods = new ArrayList<>();
        for (File d : modDirs(gameDir)) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && (n.endsWith(".zip") || n.endsWith(".pk3") || n.endsWith(".grp"))) mods.add(f);
                else if (f.isDirectory() && !n.startsWith(".")) mods.add(f); // mod ya descomprimido
            }
        }
        java.util.Collections.sort(mods, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        return mods;
    }

    /** Los .def de la raíz de un mod (zip/pk3), para cargarlos. */
    static List<String> rootDefs(File mod) {
        List<String> defs = new ArrayList<>();
        String n = mod.getName().toLowerCase(java.util.Locale.ROOT);
        if (!(n.endsWith(".zip") || n.endsWith(".pk3"))) return defs;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(mod)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (!name.contains("/") && name.toLowerCase(java.util.Locale.ROOT).endsWith(".def")) defs.add(name);
            }
        } catch (Exception ignored) {
        }
        return defs;
    }

    private static final java.util.Set<String> BASE_CONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "game.con", "user.con", "defs.con", "duke3d.con"));

    /**
     * Argumentos para los mods activados.
     * - Los .zip/.pk3 se descomprimen una vez (con los nombres en minúsculas, porque el motor
     *   solo prueba el nombre tal cual, en minúsculas y en mayúsculas) a
     *   duke3d_android/mods/NOMBRE/ y se cargan como carpeta (-j); las carpetas, tal cual.
     * - Se baja por carpetas sueltas ("Alien Armageddon/AA/...") hasta la que tiene el mod.
     * - Los .grp/.pk3/.zip/.ssi de dentro se cargan también (-g).
     * - Campañas completas (TC): si traen .grpinfo se usa su CON y DEF; si no, EDUKE.CON o
     *   GAME.CON; si no, el .CON principal que define al jugador (APLAYER). Se carga con -x,
     *   en lugar de los del juego.
     * - Mods pequeños: solo sus .CON principales (los no incluidos por otros) con -mx, y sus
     *   .DEF principales con -mh. Así nada se carga dos veces.
     * Todo lo que decide se apunta en el registro. Se llama fuera del hilo principal.
     */
    /** Lo que se está preparando ahora (para enseñarlo en el lanzador) y el porcentaje. */
    static volatile String progress = "", progressValue = "";

    static List<String> modArguments(File gameDir, java.util.Set<String> enabled) {
        List<String> args = new ArrayList<>();
        File outDir = new File(gameDir, "duke3d_android/mods");
        boolean anyMainDef = false;
        for (File f : mods(gameDir)) {
            if (!enabled.contains(f.getName())) continue;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            String safe = f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            progress = f.getName();
            progressValue = "...";
            try {
                File dest;
                if (f.isDirectory()) {
                    dest = f;
                } else if (n.endsWith(".zip") || n.endsWith(".pk3")) {
                    dest = new File(outDir, safe);
                    extract(f, dest);
                } else {
                    args.add("-g");
                    args.add(f.getAbsolutePath());
                    continue;
                }
                progressValue = "100%";
                File base = modBase(dest);
                args.add("-j");
                args.add(base.getAbsolutePath());
                anyMainDef |= analyzeMod(f.getName(), base, safe, outDir, args);
            } catch (Exception ex) {
                android.util.Log.w("Duke3D", "No se pudo leer el mod " + f, ex);
                if (f.isFile()) {
                    args.add("-g");
                    args.add(f.getAbsolutePath());
                }
            }
        }
        if (anyMainDef) {
            File empty = new File(gameDir, "duke3d_android/vacio.def");
            try {
                empty.getParentFile().mkdirs();
                new java.io.FileWriter(empty).close();
                args.add("-h");
                args.add("duke3d_android/vacio.def");
            } catch (java.io.IOException ignored) {
            }
        }
        if (!args.isEmpty()) {
            // Las campañas grandes (Alien Armageddon, AMC...) no caben en la caché de 96 MB del
            // motor ("CACHE SPACE ALL LOCKED UP!"): con mods se le dan 384 MB.
            args.add("-cachesize");
            args.add(String.valueOf(384 * 1024));
        }
        android.util.Log.i("Duke3D", "Argumentos de mods: " + args);
        return args;
    }

    /** Descomprime el zip en dest (nombres en minúsculas), solo si ha cambiado desde la última vez. */
    private static void extract(File zip, File dest) throws java.io.IOException {
        File stamp = new File(dest, ".extraido");
        String id = "v2:" + zip.length() + ":" + zip.lastModified();
        if (stamp.exists() && id.equals(readText(stamp))) return;
        android.util.Log.i("Duke3D", "Descomprimiendo el mod " + zip.getName());
        long t0 = System.currentTimeMillis();
        deleteTree(dest);
        dest.mkdirs();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) {
            long total = 0, done = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e0 = z.entries();
            while (e0.hasMoreElements()) total += Math.max(0, e0.nextElement().getCompressedSize());
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                java.util.zip.ZipEntry entry = e.nextElement();
                done += Math.max(0, entry.getCompressedSize());
                if (total > 0) progressValue = (done * 100 / total) + "%";
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.contains("..") || name.startsWith("__MACOSX/")) continue;
                File out = new File(dest, name.toLowerCase(java.util.Locale.ROOT));
                out.getParentFile().mkdirs();
                try (java.io.InputStream in = z.getInputStream(entry);
                     java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                    copy(in, o);
                }
            }
        }
        writeText(stamp, id);
        android.util.Log.i("Duke3D", "Mod " + zip.getName() + " descomprimido en " + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    private static boolean isModData(String low) {
        return low.endsWith(".con") || low.endsWith(".def") || low.endsWith(".grp") || low.endsWith(".grpinfo")
                || low.endsWith(".art") || low.endsWith(".map") || low.endsWith(".pk3") || low.endsWith(".ssi");
    }

    /** Baja por las carpetas que no tienen datos del mod y solo contienen otra carpeta. */
    private static File modBase(File dir) {
        for (int depth = 0; depth < 5; depth++) {
            File[] kids = dir.listFiles();
            if (kids == null) return dir;
            File onlyDir = null;
            int dirs = 0;
            boolean data = false;
            for (File k : kids) {
                String low = k.getName().toLowerCase(java.util.Locale.ROOT);
                if (k.isDirectory()) {
                    if (low.startsWith(".") || low.equals("__macosx")) continue;
                    dirs++;
                    onlyDir = k;
                } else if (isModData(low)) {
                    data = true;
                }
            }
            if (data || dirs != 1) return dir;
            dir = onlyDir;
        }
        return dir;
    }

    /** Lo que hay en un .grp de Ken Silverman: nombre (en minúsculas) → [posición, tamaño]. */
    private static java.util.Map<String, long[]> grpEntries(File grp) {
        java.util.Map<String, long[]> map = new java.util.LinkedHashMap<>();
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(grp, "r")) {
            byte[] head = new byte[16];
            r.readFully(head);
            if (!new String(head, 0, 12, "ISO-8859-1").equals("KenSilverman")) return map;
            int count = (head[12] & 255) | (head[13] & 255) << 8 | (head[14] & 255) << 16 | (head[15] & 255) << 24;
            if (count <= 0 || count > 100000) return map;
            byte[] dir = new byte[count * 16];
            r.readFully(dir);
            long pos = 16 + (long) count * 16;
            for (int i = 0; i < count; i++) {
                int o = i * 16;
                String name = new String(dir, o, 12, "ISO-8859-1").trim();
                int nul = name.indexOf(0);
                if (nul >= 0) name = name.substring(0, nul);
                long size = (dir[o + 12] & 255L) | (dir[o + 13] & 255L) << 8 | (dir[o + 14] & 255L) << 16 | (dir[o + 15] & 255L) << 24;
                map.put(name.toLowerCase(java.util.Locale.ROOT), new long[]{pos, size});
                pos += size;
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static String grpText(File grp, long[] e) {
        if (e[1] > 4 << 20) return "";
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(grp, "r")) {
            byte[] b = new byte[(int) e[1]];
            r.seek(e[0]);
            r.readFully(b);
            return new String(b, "ISO-8859-1");
        } catch (Exception ex) {
            return "";
        }
    }

    private static String fileText(File f) {
        if (f.length() > 4 << 20) return "";
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            copy(in, o);
            return o.toString("ISO-8859-1");
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static final java.util.regex.Pattern INCLUDE =
            java.util.regex.Pattern.compile("(?im)^[ \\t]*#?include[ \\t]+\"?([^\\s\"]+)");
    private static final java.util.regex.Pattern PLAYER =
            java.util.regex.Pattern.compile("(?i)\\bactor\\s+aplayer\\b");

    private static String normName(String s) {
        s = s.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        while (s.startsWith("./") || s.startsWith("/")) s = s.substring(s.startsWith("/") ? 1 : 2);
        return s;
    }

    /**
     * Decide cómo cargar un mod ya en carpeta y añade sus argumentos.
     * @return si trae su propio .def principal (entonces no se carga el duke3d.def del juego).
     */
    private static boolean analyzeMod(String modName, File base, String safe, File outDir, List<String> args)
            throws java.io.IOException {
        // Archivos de la raíz y textos de los .con/.def (también los de dentro de sus .grp)
        java.util.Map<String, String> rootNames = new java.util.TreeMap<>(); // minúsculas → nombre real
        java.util.Map<String, String> cons = new java.util.LinkedHashMap<>(); // ruta → texto
        java.util.Map<String, String> defs = new java.util.LinkedHashMap<>();
        String grpinfo = null;
        File[] kids = base.listFiles();
        if (kids != null) {
            java.util.Arrays.sort(kids);
            for (File k : kids) {
                if (!k.isFile() || k.getName().startsWith(".")) continue;
                String low = k.getName().toLowerCase(java.util.Locale.ROOT);
                rootNames.put(low, k.getName());
                if (low.endsWith(".grp") || low.endsWith(".pk3") || low.endsWith(".ssi")
                        || (low.endsWith(".zip") && !low.matches(".*(src|source|readme|manual|extra).*"))) {
                    args.add("-g");
                    args.add(k.getAbsolutePath());
                    if (low.endsWith(".grp")) {
                        for (java.util.Map.Entry<String, long[]> e : grpEntries(k).entrySet()) {
                            String name = e.getKey();
                            if (!rootNames.containsKey(name)) rootNames.put(name, name);
                            if (name.endsWith(".con")) cons.put(name, grpText(k, e.getValue()));
                            else if (name.endsWith(".def")) defs.put(name, grpText(k, e.getValue()));
                            else if (name.endsWith(".grpinfo") && grpinfo == null) grpinfo = grpText(k, e.getValue());
                        }
                    }
                } else if (low.endsWith(".grpinfo") && grpinfo == null) {
                    grpinfo = fileText(k);
                }
            }
        }
        // .con y .def de todas las carpetas del mod
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(base);
        int seen = 0;
        while (!stack.isEmpty() && seen < 20000) {
            File d = stack.pop();
            File[] list = d.listFiles();
            if (list == null) continue;
            for (File k : list) {
                seen++;
                if (k.isDirectory()) {
                    stack.push(k);
                    continue;
                }
                String low = k.getName().toLowerCase(java.util.Locale.ROOT);
                if (!low.endsWith(".con") && !low.endsWith(".def")) continue;
                String rel = normName(base.toURI().relativize(k.toURI()).getPath());
                (low.endsWith(".con") ? cons : defs).put(rel, fileText(k));
            }
        }

        java.util.Set<String> conIncluded = new java.util.HashSet<>(), defIncluded = new java.util.HashSet<>();
        boolean hasPlayer = false;
        String playerCon = null;
        for (java.util.Map.Entry<String, String> e : cons.entrySet()) {
            java.util.regex.Matcher m = INCLUDE.matcher(e.getValue());
            while (m.find()) conIncluded.add(normName(m.group(1)));
            if (PLAYER.matcher(e.getValue()).find()) {
                hasPlayer = true;
                if (playerCon == null) playerCon = e.getKey();
            }
        }
        for (String text : defs.values()) {
            java.util.regex.Matcher m = INCLUDE.matcher(text);
            while (m.find()) defIncluded.add(normName(m.group(1)));
        }

        String mainCon = null, mainDef = null;
        if (grpinfo != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)scriptname\\s+\"([^\"]+)\"").matcher(grpinfo);
            if (m.find()) mainCon = m.group(1);
            m = java.util.regex.Pattern.compile("(?i)defname\\s+\"([^\"]+)\"").matcher(grpinfo);
            if (m.find()) mainDef = m.group(1);
        }
        // CON de la raíz que nadie incluye
        List<String> topCons = new ArrayList<>();
        for (String low : rootNames.keySet()) {
            if (low.endsWith(".con") && !conIncluded.contains(low)) topCons.add(low);
        }
        if (mainCon == null) {
            if (rootNames.containsKey("eduke.con")) mainCon = "eduke.con";
            else if (rootNames.containsKey("game.con")) mainCon = "game.con";
            else if (hasPlayer) {
                // El principal que no es de los del juego y más incluye
                int best = -1;
                for (String c : topCons) {
                    String text = cons.get(c);
                    int inc = 0;
                    if (text != null) {
                        java.util.regex.Matcher m = INCLUDE.matcher(text);
                        while (m.find()) inc++;
                    }
                    if (text != null && PLAYER.matcher(text).find()) inc += 1000;
                    if (inc > best) {
                        best = inc;
                        mainCon = c;
                    }
                }
                if (mainCon == null) mainCon = playerCon;
            }
        }
        if (mainCon != null) {
            args.add("-x");
            args.add(mainCon);
        } else {
            for (String c : topCons) {
                if (BASE_CONS.contains(c)) continue;
                args.add("-mx");
                args.add(rootNames.get(c));
            }
        }

        // DEF
        boolean ownMainDef = false;
        if (mainDef == null && rootNames.containsKey("duke3d.def") && kidsHas(base, "duke3d.def")) mainDef = "duke3d.def";
        if (mainDef != null && normName(mainDef).equals("duke3d.def") && kidsHas(base, "duke3d.def")) {
            // Su duke3d.def, copiado con el nombre del mod (varios mods pueden traer uno).
            outDir.mkdirs();
            File copyTo = new File(outDir, safe + ".def");
            try (java.io.InputStream in = new java.io.FileInputStream(new File(base, rootNames.get("duke3d.def")));
                 java.io.OutputStream o = new java.io.FileOutputStream(copyTo)) {
                copy(in, o);
            }
            args.add("-mh");
            args.add("duke3d_android/mods/" + safe + ".def");
            ownMainDef = true;
        } else if (mainDef != null) {
            args.add("-mh");
            args.add(mainDef);
            ownMainDef = true;
        }
        for (String low : rootNames.keySet()) {
            if (!low.endsWith(".def") || defIncluded.contains(low) || low.equals("duke3d.def")) continue;
            if (mainDef != null && normName(mainDef).equals(low)) continue;
            if (!kidsHas(base, low)) continue; // los de dentro de un .grp los carga su def principal
            args.add("-mh");
            args.add(rootNames.get(low));
        }
        android.util.Log.i("Duke3D", "Mod " + modName + ": carpeta=" + base + " con=" + (mainCon != null ? mainCon + " (campaña)" : topCons)
                + " def=" + mainDef + " grpinfo=" + (grpinfo != null) + " jugador=" + hasPlayer
                + " raiz=" + new ArrayList<>(rootNames.keySet()).subList(0, Math.min(30, rootNames.size())));
        return ownMainDef;
    }

    private static boolean kidsHas(File base, String low) {
        File[] kids = base.listFiles();
        if (kids != null) for (File k : kids) if (k.isFile() && k.getName().equalsIgnoreCase(low)) return true;
        return false;
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    private static String readText(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String l = r.readLine();
            return l != null ? l : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String s) {
        try (java.io.FileWriter w = new java.io.FileWriter(f)) {
            w.write(s);
        } catch (java.io.IOException ignored) {
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Argumentos de EDuke32 para lanzar un juego desde gameDir. */
    static String[] arguments(File gameDir, Game game) {
        return arguments(gameDir, game, null);
    }

    /** @param def módulo .def extra (la traducción), relativo a la carpeta del juego, o null. */
    static String[] arguments(File gameDir, Game game, String def) {
        List<String> args = new ArrayList<>();
        args.add("-usecwd");          // configuración y partidas guardadas en la carpeta del juego
        for (File d : addonDirs(gameDir)) {
            args.add("-j");
            args.add(d.getAbsolutePath());
        }
        if (def != null) {
            args.add("-mh");
            args.add(def);
        }
        args.add("-gamegrp");
        args.add(game.grpName);
        return args.toArray(new String[0]);
    }

    /**
     * Convierte la carpeta elegida en el selector de Android (content://…/tree/primary:Juegos/Duke)
     * en una ruta normal (/storage/emulated/0/Juegos/Duke). Las tarjetas SD van en /storage/XXXX-XXXX.
     */
    static File treeUriToFile(Uri uri) {
        String id = DocumentsContract.getTreeDocumentId(uri);
        int colon = id.indexOf(':');
        String volume = colon >= 0 ? id.substring(0, colon) : id;
        String path = colon >= 0 ? id.substring(colon + 1) : "";
        File root = "primary".equalsIgnoreCase(volume)
                ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + volume);
        return path.isEmpty() ? root : new File(root, path);
    }

    /** Busca un archivo o carpeta sin distinguir mayúsculas. */
    static File findChild(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    /** Resuelve una ruta tramo a tramo sin distinguir mayúsculas. */
    private static File resolve(File base, File path) {
        if (!path.getPath().startsWith(base.getPath())) return path.isDirectory() ? path : null;
        if (!base.isDirectory()) return null;
        String rest = path.getPath().substring(base.getPath().length());
        File cur = base;
        for (String part : rest.split("/")) {
            if (part.isEmpty()) continue;
            cur = findChild(cur, part);
            if (cur == null || !cur.isDirectory()) return null;
        }
        return cur;
    }

    private GameData() {
    }
}
