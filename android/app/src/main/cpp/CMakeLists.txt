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
list(FILTER ENGINE_SRC EXCLUDE REGEX "/(sdlayer12|sdlkeytrans|startgtk\\.editor|startwin\\.editor|build|config|glbuild|glsurface|mdsprite|polymer|polymost|tilepacker|voxmodel|winbits|wiibits|winlayer|rawinput|gtkbits|dynamicgtk|animvpx)\\.cpp$")

file(GLOB MACT_SRC ${E}/mact/src/*.cpp)

file(GLOB AUDIOLIB_SRC ${E}/audiolib/src/*.cpp)
list(FILTER AUDIOLIB_SRC EXCLUDE REGEX "/(music_external|driver_directsound|driver_winmm|driver_alsa)\\.cpp$")

file(GLOB XMP_SRC ${E}/libxmp-lite/src/*.c)

file(GLOB IMGUI_SRC ${E}/imgui/src/*.cpp)
list(FILTER IMGUI_SRC EXCLUDE REGEX "/(imgui_impl_win32|imgui_impl_opengl3)\\.cpp$")

file(GLOB DUKE_SRC ${E}/duke3d/src/*.cpp)
list(FILTER DUKE_SRC EXCLUDE REGEX "/(in_android|m32structures|mdump|startgtk\\.game|startwin\\.game|m32common|m32def|m32exec|m32vars|astub|sounds_mapster32)\\.cpp$")
list(APPEND DUKE_SRC ${E}/duke3d/rsrc/game_icon.c)

# ---- libxmp-lite (música de módulos) ----
add_library(xmplite STATIC ${XMP_SRC})
target_include_directories(xmplite PRIVATE ${E}/libxmp-lite/include/libxmp-lite)
target_compile_definitions(xmplite PRIVATE HAVE_ROUND LIBXMP_CORE_PLAYER LIBXMP_NO_PROWIZARD LIBXMP_NO_DEPACKERS BUILDING_STATIC)
target_compile_options(xmplite PRIVATE -funsigned-char -w)
set_target_properties(xmplite PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)

# ---- El juego ----
add_library(main SHARED
    ${ENGINE_SRC} ${MACT_SRC} ${AUDIOLIB_SRC} ${IMGUI_SRC} ${DUKE_SRC}
    android_main.c)

target_include_directories(main PRIVATE
    ${E}/build/include
    ${E}/mact/include
    ${E}/audiolib/include
    ${E}/glad/include
    ${E}/voidwrap/include
    ${E}/imgui/include
    ${E}/libxmp-lite/include)

target_compile_definitions(main PRIVATE
    NOASM
    RENDERTYPESDL=1
    SDL_TARGET=2
    HAVE_XMP
    HAVE_VORBIS
    NDEBUG
    _REENTRANT)

target_compile_options(main PRIVATE
    -funsigned-char
    -fno-strict-aliasing
    -fwrapv
    -w
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)

set_target_properties(main PROPERTIES
    C_STANDARD 11 C_EXTENSIONS ON
    CXX_STANDARD 14 CXX_EXTENSIONS ON)

target_link_libraries(main PRIVATE SDL2 xmplite android log m dl)
