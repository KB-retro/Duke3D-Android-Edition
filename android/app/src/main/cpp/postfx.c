// Duke3D Android Edition: mejoras gráficas en la GPU (OpenGL ES 2).
//
// El juego pinta con su renderizador por software (CPU) en una imagen de la resolución interna.
// Aquí se sube esa imagen a la GPU y se pasa por unos shaders antes de enseñarla:
//   1. FXAA (opcional), a la resolución interna.
//   2. Resplandor (bloom, opcional): las zonas brillantes, reducidas y desenfocadas.
//   3. Escalado a la pantalla: píxel, nítido (sharp bilinear), suave (bilineal), bicúbico
//      (Catmull-Rom) o FSR 1.0 (EASU).
//   4. Nitidez RCAS de FSR (opcional; con FSR siempre).
//   5. Efectos finales: resplandor, color (vivo, cálido, frío, cine) y líneas / CRT / LCD.
// Los ajustes llegan desde Java (GameActivity.nativePostFx) y se leen en cada fotograma.

#include <jni.h>
#include <math.h>
#include <string.h>
#include <android/log.h>
#ifdef POSTFX_PSX
#include <GLES3/gl3.h>  // PlayStation: la imagen puede venir ya en la GPU (render por hardware)
#else
#include <GLES2/gl2.h>
#endif

#include "SDL.h"

#define LOG_TAG "Duke3D"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- Ajustes (desde Java) ----
enum { SCALE_PIXEL = 0, SCALE_SHARP = 1, SCALE_SMOOTH = 2, SCALE_BICUBIC = 3, SCALE_FSR = 4 };

static volatile int pfx_scaler = SCALE_SHARP;
static volatile int pfx_fxaa = 0;
static volatile int pfx_sharp = 0;   // 0 no, 1 baja, 2 media, 3 alta
static volatile int pfx_crt = 0;     // 0 no, 1 líneas, 2 CRT, 3 LCD
static volatile int pfx_color = 0;   // 0 normal, 1 vivo, 2 cálido, 3 frío, 4 cine
static volatile int pfx_bloom = 0;   // 0 no, 1 suave, 2 fuerte
// Proporción en la que se enseña la imagen (0 = la de sus píxeles). La pone el emulador (PS1:
// 4:3 o panorámica, según el juego y el ajuste).
static volatile float pfx_aspect = 0.f;

void android_postfx_set_aspect(float aspect)
{
    pfx_aspect = aspect;
}

#if defined(POSTFX_PSX)
// Juegos de PlayStation (psx/psx_main.cpp): los ajustes llegan desde PsxActivity.
#define POSTFX_JNI Java_com_kbretro_duke3d_PsxActivity_nativePostFx
#elif defined(POSTFX_DOS)
// Juegos de MS-DOS (dos/dos_main.cpp): los ajustes llegan desde DosActivity.
#define POSTFX_JNI Java_com_kbretro_duke3d_DosActivity_nativePostFx
#else
#define POSTFX_JNI Java_com_kbretro_duke3d_GameActivity_nativePostFx
#endif

