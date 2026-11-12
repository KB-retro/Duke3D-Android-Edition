// Duke3D Android Edition: juegos de MS-DOS (Duke Nukem 1 y 2) como extras de la Classic.
//
// Un "frontend" libretro mínimo para el núcleo DOSBox Pure, enlazado dentro de esta misma
// biblioteca (libdos.so). Va con SDL2 como el juego: ventana con OpenGL ES y los mismos filtros
// gráficos (postfx.c), y sonido con la cola de audio de SDL.
//
// Argumentos (DosActivity.getArguments): ejecutable del juego, carpeta de guardado del núcleo y
// archivo del guardado rápido.
//
// El juego arranca directamente (sin el menú de DOSBox Pure) y, al salir del juego, se cierra.
// Desde Java llegan las teclas (nativeKey) y las órdenes del menú rápido (nativeCommand): pausa,
// guardado y carga rápidos (los "estados" de libretro) y salir.

#include <jni.h>
#include <android/log.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "SDL.h"
#include "libretro.h"

#define LOG_TAG "Duke3D-DOS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
void android_postfx_attributes(void);
int android_postfx_init(SDL_Window *window);
int android_postfx_active(void);
void android_postfx_shutdown(void);
void android_postfx_present(const void *pixels, int w, int h, int pitch);
}

// ---- Estado ----

static std::string g_save_dir, g_state_path;
static retro_keyboard_event_t g_keyboard_cb;
static retro_system_av_info g_av;
static volatile bool g_quit;
static bool g_paused;

static SDL_Window *g_window;
static SDL_Renderer *g_renderer;  // solo si no hay OpenGL ES
static SDL_Texture *g_texture;
static int g_tex_w, g_tex_h;
static SDL_AudioDeviceID g_audio;
static int g_audio_rate;

static std::vector<uint32_t> g_frame;
static int g_frame_w, g_frame_h;

static bool g_keys[RETROK_LAST];

// Órdenes de Java (hilo de la interfaz) para el hilo del juego.
enum { CMD_KEY = 0, CMD_PAUSE = 1, CMD_RESUME = 2, CMD_SAVE = 3, CMD_LOAD = 4, CMD_QUIT = 5 };
struct Command {
    int type, a, b;
    int id;  // > 0 si Java espera la respuesta
};
static std::mutex g_mutex;
static std::condition_variable g_done_cv;
static std::deque<Command> g_queue;
static int g_next_id = 1, g_done_id, g_done_result;

// ---- Ajustes del núcleo ----

static const char *option(const char *key)
{
    static const struct {
        const char *key, *value;
    } opts[] = {
        // Arranca el juego sin el menú de DOSBox Pure y sale al terminar el juego.
        { "dosbox_pure_menu_time", "0" },
        { "dosbox_pure_on_screen_keyboard", "false" },
        { "dosbox_pure_auto_mapping", "false" },
        { "dosbox_pure_savestate", "on" },
        { "dosbox_pure_audiorate", "48000" },
        { "dosbox_pure_aspect_correction", "false" },
        { "dosbox_pure_perfstats", "none" },
        { "dosbox_pure_mouse_input", "false" },
    };
    for (auto &o : opts)
        if (!strcmp(o.key, key)) return o.value;
    return NULL;
}

// ---- Callbacks de libretro ----

static void core_log(enum retro_log_level level, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(level >= RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, LOG_TAG, fmt, ap);
    va_end(ap);
}

