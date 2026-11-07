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

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "LauncherBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifdef LAUNCHER_HAS_VITA3K
#include "launcher_bridge.h"
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallPkg(
    JNIEnv *env, jobject /* thiz */, jstring pkgPath, jstring zrif, jobject callback) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_installPkg(env, nullptr, pkgPath, zrif, callback);
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
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_installArchive(env, nullptr, archivePath, callback, forceReinstall);
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
    return Java_org_vita3k_emulator_NativeLib_installFirmware(env, nullptr, pupPath, callback);
#else
    LOGW("nativeInstallFirmware STUB: núcleo no embebido");
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeCopyLicense(
    JNIEnv *env, jobject /* thiz */, jstring licensePath) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_copyLicense(env, nullptr, licensePath);
#else
    LOGW("nativeCopyLicense STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeCreateLicense(
    JNIEnv *env, jobject /* thiz */, jstring zrif) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_createLicense(env, nullptr, zrif);
#else
    LOGW("nativeCreateLicense STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeFindPkgZrif(
    JNIEnv *env, jobject /* thiz */, jstring pkgPath) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_findPkgZrif(env, nullptr, pkgPath);
#else
    LOGW("nativeFindPkgZrif STUB: núcleo no embebido");
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeConvertRifToZrif(
    JNIEnv *env, jobject /* thiz */, jstring licensePath) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_convertRifToZrif(env, nullptr, licensePath);
#else
    LOGW("nativeConvertRifToZrif STUB: núcleo no embebido");
    return env->NewStringUTF("");
#endif
}
