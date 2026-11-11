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

    /**
     * Argumentos para los mods activados: cada uno con -g y sus .def de la raíz con -mh.
     *
     * Muchos packs (HRP, vóxeles...) traen su propio duke3d.def y todos se llaman igual: el
     * juego solo vería uno y lo cargaría varias veces. Por eso el duke3d.def de cada pack se
     * copia con su propio nombre (duke3d_android/mods/NOMBRE.def) y se carga ese; sus include
     * siguen encontrando los archivos dentro del pack. El duke3d.def principal pasa a ser uno
     * vacío para que no se cargue ninguno dos veces. Se llama fuera del hilo principal.
     */
    static List<String> modArguments(File gameDir, java.util.Set<String> enabled) {
        List<String> args = new ArrayList<>();
        File outDir = new File(gameDir, "duke3d_android/mods");
        boolean anyMainDef = false;
        for (File f : mods(gameDir)) {
            if (!enabled.contains(f.getName())) continue;
            args.add("-g");
            args.add(f.getAbsolutePath());
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (!(n.endsWith(".zip") || n.endsWith(".pk3"))) continue;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
                java.util.zip.ZipEntry main = null;
                List<String> others = new ArrayList<>();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = e.nextElement();
                    String name = entry.getName();
                    if (name.contains("/") || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".def")) continue;
                    if (name.equalsIgnoreCase("duke3d.def")) main = entry;
                    else others.add(name);
                }
                if (main != null) {
                    // Su duke3d.def, copiado con el nombre del pack.
                    outDir.mkdirs();
                    String safe = f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                    File copy = new File(outDir, safe + ".def");
                    try (java.io.InputStream in = z.getInputStream(main);
                         java.io.OutputStream out = new java.io.FileOutputStream(copy)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                    args.add("-mh");
                    args.add("duke3d_android/mods/" + safe + ".def");
                    anyMainDef = true;
                } else {
                    for (String d : others) {
                        args.add("-mh");
                        args.add(d);
                    }
                }
            } catch (Exception ex) {
                android.util.Log.w("Duke3D", "No se pudo leer el mod " + f, ex);
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
