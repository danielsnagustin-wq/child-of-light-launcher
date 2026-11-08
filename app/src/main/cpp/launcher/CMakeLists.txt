# launcher/ — capa adaptadora JNI del launcher (Etapa 2)
#
# Se añade SOLO cuando el árbol Vita3K está presente (lo decide el
# CMakeLists superior). Sigue el modelo EmuCoreV: no se crea una
# librería propia; las fuentes del adaptador se añaden al target
# `vita3k` de upstream (libVita3K.so), así que ven los mismos
# símbolos e includes que interface.cpp y android/jni/*.cpp.
#
# Qué hace cada fuente (todas reenvían a funciones JNI de upstream,
# declaradas en include/launcher_bridge.h; upstream las ignora el
# parámetro jclass, así que el adaptador pasa nullptr):
# - runtime_bridge.cpp: prepareFrontend/init/loadGame/getFps/isInitialized/destroySession
# - install_bridge.cpp: installPkg/installArchive/installFirmware/
#   copyLicense/createLicense/findPkgZrif/convertRifToZrif
# - session_bridge.cpp: isAppRunning/requestAppQuit/isAppPaused/
#   getRunningAppTitle/getFirmwareInstallStateMask/refreshAppsList/
#   setPauseReasonEnabled/setInputIntercepted
# - driver_bridge.cpp: installCustomDriver/getInstalledCustomDrivers/
#   removeCustomDriver (Turnip, solo Adreno)
# - input_overlay_bridge.cpp: overlay táctil -> mando virtual SDL de
#   upstream (attach/detach/setButton/setAxis/setTouchState)
# - driver_bridge.cpp: installCustomDriver/getInstalledCustomDrivers/
#   removeCustomDriver (drivers Turnip; sin este puente el
#   custom-driver-name del config.yml no apuntaba a ningún driver
#   instalado en el núcleo)
#
# LAUNCHER_HAS_VITA3K=1 marca que el árbol existe. Sin él, los
# puentes compilan como stubs NOT_IMPLEMENTED (por si alguna vez se
# compilan fuera del target `vita3k`).
if(NOT TARGET vita3k)
    message(FATAL_ERROR "launcher: el target 'vita3k' no existe; la capa adaptadora solo se añade con el árbol Vita3K presente")
endif()

target_sources(vita3k PRIVATE
    src/runtime_bridge.cpp
    src/install_bridge.cpp
    src/session_bridge.cpp
    src/driver_bridge.cpp
    src/input_overlay_bridge.cpp
)

target_include_directories(vita3k PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    # Cabeceras de upstream que usan los puentes (además de las que
    # el propio target `vita3k` ya expone a sus fuentes).
    ${CMAKE_CURRENT_SOURCE_DIR}/../vita3k
    ${CMAKE_CURRENT_SOURCE_DIR}/../vita3k/android/jni
    ${CMAKE_CURRENT_SOURCE_DIR}/../vita3k/app/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../vita3k/emuenv/include
)

target_compile_definitions(vita3k PRIVATE LAUNCHER_HAS_VITA3K=1)

# Google Play exige soporte de páginas de 16 KiB en apps nuevas.
target_link_options(vita3k PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(vita3k PRIVATE ${log-lib})