JNIEXPORT void JNICALL POSTFX_JNI(JNIEnv *env, jclass cls, jint scaler, jint fxaa,
                                                                         jint sharp, jint crt, jint color, jint bloom)
{
    (void)env;
    (void)cls;
    pfx_scaler = scaler;
    pfx_fxaa = fxaa;
    pfx_sharp = sharp;
    pfx_crt = crt;
    pfx_color = color;
    pfx_bloom = bloom;
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
    "uniform sampler2D u_bloomTex;\n"
    "uniform float u_bloomAmt;\n"
    "uniform vec4 u_vp;\n"
    "vec3 fetch(vec2 uv) { vec4 c = texture2D(u_tex, uv); return u_bgra > 0.5 ? c.bgr : c.rgb; }\n"
    "vec3 post(vec3 c) {\n"
    "  if (u_bloomAmt > 0.0) c += texture2D(u_bloomTex, (gl_FragCoord.xy - u_vp.xy) / u_vp.zw).rgb * u_bloomAmt;\n"
    "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
    "  if (u_vivid > 0.5 && u_vivid < 1.5) {\n"
    "    c = (mix(vec3(l), c, 1.3) - 0.5) * 1.06 + 0.5;\n"
    "  } else if (u_vivid > 1.5 && u_vivid < 2.5) {\n"
    "    c = mix(vec3(l), c, 1.1) * vec3(1.08, 1.0, 0.86);\n"
    "  } else if (u_vivid > 2.5 && u_vivid < 3.5) {\n"
    "    c = mix(vec3(l), c, 1.05) * vec3(0.9, 1.0, 1.12);\n"
    "  } else if (u_vivid > 3.5) {\n"
    "    c = clamp(c, 0.0, 1.0);\n"
    "    c = mix(c, c * c * (3.0 - 2.0 * c), 0.6);\n"
    "    c = mix(vec3(l), c, 1.15) * mix(vec3(0.92, 1.0, 1.06), vec3(1.08, 1.0, 0.9), l);\n"
    "  }\n"
    "  c = clamp(c, 0.0, 1.0);\n"
    "  if (u_scan > 0.5 && u_scan < 2.5) {\n"
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
    "  } else if (u_scan > 2.5) {\n"
    "    vec2 g = fract(v_tex * vec2(640.0, 400.0));\n"
    "    vec2 e = smoothstep(0.0, 0.18, g) * smoothstep(1.0, 0.82, g);\n"
    "    c *= mix(0.72, 1.0, e.x * e.y);\n"
    "    c = min(c * 1.12, 1.0);\n"
    "  }\n"
    "  return c;\n"
    "}\n";

// Escalado sencillo: 0 píxel, 1 nítido (sharp bilinear), 2 suave.
static const char *FS_SCALE =
    "uniform float u_mode;\n"
    "vec3 bicubic(vec2 uv) {\n"
    "  vec2 sp = uv * u_in;\n"
    "  vec2 t1 = floor(sp - 0.5) + 0.5;\n"
    "  vec2 f = sp - t1;\n"
    "  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));\n"
    "  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);\n"
    "  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));\n"
    "  vec2 w3 = f * f * (-0.5 + 0.5 * f);\n"
    "  vec2 w12 = w1 + w2;\n"
    "  vec2 p0 = (t1 - 1.0) / u_in;\n"
    "  vec2 p3 = (t1 + 2.0) / u_in;\n"
    "  vec2 p12 = (t1 + w2 / w12) / u_in;\n"
    "  vec3 r = (fetch(vec2(p0.x, p0.y)) * w0.x + fetch(vec2(p12.x, p0.y)) * w12.x + fetch(vec2(p3.x, p0.y)) * w3.x) * w0.y\n"
    "         + (fetch(vec2(p0.x, p12.y)) * w0.x + fetch(vec2(p12.x, p12.y)) * w12.x + fetch(vec2(p3.x, p12.y)) * w3.x) * w12.y\n"
    "         + (fetch(vec2(p0.x, p3.y)) * w0.x + fetch(vec2(p12.x, p3.y)) * w12.x + fetch(vec2(p3.x, p3.y)) * w3.x) * w3.y;\n"
    "  return clamp(r, 0.0, 1.0);\n"
    "}\n"
    "void main() {\n"
    "  if (u_mode > 2.5) { gl_FragColor = vec4(post(bicubic(v_tex)), 1.0); return; }\n"
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

// Resplandor: zonas brillantes de la imagen (a 1/4 de la pantalla).
static const char *FS_BRIGHT =
    "void main() {\n"
    "  vec2 r = 1.0 / u_in;\n"
    "  vec3 c = 0.25 * (fetch(v_tex + vec2(-r.x, -r.y)) + fetch(v_tex + vec2(r.x, -r.y))\n"
    "                 + fetch(v_tex + vec2(-r.x, r.y)) + fetch(v_tex + vec2(r.x, r.y)));\n"
    "  float l = max(c.r, max(c.g, c.b));\n"
    "  gl_FragColor = vec4(c * smoothstep(0.5, 0.95, l), 1.0);\n"
    "}\n";

// Desenfoque gaussiano en una dirección (u_dir).
static const char *FS_BLUR =
    "uniform vec2 u_dir;\n"
    "void main() {\n"
    "  vec2 o = u_dir / u_in;\n"
    "  vec3 c = fetch(v_tex) * 0.2270;\n"
    "  c += (fetch(v_tex + o * 1.3846) + fetch(v_tex - o * 1.3846)) * 0.3162;\n"
    "  c += (fetch(v_tex + o * 3.2308) + fetch(v_tex - o * 3.2308)) * 0.0703;\n"
    "  gl_FragColor = vec4(c, 1.0);\n"
    "}\n";

// ---- Estado de GL ----

typedef struct
{
    GLuint prog;
    GLint u_tex, u_in, u_out, u_bgra, u_scan, u_vivid, u_flip, u_mode, u_sharp, u_dir, u_bloomTex, u_bloomAmt, u_vp;
} program_t;

static SDL_Window *pfx_window;
static SDL_GLContext pfx_context;
static program_t p_scale, p_fxaa, p_easu, p_rcas, p_bright, p_blur;
static GLuint vbo, src_tex;
static int src_w, src_h;
static GLuint fbo_a, tex_a;  // a la resolución interna (FXAA)
static int a_w, a_h;
static GLuint fbo_b, tex_b;  // a la resolución de salida (antes de RCAS)
static int b_w, b_h;
static GLuint fbo_g1, tex_g1, fbo_g2, tex_g2;  // resplandor (1/4 de la pantalla)
static int g1_w, g1_h, g2_w, g2_h;
static GLuint bloom_tex;     // el resplandor de este fotograma (0 = sin resplandor)
static float bloom_amt;
static int vp_x, vp_y, vp_w, vp_h;
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
    p->u_dir = glGetUniformLocation(p->prog, "u_dir");
    p->u_bloomTex = glGetUniformLocation(p->prog, "u_bloomTex");
    p->u_bloomAmt = glGetUniformLocation(p->prog, "u_bloomAmt");
    p->u_vp = glGetUniformLocation(p->prog, "u_vp");
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
    if (fbo_g1) glDeleteFramebuffers(1, &fbo_g1);
    if (tex_g1) glDeleteTextures(1, &tex_g1);
    if (fbo_g2) glDeleteFramebuffers(1, &fbo_g2);
    if (tex_g2) glDeleteTextures(1, &tex_g2);
    fbo_a = tex_a = fbo_b = tex_b = src_tex = fbo_g1 = tex_g1 = fbo_g2 = tex_g2 = 0;
    a_w = a_h = b_w = b_h = src_w = src_h = g1_w = g1_h = g2_w = g2_h = 0;
}

