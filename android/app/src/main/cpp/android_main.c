// Punto de entrada para SDLActivity.
//
// EDuke32 define SDL_MAIN_HANDLED, así que su main() no se renombra a SDL_main. Aquí se
// exporta SDL_main (lo que busca SDL en Android), se entra en la carpeta del juego que
// indica la app (variable DUKE3D_DIR) y se llama al main() de siempre.
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>

int main(int argc, char *argv[]);

__attribute__((visibility("default")))
int SDL_main(int argc, char *argv[])
{
    const char *dir = getenv("DUKE3D_DIR");
    if (dir && *dir)
    {
        if (chdir(dir) != 0)
            __android_log_print(ANDROID_LOG_ERROR, "Duke3D", "No se pudo entrar en %s", dir);
        // Las rutas de usuario (HOME/.config/eduke32) también quedan en la carpeta del juego.
        setenv("HOME", dir, 1);
    }
    return main(argc, argv);
}
