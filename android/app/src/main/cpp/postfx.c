// Duke3D Android Edition: mejoras gráficas en la GPU (OpenGL ES 2).
//
// El juego pinta con su renderizador por software (CPU) en una imagen de la resolución interna.
// Aquí se sube esa imagen a la GPU y se pasa por unos shaders antes de enseñarla:
//   1. FXAA (opcional), a la resolución interna.
//   2. Escalado a la pantalla: píxel, nítido (sharp bilinear), suave (bilineal) o FSR 1.0 (EASU).
//   3. Nitidez RCAS de FSR (opcional; con FSR siempre).
//   4. Efectos finales: líneas de escaneo / CRT y color vivo.
// Los ajustes llegan desde Java (GameActivity.nativePostFx) y se leen en cada fotograma.

#include <jni.h>
#include <math.h>
#include <string.h>
#include <android/log.h>
#include <GLES2/gl2.h>

#include "SDL.h"

#define LOG_TAG "Duke3D"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- Ajustes (desde Java) ----
enum { SCALE_PIXEL = 0, SCALE_SHARP = 1, SCALE_SMOOTH = 2, SCALE_FSR = 3 };

static volatile int pfx_scaler = SCALE_SHARP;
static volatile int pfx_fxaa = 0;
static volatile int pfx_sharp = 0;   // 0 no, 1 baja, 2 media, 3 alta
static volatile int pfx_crt = 0;     // 0 no, 1 líneas, 2 CRT
static volatile int pfx_color = 0;   // 0 normal, 1 vivo

JNIEXPORT void JNICALL Java_com_kbretro_duke3d_GameActivity_nativePostFx(JNIEnv *env, jclass cls, jint scaler, jint fxaa,
                                                                         jint sharp, jint crt, jint color)
{
    (void)env;
    (void)cls;
    pfx_scaler = scaler;
    pfx_fxaa = fxaa;
    pfx_sharp = sharp;
    pfx_crt = crt;
    pfx_color = color;
}

// ---- Shaders ----

static const char *VS =
    "attribute vec2 a_pos;\n"
    "uniform float u_flip;\n"
    "varying vec2 v_tex;\n"
    "void main() {\n"
    "  v_tex = a_pos * 0.5 + 0.5;\n"
    "  if (u_flip > 0.5) v_tex.y = 1.0 - v_tex.y;\n"
    "  gl_Position = vec4(a_pos, 0.0, 1.0);\n"
    "}\n";

// Cabecera común: lectura de la imagen (la del juego viene en BGRA) y efectos finales.
static const char *FS_HEAD =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "varying vec2 v_tex;\n"
    "uniform sampler2D u_tex;\n"
    "uniform vec2 u_in;\n"
    "uniform vec2 u_out;\n"
    "uniform float u_bgra;\n"
    "uniform float u_scan;\n"
    "uniform float u_vivid;\n"
    "vec3 fetch(vec2 uv) { vec4 c = texture2D(u_tex, uv); return u_bgra > 0.5 ? c.bgr : c.rgb; }\n"
    "vec3 post(vec3 c) {\n"
    "  if (u_vivid > 0.5) {\n"
    "    float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
    "    c = clamp((mix(vec3(l), c, 1.3) - 0.5) * 1.06 + 0.5, 0.0, 1.0);\n"
    "  }\n"
    "  if (u_scan > 0.5) {\n"
    "    float y = v_tex.y * 240.0;\n"
    "    c *= 1.0 - 0.35 * (0.5 - 0.5 * cos(6.2831853 * y));\n"
    "    if (u_scan > 1.5) {\n"
    "      float m = mod(floor(gl_FragCoord.x), 3.0);\n"
    "      vec3 mask = m < 0.5 ? vec3(1.0, 0.75, 0.75) : (m < 1.5 ? vec3(0.75, 1.0, 0.75) : vec3(0.75, 0.75, 1.0));\n"
    "      c *= mask;\n"
    "      vec2 d = v_tex - 0.5;\n"
    "      c *= 1.0 - dot(d, d) * 0.6;\n"
    "    }\n"
    "    c = min(c * (u_scan > 1.5 ? 1.35 : 1.15), 1.0);\n"
    "  }\n"
    "  return c;\n"
    "}\n";

