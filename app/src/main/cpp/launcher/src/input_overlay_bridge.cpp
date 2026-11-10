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
// R5-A3-6: SetButton/SetAxis usan una caché de 250 ms de ese estado
// (overlay_is_attached_cached) para no enumerar gamepads en cada
// evento; un false nunca se cachea y se re-comprueba de inmediato.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
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
// R6-P1-15: esto es un PROXY de enumeración SDL, NO una prueba de
// registro real en state.controllers: refresh_controllers()
// (ctrl/src/ctrl.cpp:127-203) puede fallar silenciosamente aunque el
// joystick virtual exista (sin puerto libre, SDL_OpenGamepad null) y
// el launcher lo creería activo. Un readback JNI del registro real
// NO es posible sin modificar las fuentes del pin (state.controllers
// es interno del módulo ctrl y ningún JNI lo expone; el núcleo se
// clona en el build desde upstream y sus fuentes no se tocan). Por
// eso el rebind registra además overlayGamepadCount() en el log,
// para distinguir "no existe" de "existe pero quizá no registrado".
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

// R5-A3-6: caché de overlay_is_attached() con TTL corto, solo para
// el camino caliente SetButton/SetAxis: enumerar todos los gamepads
// SDL (SDL_GetGamepads + nombre por mando) en cada pulsación y cada
// movimiento de stick costaba por evento. Solo se cachea el `true`
// (250 ms con std::chrono::steady_clock, monótono); un `false`
// nunca se cachea y se re-comprueba de inmediato en la siguiente
// llamada, así que un attach reciente se ve sin esperar al TTL.
// nativeOverlayIsAttached sigue llamando a la función sin caché:
// es la consulta explícita de diagnóstico/reintento
// (EmbeddedGameActivity) y debe ver el estado fresco. La semántica
// de retorno es la misma, solo cacheada: true mientras el mando
// virtual exista, false si no existe.
// R6-P2-21: el estado cacheado vive a nivel de fichero (no como
// estáticos de la función) para que overlayDetach() pueda
// invalidarlo: sin esto, un detach dentro de los 250 ms tras un
// `true` cacheado dejaba a SetButton/SetAxis creyendo que el mando
// seguía ahí (ventana en la que un botón "entregado" podía no haber
// llegado).
static std::atomic<bool> g_cached_attached{false};
static std::atomic<long long> g_cached_at_ms{0}; // 0 = sin caché válida
static constexpr long long kCacheTtlMs = 250;

static void overlay_invalidate_attached_cache() {
    g_cached_attached.store(false, std::memory_order_relaxed);
    g_cached_at_ms.store(0, std::memory_order_relaxed);
}

static bool overlay_is_attached_cached() {
    using clock = std::chrono::steady_clock;
    const long long now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        clock::now().time_since_epoch()).count();
    // Atómicos (sin mutex): dos hilos pueden re-enumerar a la vez en
    // el peor caso, pero nunca se devuelve un `true` falso: el valor
    // se guarda antes que el instante, y un instante 0 invalida.
    if (g_cached_attached.load(std::memory_order_relaxed) &&
        now_ms - g_cached_at_ms.load(std::memory_order_relaxed) < kCacheTtlMs) {
        return true;
    }
    const bool attached = overlay_is_attached();
    g_cached_attached.store(attached, std::memory_order_relaxed);
    g_cached_at_ms.store(attached ? now_ms : 0, std::memory_order_relaxed);
    return attached;
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
    // R6-P2-28: antes se devolvía true si no hubo excepción, aunque
    // upstream no hubiera creado nada (si SDL gamepad aún no estaba
    // iniciado, attachController es void y no crea nada en silencio).
    // Mismo patrón que SetButton/SetAxis: devolver si el mando
    // virtual existe de verdad en SDL tras la llamada.
    return overlay_is_attached() ? JNI_TRUE : JNI_FALSE;
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
    // R6-P2-21: invalidar la caché de attached: el mando ya no existe
    // y un `true` cacheado haría creer a SetButton/SetAxis que sigue
    // ahí durante el TTL (250 ms). Se invalida aunque la llamada
    // dejara excepción pendiente: el detach se intentó y el estado
    // cacheado ya no es fiable.
    overlay_invalidate_attached_cache();
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

// Nº de gamepads que ve SDL (R4-P0-3, readback para el reporte de bug).
// -1 = sin dato (SDL gamepad no iniciado o lista nula). Reutiliza el
// mismo SDL_GetGamepads que overlay_is_attached().
extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayGamepadCount(
    JNIEnv * /* env */, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    if (!SDL_WasInit(SDL_INIT_GAMEPAD)) {
        return -1;
    }
    int count = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&count);
    if (ids == nullptr) {
        return -1;
    }
    SDL_free(ids);
    return count;
#else
    return -1;
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
    // (ver Vita3kCoreBridge.overlaySetButton). R5-A3-6: estado
    // cacheado 250 ms (overlay_is_attached_cached) para no enumerar
    // gamepads en cada pulsación.
    return overlay_is_attached_cached() ? JNI_TRUE : JNI_FALSE;
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
    // attach (ver Vita3kCoreBridge.overlaySetAxis). R5-A3-6: estado
    // cacheado 250 ms (overlay_is_attached_cached) para no enumerar
    // gamepads en cada movimiento de stick.
    return overlay_is_attached_cached() ? JNI_TRUE : JNI_FALSE;
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
