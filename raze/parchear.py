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
    "\tadd_library( ${PROJECT_NAME} SHARED ${GAME_SOURCES} ${CMAKE_SOURCE_DIR}/android/android_raze.cpp )\n"
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
    "#ifdef __ANDROID__\n#define RAZE_BACKEND_DEFAULT 1\n#else\n#define RAZE_BACKEND_DEFAULT 1\n#endif\n"
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

# 6. (El puente android_raze.cpp va en la biblioteca del cambio 2.)

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

# 9. Que no se cambie el nombre de la biblioteca (libmain.so) más abajo.
sub("source/CMakeLists.txt",
    "\tset_target_properties( ${PROJECT_NAME} PROPERTIES OUTPUT_NAME ${PROJECT_NAME} )",
    "\tif( NOT ANDROID )\n\tset_target_properties( ${PROJECT_NAME} PROPERTIES OUTPUT_NAME ${PROJECT_NAME} )\n\tendif()")

# 10. En Android pthread va dentro de libc (no hay libpthread).
sub("libraries/discordrpc/src/CMakeLists.txt",
    "    target_link_libraries(discord-rpc PUBLIC pthread)",
    "    if(NOT ANDROID)\n    target_link_libraries(discord-rpc PUBLIC pthread)\n    endif()")

# 11. Sonido: en Android OpenAL Soft se llama libopenal.so (no libopenal.so.1).
sub("source/common/audio/sound/oalsound.cpp",
    "#elif defined(__OpenBSD__)\n#define OPENALLIB \"libopenal.so\"",
    "#elif defined(__OpenBSD__) || defined(__ANDROID__)\n#define OPENALLIB \"libopenal.so\"")

# 12. Sincronía vertical activada por defecto en Android: sin ella la GPU dibuja sin parar
#     (calor y tirones).
sub("source/common/rendering/v_video.cpp",
    "CUSTOM_CVAR (Bool, vid_vsync, false, CVAR_ARCHIVE|CVAR_GLOBALCONFIG)",
    "#ifdef __ANDROID__\nCUSTOM_CVAR (Bool, vid_vsync, true, CVAR_ARCHIVE|CVAR_GLOBALCONFIG)\n#else\n"
    "CUSTOM_CVAR (Bool, vid_vsync, false, CVAR_ARCHIVE|CVAR_GLOBALCONFIG)\n#endif")

# 13. En Android solo OpenGL ES, diga lo que diga la configuración.
sub("source/common/rendering/v_video.cpp",
    "int V_GetBackend()\n{",
    "int V_GetBackend()\n{\n#ifdef __ANDROID__\n\treturn 2;\n#endif")

# 14. Errores graves: en Android se escriben en el registro (no hay kdialog ni GTK) y se
#     enseñan con el cuadro de SDL.
sub("source/common/platform/posix/sdl/i_system.cpp",
    "void I_ShowFatalError(const char *message)\n{\n#ifdef __APPLE__",
    "#ifdef __ANDROID__\nextern \"C\" int __android_log_print(int prio, const char* tag, const char* fmt, ...);\n#endif\n"
    "void I_ShowFatalError(const char *message)\n{\n#ifdef __ANDROID__\n"
    "\tfprintf(stderr, \"ERROR GRAVE: %s\\n\", message);\n"
    "\t__android_log_print(7 /* FATAL */, \"Duke3D\", \"ERROR GRAVE: %s\", message);\n"
    "\tSDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, \"Raze\", message, NULL);\n\treturn;\n#endif\n#ifdef __APPLE__")

# 15. Algunas GPU de móvil (Mali) dicen que admiten bloques uniformes de 4 GB (sale negativo):
#     se limita a 64 KB como en OpenGL.
sub("source/common/rendering/vulkan/system/vk_renderdevice.cpp",
    "\tmaxuniformblock = device->PhysicalDevice.Properties.Properties.limits.maxUniformBufferRange;",
    "\tmaxuniformblock = std::min<uint32_t>(device->PhysicalDevice.Properties.Properties.limits.maxUniformBufferRange, 65536);")

# 17. Vulkan en Android (las capas de validación lo señalan en la Mali-G615):
#  a) La cadena de imágenes pedía alfa OPACO y Android solo admite INHERIT: se elige uno admitido.
sub("libraries/ZVulkan/src/vulkanswapchain.cpp",
    "\tswapChainCreateInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;",
    "\t{\n\t\tVkCompositeAlphaFlagsKHR supported = caps.Capabilites.supportedCompositeAlpha;\n"
    "\t\tVkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;\n"
    "\t\tif (!(supported & alpha))\n\t\t{\n"
    "\t\t\tif (supported & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;\n"
    "\t\t\telse if (supported & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) alpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;\n"
    "\t\t\telse if (supported & VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR) alpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;\n"
    "\t\t}\n\t\tswapChainCreateInfo.compositeAlpha = alpha;\n\t}\n"
    "\t//swapChainCreateInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;")
#  b) Los búferes de luces y huesos solo se leen: «readonly» (sin él, en el sombreador de
#     vértices hace falta vertexPipelineStoresAndAtomics, que la Mali no activa).
sub("source/common/rendering/vulkan/shaders/vk_shader.cpp",
    "\tlayout(set = 1, binding = 3, std430) buffer LightBufferSSO",
    "\tlayout(set = 1, binding = 3, std430) readonly buffer LightBufferSSO")
sub("source/common/rendering/vulkan/shaders/vk_shader.cpp",
    "\tlayout(set = 1, binding = 4, std430) buffer BoneBufferSSO",
    "\tlayout(set = 1, binding = 4, std430) readonly buffer BoneBufferSSO")
#  c) Un semáforo «render terminado» por imagen de la cadena (se reutilizaba uno solo mientras
#     la pantalla aún lo usaba).
sub("source/common/rendering/vulkan/textures/vk_framebuffer.h",
    "\tstd::unique_ptr<VulkanSemaphore> RenderFinishedSemaphore;",
    "\tstd::unique_ptr<VulkanSemaphore> RenderFinishedSemaphore;\n"
    "\tstd::vector<std::unique_ptr<VulkanSemaphore>> RenderFinishedPerImage;\n"
    "\tVulkanSemaphore* RenderFinished();")
sub("source/common/rendering/vulkan/textures/vk_framebuffer.h",
    "#include <array>\n",
    "#include <array>\n#include <vector>\n")
sub("source/common/rendering/vulkan/textures/vk_framebuffer.cpp",
    "void VkFramebufferManager::QueuePresent()\n{\n\tif (PresentImageIndex != -1)\n"
    "\t\tSwapChain->QueuePresent(PresentImageIndex, RenderFinishedSemaphore.get());\n}",
    "VulkanSemaphore* VkFramebufferManager::RenderFinished()\n{\n"
    "\tif (PresentImageIndex < 0) return RenderFinishedSemaphore.get();\n"
    "\twhile ((int)RenderFinishedPerImage.size() <= PresentImageIndex)\n"
    "\t\tRenderFinishedPerImage.push_back(SemaphoreBuilder().DebugName(\"RenderFinishedSemaphore\").Create(fb->device.get()));\n"
    "\treturn RenderFinishedPerImage[PresentImageIndex].get();\n}\n\n"
    "void VkFramebufferManager::QueuePresent()\n{\n\tif (PresentImageIndex != -1)\n"
    "\t\tSwapChain->QueuePresent(PresentImageIndex, RenderFinished());\n}")
sub("source/common/rendering/vulkan/system/vk_commandbuffer.cpp",
    "\t\tsubmit.AddSignal(fb->GetFramebufferManager()->RenderFinishedSemaphore.get());",
    "\t\tsubmit.AddSignal(fb->GetFramebufferManager()->RenderFinished());")

# 19. Pantallas que Android gira (p. ej. la Odin 2 Portal, panel vertical): Raze no gira la
#     imagen él mismo, así que la cadena de imágenes va sin giro y lo hace el sistema (si no,
#     salía en vertical y deformada).
sub("libraries/ZVulkan/src/vulkanswapchain.cpp",
    "\tswapChainCreateInfo.preTransform = caps.Capabilites.currentTransform;",
    "#ifdef __ANDROID__\n"
    "\tswapChainCreateInfo.preTransform = (caps.Capabilites.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)\n"
    "\t\t? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.Capabilites.currentTransform;\n#else\n"
    "\tswapChainCreateInfo.preTransform = caps.Capabilites.currentTransform;\n#endif")

# 20. Al terminar Raze (por cualquier vía: «Salir» de su menú, el menú rápido, Android cerrando
#     la ventana...) se guarda la configuración y se sale sin la limpieza final: al cerrar el
#     sonido OpenAL abortaba en alcCloseDevice y Vulkan fallaba al desmontarse.
sub("source/core/gamecontrol.cpp",
    "\t//DeleteScreenJob();\n\tif (gi)",
    "#ifdef __ANDROID__\n\tG_SaveConfig();\n\tfflush(stdout);\n\tfflush(stderr);\n\t_Exit(r == -1 ? 1 : 0);\n#endif\n"
    "\t//DeleteScreenJob();\n\tif (gi)")
sub("source/core/gamecontrol.cpp",
    "#include <stdexcept>\n",
    "#include <stdexcept>\n#include <stdlib.h>\n#include <stdio.h>\n")

