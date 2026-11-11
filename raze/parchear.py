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

# 13. En Android solo Vulkan (sin OpenGL ni OpenGL ES), diga lo que diga la configuración.
sub("source/common/rendering/v_video.cpp",
    "int V_GetBackend()\n{",
    "int V_GetBackend()\n{\n#ifdef __ANDROID__\n\treturn 1;\n#endif")

# 14. Errores graves: en Android se escriben en el registro (no hay kdialog ni GTK) y se
#     enseñan con el cuadro de SDL.
sub("source/common/platform/posix/sdl/i_system.cpp",
    "void I_ShowFatalError(const char *message)\n{\n#ifdef __APPLE__",
    "void I_ShowFatalError(const char *message)\n{\n#ifdef __ANDROID__\n"
    "\tfprintf(stderr, \"ERROR GRAVE: %s\\n\", message);\n"
    "\tSDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, \"Raze\", message, NULL);\n\treturn;\n#endif\n#ifdef __APPLE__")

# 15. Algunas GPU de móvil (Mali) dicen que admiten bloques uniformes de 4 GB (sale negativo):
#     se limita a 64 KB como en OpenGL.
sub("source/common/rendering/vulkan/system/vk_renderdevice.cpp",
    "\tmaxuniformblock = device->PhysicalDevice.Properties.Properties.limits.maxUniformBufferRange;",
    "\tmaxuniformblock = std::min<uint32_t>(device->PhysicalDevice.Properties.Properties.limits.maxUniformBufferRange, 65536);")

# 16. Diagnóstico: con RAZE_VKDEBUG se activan las capas de validación de Vulkan (van en el
#     APK de diagnóstico) aunque la configuración guardada diga lo contrario.
sub("source/common/platform/posix/sdl/sdlglvideo.cpp",
    "\t\t\tbuilder.DebugLayer(vk_debug);",
    "#ifdef __ANDROID__\n\t\t\tbuilder.DebugLayer(vk_debug || getenv(\"RAZE_VKDEBUG\") != nullptr);\n#else\n"
    "\t\t\tbuilder.DebugLayer(vk_debug);\n#endif")

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

# 18. Driver Vulkan alternativo (malisx2, opción de Extras): ver android_raze.cpp.
sub("libraries/ZVulkan/src/vulkaninstance.cpp",
    '#include "vulkaninstance.h"\n',
    '#include "vulkaninstance.h"\n#ifdef __ANDROID__\nextern "C" void* android_raze_vk_gipa();\n#endif\n')
sub("libraries/ZVulkan/src/vulkaninstance.cpp",
    "\tif (volkInitialize() != VK_SUCCESS)",
    "#ifdef __ANDROID__\n\tif (void* gipa = android_raze_vk_gipa()) volkInitializeCustom((PFN_vkGetInstanceProcAddr)gipa); else\n#endif\n"
    "\tif (volkInitialize() != VK_SUCCESS)")
sub("source/common/platform/posix/sdl/sdlglvideo.cpp",
    "bool I_GetVulkanPlatformExtensions(unsigned int *count, const char **names)\n{",
    "#ifdef __ANDROID__\nbool android_raze_custom_driver();\nbool android_raze_vk_extensions(unsigned int *count, const char **names);\n"
    "bool android_raze_vk_surface(SDL_Window *win, VkInstance instance, VkSurfaceKHR *surface);\n#endif\n"
    "bool I_GetVulkanPlatformExtensions(unsigned int *count, const char **names)\n{\n"
    "#ifdef __ANDROID__\n\tif (android_raze_custom_driver()) return android_raze_vk_extensions(count, names);\n#endif")
sub("source/common/platform/posix/sdl/sdlglvideo.cpp",
    "bool I_CreateVulkanSurface(VkInstance instance, VkSurfaceKHR *surface)\n{",
    "bool I_CreateVulkanSurface(VkInstance instance, VkSurfaceKHR *surface)\n{\n"
    "#ifdef __ANDROID__\n\tif (android_raze_custom_driver()) return android_raze_vk_surface(Priv::window, instance, surface);\n#endif")
sub("source/CMakeLists.txt",
    "\ttarget_link_libraries( ${PROJECT_NAME} log android EGL GLESv2 )",
    "\ttarget_link_libraries( ${PROJECT_NAME} log android EGL GLESv2 ${ADRENOTOOLS_LIBS} )")
