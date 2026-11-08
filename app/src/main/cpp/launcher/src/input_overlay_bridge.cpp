// input_overlay_bridge.cpp — puente JNI del overlay táctil (Etapa 2).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para el mando virtual SDL del overlay. Reenvían a las funciones JNI de
// upstream en vita3k/android/jni/input_overlay.cpp (pin 3e43bc80), que son
// las que crean el joystick virtual ("Vita3K Virtual Controller") y
// traducen botones/ejes a entrada del juego. La capa adaptadora NO
// reimplementa el mando virtual: llama al código de upstream tal cual.
//
// Sin árbol Vita3K compilan como stubs (false) y el llamador actúa como
// si no hubiera overlay.
//
// Robustez JNI: si upstream deja una excepción pendiente, se limpia y se
// devuelve false (mismo contrato que session_bridge.cpp).
//
// nativeOverlayIsAttached: el attach de upstream devuelve void y no
// dice si el joystick se creó (si SDL gamepad aún no estaba iniciado,
// no crea nada en silencio). Para no fingir que el overlay funciona,
// se comprueba por API pública SDL si existe un gamepad con el nombre
// exacto que upstream le pone al virtual ("Vita3K Virtual Controller",
// input_overlay.cpp). Solo así EmbeddedGameActivity sabe si reintentarlo.
// P2-B11: nativeOverlaySetButton/Axis también devuelven ese estado
// (antes devolvían true aunque upstream no hiciera nada): si
// devuelven false, el llamador debe reintentar el attach.

#include <jni.h>
#include <android/log.h>

#include <cstring>

#define LOG_TAG "LauncherBridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifdef LAUNCHER_HAS_VITA3K
#include "launcher_bridge.h"

// P2-B10: los headers SDL van DENTRO del #ifdef (como en
// runtime_bridge.cpp): sin núcleo el puente compila standalone y
// nada fuera del #ifdef usa símbolos SDL (todo SDL está en
// overlay_is_attached(), dentro del #ifdef).
#include <SDL3/SDL.h>
#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_joystick.h>

// Nombre exacto del joystick virtual de upstream (input_overlay.cpp).
static constexpr const char *kVirtualControllerName = "Vita3K Virtual Controller";

// ¿Existe el mando virtual "Vita3K Virtual Controller" en SDL?
// (Extraído de nativeOverlayIsAttached para reutilizarlo en
// SetButton/SetAxis, P2-B11.)
static bool overlay_is_attached() {
    if (!SDL_WasInit(SDL_INIT_GAMEPAD)) {
        return false;
    }
    int count = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&count);
    if (ids == nullptr) {
        return false;
    }
    bool found = false;
    for (int i = 0; i < count; i++) {
        const char *name = SDL_GetGamepadNameForID(ids[i]);
        if (name != nullptr && std::strcmp(name, kVirtualControllerName) == 0) {
            found = true;
            break;
        }
    }
    SDL_free(ids);
    return found;
}
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayAttach(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    Java_org_vita3k_emulator_overlay_InputOverlay_attachController(env, nullptr);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
#else
    LOGW("nativeOverlayAttach STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayDetach(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    Java_org_vita3k_emulator_overlay_InputOverlay_detachController(env, nullptr);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
#else
    LOGW("nativeOverlayDetach STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayIsAttached(
    JNIEnv * /* env */, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return overlay_is_attached() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlaySetButton(
    JNIEnv *env, jobject /* thiz */, jint button, jboolean pressed) {
#ifdef LAUNCHER_HAS_VITA3K
    Java_org_vita3k_emulator_overlay_InputOverlay_setButton(env, nullptr, button, pressed);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    // P2-B11: upstream es void y no hace nada si no hay joystick
    // virtual (!virtual_joystick, input_overlay.cpp:104-108); antes
    // se devolvía true incondicional y el llamador creía que el
    // botón llegó al juego. Ahora se devuelve si el mando virtual
    // existe de verdad: si es false, hay que reintentar el attach
    // (ver Vita3kCoreBridge.overlaySetButton).
    return overlay_is_attached() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlaySetAxis(
    JNIEnv *env, jobject /* thiz */, jint axis, jshort value) {
#ifdef LAUNCHER_HAS_VITA3K
    Java_org_vita3k_emulator_overlay_InputOverlay_setAxis(env, nullptr, axis, value);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    // P2-B11: igual que SetButton (upstream void, no hace nada sin
    // joystick virtual, input_overlay.cpp:98-102): se devuelve si el
    // mando virtual existe de verdad; si es false, reintentar el
    // attach (ver Vita3kCoreBridge.overlaySetAxis).
    return overlay_is_attached() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlaySetTouchState(
    JNIEnv *env, jobject /* thiz */, jboolean isBack) {
#ifdef LAUNCHER_HAS_VITA3K
    Java_org_vita3k_emulator_overlay_InputOverlay_setTouchState(env, nullptr, isBack);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