// Escalado sencillo: 0 píxel, 1 nítido (sharp bilinear), 2 suave.
static const char *FS_SCALE =
    "uniform float u_mode;\n"
    "void main() {\n"
    "  vec2 uv = v_tex;\n"
    "  vec2 texel = v_tex * u_in;\n"
    "  if (u_mode < 0.5) {\n"
    "    uv = (floor(texel) + 0.5) / u_in;\n"
    "  } else if (u_mode < 1.5) {\n"
    "    vec2 prescale = max(ceil(u_out / u_in - 0.01), 1.0);\n"
    "    vec2 s = fract(texel);\n"
    "    vec2 range = 0.5 - 0.5 / prescale;\n"
    "    vec2 cd = s - 0.5;\n"
    "    vec2 f = (cd - clamp(cd, -range, range)) * prescale + 0.5;\n"
    "    uv = (floor(texel) + f) / u_in;\n"
    "  }\n"
    "  gl_FragColor = vec4(post(fetch(uv)), 1.0);\n"
    "}\n";

// FXAA (versión clásica de Timothy Lottes, simplificada).
static const char *FS_FXAA =
    "void main() {\n"
    "  vec2 rcp = 1.0 / u_in;\n"
    "  vec3 nw = fetch(v_tex + vec2(-1.0, -1.0) * rcp);\n"
    "  vec3 ne = fetch(v_tex + vec2(1.0, -1.0) * rcp);\n"
    "  vec3 sw = fetch(v_tex + vec2(-1.0, 1.0) * rcp);\n"
    "  vec3 se = fetch(v_tex + vec2(1.0, 1.0) * rcp);\n"
    "  vec3 m = fetch(v_tex);\n"
    "  vec3 L = vec3(0.299, 0.587, 0.114);\n"
    "  float lnw = dot(nw, L), lne = dot(ne, L), lsw = dot(sw, L), lse = dot(se, L), lm = dot(m, L);\n"
    "  float lmin = min(lm, min(min(lnw, lne), min(lsw, lse)));\n"
    "  float lmax = max(lm, max(max(lnw, lne), max(lsw, lse)));\n"
    "  vec2 dir = vec2(-((lnw + lne) - (lsw + lse)), (lnw + lsw) - (lne + lse));\n"
    "  float red = max((lnw + lne + lsw + lse) * 0.03125, 1.0 / 128.0);\n"
    "  float rmin = 1.0 / (min(abs(dir.x), abs(dir.y)) + red);\n"
    "  dir = clamp(dir * rmin, -8.0, 8.0) * rcp;\n"
    "  vec3 a = 0.5 * (fetch(v_tex + dir * (1.0 / 3.0 - 0.5)) + fetch(v_tex + dir * (2.0 / 3.0 - 0.5)));\n"
    "  vec3 b = a * 0.5 + 0.25 * (fetch(v_tex - dir * 0.5) + fetch(v_tex + dir * 0.5));\n"
    "  float lb = dot(b, L);\n"
    "  gl_FragColor = vec4((lb < lmin || lb > lmax) ? a : b, 1.0);\n"
    "}\n";

