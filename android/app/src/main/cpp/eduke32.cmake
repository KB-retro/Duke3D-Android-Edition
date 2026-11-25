# EDuke32 (Duke Nukem 3D por software) como biblioteca ${EDUKE_TARGET}: libmain.so en la Classic
# suelta, libeduke32.so dentro de Duke Legacy (con la SDL2 de Raze y el Java en el paquete classic).

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
add_library(${EDUKE_TARGET} SHARED
    ${ENGINE_SRC} ${MACT_SRC} ${AUDIOLIB_SRC} ${IMGUI_SRC} ${DUKE_SRC}
    android_main.c postfx.c)

target_include_directories(${EDUKE_TARGET} PRIVATE ${COMMON_INCLUDES})
target_compile_definitions(${EDUKE_TARGET} PRIVATE ${COMMON_DEFINES} ${EDUKE_EXTRA_DEFINES})
target_compile_options(${EDUKE_TARGET} PRIVATE
    ${COMMON_OPTIONS}
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)

set_target_properties(${EDUKE_TARGET} PROPERTIES
    C_STANDARD 11 C_EXTENSIONS ON
    CXX_STANDARD 14 CXX_EXTENSIONS ON)

target_link_libraries(${EDUKE_TARGET} PRIVATE SDL2 xmplite GLESv2 android log m dl)
