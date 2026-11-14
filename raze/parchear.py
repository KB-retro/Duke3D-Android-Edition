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
#     solo están ahí), pero sin «Salir del juego» (repetido: la salida va en el menú rápido de la
#     app). Se quita la entrada del menú principal y del de partida (y las de debajo suben).
sub("source/core/menu/razemenu.cpp",
    "void BuildGameMenus()\n{\n\tBuildEpisodeMenu();",
    "#ifdef __ANDROID__\nstatic void android_hide_options(FName name)\n{\n"
    "\tDMenuDescriptor** desc = MenuDescriptors.CheckKey(name);\n"
    "\tif (!desc || !(*desc)->IsKindOf(RUNTIME_CLASS(DListMenuDescriptor))) return;\n"
    "\tauto ld = static_cast<DListMenuDescriptor*>(*desc);\n"
    "\tconst char* lang = getenv(\"DUKE3D_LANG\");\n"
    "\tbool es = lang && !strcmp(lang, \"es\");\n\tint removed = 0;\n"
    "\tfor (unsigned i = 0; i < ld->mItems.Size(); i++)\n\t{\n"
    # «Terminar partida» (vuelve al título): en castellano Raze lo traduce igual que «Salir».
    "\t\tif (ld->mItems[i]->mAction == FName(\"EndgameMenu\"))\n\t\t{\n"
    "\t\t\tld->mItems[i]->StringVar(FName(\"mText\")) = es ? \"Terminar partida\" : \"End Game\";\n"
    "\t\t\tcontinue;\n\t\t}\n"
    "\t\tif (ld->mItems[i]->mAction != FName(\"QuitMenu\")) continue;\n"
    "\t\tfor (unsigned j = i + 1; j < ld->mItems.Size(); j++) ld->mItems[j]->mYpos -= ld->mLinespacing;\n"
    "\t\tld->mItems.Delete(i);\n"
    "\t\tif (ld->mSelectedItem > (int)i) ld->mSelectedItem--;\n"
    "\t\tremoved++;\n"
    "\t\ti--;\n\t}\n"
    # Con menos opciones, las que quedan bajan para seguir centradas.
    "\tfor (auto item : ld->mItems)\n"
    "\t\tif (item->mAction != NAME_None) item->mYpos += removed * ld->mLinespacing / 2.;\n"
    "}\n#endif\n\n"
    "void BuildGameMenus()\n{\n#ifdef __ANDROID__\n\tandroid_hide_options(NAME_Mainmenu);\n"
    "\tandroid_hide_options(NAME_IngameMenu);\n#endif\n\tBuildEpisodeMenu();")

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
sub("source/core/models/modeldata.h",
    "\t\treturn tilenum.GetIndex() + (palette < 20);",
    "\t\treturn tilenum.GetIndex() + (palette << 20);")
sub("source/core/models/modeldata.h",
    "\t\treturn frameMap.CheckKey(FrameMapKey(tilenum, pal)) != nullptr;",
    "\t\treturn GetModel(tilenum, pal) != nullptr;")
sub("source/core/models/modeldata.h",
    "\t\treturn frameMap.CheckKey(FrameMapKey(tilenum, pal));",
    # Como EDuke32: si no hay modelo para esa paleta, el de la paleta 0.
    "\t\tauto f = frameMap.CheckKey(FrameMapKey(tilenum, pal));\n"
    "\t\tif (!f && pal != 0) f = frameMap.CheckKey(FrameMapKey(tilenum, 0));\n"
    "\t\treturn f;")
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
    "\tvoxmodel_t* voxel;\n\tclass FModel* mdl;\n\tstruct ModelDescriptor* mddesc;\n\tint mdframe, mdskinnum;\n\n\tint index;")
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
    "\t\t\tmdl->RenderFrame(&mr, single, mdframe, mdframe, 0.f, NO_TRANSLATION, (single || !any) ? nullptr : skins, a, 0);\n"
    "\t\t\tmr.EndDrawModel(RenderStyle, 0);\n"
    "\t\t\tstate.SetDepthFunc(DF_Less);\n"
    "\t\t}")
sub("source/core/rendering/scene/hw_sprites.cpp",
    "bool HWSprite::ProcessVoxel(HWDrawInfo* di, voxmodel_t* vox, tspritetype* spr, sectortype* sector, bool rotate)\n{",
    r'''bool HWSprite::ProcessModel(HWDrawInfo* di, ModelTileFrame* pt, tspritetype* spr, sectortype* sector)
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
	mdframe = pt->framenum;
	mdskinnum = pt->skinnum;
	dynlightindex = -1;
	shade = clamp(spr->shade + desc->shadeoff, -128, 127);
	palette = spr->pal;
	fade = lookups.getFade(sector->floorpal);
	visibility = sectorVisibility(sector);
	SetSpriteTranslucency(spr, alpha, RenderStyle);

	auto ang = spr->Angles.Yaw + ownerActor->sprext.rot.Yaw;
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
