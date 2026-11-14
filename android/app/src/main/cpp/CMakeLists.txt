# Compila EDuke32 (Duke Nukem 3D) como libmain.so para SDLActivity.
# Equivale a la compilación de Linux con SDL2 del GNUmakefile original, con el renderizador
# por software (sin OpenGL), sin ensamblador (NOASM) y sin ALSA, FLAC, libvpx ni mimalloc.
cmake_minimum_required(VERSION 3.22)
project(duke3d C CXX)

# APK de Raze: el motor y SDL2 vienen ya compilados (src/raze/jniLibs, workflow raze.yml).
if(MOTOR_RAZE)
    add_library(vacio SHARED ${CMAKE_CURRENT_SOURCE_DIR}/vacio.c)
    # Los juegos de MS-DOS de los extras también van en la HD: con la libSDL2.so de Raze
    # (la que copia raze.yml en src/raze/jniLibs) y las cabeceras de android/SDL2.
    set(RAZE_SDL2 ${CMAKE_CURRENT_SOURCE_DIR}/../../raze/jniLibs/${ANDROID_ABI}/libSDL2.so)
    message(STATUS "DOS en la HD: SDL2_DIR=${SDL2_DIR} RAZE_SDL2=${RAZE_SDL2} DOSBOX_DIR=${DOSBOX_DIR}")
    if(SDL2_DIR AND EXISTS ${RAZE_SDL2})
        add_library(SDL2 SHARED IMPORTED)
        set_target_properties(SDL2 PROPERTIES IMPORTED_LOCATION ${RAZE_SDL2}
            INTERFACE_INCLUDE_DIRECTORIES ${SDL2_DIR}/include)
        include(${CMAKE_CURRENT_SOURCE_DIR}/dos/dos.cmake)
        # Juegos de PlayStation (Time to Kill): frontend propio (libpsx.so); el núcleo Beetle PSX
        # (libpsxcore.so) lo compila raze.yml con su Android.mk y lo abre con dlopen.
        if(BEETLE_DIR AND EXISTS ${BEETLE_DIR}/libretro-common/include/libretro.h)
            add_library(psx SHARED psx/psx_main.cpp postfx.c)
            target_include_directories(psx PRIVATE ${BEETLE_DIR}/libretro-common/include)
            target_compile_definitions(psx PRIVATE POSTFX_PSX)
            set_target_properties(psx PROPERTIES C_STANDARD 11 CXX_STANDARD 14)
            target_link_libraries(psx PRIVATE SDL2 GLESv3 android log m dl)
        endif()
    endif()
    return()
endif()

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

# ---- Juegos de MS-DOS (extras): ver dos/dos.cmake ----
include(${CMAKE_CURRENT_SOURCE_DIR}/dos/dos.cmake)