# 21. Logros y vibración en Duke (como en la versión EDuke32): android_raze_event() y
#     android_raze_rumble() (android_raze.cpp) avisan a la app.
#  Bajas (las que cuentan para la estadística).
sub("source/games/duke/src/inlines.h",
    "inline void addkill(DDukeActor* actor)\n{\n\tif ((actor->flags1 & SFLAG_KILLCOUNT) && (actor->spr.cstat2 & CSTAT2_SPRITE_COUNTKILL))\n\t{\n\t\tLevel.addKill(myconnectindex);",
    "#ifdef __ANDROID__\nextern \"C\" void android_raze_event(int type, int a, int b, int c);\n#endif\n"
    "inline void addkill(DDukeActor* actor)\n{\n\tif ((actor->flags1 & SFLAG_KILLCOUNT) && (actor->spr.cstat2 & CSTAT2_SPRITE_COUNTKILL))\n\t{\n\t\tLevel.addKill(myconnectindex);\n"
    "#ifdef __ANDROID__\n\t\tif (ud.multimode < 2) android_raze_event(1, 1, 0, 0);\n#endif")
#  Secretos.
sub("source/games/duke/src/sectors_d.cpp",
    "\t\tLevel.addSecret(p->pnum);\n\t\tSECRET_Trigger(sectindex(p->cursector));",
    "\t\tLevel.addSecret(p->pnum);\n#ifdef __ANDROID__\n\t\tif (ud.multimode < 2) android_raze_event(3, 0, 0, 0);\n#endif\n"
    "\t\tSECRET_Trigger(sectindex(p->cursector));")
#  Propina a la stripper.
sub("source/games/duke/src/gameexec.cpp",
    "\tcase concmd_tip:\n\t\tinsptr++;\n\t\tp->tipincs = 26;",
    "\tcase concmd_tip:\n\t\tinsptr++;\n\t\tp->tipincs = 26;\n#ifdef __ANDROID__\n\t\tif (ud.multimode < 2) android_raze_event(2, 0, 0, 0);\n#endif")
#  Aplastar a un enemigo encogido.
sub("source/games/duke/src/player.cpp",
    "\t\t\t\telse\n\t\t\t\t{\n\t\t\t\t\taddkill(p->actorsqu);\n\t\t\t\t\tp->actorsqu->Destroy();",
    "\t\t\t\telse\n\t\t\t\t{\n#ifdef __ANDROID__\n\t\t\t\t\tif (ud.multimode < 2) android_raze_event(4, 0, 0, 0);\n#endif\n"
    "\t\t\t\t\taddkill(p->actorsqu);\n\t\t\t\t\tp->actorsqu->Destroy();")
#  Fin de nivel: episodio y nivel (desde 0), si cayeron todos, si acaba el episodio y el tiempo.
sub("source/games/duke/src/premap.cpp",
    "\tLevel.fillSummary(info);\n\tinfo.endofgame = endofgame;",
    "\tLevel.fillSummary(info);\n\tinfo.endofgame = endofgame;\n#ifdef __ANDROID__\n"
    "\tif (currentLevel && currentLevel->levelNumber >= 1000 && ud.multimode < 2)\n\t{\n"
    "\t\tint flags = (info.maxkills > 0 && info.kills >= info.maxkills) ? 1 : 0;\n"
    "\t\tif (endofgame || (nextlevel && nextlevel->cluster != currentLevel->cluster)) flags |= 2;\n"
    "\t\tandroid_raze_event(9, currentLevel->levelNumber / 1000 - 1, currentLevel->levelNumber % 1000 - 1, flags | ((info.time / 120) << 8));\n"
    "\t}\n#endif")
#  Vibración: daño recibido, terremotos y disparos (cada tic del juego).
sub("source/games/duke/src/gameloop.cpp",
    "void GameInterface::Ticker()\n{",
    "#ifdef __ANDROID__\nextern \"C\" void android_raze_rumble(int strength, int ms);\n"
    "extern \"C\" void android_raze_event(int type, int a, int b, int c);\n"
    "static void android_duke_tick()\n{\n"
    "\tstatic int lastHealth = -1, lastQuake = 0, lastKick = 0;\n"
    "\tauto p = getPlayer(myconnectindex);\n"
    "\tauto act = p ? p->GetActor() : nullptr;\n"
    "\tint h = act ? act->spr.extra : -1;\n"
    "\tif (lastHealth > 0 && h >= 0 && h < lastHealth)\n\t{\n"
    "\t\tint d = lastHealth - h;\n"
    "\t\tandroid_raze_rumble(std::min(255, 70 + d * 8), std::min(400, 80 + d * 10));\n\t}\n"
    "\tlastHealth = h;\n"
    "\tif (ud.earthquaketime > 0 && lastQuake == 0) android_raze_rumble(220, 350);\n"
    "\tlastQuake = ud.earthquaketime;\n"
    "\tint kick = p ? p->kickback_pic : 0;\n"
    "\tif (kick == 1 && lastKick != 1) android_raze_rumble(80, 45);\n"
    "\tlastKick = kick;\n"
    # Logros de cercanía (cada 8 tics): ver a Luke o al marine de Doom, pisar la caca.
    "\tstatic int frame = 0;\n"
    "\tif (!act || ud.multimode > 1 || (++frame & 7) != 0) return;\n"
    "\tstatic FName nLuke(\"DukeLuke\"), nMarine(\"DukeSpaceMarine\");\n"
    "\tstatic FTextureID poop = tileGetTextureID(4094);\n"
    "\tDukeSpriteIterator it;\n"
    "\twhile (auto s = it.Next())\n\t{\n"
    "\t\tint ev = 0; double range = 0;\n"
    "\t\tFName cname = s->GetClass()->TypeName;\n"
    "\t\tif (cname == nLuke) { ev = 7; range = 128; }\n"
    "\t\telse if (cname == nMarine) { ev = 8; range = 128; }\n"
    "\t\telse if (s->spr.spritetexture() == poop) { ev = 6; range = 18; }\n"
    "\t\telse continue;\n"
    "\t\tif (!s->sector() || !act->sector()) continue;\n"
    "\t\tauto d = s->spr.pos.XY() - act->spr.pos.XY();\n"
    "\t\tif (d.LengthSquared() > range * range) continue;\n"
    "\t\tif (ev == 6) { if (!p->on_ground || fabs(s->spr.pos.Z - act->spr.pos.Z) > 16) continue; }\n"
    "\t\telse if (!cansee(s->spr.pos.plusZ(-16), s->sector(), act->spr.pos, act->sector())) continue;\n"
    "\t\tandroid_raze_event(ev, 0, 0, 0);\n\t}\n"
    "}\n#endif\n\n"
    "void GameInterface::Ticker()\n{")
sub("source/games/duke/src/gameloop.cpp",
    "\t\tfi.think();\n",
    "\t\tfi.think();\n#ifdef __ANDROID__\n\t\tandroid_duke_tick();\n#endif\n")

# 22. Carpetas de búsqueda de juegos que pasa la app (RAZE_SEARCH, separadas por ':'): las
#     expansiones de la Megaton van en addons/dc, addons/nw... (dos niveles por debajo).
sub("source/core/searchpaths.cpp",
    "\t// Unify and remove trailing slashes\n\tfor (auto &str : searchpaths)",
    "#ifdef __ANDROID__\n\tif (const char* extra = getenv(\"RAZE_SEARCH\"))\n\t{\n"
    "\t\tFString all = extra;\n\t\tauto parts = all.Split(\":\");\n"
    "\t\tfor (auto& part : parts) if (part.Len() > 0) AddSearchPath(searchpaths, part.GetChars());\n\t}\n#endif\n"
    "\t// Unify and remove trailing slashes\n\tfor (auto &str : searchpaths)")

# 23. Rendimiento en OpenGL ES (Mali/Adreno): menos luces dinámicas por superficie en el
#     sombreador (32 → 16), que es lo que más pesa por píxel en GPU de móvil.
sub("source/common/rendering/gles/gles_system.cpp",
    "CVAR(Int, gles_max_lights_per_surface, 32, 0);",
    "#ifdef __ANDROID__\nCVAR(Int, gles_max_lights_per_surface, 16, 0);\n#else\nCVAR(Int, gles_max_lights_per_surface, 32, 0);\n#endif")

# 24. Logro de trucos también con los trucos tecleados en el juego (god, give...).
sub("source/core/cheats.cpp",
    "void genericCheat(int player, TArrayView<uint8_t>& stream, bool skip)\n{\n    int cheat = ReadInt8(stream);\n    if (skip) return;",
    "#ifdef __ANDROID__\nextern \"C\" void android_raze_event(int type, int a, int b, int c);\n#endif\n"
    "void genericCheat(int player, TArrayView<uint8_t>& stream, bool skip)\n{\n    int cheat = ReadInt8(stream);\n    if (skip) return;\n"
    "#ifdef __ANDROID__\n\tif (player == myconnectindex) android_raze_event(5, cheat, 0, 0);\n#endif")

# 25. El menú del juego de Raze se queda, con sus Opciones (hay ajustes de gráficos y sonido que
#     solo están ahí). Fuera de la partida el menú rápido de la app no se abre, así que el menú
#     del juego trae «Volver al lanzador» y «Salir» (cierra la app entera), en el principal y en
#     el de partida. Los dos los hace la app (GameActivity.quitRequest), como su menú rápido.
sub("wadsrc/static/menudef.txt",
    '\t\tDukeTextItem "$MNU_QUITGAME", "q", "QuitMenu"\n',
    '\t\tDukeTextItem "Launcher", "v", "AndroidLauncher"\n\t\tDukeTextItem "$MNU_QUITGAME", "q", "AndroidQuit"\n',
    count=2)
