// trophy_bridge.cpp — puente JNI de trofeos (Ronda 0.2.57).
//
// Función JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para leer las colecciones de trofeos del núcleo. Reenvía a
// Java_org_vita3k_emulator_NativeLib_getTrophyCollections de upstream
// (vita3k/android/jni/native_trophies.cpp:206-207), igual que
// session_bridge.cpp reenvía las de sesión: llamada directa como
// función C++, porque native_trophies.cpp se compila DENTRO de
// libVita3K.so (pin CMakeLists.txt:167) y este puente se añade al
// mismo target `vita3k` (launcher/CMakeLists.txt). No hace falta
// CallStaticObjectMethod sobre NativeLib.
//
// Solo lectura: el juego desbloquea, el launcher solo observa.
//
// Robustez JNI (mismo patrón que session_bridge.cpp): si upstream
// deja una excepción pendiente, se limpia y se devuelve un array
// vacío (nunca null con excepción pendiente: el wrapper Kotlin
// declara Array no nulo). Si upstream devuelve null (solo ocurre si
// las clases org/vita3k/emulator/data no existen, caso en el que
// upstream ya devolvió vacío o null), también se devuelve vacío.
// El llamador Kotlin además captura cualquier excepción y devuelve
// vacío: un puente roto nunca tumba la vuelta a Home.
//
// Exige sesión viva: upstream devuelve vacío si no hay emuenv
// (native_trophies.cpp:224-226). El único llamador lee ANTES de
// destroySession (EmbeddedGameActivity.onDestroy); leer después
// daría siempre vacío.

#include <jni.h>
#include <android/log.h>

#include "launcher_bridge.h"

#define LOG_TAG "LauncherBridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Array vacío de NativeTrophyCollection, o nullptr SOLO si la clase
// no existe o no hay memoria (en ese caso limpia la excepción
// pendiente; el wrapper Kotlin convierte el null en vacío con su
// catch). Misma regla que launcher_new_empty_string_array: nunca se
// devuelve con una excepción pendiente.
static jobjectArray new_empty_trophy_collection_array(JNIEnv *env) {
    jclass cls = env->FindClass("org/vita3k/emulator/data/NativeTrophyCollection");
    if (cls == nullptr) {
        launcher_clear_pending_exception(env);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(0, cls, nullptr);
    if (result == nullptr) {
        launcher_clear_pending_exception(env);
    }
    env->DeleteLocalRef(cls);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetTrophyCollections(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    jobjectArray result = Java_org_vita3k_emulator_NativeLib_getTrophyCollections(env, nullptr);
    if (launcher_clear_pending_exception(env) || result == nullptr) {
        return new_empty_trophy_collection_array(env);
    }
    return result;
#else
    LOGW("nativeGetTrophyCollections STUB: núcleo no embebido");
    return new_empty_trophy_collection_array(env);
#endif
}
