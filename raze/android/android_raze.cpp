// Puente entre la app (GameActivity.java) y Raze en Android.
//
// La app llama a estas funciones desde su hilo; Raze no es seguro entre hilos, así que todo
// se guarda aquí y se aplica en el hilo del juego:
// - android_raze_tick(), desde I_StartTic(): órdenes de consola, botones y el giro táctil.
// - android_raze_axes(), desde GameInput::getInput(): el joystick táctil como ejes analógicos.
//
// Los botones llegan con los números gamefunc_* de EDuke32 (los que usa la app) y aquí se
// traducen a los botones u órdenes de Raze.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>
#include <SDL.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "c_dispatch.h"
#include "c_console.h"
#include "d_eventbase.h"
#include "gamestate.h"
#include "menustate.h"
#include "m_joy.h"
#include "gameconfigfile.h"
#include "texturemanager.h"
#include "tarray.h"
#include "zstring.h"


extern int paused;
extern bool pausedWithKey;

#define LOG_TAG "Raze"

namespace
{
std::mutex queueLock;
std::vector<std::string> pending;
std::atomic<float> moveX{0}, moveY{0};
std::atomic<float> stickX{0}, stickY{0}; // stick derecho del mando (giro continuo)
std::mutex lookLock;
float lookX = 0, lookY = 0;

void queue(const std::string &cmd)
{
    std::lock_guard<std::mutex> g(queueLock);
    pending.push_back(cmd);
}

// gamefunc_* de EDuke32 → orden de Raze. Los que empiezan por '+' se mantienen pulsados.
const char *raze_command(int func)
{
    switch (func)
    {
    case 5: return "+Fire";
    case 6: return "+Open";
    case 7: return "+Run";
    case 9: return "+Jump";
    case 10: return "+Crouch";
    case 11: return "+Look_Up";
    case 12: return "+Look_Down";
    case 49: return "+Quick_Kick";
    case 62: return "+Toggle_Crouch";
    case 50: return "weapnext";
    case 51: return "weapprev";
    case 57: return "weapalt";
    case 29: return "invuse";
    case 30: return "invprev";
    case 31: return "invnext";
    case 35: return "useitem 1"; // botiquín
    case 48: return "useitem 2"; // esteroides
    case 32: return "useitem 3"; // holoduke
    case 33: return "useitem 4"; // jetpack
    case 34: return "useitem 5"; // visión nocturna
    case 38: return "togglemap";
    case 36: return "turnaround";
    case 41: return "centerview";
    case 42: return "holsterweapon";
    case 56: return "toggle cl_autorun";
    default: return nullptr;
    }
}
// Las órdenes que manda la app son las de EDuke32: aquí se pasan a las de Raze.
std::string translate(const std::string &c)
{
    int a = 0, b = 0;
    if (sscanf(c.c_str(), "pausegame %d", &a) == 1)
    {
        // Pausa del menú rápido: se pone o se quita directamente (como hace la tecla Pausa),
        // sin alternar: al abrir y cerrar rápido se descuadraba y el juego quedaba en pausa.
        pausedWithKey = a != 0;
        paused = a != 0 ? 2 : 0;
        return "";
    }
    if (c == "android_start")
    {
        // Start del mando: abre o cierra el menú del juego (y cierra la consola si se abrió).
        if (ConsoleState != c_up) return "toggleconsole";
        return menuactive != MENU_Off ? "closemenu" : "menu_main";
    }
    if (sscanf(c.c_str(), "r_maxfps %d", &a) == 1)
        return "vid_maxfps " + std::to_string(a <= 0 ? 1000 : a);
    if (sscanf(c.c_str(), "vidmode %d %d", &a, &b) == 2)
    {
        // Resolución interna: la app pide un tamaño; en Raze es una escala de la ventana.
        int w = 0, h = 0;
        SDL_Window *win = SDL_GL_GetCurrentWindow();
        if (win) SDL_GL_GetDrawableSize(win, &w, &h);
        float f = w > 0 ? (float)a / w : 1.f;
        if (f > 1.f) f = 1.f;
        char buf[64];
        snprintf(buf, sizeof(buf), "vid_scalefactor %.3f", f);
        return buf;
    }
    return c;
}
} // namespace