sub("source/core/menu/razemenu.cpp",
    "void BuildGameMenus()\n{\n\tBuildEpisodeMenu();",
    "#ifdef __ANDROID__\nstatic void android_menu_texts(FName name)\n{\n"
    "\tDMenuDescriptor** desc = MenuDescriptors.CheckKey(name);\n"
    "\tif (!desc || !(*desc)->IsKindOf(RUNTIME_CLASS(DListMenuDescriptor))) return;\n"
    "\tauto ld = static_cast<DListMenuDescriptor*>(*desc);\n"
    "\tconst char* lang = getenv(\"DUKE3D_LANG\");\n"
    "\tbool es = lang && !strcmp(lang, \"es\");\n"
    "\tfor (auto item : ld->mItems)\n\t{\n"
    # «Terminar partida» (vuelve al título): en castellano Raze lo traduce igual que «Salir».
    "\t\tif (item->mAction == FName(\"EndgameMenu\")) item->StringVar(FName(\"mText\")) = es ? \"Terminar partida\" : \"End Game\";\n"
    "\t\telse if (item->mAction == FName(\"AndroidLauncher\")) item->StringVar(FName(\"mText\")) = es ? \"Volver al lanzador\" : \"Back to launcher\";\n"
    "\t\telse if (item->mAction == FName(\"AndroidQuit\")) item->StringVar(FName(\"mText\")) = es ? \"Salir\" : \"Quit\";\n"
    "\t}\n}\n#endif\n\n"
    "void BuildGameMenus()\n{\n#ifdef __ANDROID__\n\tandroid_menu_texts(NAME_Mainmenu);\n"
    "\tandroid_menu_texts(NAME_IngameMenu);\n#endif\n\tBuildEpisodeMenu();")
sub("source/core/menu/razemenu.cpp",
    "bool M_SetSpecialMenu(FName& menu, int param)\n{",
    "#ifdef __ANDROID__\nextern \"C\" void android_raze_quit(int all);\n#endif\n"
    "bool M_SetSpecialMenu(FName& menu, int param)\n{\n"
    "#ifdef __ANDROID__\n"
    "\tif (menu == FName(\"AndroidLauncher\") || menu == FName(\"AndroidQuit\"))\n\t{\n"
    "\t\tandroid_raze_quit(menu == FName(\"AndroidQuit\") ? 1 : 0);\n\t\treturn false;\n\t}\n"
    "#endif")

# 26. Sin consola en Android: no se puede usar sin teclado y, fuera de partida, dejaba la
#     pantalla sin el menú ni su fondo. Donde Raze pondría la consola a pantalla completa (al
#     cerrar el menú sin partida), vuelve al menú principal.
sub("source/common/console/c_console.cpp",
    "void C_FullConsole ()\n{",
    "void C_FullConsole ()\n{\n#ifdef __ANDROID__\n\tConsoleState = c_up;\n\tgamestate = GS_MENUSCREEN;\n"
    "\tC_DoCommand(\"menu_main\");\n\treturn;\n#endif")
sub("source/common/console/c_console.cpp",
    "void C_ToggleConsole ()\n{\n\tint togglestate;",
    "void C_ToggleConsole ()\n{\n#ifdef __ANDROID__\n\treturn;\n#endif\n\tint togglestate;")

# 27. Modelos 3D (MD3/MD2 del HRP). Raze lee los «model» de los .def y tiene el renderizador de
#     modelos (el mismo que dibuja los vóxeles), pero nunca llegaba a dibujarlos: el hueco de
#     hw_drawinfo.cpp estaba comentado y HWSprite::ProcessModel no existía. Se coloca el modelo
#     con las mismas cuentas que polymost_md3draw de EDuke32 (escala 0.01 * scale del .def,
#     zadd/yoffset, giro del sprite, volteos), en unidades de Raze.
# Los .def se leen al montar el juego de tiles, antes de que exista la tabla tile -> textura
# (firstarttile), así que tileGetTextureID daba lo mismo para todos los tiles y ningún sprite
# encontraba su modelo. El mapa se guarda por número de tile y se busca con legacyTileNum.
sub("source/core/models/modeldata.h",
    "\tunsigned FrameMapKey(FTextureID tilenum, unsigned palette)\n\t{\n\t\treturn tilenum.GetIndex() + (palette < 20);\n\t}",
    "\tunsigned FrameMapKey(int tile, unsigned palette)\n\t{\n\t\treturn unsigned(tile) + (palette << 20);\n\t}")
sub("source/core/models/modeldata.h",
    "\t\treturn frameMap.CheckKey(FrameMapKey(tilenum, pal)) != nullptr;",
    "\t\treturn GetModel(tilenum, pal) != nullptr;")
sub("source/core/models/modeldata.h",
    "\tModelTileFrame* GetModel(FTextureID tilenum, int pal)\n\t{\n\t\treturn frameMap.CheckKey(FrameMapKey(tilenum, pal));\n\t}",
    "\tModelTileFrame* GetModel(FTextureID tilenum, int pal);")
# Un frame sin «pal» llega con -1 (= paleta 0). La clave tiene que salir positiva: el lector
# del .def toma un resultado negativo como error y quita el modelo entero sin decir por qué.
sub("source/core/models/modeldata.cpp",
    "    auto key = FrameMapKey(tileGetTextureID(tilenum), pal);",
    "    if (pal < 0 || pal >= MAXPALOOKUPS) pal = 0;\n    auto key = FrameMapKey(tilenum, pal);")
sub("source/core/models/modeldata.cpp",
    "        frameMap.Remove(FrameMapKey(tileGetTextureID(tile), i));",
    "        frameMap.Remove(FrameMapKey(tile, i));")
sub("source/core/models/modeldata.cpp",
    "int ModelManager::LoadModel(const char* fn)",
    # Como EDuke32: si no hay modelo para esa paleta, el de la paleta 0.
    "ModelTileFrame* ModelManager::GetModel(FTextureID tilenum, int pal)\n{\n"
    "\tif (!tilenum.isValid() || frameMap.CountUsed() == 0) return nullptr;\n"
    "\tint tile = legacyTileNum(tilenum);\n"
    "\tauto f = frameMap.CheckKey(FrameMapKey(tile, pal));\n"
    "\tif (!f && pal != 0) f = frameMap.CheckKey(FrameMapKey(tile, 0));\n"
    "\treturn f;\n}\n\n"
    "int ModelManager::LoadModel(const char* fn)")
sub("source/core/textures/texinfo.cpp",
    "\t/*\n\tif (hw_models)\n\t{\n\t\treturn modelManager.CheckModel(tilenume, pal);\t// we have no models yet.\n\t}\n\t*/",
    "\tif (hw_models && modelManager.CheckModel(texid, pal)) return true;")
sub("source/core/textures/texinfo.cpp",
    '#include "texinfo.h"',
    '#include "texinfo.h"\n#include "models/modeldata.h"\nEXTERN_CVAR(Bool, hw_models)')
sub("source/core/rendering/scene/hw_drawinfo.cpp",
    "\t\t\t\t//HWSprite hwsprite;\n\t\t\t\t//if (hwsprite.ProcessModel(pt, tspr)) continue;",
    "\t\t\t\tHWSprite hwsprite;\n\t\t\t\tif (hwsprite.ProcessModel(this, pt, tspr, tspr->sectp)) continue;")
sub("source/core/rendering/scene/hw_drawstructs.h",
    "\tvoxmodel_t* voxel;\n\n\tint index;",
    "\tvoxmodel_t* voxel;\n\tclass FModel* mdl;\n\tstruct ModelDescriptor* mddesc;\n\tint mdframe, mdframe2, mdskinnum;\n\tfloat mdinter;\n\n\tint index;")
sub("source/core/rendering/scene/hw_drawstructs.h",
    "\tbool ProcessVoxel(HWDrawInfo* di, voxmodel_t* voxel, tspritetype* tspr, sectortype* sector, bool rotate);",
    "\tbool ProcessVoxel(HWDrawInfo* di, voxmodel_t* voxel, tspritetype* tspr, sectortype* sector, bool rotate);\n"
    "\tbool ProcessModel(HWDrawInfo* di, struct ModelTileFrame* pt, tspritetype* tspr, sectortype* sector);")
sub("source/core/rendering/scene/hw_sprites.cpp",
    '#include "hw_voxels.h"',
    '#include "hw_voxels.h"\n#include "models/modeldata.h"\n#include "model.h"')