// AMD FidelityFX Super Resolution 1.0: EASU (escalado que respeta los bordes).
static const char *FS_EASU =
    "vec3 tap(vec2 fp, vec2 o) { return fetch((fp + o + 0.5) / u_in); }\n"
    "float luma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }\n"
    "void setF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {\n"
    "  float lenX = max(abs(lD - lC), abs(lC - lB));\n"
    "  float dirX = lD - lB;\n"
    "  dir.x += dirX * w;\n"
    "  lenX = clamp(abs(dirX) / max(lenX, 1e-5), 0.0, 1.0);\n"
    "  len += lenX * lenX * w;\n"
    "  float lenY = max(abs(lE - lC), abs(lC - lA));\n"
    "  float dirY = lE - lA;\n"
    "  dir.y += dirY * w;\n"
    "  lenY = clamp(abs(dirY) / max(lenY, 1e-5), 0.0, 1.0);\n"
    "  len += lenY * lenY * w;\n"
    "}\n"
    "void tapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n"
    "  vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * len;\n"
    "  float d2 = min(dot(v, v), clp);\n"
    "  float wB = 0.4 * d2 - 1.0;\n"
    "  float wA = lob * d2 - 1.0;\n"
    "  wB *= wB;\n"
    "  wA *= wA;\n"
    "  wB = 1.5625 * wB - 0.5625;\n"
    "  float w = wB * wA;\n"
    "  aW += w;\n"
    "  aC += c * w;\n"
    "}\n"
    "void main() {\n"
    "  vec2 pp = v_tex * u_in - 0.5;\n"
    "  vec2 fp = floor(pp);\n"
    "  pp -= fp;\n"
    "  vec3 b = tap(fp, vec2(0.0, -1.0)), c = tap(fp, vec2(1.0, -1.0));\n"
    "  vec3 e = tap(fp, vec2(-1.0, 0.0)), f = tap(fp, vec2(0.0, 0.0)), g = tap(fp, vec2(1.0, 0.0)), h = tap(fp, vec2(2.0, 0.0));\n"
    "  vec3 i = tap(fp, vec2(-1.0, 1.0)), j = tap(fp, vec2(0.0, 1.0)), k = tap(fp, vec2(1.0, 1.0)), l = tap(fp, vec2(2.0, 1.0));\n"
    "  vec3 n = tap(fp, vec2(0.0, 2.0)), o = tap(fp, vec2(1.0, 2.0));\n"
    "  float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);\n"
    "  float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);\n"
    "  vec2 dir = vec2(0.0);\n"
    "  float len = 0.0;\n"
    "  setF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n"
    "  setF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n"
    "  setF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n"
    "  setF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n"
    "  float dirR = dot(dir, dir);\n"
    "  bool zro = dirR < 1.0 / 32768.0;\n"
    "  dirR = zro ? 1.0 : inversesqrt(dirR);\n"
    "  dir.x = zro ? 1.0 : dir.x;\n"
    "  dir *= dirR;\n"
    "  len = len * 0.5;\n"
    "  len *= len;\n"
    "  float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));\n"
    "  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n"
    "  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n"
    "  float clp = 1.0 / lob;\n"
    "  vec3 aC = vec3(0.0);\n"
    "  float aW = 0.0;\n"
    "  tapF(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);\n"
    "  tapF(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);\n"
    "  tapF(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);\n"
    "  tapF(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);\n"
    "  tapF(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);\n"
    "  tapF(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);\n"
    "  tapF(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);\n"
    "  tapF(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);\n"
    "  tapF(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);\n"
    "  tapF(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);\n"
    "  tapF(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);\n"
    "  tapF(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);\n"
    "  vec3 mn = min(min(f, g), min(j, k));\n"
    "  vec3 mx = max(max(f, g), max(j, k));\n"
    "  vec3 col = min(mx, max(mn, aC / max(aW, 1e-5)));\n"
    "  gl_FragColor = vec4(post(col), 1.0);\n"
    "}\n";

