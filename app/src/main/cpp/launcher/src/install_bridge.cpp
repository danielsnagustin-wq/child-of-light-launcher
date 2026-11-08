// install_bridge.cpp — puente JNI de instalación (Etapa 2).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para instalar juego/DLC/update/firmware DENTRO del núcleo embebido.
// Reenvían a native_install.cpp de upstream; sin árbol Vita3K compilan
// como stubs (false / ""), y el llamador usa la vía de Etapa 1.
//
// El callback de progreso (EmbeddedInstallCallback de Kotlin) se pasa
// TAL CUAL como jobject: upstream lo llama por duck-typing buscando
// el método onProgress(int, String) (ScopedJniCallback). El método
// de Kotlin se llama EXACTAMENTE así, por eso funciona sin adaptar
// nada en C++.
//
// Orden de instalación de un .pkg (ver core/EmbeddedPkgInstaller.kt):
// convertRifToZrif(work.bin) -> installPkg(pkg, zrif, cb) ->
// si no había zRIF, copyLicense(work.bin) como respaldo.
//
// Robustez JNI: los jstring nunca deben ser null (Kotlin los declara
// no nulos), pero si llegara uno, upstream haría GetStringUTFChars
// sobre null; el puente devuelve el error antes. Y si upstream deja
// una excepción pendiente, se limpia y se devuelve el error: el
// llamador Kotlin solo captura UnsatisfiedLinkError y trata ""/false
// como fallo, así que una excepción pendiente sería un crash en vez
// de un fallo controlado. Las funciones que devuelven String casi
// nunca devuelven null (Kotlin haría .isEmpty() sobre el resultado):
// solo si la JVM no puede crear ni la cadena vacía (OOM), caso en el
// que el helper limpia la excepción y devuelve null en vez de
// devolver con una excepción pendiente (P2-B5).

#include <jni.h>
#include <android/log.h>

#include <exception>

#define LOG_TAG "LauncherBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// launcher_bridge.h se incluye siempre (no solo con núcleo): los
// helpers de cadena vacía (P2-B5) se usan también en las ramas stub.
#include "launcher_bridge.h"

