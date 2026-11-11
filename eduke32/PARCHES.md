# Cambios sobre EDuke32

El código de `eduke32/` es EDuke32 (commit en `ORIGEN.txt`), recortado a lo necesario para
Duke Nukem 3D, con estos cambios para compilarlo en Android como un Linux con SDL2:

1. **Código Android antiguo desactivado.** Los `#ifdef __ANDROID__` de EDuke32 son de un port
   táctil antiguo que ya no se mantiene (y no compila). Se han renombrado a
   `EDUKE32_ANDROID_LEGACY`, que nunca se define. Así el juego usa el camino normal de SDL2,
   con soporte de mando.
   Excepciones: `microprofile.h` (sin tocar) y `BS_IWRITE`/`BS_IREAD` en `compat.h`.
2. **ALSA** (MIDI por hardware en Linux) solo con `EDUKE32_HAVE_ALSA`, que `compat.h` define en
   Linux salvo en Android.
3. **Steam (voidwrap)** desactivado en Android (`communityapi.cpp`).
4. **Redirigir stdout a un archivo** (`maybe_redirect_outputs` en `baselayer.cpp`) desactivado en
   Android: copia estructuras `FILE`, que en Android son opacas.
5. **Volcado de pila al fallar** (`execinfo.h`) desactivado en Android (`sdlayer.cpp`).

6. **Manejadores de fallos** (`signal(SIGSEGV…)` en `sdlayer.cpp` y los de loguru en
   `baselayer.cpp`) desactivados en Android: lo pone
   `android/app/src/main/cpp/android_main.c`, que escribe en el registro dónde ha fallado.
7. **Presentación en pantalla** (`sdlayer.cpp`): en Android el juego pinta en una superficie
   propia del tamaño exacto y la GPU la escala a la ventana con `SDL_Renderer`. La superficie de
   la ventana de Android no siempre mide lo que pide el juego y la copia se salía de la memoria.
8. **Limitador de fotogramas** (`engineFPSLimit` en `baselayer.cpp`): en Android duerme hasta el
   siguiente fotograma en lugar de dejar el bucle principal dando vueltas (núcleo al 100 %).
9. **Traducción** (para el castellano del lanzador):
   - `game.cpp`: tras cargar los `.def`, se aplica el idioma de la variable `DUKE3D_LANG`.
   - `screentext.cpp`: los textos que se pintan sin traducción se apuntan una vez en
     `duke3d_android/sin_traducir.txt`.
   - `defs.cpp`: en `localization`, `\n` dentro de un texto es un salto de línea.
   - `menus.cpp`: las preguntas que se montan con `printf` (cargar, borrar, etc.) traducen su
     plantilla con `localeLookup`.
10. **Menú rápido** (Atrás durante la partida):
    - `sdlayer.cpp`: en `handleevents` se ejecutan las órdenes de consola que manda el menú
      (cola en `android_main.c`, `GameActivity.nativeCommand`).
    - `osdcmds.cpp`: orden `pausegame 0|1`, la pausa de la tecla Pausa pero sin el cartel.
11. **Vibración** (`input.h`, `sdlayer.cpp`): en Android la vibración que pide el juego (daño,
    disparos, explosiones, caídas) no depende del mando: va a la app (`duke3d_android_rumble`
    en `android_main.c`), que usa el motor de la consola.
12. **Menú rápido solo en plena partida** (`game.cpp`): `duke3d_android_in_game()`.
13. *(Retirado: los textos traducidos ya no se encogen; las traducciones de los menús son cortas.)*
14. **Logros** (`game.cpp`, `player.cpp`, `player.h`, `gameexec.cpp`, `sector.cpp`, `cheats.cpp`):
    `G_AndroidEvent` avisa a Java (bajas, propinas, secretos, pisotones a enemigos encogidos,
    trucos, Luke, el marine, la caca y el fin de nivel/episodio). Nada cuenta en demos ni en red,
    salvo las bajas de Dukematch (`G_AndroidFragCheck`, tras `G_DoMoveThings`).

15. **Mejoras gráficas en la GPU** (`sdlayer.cpp`): en Android la ventana se crea con OpenGL ES 2
    y cada fotograma pasa por `android/app/src/main/cpp/postfx.c` (FXAA, FSR 1.0 EASU/RCAS,
    escalado nítido, líneas/CRT, color). Si no hay GLES, se usa SDL_Renderer como antes.

Licencias: GPL v2 (`gpl-2.0.txt`) y licencia del motor BUILD de Ken Silverman (`buildlic.txt`).