/** Antes de SDL_CreateWindow: la ventana tiene que admitir OpenGL ES 2. */
void android_postfx_attributes(void)
{
    SDL_GL_ResetAttributes();
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
#ifdef POSTFX_PSX
    // Beetle PSX dibuja con OpenGL ES 3 en el mismo contexto.
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
#else
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 2);
#endif
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
#ifdef POSTFX_PSX
    // PlayStation: cada fotograma, en un refresco de la pantalla (sin esto, 50 fps en 120 Hz
    // se veían aún más irregulares).
    SDL_GL_SetSwapInterval(1);
#else
    SDL_GL_SetSwapInterval(0);
#endif

    if (!link_program(&p_scale, FS_SCALE) || !link_program(&p_fxaa, FS_FXAA) || !link_program(&p_easu, FS_EASU)
        || !link_program(&p_rcas, FS_RCAS) || !link_program(&p_bright, FS_BRIGHT) || !link_program(&p_blur, FS_BLUR))
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
        if (p_bright.prog) glDeleteProgram(p_bright.prog);
        if (p_blur.prog) glDeleteProgram(p_blur.prog);
        SDL_GL_DeleteContext(pfx_context);
    }
    pfx_context = NULL;
    pfx_window = NULL;
    pfx_ok = 0;
}

/** La imagen de partida ya está en RGBA (viene de la GPU), no en el BGRA de la CPU. */
static int src_is_rgba;

static void present_from_src(int w, int h);

static void draw(const program_t *p, GLuint tex, int in_w, int in_h, int out_w, int out_h, int raw, int final_pass)
{
    glUseProgram(p->prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(p->u_tex, 0);
    glUniform2f(p->u_in, (float)in_w, (float)in_h);
    glUniform2f(p->u_out, (float)out_w, (float)out_h);
    glUniform1f(p->u_bgra, raw && !src_is_rgba ? 1.f : 0.f);
    glUniform1f(p->u_flip, raw ? 1.f : 0.f);
    glUniform1f(p->u_scan, final_pass ? (float)pfx_crt : 0.f);
    glUniform1f(p->u_vivid, final_pass ? (float)pfx_color : 0.f);
    if (final_pass && bloom_tex)
    {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, bloom_tex);
        glUniform1i(p->u_bloomTex, 1);
        glUniform1f(p->u_bloomAmt, bloom_amt);
        glUniform4f(p->u_vp, (float)vp_x, (float)vp_y, (float)vp_w, (float)vp_h);
        glActiveTexture(GL_TEXTURE0);
    }
    else
        glUniform1f(p->u_bloomAmt, 0.f);
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
    present_from_src(w, h);
}

#ifdef POSTFX_PSX
static GLuint fbo_src;   // para copiar el fotograma de la GPU a src_tex
static GLuint fbo_read;  // en este contexto, para leer la textura del emulador