static bool environment(unsigned cmd, void *data)
{
    switch (cmd)
    {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((retro_log_callback *)data)->log = core_log;
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = g_save_dir.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *(const retro_pixel_format *)data == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        retro_variable *v = (retro_variable *)data;
        v->value = v->key ? option(v->key) : NULL;
        return v->value != NULL;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false;
        return true;
    case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
        g_keyboard_cb = ((const retro_keyboard_callback *)data)->callback;
        return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        g_av = *(const retro_system_av_info *)data;
        return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        g_av.geometry = *(const retro_game_geometry *)data;
        return true;
    case RETRO_ENVIRONMENT_SHUTDOWN:
        LOGI("El juego ha terminado");
        g_quit = true;
        return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
        const retro_message_ext *m = (const retro_message_ext *)data;
        if (m && m->msg) LOGI("%s", m->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *(unsigned *)data = 2;
        return true;
    default:
        return false;  // OpenGL del núcleo (Voodoo), MIDI, VFS...: no hacen falta
    }
}

static void video_refresh(const void *data, unsigned w, unsigned h, size_t pitch)
{
    if (data && w && h)
    {
        if ((int)w != g_frame_w || (int)h != g_frame_h) g_frame.assign((size_t)w * h, 0);
        g_frame_w = w;
        g_frame_h = h;
        for (unsigned y = 0; y < h; y++)
            memcpy(&g_frame[(size_t)y * w], (const char *)data + y * pitch, w * 4);
    }
    if (g_frame.empty()) return;

    if (android_postfx_active())
    {
        // XRGB8888 (little endian) = BGRA en memoria, como la imagen de EDuke32.
        android_postfx_present(g_frame.data(), g_frame_w, g_frame_h, g_frame_w * 4);
    }
    else if (g_renderer)
    {
        if (!g_texture || g_tex_w != g_frame_w || g_tex_h != g_frame_h)
        {
            if (g_texture) SDL_DestroyTexture(g_texture);
            g_texture = SDL_CreateTexture(g_renderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STREAMING, g_frame_w, g_frame_h);
            g_tex_w = g_frame_w;
            g_tex_h = g_frame_h;
        }
        SDL_UpdateTexture(g_texture, NULL, g_frame.data(), g_frame_w * 4);
        int ww, wh;
        SDL_GetRendererOutputSize(g_renderer, &ww, &wh);
        int vw = ww, vh = ww * 3 / 4;
        if (vh > wh) { vh = wh; vw = wh * 4 / 3; }
        SDL_Rect dst = { (ww - vw) / 2, (wh - vh) / 2, vw, vh };
        SDL_SetRenderDrawColor(g_renderer, 0, 0, 0, 255);
        SDL_RenderClear(g_renderer);
        SDL_RenderCopy(g_renderer, g_texture, NULL, &dst);
        SDL_RenderPresent(g_renderer);
    }
}

static void audio_sample(int16_t l, int16_t r)
{
    int16_t s[2] = { l, r };
    if (g_audio) SDL_QueueAudio(g_audio, s, sizeof(s));
}

static size_t audio_batch(const int16_t *data, size_t frames)
{
    if (g_audio && !g_paused) SDL_QueueAudio(g_audio, data, (Uint32)(frames * 4));
    return frames;
}

static void input_poll(void)
{
}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id)
{
    (void)index;
    if (port == 0 && device == RETRO_DEVICE_KEYBOARD && id < RETROK_LAST) return g_keys[id] ? 1 : 0;
    return 0;  // el mando llega como teclado (lo traduce Java)
}

// ---- Teclas ----

static uint32_t key_char(unsigned k)
{
    return (k >= 32 && k < 127) ? k : 0;
}

static void send_key(unsigned k, bool down)
{
    if (k >= RETROK_LAST) return;
    if (g_keys[k] == down) return;
    g_keys[k] = down;
    if (g_keyboard_cb) g_keyboard_cb(down, k, down ? key_char(k) : 0, 0);
}

static void release_all_keys(void)
{
    for (unsigned k = 0; k < RETROK_LAST; k++)
        if (g_keys[k]) send_key(k, false);
}

// ---- Guardado rápido ----

static int save_state(void)
{
    size_t size = retro_serialize_size();
    if (!size) return -1;
    std::vector<uint8_t> buf(size);
    if (!retro_serialize(buf.data(), size)) return -1;
    std::string tmp = g_state_path + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return -1;
    bool ok = fwrite(buf.data(), 1, size, f) == size;
    ok = (fclose(f) == 0) && ok;
    if (!ok || rename(tmp.c_str(), g_state_path.c_str()) != 0) return -1;
    LOGI("Guardado rápido: %zu bytes", size);
    return 0;
}

static int load_state(void)
{
    FILE *f = fopen(g_state_path.c_str(), "rb");
    if (!f) return 1;  // no hay guardado
    std::vector<uint8_t> buf;
    uint8_t chunk[65536];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) buf.insert(buf.end(), chunk, chunk + n);
    fclose(f);
    release_all_keys();
    if (buf.empty() || !retro_unserialize(buf.data(), buf.size())) return -1;
    if (g_audio) SDL_ClearQueuedAudio(g_audio);
    return 0;
}

// ---- Órdenes de Java ----

static void finish_command(const Command &c, int result)
{
    if (c.id <= 0) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_done_id = c.id;
    g_done_result = result;
    g_done_cv.notify_all();
}

static void process_commands(void)
{
    for (;;)
    {
        Command c;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (g_queue.empty()) return;
            c = g_queue.front();
            g_queue.pop_front();
        }
        int result = 0;
        switch (c.type)
        {
        case CMD_KEY:
            send_key((unsigned)c.a, c.b != 0);
            break;
        case CMD_PAUSE:
            g_paused = true;
            release_all_keys();
            if (g_audio) { SDL_PauseAudioDevice(g_audio, 1); SDL_ClearQueuedAudio(g_audio); }
            break;
        case CMD_RESUME:
            g_paused = false;
            if (g_audio) { SDL_ClearQueuedAudio(g_audio); SDL_PauseAudioDevice(g_audio, 0); }
            break;
        case CMD_SAVE:
            result = save_state();
            break;
        case CMD_LOAD:
            result = load_state();
            if (result == 0) video_refresh(NULL, 0, 0, 0);
            break;
        case CMD_QUIT:
            g_quit = true;
            break;
        }
        finish_command(c, result);
    }
}

static int post_command(int type, int a, int b, bool wait)
{
    std::unique_lock<std::mutex> lock(g_mutex);
    Command c = { type, a, b, wait ? g_next_id++ : 0 };
    g_queue.push_back(c);
    if (!wait) return 0;
    bool ok = g_done_cv.wait_for(lock, std::chrono::seconds(10), [&] { return g_done_id == c.id; });
    return ok ? g_done_result : -2;
}

