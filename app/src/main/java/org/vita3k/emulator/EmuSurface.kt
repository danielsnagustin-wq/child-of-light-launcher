package org.vita3k.emulator

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import com.danielsnagustin.childoflight.launcher.core.GamepadOverlayView
import org.libsdl.app.SDLSurface

/**
 * Superficie SDL del juego, espejo mínimo de la clase oficial
 * `org.vita3k.emulator.EmuSurface` (Vita3K 0.2.1).
 *
 * CAUSA RAÍZ DE LA PANTALLA NEGRA (REA 2026-11-09, verificado en el
 * pin 3e43bc80): el renderizador Vulkan del núcleo tiene un
 * interruptor `has_surface` (renderer/src/vulkan/screen_renderer.cpp:47)
 * que solo se enciende con la JNI
 * `Java_org_vita3k_emulator_EmuSurface_setSurfaceStatus` (:50). Sin
 * ella, `FrameHost::drawable_size()` devuelve {0, 0} y
 * `acquire_swapchain_image()` devuelve false en silencio en cada
 * frame: el juego corre pero no se presenta nada. La app oficial
 * enciende el interruptor desde `EmuSurface.surfaceCreated`; nuestro
 * launcher usaba la `SDLSurface` plana, que no lo toca.
 *
 * El nombre JNI exige este paquete y este nombre de clase EXACTOS;
 * la clase no es visible para el usuario (ningún string de UI), así
 * que no viola la regla de no mencionar Vita3K en la app. El símbolo
 * ya está en nuestro libVita3K.so (screen_renderer.cpp se compila
 * en nuestro CI desde el mismo pin); solo faltaba llamarlo. Si el
 * símbolo no estuviera, el fallo se registra y la Activity no se
 * tumba (el juego quedaría en negro, como antes).
 *
 * TÁCTIL FRONTAL (R4-P1-17): en el oficial, EmuSurface crea su
 * propio overlay (mOverlay = new InputOverlay) y su onTouch prueba
 * el overlay primero y solo si no consume llama a super.onTouch.
 * Aquí SE MANTIENE la arquitectura: el overlay es un FrameLayout
 * aparte (GamepadOverlayView) añadido ENCIMA de esta superficie con
 * addContentView, no un hijo de EmuSurface. El orden oficial ya
 * ocurre por orden Z (el overlay recibe cada toque antes que la
 * superficie), y este dispatchTouchEvent lo hace EXPLÍCITO y robusto:
 * un ACTION_DOWN que llegue a la superficie se ofrece primero al
 * overlay (si está visible) y solo si no lo consume cae a SDL
 * (SDLSurface.onTouch -> SDLActivity.onNativeTouch ->
 * SDL_EVENT_FINGER_*). Dos precisiones:
 * - Se sobrescribe dispatchTouchEvent, NO onTouchEvent: SDLSurface
 *   se registra a sí misma como OnTouchListener (SDLSurface.java:66
 *   y :90) y su onTouch devuelve siempre true, así que
 *   View.dispatchTouchEvent nunca llega a onTouchEvent; un
 *   onTouchEvent en EmuSurface no se ejecutaría jamás.
 * - Solo ACTION_DOWN se re-ofrece. Un ACTION_POINTER_DOWN no: el
 *   framework sigue encaminando el gesto a quien recibió el DOWN,
 *   así que un control que consumiera un POINTER_DOWN re-ofrecido
 *   no recibiría jamás sus MOVE/UP (botón atascado + dedo fantasma
 *   en SDL). El multi-toque con el primer dedo en un control lo
 *   resuelve el overlay (R4-P1-16, GamepadOverlayView); el caso
 *   espejo (primer dedo en zona vacía, segundo sobre un botón)
 *   queda como SEGUIMIENTO: exige el cambio de arquitectura
 *   completo (overlay dentro de EmuSurface como el oficial, dueño
 *   de todos los punteros en una sola vista) y no se hace aquí.
 * El modo por defecto es DIRECT_TOUCH, así que el título no depende
 * de esto; el reenvío sintético del modo JOYSTICK no se toca (solo
 * se protege: mientras hay un sintético en vuelo,
 * forwardingSyntheticTouch hace que el overlay devuelva false y
 * este método no lo re-ofrece).
 *
 * PROBAR EN EL TELÉFONO (R4-P1-17): en la pantalla de título, tocar
 * fuera de cualquier botón: Igniculus debe responder (la luciérnaga
 * va al toque). Si no responde con un solo dedo, el sospechoso es
 * esta caída de toques a la superficie, no el multi-toque.
 */
class EmuSurface(context: Context) : SDLSurface(context) {

    private external fun setSurfaceStatus(present: Boolean)

    /** Overlay táctil buscado en la jerarquía de la ventana; cacheado mientras siga conectado. */
    private var overlayCache: GamepadOverlayView? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        runCatching { setSurfaceStatus(true) }
            .onFailure { Log.w("EmuSurface", "setSurfaceStatus(true) falló: $it") }
        super.surfaceCreated(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { setSurfaceStatus(false) }
            .onFailure { Log.w("EmuSurface", "setSurfaceStatus(false) falló: $it") }
        super.surfaceDestroyed(holder)
    }

    /**
     * R4-P1-17: orden oficial (overlay primero, superficie después)
     * sin cambiar la jerarquía. Un DOWN que llega aquí es que el
     * overlay ya lo declinó por orden Z o que llegó por un camino
     * que lo saltó. En el camino normal el re-ofrecimiento no puede
     * consumirlo (mismo evento, mismos hijos, mismo estado: el
     * overlay ya lo declinó); solo consumiría si el toque llegó por
     * un camino que saltó al overlay, y en ese camino el overlay
     * sigue siendo el primero para los eventos siguientes. Por eso
     * el re-ofrecimiento es seguro aquí y no deja un botón atascado:
     * nunca roba un DOWN que el framework ya encaminó a la
     * superficie con el overlay como primer destino.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val overlay = findTouchOverlay()
            if (overlay != null &&
                shouldOfferDownToOverlayFirst(
                    event.actionMasked,
                    overlay.visibility == View.VISIBLE,
                    overlay.forwardingSyntheticTouch,
                )
            ) {
                val copy = MotionEvent.obtain(event)
                try {
                    if (overlay.dispatchTouchEvent(copy)) return true
                } finally {
                    copy.recycle()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun findTouchOverlay(): GamepadOverlayView? {
        val cached = overlayCache
        if (cached != null && cached.isAttachedToWindow) return cached
        val found = (rootView as? ViewGroup)?.let { findOverlayIn(it) }
        overlayCache = found
        return found
    }

    private fun findOverlayIn(root: ViewGroup): GamepadOverlayView? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is GamepadOverlayView) return child
            if (child is ViewGroup) {
                val found = findOverlayIn(child)
                if (found != null) return found
            }
        }
        return null
    }
}

/**
 * R4-P1-17: decisión pura del orden táctil en EmuSurface (sin Views,
 * probada en EmuSurfaceTouchOrderTest). Solo un DOWN fresco se
 * ofrece primero al overlay, solo si está visible y solo si no hay
 * un toque sintético en vuelo hacia SDL (en ese caso el overlay ya
 * devolvió false por el flag y re-ofrecerlo solo lo comería).
 */
internal fun shouldOfferDownToOverlayFirst(
    actionMasked: Int,
    overlayVisible: Boolean,
    forwardingSyntheticTouch: Boolean,
): Boolean {
    return actionMasked == MotionEvent.ACTION_DOWN &&
        overlayVisible &&
        !forwardingSyntheticTouch
}