/**
 * Enseña un fotograma que el emulador ha dibujado con la GPU en la textura tex (w x h desde la
 * esquina de abajo a la izquierda). El emulador va en su propio contexto de OpenGL (compartido
 * con este): así nuestros filtros no le tocan el estado. sync es la valla que pone el emulador
 * al terminar el fotograma (o NULL). Se copia a src_tex con la misma orientación que las
 * imágenes de la CPU y sigue el mismo camino (escalado, nitidez, efectos...).
 */
void android_postfx_present_texture(unsigned tex, int w, int h, int bottom_left, void *sync)
{
    if (!pfx_ok || w <= 0 || h <= 0)
        return;
    SDL_GL_MakeCurrent(pfx_window, pfx_context);
    if (sync)
    {
        glWaitSync((GLsync)sync, 0, GL_TIMEOUT_IGNORED);
        glDeleteSync((GLsync)sync);
    }
    glActiveTexture(GL_TEXTURE0);

    if (!src_tex || src_w != w || src_h != h)
    {
        if (src_tex) glDeleteTextures(1, &src_tex);
        src_tex = make_texture(w, h);
        src_w = w;
        src_h = h;
        if (!fbo_src) glGenFramebuffers(1, &fbo_src);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_src);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, src_tex, 0);
    }
    if (!fbo_read) glGenFramebuffers(1, &fbo_read);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_read);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo_src);
    // src_tex lleva la fila de arriba de la imagen en t=0 (como lo que sube la CPU).
    if (bottom_left)
        glBlitFramebuffer(0, 0, w, h, 0, h, w, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    else
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    src_is_rgba = 1;
    present_from_src(w, h);
    src_is_rgba = 0;
}
#endif

static void present_from_src(int w, int h)
{
    // Rectángulo de salida: toda la pantalla, con la proporción de la imagen.
    int ww = 0, wh = 0;
    SDL_GL_GetDrawableSize(pfx_window, &ww, &wh);
    if (ww <= 0 || wh <= 0)
        return;
#if defined(POSTFX_DOS) || defined(POSTFX_PSX)
    // MS-DOS (320x200 y similares) en monitores 4:3; PlayStation, la proporción que diga el
    // emulador (4:3, o panorámica con el truco de pantalla ancha).
    float aspect = pfx_aspect > 0.f ? pfx_aspect : 4.f / 3.f;
    int vw = ww, vh = (int)(ww / aspect);
    if (vh > wh)
    {
        vh = wh;
        vw = (int)(wh * aspect);
    }
#else
    int vw = ww, vh = (int)((long long)ww * h / w);
    if (vh > wh)
    {
        vh = wh;
        vw = (int)((long long)wh * w / h);
    }
#endif
    int vx = (ww - vw) / 2, vy = (wh - vh) / 2;

    int scaler = pfx_scaler, sharp = pfx_sharp;
    if (scaler == SCALE_FSR && sharp == 0)
        sharp = 2;  // FSR va siempre con su nitidez
    static const float stops[4] = { 0.f, 0.8f, 0.3f, 0.0f };
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

    // Resplandor: zonas brillantes a 1/4 de la pantalla, desenfocadas; se suma al final.
    bloom_tex = 0;
    vp_x = vx;
    vp_y = vy;
    vp_w = vw;
    vp_h = vh;
    if (pfx_bloom > 0)
    {
        int gw = vw / 4 > 1 ? vw / 4 : 1, gh = vh / 4 > 1 ? vh / 4 : 1;
        make_target(&fbo_g1, &tex_g1, &g1_w, &g1_h, gw, gh);
        make_target(&fbo_g2, &tex_g2, &g2_w, &g2_h, gw, gh);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_g1);
        glViewport(0, 0, gw, gh);
        draw(&p_bright, in_tex, w, h, gw, gh, raw, 0);
        int passes = pfx_bloom >= 2 ? 2 : 1;
        for (int i = 0; i < passes; i++)
        {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo_g2);
            glUseProgram(p_blur.prog);
            glUniform2f(p_blur.u_dir, 1.f + i, 0.f);
            draw(&p_blur, tex_g1, gw, gh, gw, gh, 0, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo_g1);
            glUseProgram(p_blur.prog);
            glUniform2f(p_blur.u_dir, 0.f, 1.f + i);
            draw(&p_blur, tex_g2, gw, gh, gw, gh, 0, 0);
        }
        bloom_tex = tex_g1;
        bloom_amt = pfx_bloom >= 2 ? 1.0f : 0.55f;
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

#include <dlfcn.h>
#include <stdint.h>
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
