# CMakeLists.txt — punto de entrada nativo del launcher (Etapa 2)
#
# DOS MODOS (condicional, para no romper la Etapa 1):
#
# 1) CON árbol Vita3K (app/src/main/cpp/vita3k/CMakeLists.txt existe):
#    el árbol lo aporta CI: el workflow lo clona (commit fijado) antes
#    de compilar (ver docs/etapa2/workflow-android-native.yml). Se
#    compila el núcleo real como libVita3K.so (target `vita3k` de
#    upstream) + la capa adaptadora propia (launcher/), que añade a
#    ese target los puentes JNI de ESTE paquete. Es el modelo EmuCoreV.
#
# 2) SIN árbol Vita3K (caso actual, Etapa 1): se compila SOLO el stub
#    Vita3kBridge.cpp como libvita3k-bridge.so. Todas sus funciones
#    devuelven NOT_IMPLEMENTED y el launcher sigue con el fallback a
#    Vita3K externo. La compilación de Etapa 1 en GitHub Actions NO
#    necesita NDK en este modo (app/build.gradle.kts solo activa
#    externalNativeBuild cuando el árbol existe).
#
# Requisito Play Store (verificado en EmuCoreV): páginas de 16 KiB.
cmake_minimum_required(VERSION 3.22.1)

project(ChildOfLightBridge)

if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/vita3k/CMakeLists.txt)
    # Modo 1: núcleo real. El CMakeLists de Vita3K (raíz del repo
    # upstream) hace add_subdirectory(vita3k) con la librería
    # `vita3k` (rama ANDROID) + todas sus dependencias. Requiere
    # NDK 29 + vcpkg (los instala el workflow, ver docs/etapa2/).
    message(STATUS "ChildOfLight: árbol Vita3K presente, compilando núcleo real + adaptador")
    add_subdirectory(vita3k)
    add_subdirectory(launcher)
else()
    # Modo 2: stub sin núcleo (Etapa 1). No finge que el núcleo funciona.
    message(STATUS "ChildOfLight: sin árbol Vita3K, compilando solo el stub vita3k-bridge")
    add_library(vita3k-bridge SHARED Vita3kBridge.cpp)

    # Google Play exige soporte de páginas de 16 KiB en apps nuevas.
    target_link_options(vita3k-bridge PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )

    find_library(log-lib log)
    target_link_libraries(vita3k-bridge ${log-lib})
endif()
