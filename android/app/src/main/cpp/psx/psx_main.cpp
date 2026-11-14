// Duke3D Android Edition (HD): juegos de PlayStation de los extras (Duke Nukem: Time to Kill).
//
// "Frontend" libretro mínimo para el núcleo SwanStation (DuckStation), que va en su propia
// biblioteca (libpsxcore.so, compilada con su Android.mk) y se abre con dlopen. Como el de los
// juegos de MS-DOS (dos/dos_main.cpp): ventana con OpenGL ES y los filtros de postfx.c, sonido
// con la cola de audio de SDL, guardado y carga rápidos con los estados de libretro.
//
// Argumentos (PsxActivity.getArguments): disco (.cue/.chd/.pbp...), carpeta del sistema (la
// BIOS), carpeta de partidas (tarjetas de memoria), archivo del guardado rápido y las opciones
// iniciales como "clave=valor" (panorámico, resolución interna...).
//
// Desde Java llegan los botones del mando (nativeButton), los sticks (nativeAnalog), las
// opciones que se cambian en el menú rápido (nativeOption) y las órdenes (nativeCommand).

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "SDL.h"
#include <GLES3/gl3.h>
#include "libretro.h"

#define LOG_TAG "Duke3D-PSX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
void android_postfx_attributes(void);
int android_postfx_init(SDL_Window *window);
int android_postfx_active(void);
void android_postfx_shutdown(void);
void android_postfx_present(const void *pixels, int w, int h, int pitch);
void android_postfx_set_aspect(float aspect);
void android_postfx_present_fbo(unsigned fbo, int w, int h, int bottom_left);
}

// ---- El núcleo (libpsxcore.so) ----

