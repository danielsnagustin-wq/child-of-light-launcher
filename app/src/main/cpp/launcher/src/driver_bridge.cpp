// driver_bridge.cpp — puente JNI de drivers GPU personalizados (Etapa 2).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para instalar/listar/borrar drivers Turnip DENTRO del núcleo embebido.
// Reenvían a native_drivers.cpp de upstream; sin árbol Vita3K compilan
// como stubs ("" / vacío / false), y el llamador sigue con el driver
// del sistema.
//
// POR QUÉ EXISTE ESTE PUENTE (verificado contra upstream, pin 3e43bc80):
// `custom-driver-name` en config.yml NO es el zip ni una ruta: es el
// NOMBRE DEL DRIVER instalado, que upstream define como el stem del
// zip (native_drivers.cpp: installCustomDriver usa
// archive_path.filename().stem()). Y el núcleo NO lee el zip del
// launcher: busca una CARPETA ya extraída en
// <filesDir>/driver/<nombre>/ con meta.json o driver_name.txt
// (util/android_driver.cpp: resolve_custom_driver_library_path).
// Sin llamar a installCustomDriver, el config.yml nombraba un driver
// que no existía en <filesDir>/driver/ y el núcleo caía en silencio
// al driver del sistema (renderer.cpp: resolve_vk_get_instance_proc_addr
// avisa "Failed to load custom driver ... falling back to the system
// Vulkan loader"). El launcher guardaba el zip en
// <filesDir>/vita3k/drivers/turnip/ como copia propia; ESTE puente es
// el que lo instala de verdad en el núcleo.
//
// Robustez JNI: igual que install_bridge.cpp — si upstream deja una
// excepción pendiente se limpia y se devuelve el valor de "no hay
// driver" ("" / array vacío / false); las funciones que devuelven
// String/array casi nunca devuelven null (Kotlin los trata como no
// nulos): solo si la JVM no puede crear ni el valor vacío (OOM),
// caso en el que el helper limpia la excepción y devuelve null en
// vez de devolver con una excepción pendiente (P2-B5).

#include <jni.h>
#include <android/log.h>

#include <exception>

#define LOG_TAG "LauncherBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// launcher_bridge.h se incluye siempre (no solo con núcleo): los
// helpers de cadena vacía y array vacío (P2-B5) se usan también en
// las ramas stub.
#include "launcher_bridge.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallCustomDriver(
    JNIEnv *env, jobject /* thiz */, jstring zipPath) {
#ifdef LAUNCHER_HAS_VITA3K
    if (zipPath == nullptr) {
        LOGW("nativeInstallCustomDriver: ruta nula");
        return launcher_new_empty_string(env);
    }
    // Upstream devuelve el nombre del driver (= stem del zip) o "".
    // P4: installCustomDriver extrae un zip y toca el sistema de
    // archivos (pin 3e43bc80, native_drivers.cpp): una excepción C++
    // sin capturar cruzando JNI aborta el proceso; se captura y se
    // devuelve "" (el llamador usa el driver del sistema).
    jstring name = nullptr;
    try {
        name = Java_org_vita3k_emulator_NativeLib_installCustomDriver(env, nullptr, zipPath);
    } catch (const std::exception &e) {
        LOGE("nativeInstallCustomDriver: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return launcher_new_empty_string(env);
    } catch (...) {
        LOGE("nativeInstallCustomDriver: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return launcher_new_empty_string(env);
    }
    // P2-B1: evaluar el clear ANTES del null (el || cortocircuitaba
    // y el NewStringUTF("") corría con la excepción pendiente,
    // devolviendo null a un String no nulo de Kotlin). P2-B5: la
    // cadena vacía se crea con el helper, que limpia si NewStringUTF
    // falla por OOM.
    const bool hadException = launcher_clear_pending_exception(env);
    if (name == nullptr || hadException) {
        return launcher_new_empty_string(env);
    }
    LOGI("nativeInstallCustomDriver: instalado en el núcleo");
    return name;
#else
    LOGW("nativeInstallCustomDriver STUB: núcleo no embebido");
    return launcher_new_empty_string(env);
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetInstalledCustomDrivers(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    jobjectArray drivers = Java_org_vita3k_emulator_NativeLib_getInstalledCustomDrivers(env, nullptr);
    // P2-B1: evaluar el clear ANTES del null (el || cortocircuitaba).
    // P2-B5: el array vacío se crea con el helper (FindClass y
    // NewObjectArray comprobados; nullptr solo si no se pudo crear).
    const bool hadException = launcher_clear_pending_exception(env);
    if (drivers == nullptr || hadException) {
        return launcher_new_empty_string_array(env);
    }
    return drivers;
#else
    return launcher_new_empty_string_array(env);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRemoveCustomDriver(
    JNIEnv *env, jobject /* thiz */, jstring driverName) {
#ifdef LAUNCHER_HAS_VITA3K
    if (driverName == nullptr) {
        return JNI_FALSE;
    }
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_removeCustomDriver(env, nullptr, driverName);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    LOGW("nativeRemoveCustomDriver STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}