sub("source/core/rendering/scene/hw_sprites.cpp",
    "\t\telse\n\t\t{\n\t\t\t//RenderModel(&renderer, x, y, z, modelframe, actor, di->Viewpoint.TicFrac);\n\t\t}",
    "\t\telse if (mdl && mddesc)\n\t\t{\n"
    # Skin de cada superficie: la de la paleta del sprite o, si no hay, la de la paleta 0.
    "\t\t\tFTextureID skins[32];\n\t\t\tfor (auto& s : skins) s.SetInvalid();\n\t\t\tbool any = false;\n"
    "\t\t\tfor (int pass = 0; pass < 2; pass++)\n"
    "\t\t\t\tfor (auto& sk : mddesc->skins)\n\t\t\t\t{\n"
    "\t\t\t\t\tif (sk.skinnum != mdskinnum || (unsigned)sk.surfnum >= 32u || !sk.texture.isValid()) continue;\n"
    "\t\t\t\t\tif (sk.palette != (pass == 0 ? palette : 0)) continue;\n"
    "\t\t\t\t\tif (!skins[sk.surfnum].isValid()) { skins[sk.surfnum] = sk.texture; any = true; }\n"
    "\t\t\t\t}\n"
    "\t\t\tFGameTexture* single = (!mdl->hasSurfaces && skins[0].isValid()) ? TexMan.GetGameTexture(skins[0], true) : nullptr;\n"
    "\t\t\tstate.SetDepthFunc(DF_LEqual);\n\t\t\tstate.EnableTexture(true);\n"
    "\t\t\tmdl->BuildVertexBuffer(&mr);\n"
    "\t\t\tmr.BeginDrawModel(RenderStyle, 0, rotmat, false);\n"
    "\t\t\tstate.SetCulling(Cull_None);\n"
    "\t\t\tTArray<VSMatrix> a;\n"
    "\t\t\tmdl->RenderFrame(&mr, single, mdframe, mdframe2, mdinter, NO_TRANSLATION, (single || !any) ? nullptr : skins, a, 0);\n"
    "\t\t\tmr.EndDrawModel(RenderStyle, 0);\n"
    "\t\t\tstate.SetDepthFunc(DF_Less);\n"
    "\t\t}")
sub("source/core/rendering/scene/hw_sprites.cpp",
    "bool HWSprite::ProcessVoxel(HWDrawInfo* di, voxmodel_t* vox, tspritetype* spr, sectortype* sector, bool rotate)\n{",
    r'''// Animaciones de los modelos, como updateanimation de EDuke32: si el frame del tile es el
// primero de un «anim», se reproduce con el tiempo (en bucle, o una vez y se queda en el último
// si es ONESHOT, como al morir); al cambiar de pose se funde con la anterior (smoothduration).
// El estado va en los campos que Raze ya reserva por actor (sprext.mdanim*, spsmooth).
static void ModelAnimate(DCoreActor* act, ModelDescriptor* desc, ModelTileFrame* pt, double tms, int& f1, int& f2, float& inter)
{
	int base = pt->framenum;
	const ModelAnimation* anim = nullptr;
	for (auto& a : desc->anims) if (a.startframe == base) { anim = &a; break; }
	auto& ext = act->sprext;
	auto& sm = act->spsmooth;
	uint32_t now = (uint32_t)max(tms, 0.);
	int target = anim ? anim->startframe : base;
	if (ext.mdanimcur != target + 1)
	{
		bool first = ext.mdanimcur == 0;
		// Fundido con la postura anterior. Si el .def no dice cuánto (smoothduration), lo que
		// duró la anterior: los enemigos cambian de fotograma unas 8 veces por segundo y, sin
		// esto, el modelo saltaba de una postura a otra (movimiento robótico).
		// (Raze pone 0.1 s a todos los frames que no dicen nada: se fundía en una décima y luego
		// se quedaba quieto hasta la siguiente postura.) Se funde durante lo que duró la anterior,
		// así el modelo no para nunca entre una postura y la siguiente.
		float dur = 0;
		if (!first) dur = clamp(max(pt->smoothduration, (now - ext.mdanimtims) / 1000.f), 0.06f, 0.3f);
		ext.mdanimcur = (int16_t)(target + 1);
		ext.mdanimtims = now;
		if (!first) sm.mdoldframe = sm.mdcurframe;
		sm.mdsmooth = (!first && dur > 0) ? 1 : 0;
		sm.smoothduration = sm.mdsmooth ? dur : 0;
	}
	int d = (int)(now - ext.mdanimtims);
	if (d < 0) { ext.mdanimtims = now; d = 0; }
	float elapsed = d / 1000.f;
	if (sm.mdsmooth)
	{
		float k = elapsed / max(sm.smoothduration, 0.001f);
		if (k < 1.f)
		{
			f1 = sm.mdoldframe;
			f2 = target;
			inter = k;
			// Si cambia otra vez a mitad del fundido, se parte de la postura más cercana.
			sm.mdcurframe = (int16_t)(k < 0.5f ? sm.mdoldframe : target);
			return;
		}
		sm.mdsmooth = 0;
	}
	// mdanimtims se queda en el momento del cambio (para medir cuánto dura cada postura): la
	// animación empieza al acabar el fundido.
	elapsed = max(0.f, elapsed - sm.smoothduration);
	if (anim && anim->fpssc > 0)
	{
		float fps = anim->fpssc / 65.536f;
		int n = max(1, anim->endframe + 1 - anim->startframe);
		float pos = elapsed * fps;
		bool oneshot = (anim->flags & 1) != 0;
		if (oneshot) { if (pos > n - 1) pos = (float)(n - 1); }
		else pos = fmodf(pos, (float)n);
		int fi = (int)pos;
		f1 = anim->startframe + fi;
		f2 = f1 + 1;
		if (f2 > anim->endframe) f2 = oneshot ? anim->endframe : anim->startframe;
		inter = pos - fi;
	}
	else
	{
		f1 = f2 = base;
		inter = 0;
	}
	sm.mdoldframe = (int16_t)(inter >= 0.5f ? f2 : f1);
	sm.mdcurframe = sm.mdoldframe;
}

bool HWSprite::ProcessModel(HWDrawInfo* di, ModelTileFrame* pt, tspritetype* spr, sectortype* sector)
{
	if (!pt || (unsigned)pt->modelid >= modelManager.modelDescs.Size() || pt->framenum < 0) return false;
	auto desc = &modelManager.modelDescs[pt->modelid];
	if (desc->deleted || desc->modelID >= Models.Size() || !Models[desc->modelID]) return false;
	if ((spr->cstat & CSTAT_SPRITE_ALIGNMENT_MASK) == CSTAT_SPRITE_ALIGNMENT_FLOOR) return false;
	auto tex = TexMan.GetGameTexture(spr->spritetexture());
	if (!tex) return false;

	Sprite = spr;
	auto ownerActor = spr->ownerActor;
	texture = nullptr;
	voxel = nullptr;
	modelframe = 1;
	mdl = Models[desc->modelID];
	mddesc = desc;
	ModelAnimate(ownerActor, desc, pt, ((double)PlayClock + 4. * di->Viewpoint.TicFrac) * (1000. / 120.), mdframe, mdframe2, mdinter);
	mdskinnum = pt->skinnum;
	dynlightindex = -1;
	shade = clamp(spr->shade + desc->shadeoff, -128, 127);
	palette = spr->pal;
	fade = lookups.getFade(sector->floorpal);
	visibility = sectorVisibility(sector);
	SetSpriteTranslucency(spr, alpha, RenderStyle);

	// Giro suave: el ángulo de los enemigos cambia de golpe (Duke no guarda el anterior para
	// interpolarlo), así que el modelo se va girando hacia él en unas décimas de segundo.
	DAngle baseyaw = spr->Angles.Yaw;
	{
		struct SmoothYaw { double yaw, t; };
		static TMap<DCoreActor*, SmoothYaw> yaws;
		static double lastPurge = 0;
		double t = ((double)PlayClock + 4. * di->Viewpoint.TicFrac) / 120.;
		if (t < lastPurge || t - lastPurge > 10)
		{
			// Sin restos de actores que ya no se ven (o ya borrados).
			TArray<DCoreActor*> old;
			TMap<DCoreActor*, SmoothYaw>::Iterator it(yaws);
			TMap<DCoreActor*, SmoothYaw>::Pair* pair;
			while (it.NextPair(pair)) if (t < pair->Value.t || t - pair->Value.t > 1) old.Push(pair->Key);
			for (auto a : old) yaws.Remove(a);
			lastPurge = t;
		}
		if (spr->Angles.Yaw == ownerActor->spr.Angles.Yaw)
		{
			SmoothYaw* sy = yaws.CheckKey(ownerActor);
			double target = spr->Angles.Yaw.Degrees();
			if (!sy || t < sy->t || t - sy->t > 0.5) yaws.Insert(ownerActor, { target, t });
			else
			{
				double d = remainder(target - sy->yaw, 360.);
				double k = 1. - exp(-(t - sy->t) / 0.07);
				sy->yaw += d * k;
				sy->t = t;
				baseyaw = DAngle::fromDeg(sy->yaw);
			}
		}
	}
	auto ang = baseyaw + ownerActor->sprext.rot.Yaw;
	if (spr->clipdist & TSPR_MDLROTATE)
	{
		int myclock = (PlayClock << 3) + MulScale(4 << 3, (int)di->Viewpoint.TicFrac, 16);
		ang += DAngle::fromBuild(myclock);
	}

	bool yflip = (spr->cstat & CSTAT_SPRITE_YFLIP) != 0;
	bool xflip = (spr->cstat & CSTAT_SPRITE_XFLIP) != 0;
	// Unidades del MD3 (ya divididas entre 64 al cargarlo) a unidades de Raze.
	float sh = 0.64f * desc->bscale * (float)spr->scale.X;
	float sv = 0.64f * desc->bscale * (float)spr->scale.Y;
	float th = (float)tex->GetDisplayHeight() * (float)spr->scale.Y;

	float zpos = (float)(spr->pos.Z + ownerActor->sprext.position_offset.Z);
	bool notface = (spr->ownerActor->spr.cstat & CSTAT_SPRITE_ALIGNMENT_MASK) != CSTAT_SPRITE_ALIGNMENT_FACING;
	zpos -= spr->yoffset * (float)spr->scale.Y * ((yflip && notface) ? -1.f : 1.f);
	if (spr->cstat & CSTAT_SPRITE_YCENTER) zpos += th * 0.5f;
	if (yflip) zpos -= th;
	float up = ((yflip ? -desc->zadd : desc->zadd) + desc->yoffset) * sv;

	x = (float)(spr->pos.X + ownerActor->sprext.position_offset.X);
	z = -zpos;
	y = -(float)(spr->pos.Y + ownerActor->sprext.position_offset.Y);

	rotmat.loadIdentity();
	rotmat.translate(x, z + up, y);
	rotmat.rotate((float)ang.Degrees(), 0, 1, 0);
	rotmat.scale(sh, yflip ? -sv : sv, xflip ? -sh : sh);

	auto vp = di->Viewpoint;
	depth = (float)((x - vp.Pos.X) * vp.TanCos + (y - vp.Pos.Y) * vp.TanSin);
	PutSprite(di, spriteHasTranslucency(Sprite));
	return true;
}

bool HWSprite::ProcessVoxel(HWDrawInfo* di, voxmodel_t* vox, tspritetype* spr, sectortype* sector, bool rotate)
{''')