// FSR 1.0: RCAS (nitidez adaptativa).
static const char *FS_RCAS =
    "uniform float u_sharp;\n"
    "void main() {\n"
    "  vec2 r = 1.0 / u_in;\n"
    "  vec3 b = fetch(v_tex + vec2(0.0, -r.y));\n"
    "  vec3 d = fetch(v_tex + vec2(-r.x, 0.0));\n"
    "  vec3 e = fetch(v_tex);\n"
    "  vec3 f = fetch(v_tex + vec2(r.x, 0.0));\n"
    "  vec3 h = fetch(v_tex + vec2(0.0, r.y));\n"
    "  vec3 mn4 = min(min(b, d), min(f, h));\n"
    "  vec3 mx4 = max(max(b, d), max(f, h));\n"
    "  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, 1e-5);\n"
    "  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, -1e-5);\n"
    "  vec3 lobeRGB = max(-hitMin, hitMax);\n"
    "  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * u_sharp;\n"
    "  vec3 col = (lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0);\n"
    "  gl_FragColor = vec4(post(clamp(col, 0.0, 1.0)), 1.0);\n"
    "}\n";

// ---- Estado de GL ----

typedef struct
{
    GLuint prog;
    GLint u_tex, u_in, u_out, u_bgra, u_scan, u_vivid, u_flip, u_mode, u_sharp;
} program_t;

static SDL_Window *pfx_window;
static SDL_GLContext pfx_context;
static program_t p_scale, p_fxaa, p_easu, p_rcas;
static GLuint vbo, src_tex;
static int src_w, src_h;
static GLuint fbo_a, tex_a;  // a la resolución interna (FXAA)
static int a_w, a_h;
static GLuint fbo_b, tex_b;  // a la resolución de salida (antes de RCAS)
static int b_w, b_h;
static int pfx_ok;

static GLuint compile(GLenum type, const char *head, const char *body)
{
    GLuint s = glCreateShader(type);
    const char *src[2] = { head, body };
    glShaderSource(s, head ? 2 : 1, head ? src : &body, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok)
    {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGE("postfx: error en shader: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static int link_program(program_t *p, const char *fs)
{
    memset(p, 0, sizeof(*p));
    GLuint v = compile(GL_VERTEX_SHADER, NULL, VS);
    GLuint f = compile(GL_FRAGMENT_SHADER, FS_HEAD, fs);
    if (!v || !f)
        return 0;
    p->prog = glCreateProgram();
    glAttachShader(p->prog, v);
    glAttachShader(p->prog, f);
    glBindAttribLocation(p->prog, 0, "a_pos");
    glLinkProgram(p->prog);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p->prog, GL_LINK_STATUS, &ok);
    if (!ok)
    {
        char log[1024];
        glGetProgramInfoLog(p->prog, sizeof(log), NULL, log);
        LOGE("postfx: error al enlazar: %s", log);
        return 0;
    }
    p->u_tex = glGetUniformLocation(p->prog, "u_tex");
    p->u_in = glGetUniformLocation(p->prog, "u_in");
    p->u_out = glGetUniformLocation(p->prog, "u_out");
    p->u_bgra = glGetUniformLocation(p->prog, "u_bgra");
    p->u_scan = glGetUniformLocation(p->prog, "u_scan");
    p->u_vivid = glGetUniformLocation(p->prog, "u_vivid");
    p->u_flip = glGetUniformLocation(p->prog, "u_flip");
    p->u_mode = glGetUniformLocation(p->prog, "u_mode");
    p->u_sharp = glGetUniformLocation(p->prog, "u_sharp");
    return 1;
}

static GLuint make_texture(int w, int h)
{
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    return t;
}

static void make_target(GLuint *fbo, GLuint *tex, int *cw, int *ch, int w, int h)
{
    if (*fbo && *cw == w && *ch == h)
        return;
    if (*fbo)
    {
        glDeleteFramebuffers(1, fbo);
        glDeleteTextures(1, tex);
    }
    *tex = make_texture(w, h);
    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        LOGE("postfx: framebuffer incompleto (%dx%d)", w, h);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    *cw = w;
    *ch = h;
}

static void release_targets(void)
{
    if (fbo_a) glDeleteFramebuffers(1, &fbo_a);
    if (tex_a) glDeleteTextures(1, &tex_a);
    if (fbo_b) glDeleteFramebuffers(1, &fbo_b);
    if (tex_b) glDeleteTextures(1, &tex_b);
    if (src_tex) glDeleteTextures(1, &src_tex);
    fbo_a = tex_a = fbo_b = tex_b = src_tex = 0;
    a_w = a_h = b_w = b_h = src_w = src_h = 0;
}

/** Antes de SDL_CreateWindow: la ventana tiene que admitir OpenGL ES 2. */
void android_postfx_attributes(void)
{
    SDL_GL_ResetAttributes();
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 2);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 0);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 0);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
}

