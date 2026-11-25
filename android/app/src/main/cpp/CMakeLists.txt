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
        # Juegos de PlayStation (Time to Kill...): frontend propio (libpsx.so); el núcleo
        # SwanStation (libpsxcore.so) lo compila raze.yml y lo abre con dlopen.
        if(PSXCORE_DIR AND EXISTS ${PSXCORE_DIR}/dep/libretro-common/include/libretro.h)
            add_library(psx SHARED psx/psx_main.cpp postfx.c)
            target_include_directories(psx PRIVATE ${PSXCORE_DIR}/dep/libretro-common/include)
            target_compile_definitions(psx PRIVATE POSTFX_PSX)
            set_target_properties(psx PROPERTIES C_STANDARD 11 CXX_STANDARD 14)
            target_link_libraries(psx PRIVATE SDL2 GLESv3 android log m dl)
        endif()
        # Duke Nukem 3D Classic (EDuke32 por software) dentro de la Legacy: libeduke32.so, con
        # su Java en com.kbretro.duke3d.classic (de ahí CLASSIC_PKG para los nombres JNI).
        set(EDUKE_TARGET eduke32)
        set(EDUKE_EXTRA_DEFINES CLASSIC_PKG)
        include(${CMAKE_CURRENT_SOURCE_DIR}/eduke32.cmake)
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

set(EDUKE_TARGET main)
include(${CMAKE_CURRENT_SOURCE_DIR}/eduke32.cmake)

# ---- Juegos de MS-DOS (extras): ver dos/dos.cmake ----
include(${CMAKE_CURRENT_SOURCE_DIR}/dos/dos.cmake)