# 28. En el registro: cuántos modelos 3D y fotogramas se han leído de los .def.
sub("source/core/textures/tilesetbuilder.cpp",
    "\tfileSystem.InitHashChains(); // make sure that any resources that got added can be found again.\n}",
    "\tfileSystem.InitHashChains(); // make sure that any resources that got added can be found again.\n"
    "\tPrintf(\"Modelos 3D: %u, sprites con modelo: %u\\n\", modelManager.modelDescs.Size(), modelManager.frameMap.CountUsed());\n}")
sub("source/core/textures/tilesetbuilder.cpp",
    '#include "tilesetbuilder.h"',
    '#include "tilesetbuilder.h"\n#include "models/modeldata.h"')

# 29. Raze lee como TEXTURES de GZDoom cualquier archivo llamado «textures», tenga la extensión
#     que tenga. Duke 64 trae un textures.def (de los de Build, lo incluye su duke64.def) y el
#     juego se cerraba con «Texture definition expected, found 'undeftexturerange'».
sub("source/common/textures/texturemanager.cpp",
    "\t\tif (fileSystem.GetFileContainer(texLump) == wadnum)\n\t\t{\n\t\t\tParseTextureDef(texLump, build);",
    "\t\tif (fileSystem.GetFileContainer(texLump) == wadnum)\n\t\t{\n"
    "\t\t\tconst char* tfn = fileSystem.GetFileFullName(texLump, false);\n"
    "\t\t\tsize_t tl = tfn ? strlen(tfn) : 0;\n"
    "\t\t\tif (tl > 4 && (!stricmp(tfn + tl - 4, \".def\") || !stricmp(tfn + tl - 4, \".con\"))) continue;\n"
    "\t\t\tParseTextureDef(texLump, build);")

# 30. Las expansiones que traen sus propios TILES*.ART (el Caribe) reutilizan números de tile del
#     juego base con otros dibujos. Los packs HD (HRP) están hechos para los del juego base: en
#     el Caribe salía el cielo de Los Ángeles. Al cargar un ART que no es el del juego, se compara
#     tile a tile con el del GRP base y los que han cambiado no reciben reemplazos HD (texturas,
#     skyboxes ni modelos 3D). Los iguales (la mayoría) los siguen recibiendo.
sub("source/core/textures/tiletexture.cpp",
    "static void AddArtFile(const FString& filename)\n{\n\tFileReader fr = fileSystem.OpenFileReader(filename.GetChars());",
    r'''static uint8_t gArtChanged[MAXUSERTILES];
bool tileArtChanged(int tile) { return (unsigned)tile < MAXUSERTILES && gArtChanged[tile]; }
// 2 = cambia la silueta (otro objeto); 1 = misma silueta con otros colores (sigue valiendo el modelo 3D)
bool tileArtShapeChanged(int tile) { return (unsigned)tile < MAXUSERTILES && gArtChanged[tile] == 2; }

// Tabla de un ART (sin la cabecera BUILDART): tile -> (ancho, alto, posición de los píxeles).
static bool ArtTable(const TArray<uint8_t>& d, TMap<int, std::pair<uint32_t, uint32_t>>& out, TMap<int, uint32_t>& sizes)
{
	if (d.Size() < 16) return false;
	const uint32_t* h = (const uint32_t*)d.Data();
	if (LittleLong(h[0]) != 1) return false;
	unsigned ts = LittleLong(h[2]), te = LittleLong(h[3]);
	if (te < ts || te >= MAXUSERTILES) return false;
	unsigned n = te - ts + 1;
	size_t hdr = 16 + n * 2 + n * 2 + n * 4;
	if (d.Size() < hdr) return false;
	const uint16_t* sx = (const uint16_t*)(d.Data() + 16);
	const uint16_t* sy = sx + n;
	size_t pos = hdr;
	for (unsigned i = 0; i < n; i++)
	{
		uint32_t w = LittleShort(sx[i]), hh = LittleShort(sy[i]);
		uint32_t sz = (w > 0 && hh > 0) ? w * hh : 0;
		if (pos + sz > d.Size()) return false;
		out.Insert(ts + i, { (uint32_t)pos, sz });
		sizes.Insert(ts + i, (w << 16) | hh);
		pos += sz;
	}
	return true;
}

static void MarkChangedArt(const FString& filename, const TArray<uint8_t>& artdata)
{
	// La copia de este ART en el primer archivo que lo trae (el GRP del juego base). Al jugar una
	// expansión su GRP también cuenta como del juego, así que no vale mirar GetMaxIwadNum.
	int lump = fileSystem.FindFile(filename.GetChars());
	if (lump < 0) return;
	int base = -1, last = 0, l;
	while ((l = fileSystem.FindLumpFullName(filename.GetChars(), &last)) >= 0)
		if (base < 0 || fileSystem.GetFileContainer(l) < fileSystem.GetFileContainer(base)) base = l;
	if (base < 0 || fileSystem.GetFileContainer(base) == fileSystem.GetFileContainer(lump)) return;
	Printf("%s: de %s, comparando con %s\n", filename.GetChars(), fileSystem.GetResourceFileName(fileSystem.GetFileContainer(lump)),
		fileSystem.GetResourceFileName(fileSystem.GetFileContainer(base)));
	TMap<int, std::pair<uint32_t, uint32_t>> nt, bt;
	TMap<int, uint32_t> ns, bs;
	if (!ArtTable(artdata, nt, ns)) return;
	TArray<uint8_t> based;
	{
		FileReader br = fileSystem.OpenFileReader(base);
		based.Resize((unsigned)br.GetLength());
		br.Read(based.Data(), based.Size());
		if (based.Size() > 8 && memcmp(based.Data(), "BUILDART", 8) == 0) based.Delete(0, 8);
		ArtTable(based, bt, bs);
	}
	int changed = 0, shape = 0;
	TMap<int, std::pair<uint32_t, uint32_t>>::Iterator it(nt);
	TMap<int, std::pair<uint32_t, uint32_t>>::Pair* p;
	while (it.NextPair(p))
	{
		int t = p->Key;
		auto b = bt.CheckKey(t);
		bool same = b && *bs.CheckKey(t) == *ns.CheckKey(t) && b->second == p->Value.second
			&& (p->Value.second == 0 || memcmp(based.Data() + b->first, artdata.Data() + p->Value.first, p->Value.second) == 0);
		if (!same && p->Value.second > 0)
		{
			changed++;
			bool sameshape = b && *bs.CheckKey(t) == *ns.CheckKey(t) && b->second == p->Value.second;
			if (sameshape)
			{
				const uint8_t* x = based.Data() + b->first, *y = artdata.Data() + p->Value.first;
				unsigned diff = 0, n = p->Value.second;
				for (unsigned k = 0; k < n; k++) diff += ((x[k] == 255) != (y[k] == 255));
				sameshape = diff * 10 <= n;
			}
			gArtChanged[t] = sameshape ? 1 : 2;
			if (!sameshape) shape++;
		}
	}
	if (changed) Printf("%s: %d tiles distintos de los del juego base (sin texturas HD), %d con otra forma (sin modelo 3D)\n", filename.GetChars(), changed, shape);
}

static void AddArtFile(const FString& filename)
{
	FileReader fr = fileSystem.OpenFileReader(filename.GetChars());''')
sub("source/core/textures/tiletexture.cpp",
    "\t\t\t// Only load the data if the header is present\n\t\t\tif (CountTiles(filename.GetChars(), artdata.Data()) > 0)\n\t\t\t{",
    "\t\t\t// Only load the data if the header is present\n\t\t\tif (CountTiles(filename.GetChars(), artdata.Data()) > 0)\n\t\t\t{\n\t\t\t\tMarkChangedArt(filename, artdata);")
sub("source/core/textures/hightile.cpp",
    "int tileSetHightileReplacement(int tilenum, int palnum, FTextureID texid, float alphacut, float xscale, float yscale, float specpower, float specfactor, bool indexed)\n{",
    "bool tileArtChanged(int tile);\n"
    "int tileSetHightileReplacement(int tilenum, int palnum, FTextureID texid, float alphacut, float xscale, float yscale, float specpower, float specfactor, bool indexed)\n{\n"
    "\tif (tileArtChanged(tilenum)) return 0;")
