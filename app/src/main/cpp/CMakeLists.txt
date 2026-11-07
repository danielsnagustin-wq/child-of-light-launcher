# CMakeLists.txt — puente JNI de Etapa 2 (ESQUELETO, no funcional aún)
#
# Este archivo compila SOLO el stub Vita3kBridge.cpp como libvita3k-bridge.so.
# NO compila el núcleo Vita3K real: el árbol vendorizado (app/src/main/cpp/vita3k)
# no existe todavía en el repo. Cuando exista (ver docs/etapa2/ETAPA2-PLAN.md),
# este archivo será REEMPLAZADO por el punto de entrada estilo EmuCoreV:
#   add_subdirectory(vita3k)  + capa adaptadora en el target `vita3k`.
#
# IMPORTANTE: app/build.gradle.kts NO referencia este CMakeLists todavía.
# No se activa externalNativeBuild hasta que exista el núcleo real, para
# no romper la compilación de Etapa 1 en GitHub Actions (que no instala NDK).
# El stub existe para fijar el contrato JNI (nombres de función y códigos)
# y para compilarse a mano en Android Studio si se quiere probar la carga.
#
# Requisito Play Store (verificado en EmuCoreV): páginas de 16 KiB.
cmake_minimum_required(VERSION 3.22.1)

project(ChildOfLightBridge)

add_library(vita3k-bridge SHARED Vita3kBridge.cpp)

# Google Play exige soporte de páginas de 16 KiB en apps nuevas.
target_link_options(vita3k-bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(vita3k-bridge ${log-lib})