static struct Core {
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    bool (*load_game)(const retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
    void (*get_system_av_info)(retro_system_av_info *);
    void (*set_controller_port_device)(unsigned, unsigned);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} core;

static bool load_core(void)
{
    void *h = dlopen("libpsxcore.so", RTLD_NOW | RTLD_LOCAL);
    if (!h)
    {
        LOGE("No se pudo abrir el núcleo: %s", dlerror());
        return false;
    }
#define SYM(field, name)                                                  \
    core.field = (decltype(core.field))dlsym(h, name);                     \
    if (!core.field) { LOGE("Falta %s en el núcleo", name); return false; }
    SYM(set_environment, "retro_set_environment");
    SYM(set_video_refresh, "retro_set_video_refresh");
    SYM(set_audio_sample, "retro_set_audio_sample");
    SYM(set_audio_sample_batch, "retro_set_audio_sample_batch");
    SYM(set_input_poll, "retro_set_input_poll");
    SYM(set_input_state, "retro_set_input_state");
    SYM(init, "retro_init");
    SYM(deinit, "retro_deinit");
    SYM(load_game, "retro_load_game");
    SYM(unload_game, "retro_unload_game");
    SYM(run, "retro_run");
    SYM(get_system_av_info, "retro_get_system_av_info");
    SYM(set_controller_port_device, "retro_set_controller_port_device");
    SYM(serialize_size, "retro_serialize_size");
    SYM(serialize, "retro_serialize");
    SYM(unserialize, "retro_unserialize");
    SYM(get_memory_data, "retro_get_memory_data");
    SYM(get_memory_size, "retro_get_memory_size");
#undef SYM
    return true;
}

// ---- Estado ----

static std::string g_system_dir, g_save_dir, g_state_path;
static retro_system_av_info g_av;
static volatile bool g_quit;
static bool g_paused;

static SDL_Window *g_window;
static SDL_Renderer *g_renderer;  // solo si no hay OpenGL ES
static SDL_Texture *g_texture;
static int g_tex_w, g_tex_h;
static SDL_AudioDeviceID g_audio;
static int g_audio_rate;

static void queue_silence(Uint32 bytes);

static std::vector<uint32_t> g_frame;
static int g_frame_w, g_frame_h;

// Mando: botones de la RetroPad (bit = RETRO_DEVICE_ID_JOYPAD_*) y los dos sticks.
static std::atomic<uint32_t> g_buttons{0};
static std::atomic<int> g_analog[2][2];

// Opciones del núcleo: las de serie, las de los argumentos y las del menú rápido.
static std::mutex g_opt_mutex;
static std::map<std::string, std::string> g_options;
static std::atomic<bool> g_options_changed{false};
static std::string g_opt_value;  // la que se devuelve a GET_VARIABLE (vive hasta la siguiente)

// El núcleo es SwanStation (la versión libretro de DuckStation): sus opciones van con este prefijo.
#define OPT "swanstation_"

static void set_default_options(void)
{
    static const char *defs[][2] = {
        {OPT "GPU_Renderer", "OpenGL"},               // con la GPU (OpenGL ES 3)
        {OPT "GPU_ResolutionScale", "3"},
        {OPT "GPU_TrueColor", "true"},                // color de 32 bits
        {OPT "GPU_ScaledDithering", "true"},
        {OPT "GPU_WidescreenHack", "false"},
        {OPT "Display_AspectRatio", "4:3"},
        {OPT "GPU_PGXPEnable", "false"},
        {OPT "BIOS_PatchFastBoot", "true"},           // sin la animación de la BIOS
        {OPT "Controller1_ForceAnalog", "true"},      // DualShock siempre en analógico
        {OPT "Display_ShowOSDMessages", "false"},
        {OPT "CPU_ExecutionMode", "Recompiler"},
    };
    for (auto &d : defs) g_options[d[0]] = d[1];
}

// Los valores de serie de las opciones del núcleo (los que pondría RetroArch): sin ellos el núcleo
// se quedaba con sus valores internos, que no siempre son los de serie (p. ej. sin recortar los
// bordes negros). Los que ya tenemos (nuestros o de los argumentos) mandan.
static void default_option(const char *key, const char *value)
{
    if (!key || !value) return;
    std::lock_guard<std::mutex> lock(g_opt_mutex);
    g_options.emplace(key, value);
}

static void defaults_v2(const retro_core_options_v2 *o)
{
    if (!o || !o->definitions) return;
    for (const retro_core_option_v2_definition *d = o->definitions; d->key; d++)
        default_option(d->key, d->default_value ? d->default_value : d->values[0].value);
}

static void defaults_v1(const retro_core_option_definition *d)
{
    if (!d) return;
    for (; d->key; d++)
        default_option(d->key, d->default_value ? d->default_value : d->values[0].value);
}

static void defaults_variables(const retro_variable *v)
{
    // "Descripción; valor1|valor2|...": el primero es el de serie.
    if (!v) return;
    for (; v->key; v++)
    {
        const char *semi = v->value ? strchr(v->value, ';') : NULL;
        if (!semi) continue;
        semi++;
        while (*semi == ' ') semi++;
        const char *bar = strchr(semi, '|');
        default_option(v->key, std::string(semi, bar ? (size_t)(bar - semi) : strlen(semi)).c_str());
    }
}

static void parse_option_arg(const char *arg)
{
    const char *eq = strchr(arg, '=');
    if (!eq) return;
    g_options[std::string(arg, eq - arg)] = eq + 1;
}

// ---- Órdenes de Java (hilo de la interfaz) para el hilo del juego ----

enum { CMD_PAUSE = 1, CMD_RESUME = 2, CMD_SAVE = 3, CMD_LOAD = 4, CMD_QUIT = 5 };
struct Command {
    int type;
    int id;  // > 0 si Java espera la respuesta
};
static std::mutex g_mutex;
static std::condition_variable g_done_cv;
static std::deque<Command> g_queue;
static int g_next_id = 1, g_done_id, g_done_result;

// ---- Callbacks de libretro ----

static void core_log(enum retro_log_level level, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(level >= RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, LOG_TAG, fmt, ap);
    va_end(ap);
}

// ---- Render por hardware (OpenGL ES 3): el núcleo dibuja en nuestro framebuffer ----

static retro_hw_render_callback g_hw;
static bool g_hw_on;
static GLuint g_hw_fbo, g_hw_tex, g_hw_depth;
static int g_hw_fbo_w, g_hw_fbo_h;
static int g_last_w, g_last_h;  // el último fotograma (para repetirlo)

static uintptr_t hw_get_framebuffer(void)
{
    return g_hw_fbo;
}

static retro_proc_address_t hw_get_proc_address(const char *sym)
{
    return (retro_proc_address_t)SDL_GL_GetProcAddress(sym);
}

/** El framebuffer del núcleo, del tamaño máximo que puede dar (crece si sube la resolución). */
static void hw_make_framebuffer(int w, int h)
{
    if (!g_hw_on || w <= 0 || h <= 0) return;
    if (g_hw_fbo && w <= g_hw_fbo_w && h <= g_hw_fbo_h) return;
    if (g_hw_fbo) glDeleteFramebuffers(1, &g_hw_fbo);
    if (g_hw_tex) glDeleteTextures(1, &g_hw_tex);
    if (g_hw_depth) glDeleteRenderbuffers(1, &g_hw_depth);
    g_hw_fbo = g_hw_tex = g_hw_depth = 0;
    glGenTextures(1, &g_hw_tex);
    glBindTexture(GL_TEXTURE_2D, g_hw_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &g_hw_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_hw_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_hw_tex, 0);
    if (g_hw.depth)
    {
        glGenRenderbuffers(1, &g_hw_depth);
        glBindRenderbuffer(GL_RENDERBUFFER, g_hw_depth);
        glRenderbufferStorage(GL_RENDERBUFFER, g_hw.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, g_hw.stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, g_hw_depth);
    }
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g_hw_fbo_w = w;
    g_hw_fbo_h = h;
    LOGI("Framebuffer de la GPU: %dx%d (%s)", w, h, st == GL_FRAMEBUFFER_COMPLETE ? "bien" : "INCOMPLETO");
}

static bool environment(unsigned cmd, void *data)
{
    switch (cmd)
    {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((retro_log_callback *)data)->log = core_log;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = g_system_dir.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = g_save_dir.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *(const retro_pixel_format *)data == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        retro_variable *v = (retro_variable *)data;
        if (!v->key) return false;
        std::lock_guard<std::mutex> lock(g_opt_mutex);
        auto it = g_options.find(v->key);
        if (it == g_options.end())
        {
            v->value = NULL;
            return false;
        }
        g_opt_value = it->second;
        v->value = g_opt_value.c_str();
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = g_options_changed.exchange(false);
        return true;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        // OPENGLES_VERSION: así SwanStation prueba primero OpenGL ES (3.x).
        *(unsigned *)data = RETRO_HW_CONTEXT_OPENGLES_VERSION;
        return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        retro_hw_render_callback *cb = (retro_hw_render_callback *)data;
        bool gles3 = cb->context_type == RETRO_HW_CONTEXT_OPENGLES3
                     || (cb->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION && cb->version_major == 3);
        LOGI("El núcleo pide render por hardware: tipo %d, versión %u.%u -> %s", (int)cb->context_type,
             cb->version_major, cb->version_minor, gles3 ? "sí" : "no");
        if (!gles3) return false;
        cb->get_current_framebuffer = hw_get_framebuffer;
        cb->get_proc_address = hw_get_proc_address;
        g_hw = *cb;
        g_hw_on = true;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        g_av = *(const retro_system_av_info *)data;
        hw_make_framebuffer((int)g_av.geometry.max_width, (int)g_av.geometry.max_height);
        LOGI("Nuevo vídeo/sonido: %ux%u, aspecto %.3f, %.3f fps, sonido a %.0f Hz", g_av.geometry.base_width,
             g_av.geometry.base_height, g_av.geometry.aspect_ratio, g_av.timing.fps, g_av.timing.sample_rate);
        return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        g_av.geometry = *(const retro_game_geometry *)data;
        hw_make_framebuffer((int)g_av.geometry.max_width, (int)g_av.geometry.max_height);
        return true;
    case RETRO_ENVIRONMENT_SHUTDOWN:
        LOGI("El juego ha terminado");
        g_quit = true;
        return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE: {
        const retro_message *m = (const retro_message *)data;
        if (m && m->msg) LOGI("%s", m->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
        const retro_message_ext *m = (const retro_message_ext *)data;
        if (m && m->msg) LOGI("%s", m->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *(unsigned *)data = 2;
        return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES:
        defaults_variables((const retro_variable *)data);
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        defaults_v1((const retro_core_option_definition *)data);
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
        if (data) defaults_v1(((const retro_core_options_intl *)data)->us);
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        defaults_v2((const retro_core_options_v2 *)data);
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
        if (data) defaults_v2(((const retro_core_options_v2_intl *)data)->us);
        return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        return true;
    default:
        return false;  // render por hardware, VFS, vibración...: no hacen falta
    }
}

static void present(void)
{
    if (g_frame.empty()) return;
    float aspect = g_av.geometry.aspect_ratio > 0.f ? g_av.geometry.aspect_ratio : 4.f / 3.f;
    if (android_postfx_active())
    {
        android_postfx_set_aspect(aspect);
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
        int vw = ww, vh = (int)(ww / aspect);
        if (vh > wh) { vh = wh; vw = (int)(wh * aspect); }
        SDL_Rect dst = { (ww - vw) / 2, (wh - vh) / 2, vw, vh };
        SDL_SetRenderDrawColor(g_renderer, 0, 0, 0, 255);
        SDL_RenderClear(g_renderer);
        SDL_RenderCopy(g_renderer, g_texture, NULL, &dst);
        SDL_RenderPresent(g_renderer);
    }
}

static void video_refresh(const void *data, unsigned w, unsigned h, size_t pitch)
{
    {
        // Cada cambio de tamaño de la imagen, al registro (vídeos, menús, resolución...).
        static unsigned lw, lh;
        if (data && (w != lw || h != lh))
        {
            LOGI("Imagen %ux%u (aspecto del núcleo %.3f, %s)", w, h, g_av.geometry.aspect_ratio,
                 data == RETRO_HW_FRAME_BUFFER_VALID ? "GPU" : "CPU");
            lw = w;
            lh = h;
        }
    }
    if (g_hw_on && (data == RETRO_HW_FRAME_BUFFER_VALID || (!data && g_last_w > 0)))
    {
        // Dibujado con la GPU en g_hw_fbo (NULL = el mismo fotograma de antes).
        if (data) { g_last_w = (int)w; g_last_h = (int)h; }
        float aspect = g_av.geometry.aspect_ratio > 0.f ? g_av.geometry.aspect_ratio : 4.f / 3.f;
        android_postfx_set_aspect(aspect);
        android_postfx_present_fbo(g_hw_fbo, g_last_w, g_last_h, g_hw.bottom_left_origin ? 1 : 0);
        return;
    }
    if (data && w && h)  // NULL = el mismo fotograma de antes (frame duping)
    {
        if ((int)w != g_frame_w || (int)h != g_frame_h) g_frame.assign((size_t)w * h, 0);
        g_frame_w = w;
        g_frame_h = h;
        for (unsigned y = 0; y < h; y++)
            memcpy(&g_frame[(size_t)y * w], (const char *)data + y * pitch, w * 4);
    }
    present();
}

static void audio_sample(int16_t l, int16_t r)
{
    int16_t s[2] = { l, r };
    if (g_audio && !g_paused) SDL_QueueAudio(g_audio, s, sizeof(s));
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
    if (port != 0) return 0;
    switch (device & RETRO_DEVICE_MASK)
    {
    case RETRO_DEVICE_JOYPAD: {
        uint32_t b = g_buttons.load();
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)b;
        return id < 16 && (b >> id) & 1 ? 1 : 0;
    }
    case RETRO_DEVICE_ANALOG:
        if (index < 2 && id < 2) return (int16_t)g_analog[index][id].load();
        return 0;
    default:
        return 0;
    }
}

// ---- Tarjeta de memoria (las partidas del propio juego) ----
// SwanStation la da como SAVE_RAM: se carga al empezar y se guarda en un .srm junto a las
// demás partidas cuando cambia (cada pocos segundos), en pausa y al salir.

static std::string g_srm_path;
static std::vector<uint8_t> g_srm_last;

static void srm_load(void)
{
    uint8_t *mem = (uint8_t *)core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || !size) return;
    FILE *f = fopen(g_srm_path.c_str(), "rb");
    if (f)
    {
        size_t n = fread(mem, 1, size, f);
        fclose(f);
        LOGI("Tarjeta de memoria cargada: %zu bytes", n);
    }
    g_srm_last.assign(mem, mem + size);
}

static void srm_save(bool force)
{
    uint8_t *mem = (uint8_t *)core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || !size) return;
    if (!force && g_srm_last.size() == size && memcmp(g_srm_last.data(), mem, size) == 0) return;
    std::string tmp = g_srm_path + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    bool ok = fwrite(mem, 1, size, f) == size;
    ok = (fclose(f) == 0) && ok;
    if (ok && rename(tmp.c_str(), g_srm_path.c_str()) == 0)
    {
        g_srm_last.assign(mem, mem + size);
        LOGI("Tarjeta de memoria guardada");
    }
}

// ---- Guardado rápido ----

static int save_state(void)
{
    size_t size = core.serialize_size();
    if (!size) return -1;
    std::vector<uint8_t> buf(size);
    if (!core.serialize(buf.data(), size)) return -1;
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
    if (buf.empty() || !core.unserialize(buf.data(), buf.size())) return -1;
    if (g_audio) SDL_ClearQueuedAudio(g_audio);
    return 0;
}

// ---- Órdenes ----

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
        case CMD_PAUSE:
            g_paused = true;
            srm_save(false);  // por si Android cierra la app en segundo plano
            if (g_audio) { SDL_PauseAudioDevice(g_audio, 1); SDL_ClearQueuedAudio(g_audio); }
            break;
        case CMD_RESUME:
            g_paused = false;
            if (g_audio)
            {
                SDL_ClearQueuedAudio(g_audio);
                queue_silence((Uint32)(g_audio_rate * 4 / 1000) * 80);
                SDL_PauseAudioDevice(g_audio, 0);
            }
            break;
        case CMD_SAVE:
            result = save_state();
            break;
        case CMD_LOAD:
            result = load_state();
            break;
        case CMD_QUIT:
            g_quit = true;
            break;
        }
        finish_command(c, result);
    }
}