sub("source/core/textures/hightile.cpp",
    "int tileSetSkybox(int tilenum, int palnum, FString* facenames, bool indexed)\n{",
    "int tileSetSkybox(int tilenum, int palnum, FString* facenames, bool indexed)\n{\n"
    "\tif (tileArtChanged(tilenum)) return 0;")
sub("source/core/models/modeldata.cpp",
    "    if (pal < 0 || pal >= MAXPALOOKUPS) pal = 0;\n    auto key = FrameMapKey(tilenum, pal);",
    "    if (pal < 0 || pal >= MAXPALOOKUPS) pal = 0;\n    extern bool tileArtShapeChanged(int tile);\n"
    "    if (tileArtShapeChanged(tilenum)) return 0;\n    auto key = FrameMapKey(tilenum, pal);")

# 31. Luces dinámicas (como las de Polymer en EDuke32). Raze trae la parte del shader (luces por
#     superficie en OpenGL ES), pero ningún juego las crea y la recogida estaba comentada. Cada
#     fotograma el juego da su lista de luces (explosiones, cohetes, fuego, rayos, fogonazo del
#     arma...) y cada pared, suelo, sprite y modelo 3D se lleva las que le tocan. Sin mapas de
#     sombras: para que no atraviesen paredes, cada luz solo alumbra su sector y los contiguos a
#     los que se asoma por un hueco abierto.
sub("source/core/gamefuncs.h",
    '#include "updatesector.h"',
    '#include "updatesector.h"\n\n'
    "struct HWSceneLight\n{\n\tenum { MAXSECT = 24 };\n\tFVector3 pos;\t\t// en coordenadas del render (x, altura, -y)\n"
    "\tDVector3 mappos;\n\tfloat radius;\n\tFVector3 color;\n\tsectortype* sector;\n"
    "\tsectortype* sect[MAXSECT];\n\tint nsect;\n};\n"
    "extern TArray<HWSceneLight> hw_scenelights;\n"
    "extern void (*hw_gamelights)(TArray<HWSceneLight>& out, double interpfrac);\n"
    "void hw_AddSceneLight(TArray<HWSceneLight>& out, const DVector3& pos, sectortype* sect, float radius, float r, float g, float b);\n")
sub("source/core/rendering/scene/hw_sprites.cpp",
    '#include "models/modeldata.h"\n#include "model.h"',
    '#include "models/modeldata.h"\n#include "model.h"\n#include "gamefuncs.h"\n#include "hw_dynlightdata.h"\n#include "hw_lightbuffer.h"')
sub("source/core/rendering/scene/hw_sprites.cpp",
    "bool HWSprite::ProcessModel(HWDrawInfo* di, ModelTileFrame* pt, tspritetype* spr, sectortype* sector)\n{",
    r'''// ---- Luces dinámicas ----
CVAR(Bool, hw_dynlights, true, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
TArray<HWSceneLight> hw_scenelights;
void (*hw_gamelights)(TArray<HWSceneLight>& out, double interpfrac) = nullptr;

void hw_AddSceneLight(TArray<HWSceneLight>& out, const DVector3& pos, sectortype* sect, float radius, float r, float g, float b)
{
	if (!sect || radius <= 0 || out.Size() >= 64) return;
	HWSceneLight& l = out[out.Reserve(1)];
	l.mappos = pos;
	l.pos = FVector3((float)pos.X, (float)-pos.Z, (float)-pos.Y);
	l.radius = radius;
	l.color = FVector3(r, g, b);
	l.sector = sect;
	l.nsect = 0;
}

static double LightSegDistSq(const DVector2& p, const DVector2& a, const DVector2& b)
{
	DVector2 ab = b - a;
	double len = ab.LengthSquared();
	double t = len > 0 ? clamp(((p - a) | ab) / len, 0., 1.) : 0.;
	return (a + ab * t - p).LengthSquared();
}

void hw_CollectSceneLights(double interpfrac)
{
	hw_scenelights.Clear();
	if (!hw_dynlights || !hw_gamelights) return;
	hw_gamelights(hw_scenelights, interpfrac);
	for (auto& l : hw_scenelights)
	{
		// Sectores a los que llega: el suyo y, por huecos abiertos que estén a su alcance, los de al lado.
		l.sect[l.nsect++] = l.sector;
		DVector2 mp = l.mappos.XY();
		double r2 = (double)l.radius * l.radius;
		for (int i = 0; i < l.nsect && l.nsect < HWSceneLight::MAXSECT; i++)
		{
			for (auto& wal : l.sect[i]->walls)
			{
				if (!wal.twoSided()) continue;
				auto ns = wal.nextSector();
				if (!ns || ns->floorz <= ns->ceilingz) continue;	// puerta cerrada
				bool have = false;
				for (int k = 0; k < l.nsect; k++) if (l.sect[k] == ns) { have = true; break; }
				if (have || LightSegDistSq(mp, wal.pos, wal.point2Wall()->pos) > r2) continue;
				l.sect[l.nsect++] = ns;
				if (l.nsect >= HWSceneLight::MAXSECT) break;
			}
		}
	}
}

// Sube al búfer las luces que tocan una caja (coordenadas del render) en ese sector.
// attenuate: según el ángulo con la superficie (paredes y suelos); los sprites no tienen normal.
int hw_GetLights(sectortype* sec, const FVector3& mins, const FVector3& maxs, bool attenuate)
{
	if (hw_scenelights.Size() == 0 || !sec) return -1;
	static thread_local FDynLightData lightdata;	// (la «lightdata» global de Raze no está definida)
	lightdata.Clear();
	int n = 0;
	for (auto& l : hw_scenelights)
	{
		bool ok = false;
		for (int k = 0; k < l.nsect; k++) if (l.sect[k] == sec) { ok = true; break; }
		if (!ok) continue;
		float d = 0;
		for (int a = 0; a < 3; a++)
		{
			float v = l.pos[a];
			if (v < mins[a]) d += (mins[a] - v) * (mins[a] - v);
			else if (v > maxs[a]) d += (v - maxs[a]) * (v - maxs[a]);
		}
		if (d >= l.radius * l.radius) continue;
		float* data = &lightdata.arrays[0][lightdata.arrays[0].Reserve(16)];
		data[0] = l.pos.X; data[1] = l.pos.Y; data[2] = l.pos.Z; data[3] = l.radius;
		data[4] = l.color.X; data[5] = l.color.Y; data[6] = l.color.Z; data[7] = attenuate ? -1.f : 1.f;
		for (int i = 8; i < 16; i++) data[i] = 0;
		if (++n >= 8) break;
	}
	if (n == 0) return -1;
	return screen->mLights->UploadLights(lightdata);
}

int hw_GetFlatLights(sectortype* sec)
{
	if (hw_scenelights.Size() == 0 || !sec) return -1;
	float x0 = 1e30f, x1 = -1e30f, y0 = 1e30f, y1 = -1e30f;
	for (auto& wal : sec->walls)
	{
		float x = (float)wal.pos.X, y = (float)-wal.pos.Y;
		x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y);
	}
	return hw_GetLights(sec, FVector3(x0, -1e30f, y0), FVector3(x1, 1e30f, y1), true);
}

bool HWSprite::ProcessModel(HWDrawInfo* di, ModelTileFrame* pt, tspritetype* spr, sectortype* sector)
{''')
# Sprites, modelos y vóxeles: sus luces al dibujarlos (los aditivos, como las explosiones, no).
sub("source/core/rendering/scene/hw_sprites.cpp",
    "void HWSprite::DrawSprite(HWDrawInfo* di, FRenderState& state, bool translucent)\n{\n\tbool additivefog = false;",
    "int hw_GetLights(sectortype* sec, const FVector3& mins, const FVector3& maxs, bool attenuate);\n"
    "void HWSprite::DrawSprite(HWDrawInfo* di, FRenderState& state, bool translucent)\n{\n"
    "\tdynlightindex = -1;\n"
    "\tif (Sprite && hw_scenelights.Size() > 0 && RenderStyle.BlendOp != STYLEOP_Add)\n\t{\n"
    "\t\tFVector3 c((float)Sprite->pos.X, (float)-Sprite->pos.Z, (float)-Sprite->pos.Y);\n"
    "\t\tdynlightindex = hw_GetLights(Sprite->sectp, c - FVector3(24, 8, 24), c + FVector3(24, 72, 24), false);\n\t}\n"
    "\tbool additivefog = false;")
sub("source/core/rendering/scene/hw_sprites.cpp",
    "\t\tstate.SetLightIndex(-1);\n\t\tstate.Draw(DT_TriangleStrip, vertexindex, 4);",
    "\t\tstate.SetLightIndex(dynlightindex);\n\t\tstate.Draw(DT_TriangleStrip, vertexindex, 4);\n\t\tstate.SetLightIndex(-1);")