#ifdef LAUNCHER_HAS_VITA3K
// Upstream devolvió null o lanzó: error limpio ("") en vez de null.
// P2-B1: el clear se evalúa ANTES del null (el || cortocircuitaba y
// el NewStringUTF("") corría con la excepción pendiente, devolviendo
// null a un String no nulo de Kotlin). P2-B5: la cadena vacía se crea
// con el helper, que limpia si NewStringUTF falla por OOM.
static jstring launcher_empty_string_or_null(JNIEnv *env, jstring result) {
    const bool hadException = launcher_clear_pending_exception(env);
    if (result == nullptr || hadException) {
        return launcher_new_empty_string(env);
    }
    return result;
}
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallPkg(
    JNIEnv *env, jobject /* thiz */, jstring pkgPath, jstring zrif, jobject callback) {
#ifdef LAUNCHER_HAS_VITA3K
    if (pkgPath == nullptr || zrif == nullptr) {
        LOGW("nativeInstallPkg: ruta o zrif nulos");
        return JNI_FALSE;
    }
    // P4: install_pkg llama a app::scan_apps al terminar y hace
    // fs::remove_all/rename sin error_code (pin 3e43bc80,
    // packages/src/pkg.cpp): un error de E/S lanza una excepción C++
    // que cruzando JNI abortaría el proceso. Se captura y se devuelve
    // false (el llamador muestra el error en vez de cerrarse).
    jboolean ok = JNI_FALSE;
    try {
        ok = Java_org_vita3k_emulator_NativeLib_installPkg(env, nullptr, pkgPath, zrif, callback);
    } catch (const std::exception &e) {
        LOGE("nativeInstallPkg: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeInstallPkg: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return JNI_FALSE;
    }
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    LOGI("nativeInstallPkg: %s", ok == JNI_TRUE ? "OK" : "falló");
    return ok;
#else
    LOGW("nativeInstallPkg STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallArchive(
    JNIEnv *env, jobject /* thiz */, jstring archivePath, jobject callback, jboolean forceReinstall) {
#ifdef LAUNCHER_HAS_VITA3K
    if (archivePath == nullptr) {
        LOGW("nativeInstallArchive: ruta nula");
        return JNI_FALSE;
    }
    // P4: igual que installPkg (install_archive + scan_apps, pin
    // 3e43bc80): una excepción C++ sin capturar cruzando JNI aborta
    // el proceso; se captura y se devuelve false.
    jboolean ok = JNI_FALSE;
    try {
        ok = Java_org_vita3k_emulator_NativeLib_installArchive(env, nullptr, archivePath, callback, forceReinstall);
    } catch (const std::exception &e) {
        LOGE("nativeInstallArchive: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeInstallArchive: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return JNI_FALSE;
    }
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    LOGI("nativeInstallArchive: %s", ok == JNI_TRUE ? "OK" : "falló");
    return ok;
#else
    LOGW("nativeInstallArchive STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallFirmware(
    JNIEnv *env, jobject /* thiz */, jstring pupPath, jobject callback) {
#ifdef LAUNCHER_HAS_VITA3K
    if (pupPath == nullptr) {
        LOGW("nativeInstallFirmware: ruta nula");
        return launcher_new_empty_string(env);
    }
    // P4: igual que installPkg (install_pup + scan_apps, pin
    // 3e43bc80): una excepción C++ sin capturar cruzando JNI aborta
    // el proceso; se captura y se devuelve "" (fallo controlado).
    jstring result = nullptr;
    try {
        result = Java_org_vita3k_emulator_NativeLib_installFirmware(env, nullptr, pupPath, callback);
    } catch (const std::exception &e) {
        LOGE("nativeInstallFirmware: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return launcher_new_empty_string(env);
    } catch (...) {
        LOGE("nativeInstallFirmware: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return launcher_new_empty_string(env);
    }
    return launcher_empty_string_or_null(env, result);
#else
    LOGW("nativeInstallFirmware STUB: núcleo no embebido");
    return launcher_new_empty_string(env);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeCopyLicense(
    JNIEnv *env, jobject /* thiz */, jstring licensePath) {
#ifdef LAUNCHER_HAS_VITA3K
    if (licensePath == nullptr) {
        return JNI_FALSE;
    }
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_copyLicense(env, nullptr, licensePath);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    LOGW("nativeCopyLicense STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeCreateLicense(
    JNIEnv *env, jobject /* thiz */, jstring zrif) {
#ifdef LAUNCHER_HAS_VITA3K
    if (zrif == nullptr) {
        return JNI_FALSE;
    }
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_createLicense(env, nullptr, zrif);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    LOGW("nativeCreateLicense STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeFindPkgZrif(
    JNIEnv *env, jobject /* thiz */, jstring pkgPath) {
#ifdef LAUNCHER_HAS_VITA3K
    if (pkgPath == nullptr) {
        return launcher_new_empty_string(env);
    }
    return launcher_empty_string_or_null(
        env, Java_org_vita3k_emulator_NativeLib_findPkgZrif(env, nullptr, pkgPath));
#else
    LOGW("nativeFindPkgZrif STUB: núcleo no embebido");
    return launcher_new_empty_string(env);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeConvertRifToZrif(
    JNIEnv *env, jobject /* thiz */, jstring licensePath) {
#ifdef LAUNCHER_HAS_VITA3K
    if (licensePath == nullptr) {
        return launcher_new_empty_string(env);
    }
    return launcher_empty_string_or_null(
        env, Java_org_vita3k_emulator_NativeLib_convertRifToZrif(env, nullptr, licensePath));
#else
    LOGW("nativeConvertRifToZrif STUB: núcleo no embebido");
    return launcher_new_empty_string(env);
#endif
}
