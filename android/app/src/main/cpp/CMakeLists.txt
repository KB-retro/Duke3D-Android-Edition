# Compila EDuke32 (Duke Nukem 3D) como libmain.so para SDLActivity.
# Equivale a la compilación de Linux con SDL2 del GNUmakefile original, con el renderizador
# por software (sin OpenGL), sin ensamblador (NOASM) y sin ALSA, FLAC, libvpx ni mimalloc.
cmake_minimum_required(VERSION 3.22)
project(duke3d C CXX)

if(NOT SDL2_DIR)
    message(FATAL_ERROR "Falta -DSDL2_DIR con el código de SDL2")
endif()

# SDL2 como libSDL2.so (SDLActivity la carga por nombre).
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(${SDL2_DIR} SDL2-build)

set(E ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../eduke32/source)

# ---- Fuentes (mismas exclusiones que el GNUmakefile para Linux con SDL2) ----
file(GLOB ENGINE_SRC ${E}/build/src/*.c ${E}/build/src/*.cpp)
list(FILTER ENGINE_SRC EXCLUDE REGEX "/(sdlayer12|sdlkeytrans|startgtk\\.editor|startwin\\.editor|build|config|polymer|winbits|wiibits|winlayer|rawinput|gtkbits|dynamicgtk|animvpx)\\.cpp$")

# Polymost (OpenGL): glad carga las funciones de gl4es; los shaders .glsl van como texto.
list(APPEND ENGINE_SRC ${E}/glad/src/glad.c)
file(READ ${E}/build/src/polymost1Frag.glsl POLYMOST1_FRAG)
file(READ ${E}/build/src/polymost1Vert.glsl POLYMOST1_VERT)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/polymost_shaders.cpp
    "char const *polymost1Frag = R\"GLSL(${POLYMOST1_FRAG})GLSL\";\nchar const *polymost1Vert = R\"GLSL(${POLYMOST1_VERT})GLSL\";\n")
list(APPEND ENGINE_SRC ${CMAKE_CURRENT_BINARY_DIR}/polymost_shaders.cpp)

file(GLOB MACT_SRC ${E}/mact/src/*.cpp)

file(GLOB AUDIOLIB_SRC ${E}/audiolib/src/*.cpp)
list(FILTER AUDIOLIB_SRC EXCLUDE REGEX "/(music_external|driver_directsound|driver_winmm|driver_alsa)\\.cpp$")

file(GLOB XMP_SRC ${E}/libxmp-lite/src/*.c)

file(GLOB IMGUI_SRC ${E}/imgui/src/*.cpp)
list(FILTER IMGUI_SRC EXCLUDE REGEX "/(imgui_impl_win32|imgui_impl_opengl3)\\.cpp$")

file(GLOB DUKE_SRC ${E}/duke3d/src/*.cpp)
list(FILTER DUKE_SRC EXCLUDE REGEX "/(in_android|m32structures|mdump|startgtk\\.game|startwin\\.game|m32common|m32def|m32exec|m32vars|astub|sounds_mapster32)\\.cpp$")
list(APPEND DUKE_SRC ${E}/duke3d/rsrc/game_icon.c)

# Rutas y opciones comunes (en el GNUmakefile van en COMPILERFLAGS, para todo).
set(COMMON_INCLUDES
    ${E}/build/include
    ${E}/mact/include
    ${E}/audiolib/include
    ${E}/glad/include
    ${E}/voidwrap/include
    ${E}/imgui/include
    ${E}/libxmp-lite/include)
set(COMMON_DEFINES
    NOASM
    USE_OPENGL
    RENDERTYPESDL=1
    SDL_TARGET=2
    HAVE_XMP
    HAVE_VORBIS
    NDEBUG
    _REENTRANT)
set(COMMON_OPTIONS
    -funsigned-char
    -fno-strict-aliasing
    -fwrapv
    -U_FORTIFY_SOURCE
    -D_FORTIFY_SOURCE=0
    -w)

# ---- libxmp-lite (música de módulos) ----
add_library(xmplite STATIC ${XMP_SRC})
target_include_directories(xmplite PRIVATE ${E}/libxmp-lite/include/libxmp-lite ${COMMON_INCLUDES})
target_compile_definitions(xmplite PRIVATE HAVE_ROUND LIBXMP_CORE_PLAYER LIBXMP_NO_PROWIZARD LIBXMP_NO_DEPACKERS BUILDING_STATIC ${COMMON_DEFINES})
target_compile_options(xmplite PRIVATE ${COMMON_OPTIONS})
set_target_properties(xmplite PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)

# ---- gl4es: OpenGL de escritorio -> OpenGL ES 2 (para el modo Polymost) ----
# Biblioteca aparte (libgl4es.so); el juego la carga solo si se elige OpenGL en el lanzador.
set(G4 ${CMAKE_CURRENT_SOURCE_DIR}/gl4es)
add_library(gl4es SHARED
    ${G4}/src/gl/arbconverter.c
    ${G4}/src/gl/arbgenerator.c
    ${G4}/src/gl/arbhelper.c
    ${G4}/src/gl/arbparser.c
    ${G4}/src/gl/array.c
    ${G4}/src/gl/blend.c
    ${G4}/src/gl/blit.c
    ${G4}/src/gl/buffers.c
    ${G4}/src/gl/build_info.c
    ${G4}/src/gl/debug.c
    ${G4}/src/gl/decompress.c
    ${G4}/src/gl/depth.c
    ${G4}/src/gl/directstate.c
    ${G4}/src/gl/drawing.c
    ${G4}/src/gl/enable.c
    ${G4}/src/gl/envvars.c
    ${G4}/src/gl/eval.c
    ${G4}/src/gl/face.c
    ${G4}/src/gl/fog.c
    ${G4}/src/gl/fpe.c
    ${G4}/src/gl/fpe_cache.c
    ${G4}/src/gl/fpe_shader.c
    ${G4}/src/gl/framebuffers.c
    ${G4}/src/gl/gl_lookup.c
    ${G4}/src/gl/getter.c
    ${G4}/src/gl/gl4es.c
    ${G4}/src/gl/glstate.c
    ${G4}/src/gl/hint.c
    ${G4}/src/gl/init.c
    ${G4}/src/gl/light.c
    ${G4}/src/gl/line.c
    ${G4}/src/gl/list.c
    ${G4}/src/gl/listdraw.c
    ${G4}/src/gl/listrl.c
    ${G4}/src/gl/loader.c
    ${G4}/src/gl/logs.c
    ${G4}/src/gl/matrix.c
    ${G4}/src/gl/matvec.c
    ${G4}/src/gl/oldprogram.c
    ${G4}/src/gl/pixel.c
    ${G4}/src/gl/planes.c
    ${G4}/src/gl/pointsprite.c
    ${G4}/src/gl/preproc.c
    ${G4}/src/gl/program.c
    ${G4}/src/gl/queries.c
    ${G4}/src/gl/raster.c
    ${G4}/src/gl/render.c
    ${G4}/src/gl/samplers.c
    ${G4}/src/gl/shader.c
    ${G4}/src/gl/shaderconv.c
    ${G4}/src/gl/shader_hacks.c
    ${G4}/src/gl/stack.c
    ${G4}/src/gl/stencil.c
    ${G4}/src/gl/string_utils.c
    ${G4}/src/gl/stubs.c
    ${G4}/src/gl/texenv.c
    ${G4}/src/gl/texgen.c
    ${G4}/src/gl/texture.c
    ${G4}/src/gl/texture_compressed.c
    ${G4}/src/gl/texture_params.c
    ${G4}/src/gl/texture_read.c
    ${G4}/src/gl/texture_3d.c
    ${G4}/src/gl/uniform.c
    ${G4}/src/gl/vertexattrib.c
    ${G4}/src/gl/wrap/gl4eswraps.c
    ${G4}/src/gl/wrap/gles.c
    ${G4}/src/gl/wrap/glstub.c
    ${G4}/src/gl/math/matheval.c
    ${G4}/src/glx/hardext.c)
target_include_directories(gl4es PRIVATE ${G4}/include)
# Sin EGL propio: el contexto lo crea SDL y el juego llama a gl4es_pre_swap/post_swap.
target_compile_definitions(gl4es PRIVATE ANDROID NOX11 NOEGL NO_GBM DEFAULT_ES=2 NO_INIT_CONSTRUCTOR USE_ANDROID_LOG)
target_compile_options(gl4es PRIVATE -std=gnu99 -O3 -funwind-tables -fvisibility=hidden
    -include ${G4}/include/android_debug.h -w)
target_link_libraries(gl4es PRIVATE log dl m)

# ---- El juego ----
add_library(main SHARED
    ${ENGINE_SRC} ${MACT_SRC} ${AUDIOLIB_SRC} ${IMGUI_SRC} ${DUKE_SRC}
    android_main.c postfx.c)

target_include_directories(main PRIVATE ${COMMON_INCLUDES})
target_compile_definitions(main PRIVATE ${COMMON_DEFINES})
target_compile_options(main PRIVATE
    ${COMMON_OPTIONS}
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)

set_target_properties(main PROPERTIES
    C_STANDARD 11 C_EXTENSIONS ON
    CXX_STANDARD 14 CXX_EXTENSIONS ON)

target_link_libraries(main PRIVATE SDL2 xmplite GLESv2 android log m dl)