/** Crea el contexto y los shaders. Devuelve 0 si no se puede (se usa el método de siempre). */
int android_postfx_init(SDL_Window *window)
{
    pfx_ok = 0;
    pfx_window = window;
    pfx_context = SDL_GL_CreateContext(window);
    if (!pfx_context)
    {
        LOGE("postfx: sin contexto GLES: %s", SDL_GetError());
        return 0;
    }
    SDL_GL_MakeCurrent(window, pfx_context);
    SDL_GL_SetSwapInterval(0);

    if (!link_program(&p_scale, FS_SCALE) || !link_program(&p_fxaa, FS_FXAA) || !link_program(&p_easu, FS_EASU)
        || !link_program(&p_rcas, FS_RCAS))
    {
        SDL_GL_DeleteContext(pfx_context);
        pfx_context = NULL;
        return 0;
    }

    static const GLfloat quad[] = { -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f };
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    LOGI("postfx: GLES listo (%s)", (const char *)glGetString(GL_RENDERER));
    pfx_ok = 1;
    return 1;
}

int android_postfx_active(void)
{
    return pfx_ok;
}

void android_postfx_shutdown(void)
{
    if (pfx_context)
    {
        SDL_GL_MakeCurrent(pfx_window, pfx_context);
        release_targets();
        if (vbo) glDeleteBuffers(1, &vbo);
        vbo = 0;
        if (p_scale.prog) glDeleteProgram(p_scale.prog);
        if (p_fxaa.prog) glDeleteProgram(p_fxaa.prog);
        if (p_easu.prog) glDeleteProgram(p_easu.prog);
        if (p_rcas.prog) glDeleteProgram(p_rcas.prog);
        SDL_GL_DeleteContext(pfx_context);
    }
    pfx_context = NULL;
    pfx_window = NULL;
    pfx_ok = 0;
}

