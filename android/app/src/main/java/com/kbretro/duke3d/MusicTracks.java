package com.kbretro.duke3d;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pistas de música del juego y sus expansiones, sacadas de los GRP del usuario. Cada pista se
 * nombra con el nivel donde suena, según el comando "music" de los .CON:
 *
 *   music 1 stalker.mid dethtoll.mid ...   (episodio 1: una pista por nivel, en orden)
 *   music 0 grabbag.mid briefing.mid ...   (especiales: menú, presentación, final...)
 */
final class MusicTracks {

    static final class Track {
        final String label;
        final String file;       // nombre dentro del GRP, en mayúsculas (p. ej. STALKER.MID)
        final GrpArchive grp;

        Track(String label, String file, GrpArchive grp) {
            this.label = label;
            this.file = file;
            this.grp = grp;
        }
    }

    private static final Pattern LEVEL = Pattern.compile(
            "(?im)^\\s*definelevelname\\s+(\\d+)\\s+(\\d+)\\s+\\S+\\s+\\d+:\\d+(?:\\s+\\d+:\\d+)?\\s+(.*?)\\s*$");

    /** Nombres de las pistas especiales del episodio 0. */
    private static String specialName(String file) {
        String base = file.replaceAll("\\..*$", "").toUpperCase(Locale.ROOT);
        boolean es = I18n.ES.equals(I18n.lang());
        switch (base) {
            case "GRABBAG":
                return es ? "Tema principal" : "Main theme";
            case "BRIEFING":
                return es ? "Presentación" : "Briefing";
            case "LOGO":
                return es ? "Logo" : "Logo";
            case "ENDLEVEL":
            case "BONUS":
                return es ? "Fin de nivel" : "Level end";
            default:
                return base;
        }
    }

    /**
     * @param games juego base primero; cada uno con su GRP abierto en grps (mismo orden).
     */
    static List<Track> load(Context c, List<GameData.Game> games, List<GrpArchive> grps) {
        List<Track> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (int g = 0; g < games.size() && g < grps.size(); g++) {
            GrpArchive grp = grps.get(g);
            String prefix = g == 0 ? "" : shortName(games.get(g)) + " ";
            StringBuilder con = new StringBuilder();
            for (String name : grp.names()) {
                if (!name.endsWith(".CON")) continue;
                try {
                    con.append(new String(grp.read(name), StandardCharsets.ISO_8859_1)).append('\n');
                } catch (Exception ignored) {
                }
            }
            String text = stripComments(con.toString());

            // Nombres de nivel: episodio (0..) y nivel (0..).
            Map<String, String> levels = new HashMap<>();
            Matcher m = LEVEL.matcher(text);
            while (m.find()) levels.put(m.group(1) + ":" + m.group(2), m.group(3));

            // music <episodio> pista pista ...
            for (Map.Entry<Integer, List<String>> e : musicLines(text).entrySet()) {
                int vol = e.getKey();
                List<String> files = e.getValue();
                for (int i = 0; i < files.size(); i++) {
                    String file = files.get(i).toUpperCase(Locale.ROOT);
                    GrpArchive src = grp.has(file) ? grp : (grps.get(0).has(file) ? grps.get(0) : null);
                    if (src == null || seen.containsKey(file)) continue;
                    String label;
                    if (vol <= 0) {
                        label = specialName(file);
                    } else {
                        String lvl = levels.get((vol - 1) + ":" + i);
                        String name = lvl != null ? Translator.lookup(c, lvl) : file.replaceAll("\\..*$", "");
                        label = "E" + vol + "L" + (i + 1) + " " + name;
                    }
                    out.add(new Track(prefix + label, file, src));
                    seen.put(file, true);
                }
            }
            // Las que hay en el GRP pero no salen en ningún "music".
            for (String name : grp.names()) {
                if (!(name.endsWith(".MID") || name.endsWith(".OGG")) || seen.containsKey(name)) continue;
                out.add(new Track(prefix + specialName(name), name, grp));
                seen.put(name, true);
            }
        }
        return out;
    }

    private static String shortName(GameData.Game g) {
        String t = g.title.toLowerCase(Locale.ROOT);
        if (t.contains("d.c.")) return "DC";
        if (t.contains("winter")) return "NW";
        if (t.contains("caribbean")) return "CB";
        return "";
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /** Las listas de "music": episodio → pistas, en orden (pueden ocupar varias líneas). */
    private static Map<Integer, List<String>> musicLines(String text) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        String[] tok = text.split("\\s+");
        for (int i = 0; i < tok.length - 1; i++) {
            if (!tok[i].equalsIgnoreCase("music")) continue;
            int vol;
            try {
                vol = Integer.parseInt(tok[i + 1]);
            } catch (NumberFormatException e) {
                continue;
            }
            List<String> files = out.computeIfAbsent(vol, k -> new ArrayList<>());
            int j = i + 2;
            while (j < tok.length && tok[j].matches("(?i)[\\w\\-/\\\\]+\\.(mid|ogg|mp3|flac|voc|wav)")) {
                String f = tok[j];
                int slash = Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\'));
                files.add(slash >= 0 ? f.substring(slash + 1) : f);
                j++;
            }
            i = j - 1;
        }
        return out;
    }

    private MusicTracks() {
    }
}
