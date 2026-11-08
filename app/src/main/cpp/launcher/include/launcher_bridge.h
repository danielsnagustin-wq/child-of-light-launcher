// launcher_bridge.h — reenvíos a las funciones JNI de Vita3K upstream.
//
// La capa adaptadora NO reimplementa el núcleo: llama a las funciones
// JNI que upstream ya compila dentro de libvita3k.so
// (vita3k/android/jni/native_bootstrap.cpp, native_install.cpp,
// native_session.cpp). Esas funciones están ligadas a la clase
// org.vita3k.emulator.NativeLib y nuestro paquete no puede llamarlas
// desde Kotlin tal cual; pero desde C++ son funciones `extern "C"`
// normales dentro del mismo .so, así que el adaptador las declara
// aquí y las llama directamente.
//
// Verificado contra Vita3K master 3e43bc803a04c775737a3474e0d54f3e10ca61bf
// (2026-11-07). Si upstream cambia una firma, hay que fijar el commit
// (docs/etapa2/ETAPA2-ESTADO.md) o actualizar estas declaraciones.
//
// NOTA: upstream IGNORA el parámetro jclass en todas estas funciones
// (usan solo env + los argumentos); el adaptador pasa nullptr.
// Algunas están declaradas para uso futuro (isInitialized, IME):
// declararlas no cuesta nada y documenta la superficie JNI
// disponible de upstream.
//
// Si una llamada a upstream dejó una excepción JNI pendiente, la
// limpia y devuelve true. El puente usa códigos de error, no
// excepciones: el llamador Kotlin solo captura UnsatisfiedLinkError,
// así que una excepción pendiente aquí llegaría como crash a
// EmbeddedGameActivity / EmbeddedPkgInstaller en vez de como
// código de error. Upstream normalmente devuelve false/"" sin
// lanzar, pero si lanza, el puente lo convierte en error.
static inline bool launcher_clear_pending_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

#pragma once

#include <jni.h>

extern "C" {

// --- native_bootstrap.cpp ---
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_prepareFrontend(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_init(JNIEnv *env, jclass clazz, jstring storage_path);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isInitialized(JNIEnv *env, jclass clazz);
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_NativeLib_refreshAppsList(JNIEnv *env, jclass clazz);
JNIEXPORT jint JNICALL
Java_org_vita3k_emulator_NativeLib_getFirmwareInstallStateMask(JNIEnv *env, jclass clazz);

// --- native_install.cpp ---
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_installFirmware(JNIEnv *env, jclass clazz, jstring path, jobject callback);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_installPkg(JNIEnv *env, jclass clazz, jstring path, jstring zrif, jobject callback);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_installArchive(JNIEnv *env, jclass clazz, jstring path, jobject callback, jboolean force_reinstall);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_copyLicense(JNIEnv *env, jclass clazz, jstring path);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_createLicense(JNIEnv *env, jclass clazz, jstring zrif);
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_findPkgZrif(JNIEnv *env, jclass clazz, jstring pkg_path);
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_convertRifToZrif(JNIEnv *env, jclass clazz, jstring path);

// --- native_session.cpp ---
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_setInputIntercepted(JNIEnv *env, jclass clazz, jboolean intercepted);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_setPauseReasonEnabled(JNIEnv *env, jclass clazz, jint reason_mask, jboolean enabled);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isAppRunning(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_requestAppQuit(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isAppPaused(JNIEnv *env, jclass clazz);
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_getRunningAppTitle(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isImeActive(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_submitIme(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_dismissIme(JNIEnv *env, jclass clazz);

} // extern "C"