static void draw(const program_t *p, GLuint tex, int in_w, int in_h, int out_w, int out_h, int raw, int final_pass)
{
    glUseProgram(p->prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(p->u_tex, 0);
    glUniform2f(p->u_in, (float)in_w, (float)in_h);
    glUniform2f(p->u_out, (float)out_w, (float)out_h);
    glUniform1f(p->u_bgra, raw ? 1.f : 0.f);
    glUniform1f(p->u_flip, raw ? 1.f : 0.f);
    glUniform1f(p->u_scan, final_pass ? (float)pfx_crt : 0.f);
    glUniform1f(p->u_vivid, final_pass ? (float)pfx_color : 0.f);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

/** Enseña un fotograma del juego (ARGB8888, w x h) con las mejoras elegidas. */
void android_postfx_present(const void *pixels, int w, int h, int pitch)
{
    if (!pfx_ok)
        return;

    SDL_GL_MakeCurrent(pfx_window, pfx_context);

    // La imagen del juego, a la GPU.
    if (!src_tex || src_w != w || src_h != h)
    {
        if (src_tex) glDeleteTextures(1, &src_tex);
        src_tex = make_texture(w, h);
        src_w = w;
        src_h = h;
    }
    glBindTexture(GL_TEXTURE_2D, src_tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (pitch == w * 4)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    else
        for (int y = 0; y < h; y++)
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, w, 1, GL_RGBA, GL_UNSIGNED_BYTE, (const char *)pixels + y * pitch);

    // Rectángulo de salida: toda la pantalla, con la proporción de la imagen.
    int ww = 0, wh = 0;
    SDL_GL_GetDrawableSize(pfx_window, &ww, &wh);
    if (ww <= 0 || wh <= 0)
        return;
    int vw = ww, vh = (int)((long long)ww * h / w);
    if (vh > wh)
    {
        vh = wh;
        vw = (int)((long long)wh * w / h);
    }
    int vx = (ww - vw) / 2, vy = (wh - vh) / 2;

    int scaler = pfx_scaler, sharp = pfx_sharp;
    if (scaler == SCALE_FSR && sharp == 0)
        sharp = 2;  // FSR va siempre con su nitidez
    static const float stops[4] = { 0.f, 1.0f, 0.5f, 0.2f };
    float sharpness = sharp > 0 && sharp < 4 ? exp2f(-stops[sharp]) : 0.f;

    GLuint in_tex = src_tex;
    int raw = 1;

    // 1. FXAA a la resolución interna.
    if (pfx_fxaa)
    {
        make_target(&fbo_a, &tex_a, &a_w, &a_h, w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_a);
        glViewport(0, 0, w, h);
        draw(&p_fxaa, in_tex, w, h, w, h, raw, 0);
        in_tex = tex_a;
        raw = 0;
    }

    // 2. Escalado (a la pantalla o, si luego va la nitidez, a una imagen intermedia).
    const program_t *sp = scaler == SCALE_FSR ? &p_easu : &p_scale;
    int to_screen = sharpness <= 0.f;
    if (to_screen)
    {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, ww, wh);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport(vx, vy, vw, vh);
    }
    else
    {
        make_target(&fbo_b, &tex_b, &b_w, &b_h, vw, vh);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_b);
        glViewport(0, 0, vw, vh);
    }
    glUseProgram(sp->prog);
    if (sp->u_mode >= 0)
        glUniform1f(sp->u_mode, (float)(scaler == SCALE_FSR ? 2 : scaler));
    draw(sp, in_tex, w, h, vw, vh, raw, to_screen);

    // 3. Nitidez RCAS (FSR) a la resolución de salida.
    if (!to_screen)
    {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, ww, wh);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport(vx, vy, vw, vh);
        glUseProgram(p_rcas.prog);
        glUniform1f(p_rcas.u_sharp, sharpness);
        draw(&p_rcas, tex_b, vw, vh, vw, vh, 0, 1);
    }

    SDL_GL_SwapWindow(pfx_window);
}

// ---- Modo Polymost (OpenGL de escritorio a través de gl4es) ----
// libgl4es.so se carga solo en este modo, con el contexto GLES 2 de SDL ya activo.

#include <dlfcn.h>
#include <stdint.h>

static void *gl4es_lib;
static void *(*gl4es_getproc)(const char *);
static void (*gl4es_pre)(void);
static void (*gl4es_post)(void);

static void *sdl_getproc(const char *name)
{
    return SDL_GL_GetProcAddress(name);
}

static void main_fb_size(int *w, int *h)
{
    SDL_Window *win = SDL_GL_GetCurrentWindow();
    if (win)
        SDL_GL_GetDrawableSize(win, w, h);
}