extern "C" {

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_DosActivity_nativeKey(JNIEnv *env, jclass cls, jint key, jboolean down)
{
    (void)env;
    (void)cls;
    post_command(CMD_KEY, key, down ? 1 : 0, false);
}

/** 1 pausa, 2 seguir, 3 guardado rápido, 4 carga rápida, 5 salir. Devuelve 0 si fue bien. */
JNIEXPORT jint JNICALL Java_com_kbretro_duke3d_DosActivity_nativeCommand(JNIEnv *env, jclass cls, jint cmd)
{
    (void)env;
    (void)cls;
    return post_command(cmd, 0, 0, cmd == CMD_SAVE || cmd == CMD_LOAD);
}

}  // extern "C"

// ---- Ventana y sonido ----

static void open_audio(int rate)
{
    if (g_audio) SDL_CloseAudioDevice(g_audio);
    SDL_AudioSpec want, have;
    SDL_zero(want);
    want.freq = rate;
    want.format = AUDIO_S16SYS;
    want.channels = 2;
    want.samples = 1024;
    g_audio = SDL_OpenAudioDevice(NULL, 0, &want, &have, 0);
    g_audio_rate = rate;
    if (!g_audio) LOGE("Sin sonido: %s", SDL_GetError());
    else SDL_PauseAudioDevice(g_audio, 0);
}

static bool open_window(void)
{
    android_postfx_attributes();
    g_window = SDL_CreateWindow("", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED, 0, 0,
                                SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN_DESKTOP);
    if (!g_window)
    {
        LOGE("Sin ventana: %s", SDL_GetError());
        return false;
    }
    if (!android_postfx_init(g_window))
    {
        LOGI("Sin OpenGL ES: se presenta con SDL_Renderer");
        g_renderer = SDL_CreateRenderer(g_window, -1, 0);
    }
    return true;
}

// ---- Programa ----

extern "C" int SDL_main(int argc, char *argv[])
{
    if (argc < 4)
    {
        LOGE("Faltan argumentos");
        return 1;
    }
    const char *exe = argv[1];
    g_save_dir = argv[2];
    g_state_path = argv[3];
    LOGI("Juego: %s", exe);

    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO) != 0)
    {
        LOGE("SDL_Init: %s", SDL_GetError());
        return 1;
    }
    if (!open_window()) return 1;

    retro_set_environment(environment);
    retro_set_video_refresh(video_refresh);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();

    retro_game_info game;
    SDL_zero(game);
    game.path = exe;
    if (!retro_load_game(&game))
    {
        LOGE("DOSBox Pure no pudo abrir %s", exe);
        retro_deinit();
        return 1;
    }
    retro_get_system_av_info(&g_av);
    LOGI("Vídeo %ux%u a %.2f Hz, sonido a %.0f Hz", g_av.geometry.base_width, g_av.geometry.base_height,
         g_av.timing.fps, g_av.timing.sample_rate);
    open_audio(g_av.timing.sample_rate > 0 ? (int)g_av.timing.sample_rate : 48000);

    Uint64 freq = SDL_GetPerformanceFrequency();
    Uint64 next = SDL_GetPerformanceCounter();

    while (!g_quit)
    {
        SDL_Event ev;
        while (SDL_PollEvent(&ev))  // en segundo plano SDL se queda aquí esperando
        {
            if (ev.type == SDL_QUIT) g_quit = true;
        }
        process_commands();
        if (g_quit) break;
        if (g_paused)
        {
            SDL_Delay(16);
            next = SDL_GetPerformanceCounter();
            continue;
        }

        if (g_av.timing.sample_rate > 0 && (int)g_av.timing.sample_rate != g_audio_rate)
            open_audio((int)g_av.timing.sample_rate);

        retro_run();

        // Ritmo: el del sonido (unos 60 ms en cola); sin sonido, el del reloj.
        double fps = g_av.timing.fps > 1 ? g_av.timing.fps : 60.0;
        if (g_audio)
        {
            Uint32 limit = (Uint32)(g_audio_rate * 4 * 0.06);
            if (SDL_GetQueuedAudioSize(g_audio) > limit * 4) SDL_ClearQueuedAudio(g_audio);  // muy atrasado
            while (!g_quit && SDL_GetQueuedAudioSize(g_audio) > limit) SDL_Delay(1);
        }
        else
        {
            next += (Uint64)(freq / fps);
            Uint64 now = SDL_GetPerformanceCounter();
            if (next > now) SDL_Delay((Uint32)((next - now) * 1000 / freq));
            else next = now;
        }
    }

    LOGI("Saliendo");
    release_all_keys();
    retro_unload_game();  // aquí DOSBox Pure guarda los archivos que ha escrito el juego
    retro_deinit();
    if (g_audio) SDL_CloseAudioDevice(g_audio);
    if (android_postfx_active()) android_postfx_shutdown();
    SDL_Quit();
    return 0;
}