static int post_command(int type, bool wait)
{
    std::unique_lock<std::mutex> lock(g_mutex);
    Command c = { type, wait ? g_next_id++ : 0 };
    g_queue.push_back(c);
    if (!wait) return 0;
    bool ok = g_done_cv.wait_for(lock, std::chrono::seconds(4), [&] { return g_done_id == c.id; });
    return ok ? g_done_result : -2;
}

extern "C" {

/** Botón de la RetroPad (RETRO_DEVICE_ID_JOYPAD_*). */
JNIEXPORT void JNICALL Java_com_kbretro_duke3d_PsxActivity_nativeButton(JNIEnv *, jclass, jint id, jboolean down)
{
    if (id < 0 || id >= 16) return;
    if (down) g_buttons.fetch_or(1u << id);
    else g_buttons.fetch_and(~(1u << id));
}

/** Stick 0 (izquierdo) o 1 (derecho), -32767..32767. */
JNIEXPORT void JNICALL Java_com_kbretro_duke3d_PsxActivity_nativeAnalog(JNIEnv *, jclass, jint stick, jint x, jint y)
{
    if (stick < 0 || stick > 1) return;
    g_analog[stick][0] = x;
    g_analog[stick][1] = y;
}

/** Cambia una opción del núcleo en plena partida (el núcleo la lee en el siguiente fotograma). */
JNIEXPORT void JNICALL Java_com_kbretro_duke3d_PsxActivity_nativeOption(JNIEnv *env, jclass, jstring key, jstring value)
{
    const char *k = env->GetStringUTFChars(key, NULL);
    const char *v = env->GetStringUTFChars(value, NULL);
    if (k && v)
    {
        std::lock_guard<std::mutex> lock(g_opt_mutex);
        g_options[k] = v;
        g_options_changed = true;
    }
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

/** 1 pausa, 2 seguir, 3 guardado rápido, 4 carga rápida, 5 salir. Devuelve 0 si fue bien. */
JNIEXPORT jint JNICALL Java_com_kbretro_duke3d_PsxActivity_nativeCommand(JNIEnv *, jclass, jint cmd)
{
    return post_command(cmd, cmd == CMD_SAVE || cmd == CMD_LOAD);
}

}  // extern "C"

// ---- Ventana y sonido ----

/** Silencio en la cola de sonido (colchón al empezar o si se queda sin sonido). */
static void queue_silence(Uint32 bytes)
{
    if (!g_audio) return;
    bytes &= ~3u;
    std::vector<uint8_t> zero(bytes, 0);
    SDL_QueueAudio(g_audio, zero.data(), bytes);
}

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
    else
    {
        LOGI("Sonido: pedido %d Hz, el dispositivo da %d Hz, %d muestras por trozo", rate, have.freq, have.samples);
        queue_silence((Uint32)(rate * 4 / 1000) * 80);
        SDL_PauseAudioDevice(g_audio, 0);
    }
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
    if (argc < 5)
    {
        LOGE("Faltan argumentos");
        return 1;
    }
    const char *disc = argv[1];
    g_system_dir = argv[2];
    g_save_dir = argv[3];
    g_state_path = argv[4];
    set_default_options();
    for (int i = 5; i < argc; i++) parse_option_arg(argv[i]);
    LOGI("Disco: %s (sistema: %s)", disc, g_system_dir.c_str());

    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO) != 0)
    {
        LOGE("SDL_Init: %s", SDL_GetError());
        return 1;
    }
    if (!load_core()) return 1;
    if (!open_window()) return 1;

    core.set_environment(environment);
    core.set_video_refresh(video_refresh);
    core.set_audio_sample(audio_sample);
    core.set_audio_sample_batch(audio_batch);
    core.set_input_poll(input_poll);
    core.set_input_state(input_state);
    core.init();

    retro_game_info game;
    SDL_zero(game);
    game.path = disc;
    if (!core.load_game(&game))
    {
        LOGE("SwanStation no pudo abrir %s", disc);
        core.deinit();
        return 1;
    }
    if (g_hw_on)
    {
        if (!android_postfx_active())
        {
            LOGE("El núcleo quiere la GPU pero no hay contexto OpenGL ES 3");
            core.unload_game();
            core.deinit();
            return 1;
        }
        retro_system_av_info av;
        core.get_system_av_info(&av);
        hw_make_framebuffer((int)av.geometry.max_width, (int)av.geometry.max_height);
        if (g_hw.context_reset) g_hw.context_reset();
        LOGI("Render por hardware (OpenGL ES 3) listo");
    }
    else
        LOGI("Render por software");
    // Mando DualShock (con los dos sticks) en el puerto 1 (SwanStation: subclase 0 de analógico).
    core.set_controller_port_device(0, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 0));
    // La tarjeta de memoria, junto al guardado rápido (mismo nombre, .srm).
    g_srm_path = g_state_path;
    {
        size_t dot = g_srm_path.rfind('.');
        if (dot != std::string::npos && g_srm_path.find('/', dot) == std::string::npos) g_srm_path.resize(dot);
        g_srm_path += ".srm";
    }
    srm_load();
    core.get_system_av_info(&g_av);
    {
        std::lock_guard<std::mutex> lock(g_opt_mutex);
        for (auto &o : g_options) LOGI("Opción %s = %s", o.first.c_str(), o.second.c_str());
    }
    LOGI("Vídeo %ux%u (aspecto %.3f) a %.2f Hz, sonido a %.0f Hz", g_av.geometry.base_width,
         g_av.geometry.base_height, g_av.geometry.aspect_ratio, g_av.timing.fps, g_av.timing.sample_rate);
    open_audio(g_av.timing.sample_rate > 0 ? (int)g_av.timing.sample_rate : 44100);

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

        core.run();
        {
            // Cada 5 s, al registro: fotogramas por segundo de verdad y sonido en cola.
            static Uint64 t0 = 0;
            static int frames = 0;
            Uint64 t = SDL_GetPerformanceCounter();
            if (!t0) t0 = t;
            frames++;
            if (t - t0 >= freq * 5)
            {
                LOGI("Ritmo: %.2f fps (núcleo %.2f), sonido en cola %u ms", frames * (double)freq / (t - t0),
                     g_av.timing.fps, g_audio ? SDL_GetQueuedAudioSize(g_audio) / (Uint32)(g_audio_rate * 4 / 1000) : 0);
                t0 = t;
                frames = 0;
                srm_save(false);  // cada 5 s, si el juego ha guardado algo
            }
        }

        // Ritmo: el reloj de la consola (unos 60 o 50 fotogramas por segundo), no el de la
        // pantalla ni la cola de sonido. El sonido solo corrige un poco: con mucho en cola se
        // espera algo más y, si se queda corto, no se espera. (Solo con la cola de sonido, si el
        // núcleo no la llenaba a tiempo el juego corría sin freno y rascaba.)
        double fps = g_av.timing.fps > 1 ? g_av.timing.fps : 60.0;
        double period = (double)freq / fps;
        // Sonido: un colchón de unos 80 ms. El núcleo dice 50/60 fps pero su sonido va al ritmo
        // de la consola de verdad (p. ej. 49,76): la cola crecía poco a poco hasta vaciarse de
        // golpe (rascada). El ritmo se ajusta muy poco (hasta un 1 %) según la cola, como hace
        // RetroArch: nada que se note en la velocidad.
        if (g_audio)
        {
            Uint32 bytes_per_ms = (Uint32)(g_audio_rate * 4 / 1000);
            Uint32 queued = SDL_GetQueuedAudioSize(g_audio);
            double err = ((double)queued / bytes_per_ms - 80.0) / 80.0;
            if (err > 1) err = 1;
            if (err < -1) err = -1;
            period *= 1.0 + 0.01 * err;
            if (queued > bytes_per_ms * 600)
                SDL_ClearQueuedAudio(g_audio);           // muy atrasado: se vuelve a empezar
            else if (queued < bytes_per_ms * 8)
                queue_silence(bytes_per_ms * 60);        // a punto de quedarse sin sonido
        }
        next += (Uint64)period;
        Uint64 now = SDL_GetPerformanceCounter();
        if (next > now)
        {
            Uint64 wait_ms = (next - now) * 1000 / freq;
            if (wait_ms > 0) SDL_Delay((Uint32)wait_ms);
            while (SDL_GetPerformanceCounter() < next) {}  // el último trocito, exacto
        }
        else if (now - next > freq / 4)
            next = now;  // muy retrasado (pausa, segundo plano): se vuelve a empezar
    }

    LOGI("Saliendo");
    srm_save(false);
    if (g_hw_on && g_hw.context_destroy) g_hw.context_destroy();
    core.unload_game();  // aquí se guarda la tarjeta de memoria
    core.deinit();
    if (g_audio) SDL_CloseAudioDevice(g_audio);
    if (android_postfx_active()) android_postfx_shutdown();
    SDL_Quit();
    return 0;
}
