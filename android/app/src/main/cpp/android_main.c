// Punto de entrada para SDLActivity.
//
// EDuke32 define SDL_MAIN_HANDLED, así que su main() no se renombra a SDL_main. Aquí se
// exporta SDL_main (lo que busca SDL en Android), se entra en la carpeta del juego que
// indica la app (variable DUKE3D_DIR) y se llama al main() de siempre.
#include <dlfcn.h>
#include <jni.h>
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
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT };
    for (unsigned i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++)
        sigaction(sigs[i], &sa, NULL);
}

// ---- Órdenes del menú rápido ----
// GameActivity.nativeCommand() las encola desde Java y el juego las ejecuta en su hilo
// (handleevents en sdlayer.cpp) con OSD_Dispatch, como si se escribieran en la consola.

#define CMD_MAX 32
#define CMD_LEN 256
static char cmd_queue[CMD_MAX][CMD_LEN];
static int cmd_head, cmd_count;
static pthread_mutex_t cmd_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeCommand(JNIEnv *env, jclass cls, jstring cmd)
{
    (void)cls;
    const char *c = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (!c) return;
    pthread_mutex_lock(&cmd_lock);
    if (cmd_count < CMD_MAX)
    {
        int slot = (cmd_head + cmd_count) % CMD_MAX;
        strncpy(cmd_queue[slot], c, CMD_LEN - 1);
        cmd_queue[slot][CMD_LEN - 1] = '\0';
        cmd_count++;
    }
    pthread_mutex_unlock(&cmd_lock);
    (*env)->ReleaseStringUTFChars(env, cmd, c);
}

// ---- Vibración ----
// El juego pide vibrar (daño, disparos, explosiones...) en su hilo; se llama al método
// estático GameActivity.vibrate(int fuerza, int ms), que usa el motor de la consola.

static JavaVM *java_vm;
static jclass game_class;
static jmethodID vibrate_method;
static jmethodID achievement_method;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    java_vm = vm;
    return JNI_VERSION_1_6;
}

