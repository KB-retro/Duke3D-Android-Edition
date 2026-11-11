#!/usr/bin/env python3
"""Cambios sobre Raze para compilarlo en Android (se aplican sobre el código descargado).

Uso: python3 raze/parchear.py RUTA_A_RAZE
Cada cambio comprueba que encuentra el texto original; si Raze cambia, falla y avisa.
"""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent

# El puente con la app se copia dentro del código de Raze (android/android_raze.cpp).
import shutil
(root / "android").mkdir(exist_ok=True)
shutil.copy(here / "android" / "android_raze.cpp", root / "android" / "android_raze.cpp")


def sub(path, old, new, count=1):
    p = root / path
    s = p.read_text(encoding="utf-8", errors="surrogateescape")
    if new in s:
        return  # ya aplicado
    if s.count(old) < 1:
        sys.exit(f"parchear: no se encuentra en {path}:\n{old}")
    s = s.replace(old, new, count)
    p.write_text(s, encoding="utf-8", errors="surrogateescape")
    print(f"parcheado {path}")


# 1. libvpx es opcional (solo hace falta para los vídeos de Blood y Shadow Warrior).
sub("source/CMakeLists.txt",
    'message( SEND_ERROR "Could not find libvpx" )',
    'message( STATUS "Sin libvpx: no se reproducen videos" )')

# 2. En Android el juego es una biblioteca (libmain.so) que carga SDLActivity.
sub("source/CMakeLists.txt",
    "add_executable( ${PROJECT_NAME} WIN32 MACOSX_BUNDLE ${GAME_SOURCES} )",
    "if( ANDROID )\n"
    "\tadd_library( ${PROJECT_NAME} SHARED ${GAME_SOURCES} )\n"
    "\tset_target_properties( ${PROJECT_NAME} PROPERTIES OUTPUT_NAME main )\n"
    "\ttarget_link_libraries( ${PROJECT_NAME} log android EGL GLESv2 )\n"
    "else()\n"
    "add_executable( ${PROJECT_NAME} WIN32 MACOSX_BUNDLE ${GAME_SOURCES} )\n"
    "endif()")

# 3. La carpeta del programa (raze.pk3, soundfont) la dice la app: en Android argv[0] no sirve.
sub("source/common/platform/posix/sdl/i_main.cpp",
    "#ifdef PROGDIR\n\tprogdir = PROGDIR;\n#else",
    "#if defined(__ANDROID__)\n"
    "\tprogdir = getenv(\"RAZE_PROGDIR\") ? getenv(\"RAZE_PROGDIR\") : \"./\";\n"
    "\tif (progdir.Len() > 0 && progdir[progdir.Len() - 1] != '/') progdir += '/';\n"
    "#elif defined(PROGDIR)\n\tprogdir = PROGDIR;\n#else")

# 4. En Android solo hay OpenGL ES: se usa ese motor de dibujo por defecto.
sub("source/common/rendering/v_video.cpp",
    "CUSTOM_CVAR(Int, vid_preferbackend, 1, CVAR_ARCHIVE",
    "#ifdef __ANDROID__\n#define RAZE_BACKEND_DEFAULT 2\n#else\n#define RAZE_BACKEND_DEFAULT 1\n#endif\n"
    "CUSTOM_CVAR(Int, vid_preferbackend, RAZE_BACKEND_DEFAULT, CVAR_ARCHIVE")
sub("source/common/platform/posix/sdl/sdlglvideo.cpp",
    "CUSTOM_CVAR(Bool, gl_es, false, CVAR_ARCHIVE",
    "#ifdef __ANDROID__\n#define RAZE_GLES_DEFAULT true\n#else\n#define RAZE_GLES_DEFAULT false\n#endif\n"
    "CUSTOM_CVAR(Bool, gl_es, RAZE_GLES_DEFAULT, CVAR_ARCHIVE")

# 5. main() → raze_main() en Android: SDL_main lo pone raze/android/android_raze.cpp (hilo con
#    pila grande, registro y fallos). Sin el capturador de fallos de Raze (usa el de la app).
sub("source/common/platform/posix/sdl/i_main.cpp",
    "int main (int argc, char **argv)\n{\n#if !defined (__APPLE__)",
    "#ifdef __ANDROID__\n#undef main\nint raze_main (int argc, char **argv)\n#else\nint main (int argc, char **argv)\n#endif\n"
    "{\n#if !defined (__APPLE__) && !defined (__ANDROID__)")

# 6. El puente con la app (android_raze.cpp) va dentro de libmain.so.
sub("source/CMakeLists.txt",
    "\tadd_library( ${PROJECT_NAME} SHARED ${GAME_SOURCES} )",
    "\tadd_library( ${PROJECT_NAME} SHARED ${GAME_SOURCES} ${CMAKE_SOURCE_DIR}/android/android_raze.cpp )")

# 7. Cada tic: órdenes, botones y giro de la app; y el joystick táctil como ejes.
sub("source/common/platform/posix/sdl/i_input.cpp",
    "void I_StartTic ()\n{",
    "#ifdef __ANDROID__\nvoid android_raze_tick();\n#endif\nvoid I_StartTic ()\n{\n#ifdef __ANDROID__\n\tandroid_raze_tick();\n#endif")
sub("source/core/gameinput.cpp",
    "\tI_GetAxes(joyAxes);",
    "\tI_GetAxes(joyAxes);\n#ifdef __ANDROID__\n\t{ void android_raze_axes(float *); android_raze_axes(joyAxes); }\n#endif")

# 8. Sin libvpx (no hay en Android): los vídeos VP8/VP9 no se reproducen y el resto sí.
sub("source/common/textures/animtexture.cpp",
    '#include "vpx/vpx_image.h"',
    '#ifdef USE_LIBVPX\n#include "vpx/vpx_image.h"\n#endif')
sub("source/common/textures/animtexture.cpp",
    "\t\telse if(pixelformat == VPX)\n",
    "#ifdef USE_LIBVPX\n\t\telse if(pixelformat == VPX)\n")
sub("source/common/textures/animtexture.cpp",
    "\t\t}\n\t\telse if(pixelformat == RGB)",
    "\t\t}\n#endif\n\t\telse if(pixelformat == RGB)")
sub("source/common/cutscenes/movieplayer.cpp",
    "#include <vpx/vpx_decoder.h>\n#include <vpx/vp8dx.h>",
    "#ifdef USE_LIBVPX\n#include <vpx/vpx_decoder.h>\n#include <vpx/vp8dx.h>\n#endif")
sub("source/common/cutscenes/movieplayer.cpp",
    "class VpxPlayer : public MoviePlayer\n{",
    "#ifdef USE_LIBVPX\nclass VpxPlayer : public MoviePlayer\n{")
sub("source/common/cutscenes/movieplayer.cpp",
    "struct AudioData\n{",
    "#else\nclass VpxPlayer : public MoviePlayer\n{\npublic:\n\tint soundtrack = -1;\n"
    "\tVpxPlayer(FileReader&, TArray<int>&, int, int, FString& error) { error = \"VPX video not supported\"; }\n"
    "\tbool isvalid() { return false; }\n\tbool Frame(uint64_t) override { return false; }\n"
    "\tFTextureID GetTexture() override { return FTextureID(); }\n};\n#endif\n\nstruct AudioData\n{")