// Lo llama I_StartTic() en el hilo del juego.
void android_raze_tick()
{
    std::vector<std::string> cmds;
    {
        std::lock_guard<std::mutex> g(queueLock);
        cmds.swap(pending);
    }
    bool flush = false;
    for (auto &c : cmds)
    {
        // Filtrado, anisotrópico y suavizado de texturas: en OpenGL ES solo se aplican al crear
        // cada textura, así que tras cambiarlos se rehacen todas.
        if (c.rfind("gl_texture_filter", 0) == 0 || c.rfind("gl_texture_hqresize", 0) == 0 ||
            c.rfind("hw_useindexedcolortextures", 0) == 0)
            flush = true;
        if (c == "quit")
        {
            // Salir: se guarda la configuración y se termina sin desmontar Vulkan (al cerrarlo
            // fallaba y el lanzador enseñaba la pantalla de fallo).
            G_SaveConfig();
            fflush(stdout);
            fflush(stderr);
            _exit(0);
        }
        std::string r = translate(c);
        if (!r.empty())
            AddCommandString(r.c_str());
    }
    if (flush)
        TexMan.FlushAll();

    float dx, dy;
    {
        std::lock_guard<std::mutex> g(lookLock);
        dx = lookX + stickX.load() * 22.f;
        dy = lookY + stickY.load() * 14.f;
        lookX = lookY = 0;
    }
    if (dx != 0 || dy != 0)
    {
        event_t ev = {};
        ev.type = EV_Mouse;
        ev.x = dx;
        ev.y = -dy;
        D_PostEvent(&ev);
    }
}

// Lo llama GameInput::getInput() tras leer los mandos: suma el joystick táctil.
void android_raze_axes(float *axes)
{
    float x = moveX.load(), y = moveY.load();
    axes[JOYAXIS_Forward] -= y;
    axes[JOYAXIS_Side] -= x;
}

