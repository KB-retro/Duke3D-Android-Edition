#!/usr/bin/env python3
"""Cambios sobre ZMusic para Android: FluidSynth sin GLib (con posix_glibstubs).

Uso: python3 raze/zmusic/parchear.py RUTA_A_ZMUSIC
En Android (o con -DFLUID_POSIX_GLIB_STUBS=ON para probar en el PC) FluidSynth usa
posix_glibstubs.h/.c en lugar de GLib, igual que en Windows usa win32_glibstubs.
"""
import pathlib
import shutil
import sys

root = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
fs = root / "thirdparty/fluidsynth/src"


def sub(path, old, new):
    p = root / path
    s = p.read_text(encoding="utf-8", errors="surrogateescape")
    if new in s:
        return
    if old not in s:
        sys.exit(f"parchear zmusic: no se encuentra en {path}:\n{old}")
    p.write_text(s.replace(old, new, 1), encoding="utf-8", errors="surrogateescape")
    print(f"parcheado {path}")


for f in ("posix_glibstubs.h", "posix_glibstubs.c"):
    shutil.copy(here / f, fs / "utils" / f)

sub("thirdparty/fluidsynth/src/config.h",
    "#ifdef _WIN32\n#define WITH_GLIB_STUBS\n#endif",
    "#if defined(__ANDROID__) && !defined(FLUID_POSIX_GLIB_STUBS)\n#define FLUID_POSIX_GLIB_STUBS\n#endif\n"
    "#if defined(_WIN32) || defined(FLUID_POSIX_GLIB_STUBS)\n#define WITH_GLIB_STUBS\n#endif")

sub("thirdparty/fluidsynth/src/utils/fluid_sys.h",
    "/* Darwin special defines (taken from config_macosx.h) */",
    "#if defined(FLUID_POSIX_GLIB_STUBS)\n#include \"posix_glibstubs.h\"\n#endif\n\n"
    "/* Darwin special defines (taken from config_macosx.h) */")

sub("thirdparty/fluidsynth/src/utils/fluid_sys.h",
    "#ifndef WIN32\n#include <glib/gstdio.h>\n#endif",
    "#if !defined(WIN32) && !defined(WITH_GLIB_STUBS)\n#include <glib/gstdio.h>\n#endif")

sub("thirdparty/fluidsynth/src/CMakeLists.txt",
    "if (NOT WIN32)\n   find_package(PkgConfig REQUIRED)",
    "if (ANDROID OR FLUID_POSIX_GLIB_STUBS)\n"
    "   target_sources ( fluidsynth PRIVATE utils/posix_glibstubs.c )\n"
    "   target_compile_definitions ( fluidsynth PRIVATE FLUID_POSIX_GLIB_STUBS )\n"
    "elseif (NOT WIN32)\n   find_package(PkgConfig REQUIRED)")

# config.h pone HAVE_WINDOWS_H en todos los sistemas: con los stubs se usa struct stat.
sub("thirdparty/fluidsynth/src/utils/fluid_sys.h",
    "    #if defined(_WIN32) || HAVE_WINDOWS_H // somehow reliably mock G_OS_WIN32??",
    "    #if defined(_WIN32) // somehow reliably mock G_OS_WIN32??")