# Paredes.
sub("source/core/rendering/scene/hw_walls.cpp",
    "void HWWall::DrawWall(HWDrawInfo *di, FRenderState &state, bool translucent)\n{\n\trendered_lines++;",
    "int hw_GetLights(sectortype* sec, const FVector3& mins, const FVector3& maxs, bool attenuate);\n"
    "void HWWall::DrawWall(HWDrawInfo *di, FRenderState &state, bool translucent)\n{\n\trendered_lines++;\n"
    "\tdynlightindex = -1;\n"
    "\tif (hw_scenelights.Size() > 0 && texture != nullptr && type != RENDERWALL_MIRRORSURFACE && RenderStyle.BlendOp != STYLEOP_Add)\n"
    "\t\tdynlightindex = hw_GetLights(frontsector,\n"
    "\t\t\tFVector3(min(glseg.x1, glseg.x2), min(zbottom[0], zbottom[1]), min(glseg.y1, glseg.y2)),\n"
    "\t\t\tFVector3(max(glseg.x1, glseg.x2), max(ztop[0], ztop[1]), max(glseg.y1, glseg.y2)), true);")
sub("source/core/rendering/scene/hw_walls.cpp",
    '#include "hw_lightbuffer.h"',
    '#include "hw_lightbuffer.h"\n#include "gamefuncs.h"')
sub("source/core/rendering/scene/hw_walls.cpp",
    "\t\tdefault:\n\t\t\tRenderTranslucentWall(di, state);\n\t\t\tbreak;\n\t\t}\n\t}\n}",
    "\t\tdefault:\n\t\t\tRenderTranslucentWall(di, state);\n\t\t\tbreak;\n\t\t}\n\t}\n\tstate.SetLightIndex(-1);\n}")
# Suelos y techos.
sub("source/core/rendering/scene/hw_flats.cpp",
    "\tstate.SetLightIndex(dynlightindex);\n\tstate.Draw(DT_Triangles, vertindex, vertcount);",
    "\t{\n\t\tint hw_GetFlatLights(sectortype* sec);\n"
    "\t\tdynlightindex = (texture && RenderStyle.BlendOp != STYLEOP_Add) ? hw_GetFlatLights(sec) : -1;\n\t}\n"
    "\tstate.SetLightIndex(dynlightindex);\n\tstate.Draw(DT_Triangles, vertindex, vertcount);\n\tstate.SetLightIndex(-1);")
sub("source/core/rendering/scene/hw_flats.cpp",
    '#include "hw_lightbuffer.h"',
    '#include "hw_lightbuffer.h"\n#include "gamefuncs.h"')
# La lista de luces, una vez por fotograma (antes de los espejos y portales).
sub("source/core/rendering/hw_entrypoint.cpp",
    "\tscreen->mLights->Clear();\n\tscreen->mViewpoints->Clear();\n\tscreen->mVertexData->Reset();\n\n\tif (writingsavepic)",
    "\tscreen->mLights->Clear();\n\tscreen->mViewpoints->Clear();\n\tscreen->mVertexData->Reset();\n"
    "\t{\n\t\tvoid hw_CollectSceneLights(double interpfrac);\n\t\thw_CollectSceneLights(writingsavepic ? 1. : interpfrac);\n\t}\n\n\tif (writingsavepic)")
# Duke: qué da luz. Radio en unidades del mapa de Raze (1 = 16 de Build).
sub("source/games/duke/src/animatesprites.cpp",
    "\t\t\t\tgetPlayer(screenpeek)->visibility = -127;\n\t\t\t\tlastvisinc = PlayClock + 32;\n\t\t\t}\n\t\t}\n\n\t}\n}\nEND_DUKE_NS",
    r'''				getPlayer(screenpeek)->visibility = -127;
				lastvisinc = PlayClock + 32;
			}
		}

	}
}

int gDukeMuzzleClock = -1000;	// último disparo del jugador (lo apunta el bucle del juego)

static void DukeSceneLights(TArray<HWSceneLight>& out, double interpfrac)
{
	if (!isDuke()) return;
	struct LightDef { const char* cls; float radius, r, g, b; bool flicker; };
	static const LightDef defs[] = {
		{ "DukeExplosion2", 170, 1.0f, 0.6f, 0.25f, false },
		{ "DukeExplosion2Bot", 170, 1.0f, 0.6f, 0.25f, false },
		{ "DukeSeenine", 0, 0, 0, 0, false },
		{ "DukeRPG", 72, 1.0f, 0.6f, 0.25f, false },
		{ "DukeFireball", 90, 1.0f, 0.55f, 0.2f, true },
		{ "DukeFreezeBlast", 64, 0.3f, 0.55f, 1.0f, false },
		{ "DukeShrinkSpark", 64, 0.35f, 1.0f, 0.35f, false },
		{ "DukeShrinkerExplosion", 128, 0.35f, 1.0f, 0.35f, false },
		{ "DukeGrowSpark", 64, 1.0f, 0.35f, 0.35f, false },
		{ "DukeCoolExplosion1", 96, 0.8f, 0.35f, 1.0f, false },
		{ "DukeFirelaser", 48, 1.0f, 0.3f, 0.15f, false },
		{ "DukeDevastatorBlast", 72, 1.0f, 0.6f, 0.25f, false },
		{ "DukeShotSpark", 28, 1.0f, 0.8f, 0.45f, false },
		{ "DukeBurning", 72, 1.0f, 0.5f, 0.15f, true },
		{ "DukeBurning2", 72, 1.0f, 0.5f, 0.15f, true },
		{ "DukeFire", 72, 1.0f, 0.5f, 0.15f, true },
		{ "DukeFire2", 72, 1.0f, 0.5f, 0.15f, true },
		{ "DukeOnFire", 56, 1.0f, 0.5f, 0.15f, true },
		{ "DukeFloorFlame", 72, 1.0f, 0.5f, 0.15f, true },
		{ "DukeFlamethrowerFlame", 64, 1.0f, 0.5f, 0.15f, true },
		{ "DukeAtomicHealth", 48, 0.3f, 0.55f, 1.0f, false },
		{ "DukeTransporterStar", 56, 0.4f, 0.6f, 1.0f, false },
		{ "DukeTransporterBeam", 80, 0.4f, 0.6f, 1.0f, false },
		{ "DukeReactorSpark", 64, 0.6f, 0.8f, 1.0f, false },
		{ "DukeReactor2Spark", 64, 0.6f, 0.8f, 1.0f, false },
	};
	static TMap<FName, int> map;
	if (map.CountUsed() == 0)
		for (int i = 0; i < (int)countof(defs); i++) map.Insert(FName(defs[i].cls), i);

	DukeSpriteIterator it;
	while (auto act = it.Next())
	{
		if (act->spr.scale.X <= 0 || (act->spr.cstat & CSTAT_SPRITE_INVISIBLE) || !act->sector()) continue;
		auto idx = map.CheckKey(act->GetClass()->TypeName);
		if (!idx) continue;
		auto& d = defs[*idx];
		if (d.radius <= 0) continue;
		float rad = d.radius;
		if (d.flicker) rad *= 0.85f + 0.15f * sinf(PlayClock * 0.35f + act->GetIndex() * 1.7f);
		hw_AddSceneLight(out, act->interpolatedpos(interpfrac).plusZ(-6), act->sector(), rad, d.r, d.g, d.b);
	}
	// Fogonazo del arma del jugador (unos 80 ms tras cada disparo).
	auto p = getPlayer(screenpeek);
	auto pact = p ? p->GetActor() : nullptr;
	if (pact && pact->sector() && PlayClock - gDukeMuzzleClock >= 0 && PlayClock - gDukeMuzzleClock < 10)
	{
		auto pos = pact->getRenderPos(interpfrac) + DVector3(pact->spr.Angles.Yaw.ToVector() * 12, 8);
		hw_AddSceneLight(out, pos, pact->sector(), 120, 1.0f, 0.78f, 0.45f);
	}
}

static struct DukeSceneLightsInit { DukeSceneLightsInit() { hw_gamelights = DukeSceneLights; } } dukeSceneLightsInit;

END_DUKE_NS''')
sub("source/games/duke/src/gameloop.cpp",
    "\t\tfi.think();\n#ifdef __ANDROID__\n\t\tandroid_duke_tick();\n#endif\n",
    "\t\tfi.think();\n#ifdef __ANDROID__\n\t\tandroid_duke_tick();\n#endif\n"
    # El jugador dispara: sus armas ponen visibility a 0 (el fogonazo del original).
    "\t\t{\n\t\t\textern int gDukeMuzzleClock;\n\t\t\tauto mp = getPlayer(screenpeek);\n"
    "\t\t\tif (mp && mp->visibility == 0 && ud.const_visibility != 0) gDukeMuzzleClock = PlayClock;\n\t\t}\n")

# 32. Caché de shaders en OpenGL ES (Android). El motor compila un programa de GPU por cada
#     combinación de efectos la primera vez que aparece en pantalla; en las GPU Mali (RG477M)
#     cada compilación tarda y el juego daba parones. Raze trae una caché en disco, pero en GLES
#     no la usaba nunca (solo leía, sin glProgramBinary, y solo con gráficas Intel). Aquí se
#     guarda cada programa ya compilado (glGetProgramBinary) y se carga en las partidas siguientes
#     (glProgramBinary). Si el driver no acepta el guardado, se compila como siempre.
sub("source/common/rendering/gles/gles_shader.cpp",
    '\t\tactive = !(strstr(vendor, "Intel") == nullptr);',
    '\t\tactive = !(strstr(vendor, "Intel") == nullptr);\n#ifdef __ANDROID__\n\t\tactive = true;\n#endif')