int android_gl4es_load(void)
{
    if (gl4es_getproc)
        return 1;
    gl4es_lib = dlopen("libgl4es.so", RTLD_NOW | RTLD_LOCAL);
    if (!gl4es_lib)
    {
        LOGE("gl4es: no se pudo cargar: %s", dlerror());
        return 0;
    }
    void (*set_proc)(void *(*)(const char *)) = (void (*)(void *(*)(const char *)))dlsym(gl4es_lib, "set_getprocaddress");
    void (*set_fb)(void (*)(int *, int *)) = (void (*)(void (*)(int *, int *)))dlsym(gl4es_lib, "set_getmainfbsize");
    void (*init)(void) = (void (*)(void))dlsym(gl4es_lib, "initialize_gl4es");
    gl4es_getproc = (void *(*)(const char *))dlsym(gl4es_lib, "gl4es_GetProcAddress");
    gl4es_pre = (void (*)(void))dlsym(gl4es_lib, "gl4es_pre_swap");
    gl4es_post = (void (*)(void))dlsym(gl4es_lib, "gl4es_post_swap");
    if (!init || !gl4es_getproc)
    {
        LOGE("gl4es: faltan funciones");
        gl4es_getproc = NULL;
        return 0;
    }
    if (set_proc)
        set_proc(sdl_getproc);
    if (set_fb)
        set_fb(main_fb_size);
    init();
    LOGI("gl4es listo");
    return 1;
}

// Las funciones que gl4es no tiene (OpenGL 3+, como glIsSync) apuntan aquí en vez de a NULL:
// no hacen nada y devuelven 0. Polymost solo las usa si la extensión existe, salvo algún caso
// suelto (glIsSync al iniciar) que con esto deja de cerrar el juego.
static uintptr_t gl_missing_stub(void)
{
    return 0;
}

void *android_gl4es_getproc(const char *name)
{
    void *p = gl4es_getproc ? gl4es_getproc(name) : NULL;
    if (!p)
    {
        LOGI("gl4es: sin %s (se ignora)", name);
        p = (void *)gl_missing_stub;
    }
    return p;
}

void android_gl4es_pre_swap(void)
{
    if (gl4es_pre)
        gl4es_pre();
}

void android_gl4es_post_swap(void)
{
    if (gl4es_post)
        gl4es_post();
}

// ---- Rendimiento: ADPF (Android Dynamic Performance Framework) ----
// Se le dice a Android cuánto debe tardar cada fotograma y cuánto tarda de verdad; así sube
// la frecuencia de la CPU a tiempo y no hay bajones (Android 13+, se busca en libandroid).

#include <time.h>
#include <unistd.h>

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

static APerformanceHintManager *(*ph_getManager)(void);
static APerformanceHintSession *(*ph_createSession)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
static int (*ph_report)(APerformanceHintSession *, int64_t);
static int (*ph_updateTarget)(APerformanceHintSession *, int64_t);
static APerformanceHintSession *ph_session;
static int ph_tried;
static int64_t ph_last, ph_target;

static int64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/** Una vez por fotograma, en el hilo del juego. @param fps el objetivo (0 = sin límite). */
void android_perf_frame(int fps)
{
    int64_t const t = now_ns();
    int64_t const target = 1000000000LL / (fps > 0 ? fps : 120);

    if (!ph_tried)
    {
        ph_tried = 1;
        void *lib = dlopen("libandroid.so", RTLD_NOW);
        if (lib)
        {
            ph_getManager = (APerformanceHintManager * (*)(void)) dlsym(lib, "APerformanceHint_getManager");
            ph_createSession = (APerformanceHintSession * (*)(APerformanceHintManager *, const int32_t *, size_t, int64_t))
                dlsym(lib, "APerformanceHint_createSession");
            ph_report = (int (*)(APerformanceHintSession *, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
            ph_updateTarget = (int (*)(APerformanceHintSession *, int64_t))dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
        }
        if (ph_getManager && ph_createSession && ph_report)
        {
            APerformanceHintManager *m = ph_getManager();
            int32_t tid = gettid();
            if (m)
                ph_session = ph_createSession(m, &tid, 1, target);
            ph_target = target;
        }
        LOGI("ADPF: %s", ph_session ? "activo" : "no disponible");
        ph_last = t;
        return;
    }

    if (!ph_session)
        return;

    if (target != ph_target && ph_updateTarget)
    {
        ph_updateTarget(ph_session, target);
        ph_target = target;
    }

    int64_t const work = t - ph_last;
    ph_last = t;
    if (work > 0 && work < 1000000000LL)
        ph_report(ph_session, work);
}
