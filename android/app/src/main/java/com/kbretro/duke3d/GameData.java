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

    /** Todos los mods (.zip, .pk3, .grp) que hay, ordenados por nombre. */
    static List<File> mods(File gameDir) {
        List<File> mods = new ArrayList<>();
        for (File d : modDirs(gameDir)) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && (n.endsWith(".zip") || n.endsWith(".pk3") || n.endsWith(".grp"))) mods.add(f);
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
     * Argumentos para los mods activados. Cada .zip/.pk3 se carga con -g y además:
     * - su duke3d.def se copia con su propio nombre (duke3d_android/mods/NOMBRE.def) y se carga
     *   ese (varios packs traen uno y el juego solo vería uno); los demás .def de la raíz, con -mh;
     * - los .con propios de la raíz (no GAME/USER/DEFS.CON, que ya sustituyen a los del juego),
     *   como módulos con -mx;
     * - si todo va dentro de una sola carpeta del zip, esa carpeta se extrae una vez a
     *   duke3d_android/mods/NOMBRE/ y se carga como carpeta (-j).
     * El contenido de cada mod se apunta en el registro. Se llama fuera del hilo principal.
     */
    static List<String> modArguments(File gameDir, java.util.Set<String> enabled) {
        List<String> args = new ArrayList<>();
        File outDir = new File(gameDir, "duke3d_android/mods");
        boolean anyMainDef = false;
        for (File f : mods(gameDir)) {
            if (!enabled.contains(f.getName())) continue;
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            String safe = f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            if (!(n.endsWith(".zip") || n.endsWith(".pk3"))) {
                args.add("-g");
                args.add(f.getAbsolutePath());
                continue;
            }
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
                List<String> rootFiles = new ArrayList<>();
                java.util.Set<String> topDirs = new java.util.TreeSet<>();
                java.util.Map<String, Integer> exts = new java.util.TreeMap<>();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = e.nextElement();
                    String name = entry.getName();
                    int slash = name.indexOf('/');
                    if (slash >= 0) topDirs.add(name.substring(0, slash));
                    if (entry.isDirectory()) continue;
                    if (slash < 0) rootFiles.add(name);
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "-";
                    exts.merge(ext, 1, Integer::sum);
                }
                android.util.Log.i("Duke3D", "Mod " + f.getName() + ": raiz=" + rootFiles.subList(0, Math.min(25, rootFiles.size()))
                        + " carpetas=" + topDirs + " tipos=" + exts);

                // Todo dentro de una carpeta: se extrae y se usa como carpeta.
                File base = null;
                if (rootFiles.isEmpty() && topDirs.size() == 1) {
                    String dir = topDirs.iterator().next();
                    File dest = new File(outDir, safe);
                    File stamp = new File(dest, ".extraido");
                    String id = f.length() + ":" + f.lastModified();
                    if (!stamp.exists() || !id.equals(readText(stamp))) {
                        deleteTree(dest);
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> e2 = z.entries();
                        while (e2.hasMoreElements()) {
                            java.util.zip.ZipEntry entry = e2.nextElement();
                            String name = entry.getName();
                            if (entry.isDirectory() || !name.startsWith(dir + "/") || name.contains("..")) continue;
                            File out = new File(dest, name.substring(dir.length() + 1));
                            out.getParentFile().mkdirs();
                            try (java.io.InputStream in = z.getInputStream(entry);
                                 java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                                copy(in, o);
                            }
                        }
                        writeText(stamp, id);
                    }
                    base = dest;
                    args.add("-j");
                    args.add(dest.getAbsolutePath());
                    rootFiles.clear();
                    File[] inner = dest.listFiles();
                    if (inner != null) for (File x : inner) if (x.isFile() && !x.getName().startsWith(".")) rootFiles.add(x.getName());
                } else {
                    args.add("-g");
                    args.add(f.getAbsolutePath());
                }

                String mainDef = null;
                for (String r : rootFiles) {
                    String low = r.toLowerCase(java.util.Locale.ROOT);
                    if (low.equals("duke3d.def")) {
                        mainDef = r;
                    } else if (low.endsWith(".def")) {
                        args.add("-mh");
                        args.add(r);
                    } else if (low.endsWith(".con") && !BASE_CONS.contains(low)) {
                        args.add("-mx");
                        args.add(r);
                    }
                }
                if (mainDef != null) {
                    // Su duke3d.def, copiado con el nombre del pack.
                    outDir.mkdirs();
                    File copyTo = new File(outDir, safe + ".def");
                    if (base != null) {
                        try (java.io.InputStream in = new java.io.FileInputStream(new File(base, mainDef));
                             java.io.OutputStream o = new java.io.FileOutputStream(copyTo)) {
                            copy(in, o);
                        }
                    } else {
                        try (java.io.InputStream in = z.getInputStream(z.getEntry(mainDef));
                             java.io.OutputStream o = new java.io.FileOutputStream(copyTo)) {
                            copy(in, o);
                        }
                    }
                    args.add("-mh");
                    args.add("duke3d_android/mods/" + safe + ".def");
                    anyMainDef = true;
                }
            } catch (Exception ex) {
                android.util.Log.w("Duke3D", "No se pudo leer el mod " + f, ex);
                args.add("-g");
                args.add(f.getAbsolutePath());
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
        return args;
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
