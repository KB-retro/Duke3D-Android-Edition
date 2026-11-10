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

Licencias: GPL v2 (`gpl-2.0.txt`) y licencia del motor BUILD de Ken Silverman (`buildlic.txt`).