/** Desde Java (hilo principal): se guarda la clase, que el hilo del juego no puede buscar. */
JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeInit(JNIEnv *env, jclass cls)
{
    if (!game_class) game_class = (*env)->NewGlobalRef(env, cls);
    vibrate_method = (*env)->GetStaticMethodID(env, cls, "vibrate", "(II)V");
    achievement_method = (*env)->GetStaticMethodID(env, cls, "achievementEvent", "(IIII)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

__attribute__((visibility("default")))
void duke3d_android_rumble(int low, int high, int ms)
{
    if (!java_vm || !game_class || !vibrate_method) return;
    JNIEnv *env = NULL;
    if ((*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK) return;
    }
    int strength = (low > high ? low : high) / 257; // 0..255
    (*env)->CallStaticVoidMethod(env, game_class, vibrate_method, strength, ms);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

// ---- Logros ----
// El juego avisa de lo que pasa (bajas, propinas, secretos, fin de nivel...) y
// GameActivity.achievementEvent(tipo, a, b, c) decide qué logros se desbloquean.

__attribute__((visibility("default")))
void duke3d_android_event(int type, int a, int b, int c)
{
    if (!java_vm || !game_class || !achievement_method) return;
    JNIEnv *env = NULL;
    if ((*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK) return;
    }
    (*env)->CallStaticVoidMethod(env, game_class, achievement_method, type, a, b, c);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

int duke3d_android_in_game(void);

JNIEXPORT jboolean JNICALL Java_com_kbretro_duke3d_GameActivity_nativeInGame(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return duke3d_android_in_game() ? JNI_TRUE : JNI_FALSE;
}

__attribute__((visibility("default")))
int duke3d_android_next_command(char *buf, int len)
{
    int got = 0;
    pthread_mutex_lock(&cmd_lock);
    if (cmd_count > 0)
    {
        strncpy(buf, cmd_queue[cmd_head], len - 1);
        buf[len - 1] = '\0';
        cmd_head = (cmd_head + 1) % CMD_MAX;
        cmd_count--;
        got = 1;
    }
    pthread_mutex_unlock(&cmd_lock);
    return got;
}

struct game_args { int argc; char **argv; int rc; };

static void *game_thread(void *p)
{
    struct game_args *ga = (struct game_args *)p;
    // Pila aparte para el manejador de fallos: si el fallo es por quedarse sin pila, el
    // manejador necesita otra para poder escribir en el registro.
    stack_t ss;
    ss.ss_sp = malloc(256 * 1024);
    ss.ss_size = 256 * 1024;
    ss.ss_flags = 0;
    if (ss.ss_sp) sigaltstack(&ss, NULL);
    ga->rc = main(ga->argc, ga->argv);
    // Si este hilo se enganchó a Java (vibración, logros), soltarlo antes de terminar: un hilo
    // que acaba enganchado hace que Android cierre la app como si fuera un fallo.
    JNIEnv *env = NULL;
    if (java_vm && (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK)
        (*java_vm)->DetachCurrentThread(java_vm);
    return NULL;
}

__attribute__((visibility("default")))
int SDL_SetHint(const char *name, const char *value);

/**
 * Al salir con exit() (el juego ya ha guardado su configuración), el proceso termina en el acto.
 * Si no, se ejecutaba la limpieza final mientras el hilo de dibujo de Android seguía activo; usaba
 * un mutex ya destruido, abortaba y el lanzador lo tomaba por un fallo (pantalla de Game Over).
 */
static void quick_exit_handler(void)
{
    fflush(stdout);
    fflush(stderr);
    _exit(0);
}

int SDL_main(int argc, char *argv[])
{
    redirect_output();
    install_crash_handler();
    atexit(quick_exit_handler);

    // Siempre en horizontal (SDL dejaba girar a vertical si la consola se inclinaba).
    SDL_SetHint("SDL_IOS_ORIENTATIONS", "LandscapeLeft LandscapeRight");

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

    // El hilo de SDL en Android tiene ~1 MB de pila y EDuke32 cuenta con los 8 MB de un PC:
    // se queda sin pila y falla. El juego va en un hilo propio con pila de sobra.
    struct game_args ga = { argc, argv, 0 };
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 64 * 1024 * 1024);
    pthread_t t;
    int rc;
    if (pthread_create(&t, &attr, game_thread, &ga) == 0)
    {
        pthread_join(t, NULL);
        rc = ga.rc;
    }
    else
    {
        __android_log_print(ANDROID_LOG_ERROR, "Duke3D", "No se pudo crear el hilo del juego");
        rc = main(argc, argv);
    }
    pthread_attr_destroy(&attr);
    __android_log_print(ANDROID_LOG_INFO, "Duke3D", "El juego ha terminado (código %d)", rc);
    return rc;
}

// ---- Controles táctiles y botones reasignados ----
// Java manda el joystick virtual (-1..1), lo que se arrastra para mirar (píxeles) y las
// acciones del juego pulsadas (gamefunc_*). El motor lo lee al leer los controles:
// control.cpp (botones, como si fueran de un mando) y player.cpp (movimiento y mirada).

static pthread_mutex_t touch_lock = PTHREAD_MUTEX_INITIALIZER;
static float touch_mx, touch_my, touch_lx, touch_ly, touch_sens = 1.f;
static uint64_t touch_buttons;

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeTouchMove(JNIEnv *env, jclass cls, jfloat x, jfloat y)
{
    (void)env;
    (void)cls;
    pthread_mutex_lock(&touch_lock);
    touch_mx = x;
    touch_my = y;
    pthread_mutex_unlock(&touch_lock);
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeTouchLook(JNIEnv *env, jclass cls, jfloat dx, jfloat dy, jfloat sens)
{
    (void)env;
    (void)cls;
    pthread_mutex_lock(&touch_lock);
    touch_lx += dx;
    touch_ly += dy;
    touch_sens = sens;
    pthread_mutex_unlock(&touch_lock);
}

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativeButton(JNIEnv *env, jclass cls, jint func, jboolean down)
{
    (void)env;
    (void)cls;
    if (func < 0 || func >= 64) return;
    pthread_mutex_lock(&touch_lock);
    if (down) touch_buttons |= (uint64_t)1 << func;
    else touch_buttons &= ~((uint64_t)1 << func);
    pthread_mutex_unlock(&touch_lock);
}

/** Acciones pulsadas ahora (bit = gamefunc_*). */
__attribute__((visibility("default")))
uint64_t duke3d_android_buttons(void)
{
    pthread_mutex_lock(&touch_lock);
    uint64_t b = touch_buttons;
    pthread_mutex_unlock(&touch_lock);
    return b;
}

/**
 * Movimiento y mirada táctiles: suma al joystick (dx = lado, dz = adelante/atrás, -32767..32767)
 * y devuelve el giro (unidades de ratón) y la inclinación (horizonte) acumulados desde la última vez.
 */
__attribute__((visibility("default")))
void duke3d_android_touch(int32_t *dx, int32_t *dz, int32_t *yaw, float *pitch)
{
    pthread_mutex_lock(&touch_lock);
    float mx = touch_mx, my = touch_my, lx = touch_lx, ly = touch_ly, s = touch_sens;
    touch_lx = touch_ly = 0;
    pthread_mutex_unlock(&touch_lock);

    if (mx != 0.f || my != 0.f)
    {
        int32_t const ax = (int32_t)(mx * 32767.f), az = (int32_t)(my * 32767.f);
        *dx = *dx + ax;
        *dz = *dz + az;
        if (*dx > 32767) *dx = 32767;
        if (*dx < -32767) *dx = -32767;
        if (*dz > 32767) *dz = 32767;
        if (*dz < -32767) *dz = -32767;
    }
    // Arrastrar la pantalla entera (unos 1900 px) gira algo más de media vuelta.
    *yaw += (int32_t)(lx * 18.f * s);
    *pitch = ly * 0.22f * s;
}
