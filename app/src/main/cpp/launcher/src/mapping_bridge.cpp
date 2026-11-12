// mapping_bridge.cpp — puente JNI de la máscara de mapeo de memoria
// (ronda 0.2.64, P1-3 Mali).
//
// Función JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para preguntar al núcleo qué métodos de mapeo de memoria soporta el
// driver. Reenvía a native_config.cpp de upstream (pin 3e43bc80,
// getSupportedMemoryMappingMask:804); sin árbol Vita3K compila como
// stub (0 = desconocida) y el llamador no filtra nada.
//
// POR QUÉ EXISTE ESTE PUENTE (verificado contra upstream, pin
// 3e43bc80): el método de mapeo pedido en config.yml
// (memory-mapping-method) solo se honra si su bit está en la máscara
// soportada (vulkan/renderer.cpp:1019-1022); si no, el núcleo vuelve
// a Double Buffer EN SILENCIO (renderer.cpp:750). En Mali, Native
// Buffer no siempre está soportado y Page Table depende del driver:
// sin la máscara, la UI ofrecía 5 métodos sin saber cuáles existen
// y el usuario no veía por qué su elección no tenía efecto.
//
// Robustez JNI: igual que driver_bridge.cpp — si upstream deja una
// excepción pendiente se limpia y se devuelve 0 (desconocida); una
// excepción C++ sin capturar cruzando JNI aborta el proceso, así que
// se captura y se devuelve 0. El argumento es el nombre del driver
// Turnip instalado ("" = driver del sistema); null desde Kotlin no
// puede llegar (String no nulo), pero se protege igual.

#include <jni.h>
#include <android/log.h>

#include <exception>

#define LOG_TAG "LauncherBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// launcher_bridge.h se incluye siempre (no solo con núcleo): el
// helper de limpiar excepciones pendientes se usa también en la
// rama stub.
#include "launcher_bridge.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetSupportedMemoryMappingMask(
    JNIEnv *env, jobject /* thiz */, jstring customDriverName) {
#ifdef LAUNCHER_HAS_VITA3K
    if (customDriverName == nullptr) {
        LOGW("nativeGetSupportedMemoryMappingMask: nombre de driver nulo");
        return 0;
    }
    jint mask = 0;
    try {
        mask = Java_org_vita3k_emulator_NativeLib_getSupportedMemoryMappingMask(env, nullptr, customDriverName);
    } catch (const std::exception &e) {
        LOGE("nativeGetSupportedMemoryMappingMask: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return 0;
    } catch (...) {
        LOGE("nativeGetSupportedMemoryMappingMask: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return 0;
    }
    // Si upstream dejó una excepción pendiente, la máscara no es
    // fiable: se limpia y se devuelve 0 (desconocida).
    if (launcher_clear_pending_exception(env)) {
        return 0;
    }
    return mask;
#else
    LOGW("nativeGetSupportedMemoryMappingMask STUB: núcleo no embebido");
    return 0;
#endif
}