sub("source/common/rendering/gles/gles_shader.cpp",
    '#include <map>\n#include <memory>',
    '#include <map>\n#include <memory>\n#ifdef __ANDROID__\n#include <EGL/egl.h>\n#include <chrono>\n'
    'typedef void (*RazeProgramBinaryFn)(GLuint, GLenum, const void*, GLsizei);\n'
    'typedef void (*RazeGetProgramBinaryFn)(GLuint, GLsizei, GLsizei*, GLenum*, void*);\n'
    'typedef void (*RazeProgramParameteriFn)(GLuint, GLenum, GLint);\n#endif')
sub("source/common/rendering/gles/gles_shader.cpp",
    "\tbool linked = false;\n\n\tif (!linked)\n\t{\n\t\tshaderData->hVertProg = glCreateShader(GL_VERTEX_SHADER);",
    "\tbool linked = false;\n"
    "#ifdef __ANDROID__\n"
    "\tstatic auto pProgramBinary = (RazeProgramBinaryFn)eglGetProcAddress(\"glProgramBinary\");\n"
    "\tstatic auto pGetProgramBinary = (RazeGetProgramBinaryFn)eglGetProcAddress(\"glGetProgramBinary\");\n"
    "\tstatic auto pProgramParameteri = (RazeProgramParameteriFn)eglGetProcAddress(\"glProgramParameteri\");\n"
    "\tif (binary.Size() > 0 && pProgramBinary)\n\t{\n"
    "\t\tpProgramBinary(shaderData->hShader, binaryFormat, binary.Data(), (GLsizei)binary.Size());\n"
    "\t\tGLint ok = 0;\n\t\tglGetProgramiv(shaderData->hShader, GL_LINK_STATUS, &ok);\n"
    "\t\tlinked = ok == GL_TRUE;\n"
    "\t\tif (!linked) { glDeleteProgram(shaderData->hShader); shaderData->hShader = glCreateProgram(); binary.Clear(); }\n\t}\n"
    "\tauto compileStart = std::chrono::steady_clock::now();\n"
    "#endif\n\n"
    "\tif (!linked)\n\t{\n"
    "#ifdef __ANDROID__\n"
    "\t\tif (pProgramParameteri) pProgramParameteri(shaderData->hShader, 0x8257 /* GL_PROGRAM_BINARY_RETRIEVABLE_HINT */, GL_TRUE);\n"
    "#endif\n"
    "\t\tshaderData->hVertProg = glCreateShader(GL_VERTEX_SHADER);")
sub("source/common/rendering/gles/gles_shader.cpp",
    "\t\tglGetProgramiv(shaderData->hShader, GL_LINK_STATUS, &status);\n\t\tlinked = (status == GL_TRUE);\n",
    "\t\tglGetProgramiv(shaderData->hShader, GL_LINK_STATUS, &status);\n\t\tlinked = (status == GL_TRUE);\n"
    "#ifdef __ANDROID__\n"
    "\t\tif (linked)\n\t\t{\n"
    "\t\t\tint ms = (int)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - compileStart).count();\n"
    "\t\t\tPrintf(\"Shader compilado: %s (%d ms)\\n\", name, ms);\n"
    "\t\t\tGLint len = 0;\n"
    "\t\t\tglGetProgramiv(shaderData->hShader, 0x8741 /* GL_PROGRAM_BINARY_LENGTH */, &len);\n"
    "\t\t\tif (len > 0 && pGetProgramBinary && IsShaderCacheActive())\n\t\t\t{\n"
    "\t\t\t\tTArray<uint8_t> out;\n\t\t\t\tout.Resize(len);\n\t\t\t\tGLenum fmt = 0;\n"
    "\t\t\t\tpGetProgramBinary(shaderData->hShader, len, &len, &fmt, out.Data());\n"
    "\t\t\t\tif (len > 0) { out.Resize(len); SaveCachedProgramBinary(vp_comb, fp_comb, out, fmt); }\n"
    "\t\t\t}\n\t\t}\n"
    "#endif\n")
#  El archivo de la caché se reescribía entero con cada shader nuevo (con muchos, otro parón):
#  en Android se añade cada uno al final (cabecera con recuento 0xFFFFFFFF = «hasta el final») y
#  una entrada a medias (si se cerró el juego escribiendo) solo se descarta ella.
sub("source/common/rendering/gles/gles_shader.cpp",
    "\t\tuint32_t count = fr.ReadUInt32();\n\t\tif (count > 512)\n\t\t\tI_Error(\"Too many shaders cached\");\n\n"
    "\t\tfor (uint32_t i = 0; i < count; i++)\n\t\t{\n\t\t\tchar hexdigest[33];\n\t\t\tif (fr.Read(hexdigest, 32) != 32)\n\t\t\t\tI_Error(\"Read error\");",
    "\t\tuint32_t count = fr.ReadUInt32();\n\t\tbool appended = count == 0xFFFFFFFFu;\n"
    "\t\tif (count > 512 && !appended)\n\t\t\tI_Error(\"Too many shaders cached\");\n\n"
    "\t\tfor (uint32_t i = 0; appended || i < count; i++)\n\t\t{\n\t\t\tchar hexdigest[33];\n"
    "\t\t\tif (fr.Read(hexdigest, 32) != 32)\n\t\t\t{\n\t\t\t\tif (appended) break;\n\t\t\t\tI_Error(\"Read error\");\n\t\t\t}")
sub("source/common/rendering/gles/gles_shader.cpp",
    "\t\t\tbinary->data.Resize(size);\n\t\t\tif (fr.Read(binary->data.Data(), binary->data.Size()) != binary->data.Size())\n\t\t\t\tI_Error(\"Read error\");",
    "\t\t\tbinary->data.Resize(size);\n\t\t\tif (fr.Read(binary->data.Data(), binary->data.Size()) != binary->data.Size())\n\t\t\t{\n"
    "\t\t\t\tif (appended) break;\n\t\t\t\tI_Error(\"Read error\");\n\t\t\t}")
sub("source/common/rendering/gles/gles_shader.cpp",
    "\tentry->format = binaryFormat;\n\tentry->data = binary;\n\n\tSaveShaders();",
    "\tentry->format = binaryFormat;\n\tentry->data = binary;\n\n"
    "#ifdef __ANDROID__\n"
    "\t{\n\t\tFString path = CreateProgramCacheName(true);\n"
    "\t\tFILE* f = fopen(path.GetChars(), \"r+b\");\n"
    "\t\tbool fresh = false;\n"
    "\t\tif (f)\n\t\t{\n\t\t\tchar magic[4] = {};\n\t\t\tuint32_t cnt = 0;\n"
    "\t\t\tfresh = fread(magic, 1, 4, f) != 4 || memcmp(magic, ShaderMagic, 4) != 0 || fread(&cnt, 4, 1, f) != 1 || cnt != 0xFFFFFFFFu;\n"
    "\t\t\tfclose(f);\n\t\t}\n\t\telse fresh = true;\n"
    "\t\tf = fopen(path.GetChars(), fresh ? \"wb\" : \"ab\");\n"
    "\t\tif (f)\n\t\t{\n"
    "\t\t\tif (fresh) { uint32_t all = 0xFFFFFFFFu; fwrite(ShaderMagic, 1, 4, f); fwrite(&all, 4, 1, f); }\n"
    "\t\t\tFString key = CalcProgramBinaryChecksum(vertex, fragment);\n"
    "\t\t\tuint32_t size = binary.Size();\n"
    "\t\t\tfwrite(key.GetChars(), 1, 32, f);\n\t\t\tfwrite(&binaryFormat, 4, 1, f);\n\t\t\tfwrite(&size, 4, 1, f);\n"
    "\t\t\tfwrite(binary.Data(), 1, size, f);\n\t\t\tfclose(f);\n\t\t}\n\t\treturn;\n\t}\n"
    "#endif\n\tSaveShaders();")

# 33. Con «Modelos 3D» apagado (hw_models 0) no se leen los modelos de los .def: arranca antes y
#     con menos memoria. Y, para el fallo del Caribe / Nuclear Winter con el pack HD, el registro
#     apunta qué actor del guion se estaba creando.
sub("source/core/defparser.cpp",
    "static void parseModel(FScanner& sc, FScriptPosition& pos)\n{\n\tFScanner::SavedPos blockend;\n",
    "EXTERN_CVAR(Bool, hw_models)\n"
    "static void parseModel(FScanner& sc, FScriptPosition& pos)\n{\n\tFScanner::SavedPos blockend;\n"
    "\tif (!hw_models)\n\t{\n"
    "\t\tFString skipfn;\n\t\tif (!sc.GetString(skipfn)) return;\n"
    "\t\tif (sc.StartBraces(&blockend)) return;\n"
    "\t\tsc.RestorePos(blockend);\n\t\tsc.CheckString(\"}\");\n\t\treturn;\n\t}\n")
sub("source/games/duke/src/game.cpp",
    "\t\t\t\tauto newcls = (PClassActor*)RUNTIME_CLASS(DDukeActor)->CreateDerivedClass(FStringf(\"NewConActor%d\", i), RUNTIME_CLASS(DDukeActor)->Size);",
    "\t\t\t\tPrintf(\"Clase nueva para el actor del guion %d\\n\", i);\n"
    "\t\t\t\tauto newcls = (PClassActor*)RUNTIME_CLASS(DDukeActor)->CreateDerivedClass(FStringf(\"NewConActor%d\", i), RUNTIME_CLASS(DDukeActor)->Size);")