extern "C" {

// ---- Vibración y logros ----
// El juego avisa (daño, disparos, bajas, secretos, fin de nivel...) desde su hilo; se llama a
// los métodos estáticos GameActivity.vibrate(fuerza, ms) y achievementEvent(tipo, a, b, c).

static JavaVM *java_vm;
static jclass game_class;
static jmethodID vibrate_method, achievement_method;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *)
{
    java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeInit(JNIEnv *env, jclass cls)
{
    if (!game_class) game_class = (jclass)env->NewGlobalRef(cls);
    vibrate_method = env->GetStaticMethodID(cls, "vibrate", "(II)V");
    achievement_method = env->GetStaticMethodID(cls, "achievementEvent", "(IIII)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static JNIEnv *game_env()
{
    if (!java_vm || !game_class) return nullptr;
    JNIEnv *env = nullptr;
    if (java_vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK &&
        java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
        return nullptr;
    return env;
}

__attribute__((visibility("default"))) void android_raze_rumble(int strength, int ms)
{
    JNIEnv *env = game_env();
    if (!env || !vibrate_method) return;
    env->CallStaticVoidMethod(game_class, vibrate_method, strength, ms);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

__attribute__((visibility("default"))) void android_raze_event(int type, int a, int b, int c)
{
    JNIEnv *env = game_env();
    if (!env || !achievement_method) return;
    env->CallStaticVoidMethod(game_class, achievement_method, type, a, b, c);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeCommand(JNIEnv *env, jclass, jstring cmd)
{
    const char *c = env->GetStringUTFChars(cmd, nullptr);
    if (c)
    {
        queue(c);
        env->ReleaseStringUTFChars(cmd, c);
    }
}

JNIEXPORT jboolean JNICALL Java_com_kbretro_duke3d_GameActivity_nativeInGame(JNIEnv *, jclass)
{
    return gamestate == GS_LEVEL && menuactive == MENU_Off && ConsoleState == c_up;
}

// Raze tiene sus propios filtros (FXAA, escalado...): los de EDuke32 no se usan aquí.
JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativePostFx(JNIEnv *, jclass, jint, jint, jint, jint, jint, jint)
{
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeTouchMove(JNIEnv *, jclass, jfloat x, jfloat y)
{
    moveX.store(x);
    moveY.store(y);
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeTouchLook(JNIEnv *, jclass, jfloat dx, jfloat dy, jfloat sens)
{
    std::lock_guard<std::mutex> g(lookLock);
    lookX += dx * sens * 4.f;
    lookY += dy * sens * 4.f;
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeStickLook(JNIEnv *, jclass, jfloat x, jfloat y)
{
    stickX.store(x);
    stickY.store(y);
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeButton(JNIEnv *, jclass, jint func, jboolean down)
{
    const char *c = raze_command(func);
    if (!c)
        return;
    if (c[0] == '+')
    {
        std::string s = c;
        if (!down)
            s[0] = '-';
        queue(s);
    }
    else if (down)
    {
        queue(c);
    }
}

} // extern "C"

// ---- Arranque ----
// i_main.cpp (parcheado) llama a su main raze_main(). SDLActivity busca SDL_main: aquí se
// prepara todo como en la versión EDuke32 (android_main.c) y se arranca el juego.

int raze_main(int argc, char **argv);

namespace
{
int log_pipe[2];

void *log_thread(void *)
{
    char buf[1024];
    ssize_t n;
    while ((n = read(log_pipe[0], buf, sizeof(buf) - 1)) > 0)
    {
        buf[n] = '\0';
        char *line = buf, *end;
        while ((end = strchr(line, '\n')) != nullptr)
        {
            *end = '\0';
            if (*line) __android_log_write(ANDROID_LOG_INFO, LOG_TAG, line);
            line = end + 1;
        }
        if (*line) __android_log_write(ANDROID_LOG_INFO, LOG_TAG, line);
    }
    return nullptr;
}

void redirect_output()
{
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(log_pipe) != 0) return;
    dup2(log_pipe[1], STDOUT_FILENO);
    dup2(log_pipe[1], STDERR_FILENO);
    pthread_t t;
    if (pthread_create(&t, nullptr, log_thread, nullptr) == 0) pthread_detach(t);
}

void log_addr(const char *what, uintptr_t pc)
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

_Unwind_Reason_Code unwind_step(struct _Unwind_Context *ctx, void *arg)
{
    int *n = (int *)arg;
    char what[8];
    snprintf(what, sizeof(what), "#%02d", *n);
    log_addr(what, (uintptr_t)_Unwind_GetIP(ctx));
    return ++*n >= 32 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

void crash_handler(int sig, siginfo_t *si, void *ucv)
{
    __android_log_print(ANDROID_LOG_FATAL, "Duke3D", "FALLO: señal %d en la dirección %p", sig, si ? si->si_addr : nullptr);
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

void install_crash_handler()
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    int sigs[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};
    for (int s : sigs) sigaction(s, &sa, nullptr);
}

// Al salir, el proceso termina en el acto (como en la versión EDuke32).
void quick_exit_handler()
{
    fflush(stdout);
    fflush(stderr);
    _exit(0);
}

struct GameArgs
{
    int argc;
    char **argv;
    int rc;
};

void *game_thread(void *p)
{
    GameArgs *ga = (GameArgs *)p;
    ga->rc = raze_main(ga->argc, ga->argv);
    return nullptr;
}
} // namespace

extern "C" int SDL_main(int argc, char *argv[])
{
    redirect_output();
    install_crash_handler();
    atexit(quick_exit_handler);
    SDL_SetHint("SDL_IOS_ORIENTATIONS", "LandscapeLeft LandscapeRight");

    const char *dir = getenv("DUKE3D_DIR");
    __android_log_print(ANDROID_LOG_INFO, "Duke3D", "Raze. Carpeta del juego: %s", dir ? dir : "(ninguna)");
    for (int i = 0; i < argc; i++)
        __android_log_print(ANDROID_LOG_INFO, "Duke3D", "argv[%d] = %s", i, argv[i]);
    if (dir && *dir)
    {
        if (chdir(dir) != 0)
            __android_log_print(ANDROID_LOG_ERROR, "Duke3D", "No se pudo entrar en %s", dir);
        // La configuración y las partidas de Raze ($HOME/.config/raze) van en la carpeta del juego.
        setenv("HOME", dir, 1);
    }

    // Pila grande: el hilo de SDL en Android tiene ~1 MB.
    GameArgs ga = {argc, argv, 0};
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 64 * 1024 * 1024);
    pthread_t t;
    if (pthread_create(&t, &attr, game_thread, &ga) == 0)
        pthread_join(t, nullptr);
    else
        ga.rc = raze_main(argc, argv);
    pthread_attr_destroy(&attr);
    __android_log_print(ANDROID_LOG_INFO, "Duke3D", "El juego ha terminado (código %d)", ga.rc);
    return ga.rc;
}
