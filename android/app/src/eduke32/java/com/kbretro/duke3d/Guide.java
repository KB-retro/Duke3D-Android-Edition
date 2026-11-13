package com.kbretro.duke3d;

/** Textos de la guía de uso (Extras > Guía). Cada tema: título y líneas ("- " = punto). */
final class Guide {

    static final class Topic {
        final String titleEs, titleEn;
        final String[] es, en;

        Topic(String titleEs, String titleEn, String[] es, String[] en) {
            this.titleEs = titleEs;
            this.titleEn = titleEn;
            this.es = es;
            this.en = en;
        }

        String title() {
            return I18n.EN.equals(I18n.lang()) ? titleEn : titleEs;
        }

        String[] lines() {
            return I18n.EN.equals(I18n.lang()) ? en : es;
        }
    }

    static final Topic[] TOPICS = {
            new Topic("Carpeta del juego", "Game folder", new String[]{
                    "- Copia los datos del Duke Nukem 3D (DUKE3D.GRP) en la carpeta Duke3D de la memoria. Vale la carpeta del juego de Steam o GOG entera.",
                    "- Si los tienes en otro sitio, eligela en Opciones > Carpeta. Selecciona la carpeta que contiene a la del juego, no la de dentro.",
                    "- Las expansiones (Duke It Out in D.C., Nuclear Winter, Caribbean) salen solas si estan en la carpeta addons.",
                    "- Penthouse Paradise: descomprime su zip tal cual en extras/penthouse y sale en el menu como un juego mas.",
            }, new String[]{
                    "- Copy the Duke Nukem 3D data (DUKE3D.GRP) to the Duke3D folder in storage. The whole Steam or GOG game folder works.",
                    "- If it is somewhere else, pick it in Options > Folder. Select the folder that contains the game folder, not the one inside.",
                    "- The expansions (Duke It Out in D.C., Nuclear Winter, Caribbean) show up on their own if they are in the addons folder.",
                    "- Penthouse Paradise: unzip it as is into extras/penthouse and it shows up in the menu like another game.",
            }),
            new Topic("Mods", "Mods", new String[]{
                    "- Van en la carpeta mods del juego, que tiene dos subcarpetas:",
                    "- Campanas: aqui van las Campanas MOD, mods que cambian el juego entero (Alien Armageddon, DNF 2001...).",
                    "- Graficos y sonido: aqui van los mods de texturas, voxeles, musica y sonidos.",
                    "- Valen en zip o descomprimidos.",
                    "- Activalos y desactivalos en Mods.",
            }, new String[]{
                    "- They go in the game's mods folder, which has two subfolders:",
                    "- Campanas: MOD Campaigns, mods that change the whole game (Alien Armageddon, DNF 2001...).",
                    "- Graficos y sonido: texture, voxel, music and sound mods.",
                    "- Zipped or unzipped both work.",
                    "- Turn them on and off in Mods.",
            }),
            new Topic("Campanas MOD", "MOD Campaigns", new String[]{
                    "- Solo puede haber una Campana MOD activada a la vez.",
                    "- Una Campana MOD va sola: al activarla se apagan los demas mods.",
                    "- Las Campanas MOD solo funcionan con el juego principal. Mientras haya una activada, las expansiones (DLC) se ocultan del menu.",
                    "- Para volver a jugar las expansiones, desactiva la Campana MOD.",
            }, new String[]{
                    "- Only one MOD Campaign can be active at a time.",
                    "- A MOD Campaign runs alone: turning it on turns the other mods off.",
                    "- MOD Campaigns only work with the main game. While one is active, the expansions (DLC) are hidden from the menu.",
                    "- To play the expansions again, turn the MOD Campaign off.",
            }),
            new Topic("Menu rapido", "Quick menu", new String[]{
                    "- En plena partida, pulsa Atras para abrirlo.",
                    "- Desde ahi: seguir, trucos, graficos, controles, vibracion, volver al lanzador o salir.",
                    "- Para guardar y cargar, usa el menu del propio juego (Start).",
            }, new String[]{
                    "- In game, press Back to open it.",
                    "- From there: continue, cheats, graphics, controls, vibration, back to the launcher or quit.",
                    "- To save and load, use the game's own menu (Start).",
            }),
            new Topic("Mando y tactiles", "Gamepad and touch", new String[]{
                    "- A saltar, B agacharse, X abrir, Y usar objeto.",
                    "- R2 disparar, L2 patada, L1 y R1 cambiar de arma.",
                    "- L3 correr, R3 centrar la vista, Select mapa, Start menu del juego.",
                    "- Cruceta: arriba botiquin, abajo jetpack, izquierda y derecha cambiar de objeto.",
                    "- Cambia cualquier boton en el menu rapido > Controles > Configurar mando.",
                    "- Sensibilidad horizontal y vertical de los sticks (1 a 10) en el menu rapido > Controles.",
                    "- Tactiles en automatico: salen si no hay mando o al tocar la pantalla y se ocultan al usar el mando. Tambien siempre o nunca, y su sensibilidad, en Opciones o en el menu rapido.",
            }, new String[]{
                    "- A jump, B crouch, X open, Y use item.",
                    "- R2 fire, L2 kick, L1 and R1 change weapon.",
                    "- L3 run, R3 center view, Select map, Start game menu.",
                    "- D-pad: up medkit, down jetpack, left and right change item.",
                    "- Change any button in the quick menu > Controls > Gamepad setup.",
                    "- Horizontal and vertical stick sensitivity (1 to 10) in the quick menu > Controls.",
                    "- Touch controls on automatic: shown with no gamepad or when touching the screen, hidden when using the gamepad. Also always or never, and their sensitivity, in Options or the quick menu.",
            }),
            new Topic("Graficos", "Graphics", new String[]{
                    "- Resolucion interna.",
                    "- Escalado: pixel, nitido, suave, bicubico o FSR.",
                    "- FXAA, nitidez, resplandor, tonos de color y efecto (lineas, CRT o LCD).",
                    "- Estan en Opciones y en el menu rapido.",
            }, new String[]{
                    "- Internal resolution.",
                    "- Scaling: pixel, sharp, smooth, bicubic or FSR.",
                    "- FXAA, sharpness, bloom, color tones and effect (lines, CRT or LCD).",
                    "- In Options and in the quick menu.",
            }),
            new Topic("Clasicos", "Classics", new String[]{
                    "- Duke Nukem 1 y Duke Nukem II, los originales de MS-DOS.",
                    "- Copia el Duke 1 en extras/duke1 y el Duke II en extras/duke2. Vale el zip descomprimido tal cual.",
                    "- Atras abre su menu rapido: guardado y carga rapida en cualquier momento, graficos y configurar mando.",
            }, new String[]{
                    "- Duke Nukem 1 and Duke Nukem II, the original MS-DOS games.",
                    "- Copy Duke 1 to extras/duke1 and Duke II to extras/duke2. The unzipped archive works as is.",
                    "- Back opens their quick menu: quick save and load at any time, graphics and gamepad setup.",
            }),
            new Topic("Musica y logros", "Music and achievements", new String[]{
                    "- Musica: escucha las pistas del juego desde el lanzador.",
                    "- Logros: 17 logros que se desbloquean jugando, con su aviso en pantalla. Son los de la Megaton, sin los de partidas online.",
                    "- En la lista, los conseguidos salen en amarillo. Al elegir uno, abajo pone que hay que hacer.",
            }, new String[]{
                    "- Music: listen to the game's tracks from the launcher.",
                    "- Achievements: 17 achievements unlocked by playing, with an on-screen notice. The Megaton ones, without the online ones.",
                    "- In the list, unlocked ones are yellow. Pick one to see below what to do.",
            }),
            new Topic("Si algo falla", "If something fails", new String[]{
                    "- Opciones > Generar registro guarda el archivo registro.txt en la carpeta que elegiste (la que contiene la del juego); abajo sale la ruta exacta. Mandalo para ver que ha pasado.",
            }, new String[]{
                    "- Options > Save log saves registro.txt in the folder you picked (the one containing the game folder); the exact path shows below. Send it to see what happened.",
            }),
    };

    private Guide() {
    }
}
