// Punto de entrada para SDLActivity.
//
// EDuke32 define SDL_MAIN_HANDLED, así que su main() no se renombra a SDL_main. Aquí se
// exporta SDL_main (lo que busca SDL en Android), se entra en la carpeta del juego que
// indica la app (variable DUKE3D_DIR) y se llama al main() de siempre.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

int main(int argc, char *argv[]);

static int log_pipe[2];

// En Android stdout y stderr no van a ninguna parte: se mandan al registro del sistema
// (logcat), que la app guarda en /Duke3D/registro.txt al volver al menú.
static void *log_thread(void *unused)
{
    (void)unused;
    char buf[1024];
    ssize_t n;
    while ((n = read(log_pipe[0], buf, sizeof(buf) - 1)) > 0)
    {
        buf[n] = '\0';
        char *line = buf, *end;
        while ((end = strchr(line, '\n')) != NULL)
        {
            *end = '\0';
            if (*line) __android_log_write(ANDROID_LOG_INFO, "EDuke32", line);
            line = end + 1;
        }
        if (*line) __android_log_write(ANDROID_LOG_INFO, "EDuke32", line);
    }
    return NULL;
}

static void redirect_output(void)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    if (pipe(log_pipe) != 0) return;
    dup2(log_pipe[1], STDOUT_FILENO);
    dup2(log_pipe[1], STDERR_FILENO);
    pthread_t t;
    if (pthread_create(&t, NULL, log_thread, NULL) == 0) pthread_detach(t);
}

__attribute__((visibility("default")))
int SDL_main(int argc, char *argv[])
{
    redirect_output();

    const char *dir = getenv("DUKE3D_DIR");
    __android_log_print(ANDROID_LOG_INFO, "Duke3D", "Carpeta del juego: %s", dir ? dir : "(ninguna)");
    for (int i = 0; i < argc; i++)
        __android_log_print(ANDROID_LOG_INFO, "Duke3D", "argv[%d] = %s", i, argv[i]);

    if (dir && *dir)
    {
        if (chdir(dir) != 0)
            __android_log_print(ANDROID_LOG_ERROR, "Duke3D", "No se pudo entrar en %s", dir);
        // Las rutas de usuario (HOME/.config/eduke32) también quedan en la carpeta del juego.
        setenv("HOME", dir, 1);
    }

    int rc = main(argc, argv);
    __android_log_print(ANDROID_LOG_INFO, "Duke3D", "El juego ha terminado (código %d)", rc);
    return rc;
}
