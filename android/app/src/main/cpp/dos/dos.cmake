# ---- Juegos de MS-DOS (extras: Duke Nukem 1 y 2): DOSBox Pure + frontend propio (libdos.so) ----
# Aparte del juego: va en su propia biblioteca y en su propio proceso (DosActivity). El código
# de DOSBox Pure lo descarga el workflow en android/dosbox-pure.
if(DOSBOX_DIR AND EXISTS ${DOSBOX_DIR}/dosbox_pure_libretro.cpp)
    file(GLOB DBP_SRC ${DOSBOX_DIR}/*.cpp ${DOSBOX_DIR}/src/*.cpp ${DOSBOX_DIR}/src/*/*.cpp ${DOSBOX_DIR}/src/*/*/*.cpp)
    add_library(dosbox_pure STATIC ${DBP_SRC})
    target_include_directories(dosbox_pure PRIVATE ${DOSBOX_DIR}/include)
    target_compile_definitions(dosbox_pure PRIVATE NDEBUG __LIBRETRO__)
    target_compile_options(dosbox_pure PRIVATE -O2 -fomit-frame-pointer -fexceptions -frtti -pthread
        -ffunction-sections -fdata-sections -w)
    set_target_properties(dosbox_pure PROPERTIES CXX_STANDARD 11 POSITION_INDEPENDENT_CODE ON)

    add_library(dos SHARED dos/dos_main.cpp postfx.c)
    target_include_directories(dos PRIVATE ${DOSBOX_DIR}/libretro-common/include)
    target_compile_definitions(dos PRIVATE POSTFX_DOS)
    set_target_properties(dos PROPERTIES C_STANDARD 11 CXX_STANDARD 14)
    target_link_libraries(dos PRIVATE dosbox_pure SDL2 GLESv2 android log m dl)
endif()
