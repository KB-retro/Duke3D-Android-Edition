// Punto de entrada para SDLActivity.
//
// EDuke32 define SDL_MAIN_HANDLED, así que su main() no se renombra a SDL_main. Aquí se
// exporta SDL_main (lo que busca SDL en Android), se entra en la carpeta del juego que
// indica la app (variable DUKE3D_DIR) y se llama al main() de siempre.
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <ucontext.h>
#include <unwind.h>
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

// ---- Fallos: escribe en el registro la función donde se ha roto y la cadena de llamadas ----

static void log_addr(const char *what, uintptr_t pc)
{
    Dl_info info;
    if (pc && dladdr((void *)pc, &info) && info.dli_fname)
    {
        const char *lib = strrchr(info.dli_fname, '/');
        lib = lib ? lib + 1 : info.dli_fname;
        __android_log_print(ANDROID_LOG_FATAL, "Duke3D", "%s %s+0x%lx %s+0x%lx", what, lib,
                            (unsigned long)(pc - (uintptr_t)info.dli_fbase),
                            info.dli_sname ? info.dli_sname : "?",
                            info.dli_saddr ? (unsigned long)(pc - (uintptr_t)info.dli_saddr) : 0UL);
    }
    else
        __android_log_print(ANDROID_LOG_FATAL, "Duke3D", "%s 0x%lx", what, (unsigned long)pc);
}

static _Unwind_Reason_Code unwind_step(struct _Unwind_Context *ctx, void *arg)
{
    int *n = (int *)arg;
    char what[8];
    snprintf(what, sizeof(what), "#%02d", *n);
    log_addr(what, (uintptr_t)_Unwind_GetIP(ctx));
    return ++*n >= 32 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void crash_handler(int sig, siginfo_t *si, void *ucv)
{
    __android_log_print(ANDROID_LOG_FATAL, "Duke3D", "FALLO: señal %d en la dirección %p", sig, si ? si->si_addr : NULL);
#if defined __aarch64__
    ucontext_t *uc = (ucontext_t *)ucv;
    log_addr("pc", (uintptr_t)uc->uc_mcontext.pc);
    log_addr("lr", (uintptr_t)uc->uc_mcontext.regs[30]);
#else
    (void)ucv;
#endif
    int n = 0;
    _Unwind_Backtrace(unwind_step, &n);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handler(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT };
    for (unsigned i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++)
        sigaction(sigs[i], &sa, NULL);
}

__attribute__((visibility("default")))
int SDL_main(int argc, char *argv[])
{
    redirect_output();
    install_crash_handler();

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
