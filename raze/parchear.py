#!/usr/bin/env python3
"""Cambios sobre Raze para compilarlo en Android (se aplican sobre el código descargado).

Uso: python3 raze/parchear.py RUTA_A_RAZE
Cada cambio comprueba que encuentra el texto original; si Raze cambia, falla y avisa.
"""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])


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
