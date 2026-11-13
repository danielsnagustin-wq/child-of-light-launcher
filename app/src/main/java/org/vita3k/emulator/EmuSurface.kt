package org.vita3k.emulator

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import com.danielsnagustin.childoflight.launcher.core.GamepadOverlayView
import com.danielsnagustin.childoflight.launcher.core.TouchStats
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
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
 *   0.2.64 (D-P1-1): el caso con el primer dedo EN un control y el
 *   segundo sobre un botón SÍ está resuelto, en el overlay
 *   (encaminado manual del puntero a su botón, routedPointers):
 *   lo que queda pendiente es SOLO este caso espejo (primer dedo
 *   en zona vacía).
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

    /**
     * Ronda 0.2.65 (O-P2-4): último DOWN libre no duplicado, para
     * descartar el segundo envío del mismo toque (menos de 100 ms,
     * casi misma posición). -1 = aún no hubo ningún DOWN libre.
     */
    private var lastFreeDownEventTimeMillis: Long = -1L
    private var lastFreeDownX: Float = TouchStats.NO_POSITION
    private var lastFreeDownY: Float = TouchStats.NO_POSITION

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Ronda 0.2.70 (9-P2-1): el set(true) va DESPUÉS de
        // super.surfaceCreated(): es super quien llama a
        // SDLActivity.onNativeSurfaceCreated() y registra la
        // superficie nueva en SDL. Antes el flag has_surface se
        // encendía con la superficie vieja (o con el 960x544 por
        // defecto) hasta surfaceChanged. surfaceDestroyed ya hace
        // set(false) ANTES de super, que es el orden seguro en ese
        // sentido (apagar antes de soltar).
        super.surfaceCreated(holder)
        runCatching { setSurfaceStatus(true) }
            .onFailure {
                Log.w("EmuSurface", "setSurfaceStatus(true) falló: $it")
                // R5-A3-15: además de logcat, queda en launcher.log (el
                // reporte de bug incluye su cola): así el próximo
                // reporte distingue "superficie sin avisar al núcleo"
                // de otros negros. LauncherLog es un object público y
                // se importa aquí igual que GamepadOverlayView, sin
                // vía compartida nueva. Ronda 0.2.65 (S-P2-5): el
                // set(false) de surfaceDestroyed también queda en
                // launcher.log (ver abajo).
                LauncherLog.append(context, "surface", "setSurfaceStatus(true) falló: $it; el núcleo no presentará frames (superficie sin avisar al núcleo)")
            }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Ronda 0.2.65 (S-P2-5): el set(false) también queda en
        // launcher.log, no solo en logcat: un reporte de bug tras un
        // negro por superficie perdida muestra cuándo se apagó el
        // flag. Una vez por destrucción de superficie, no es camino
        // caliente: el append síncrono no cuesta nada aquí.
        runCatching { setSurfaceStatus(false) }
            .onSuccess {
                LauncherLog.append(context, "surface", "setSurfaceStatus(false): superficie destruida; el núcleo dejará de presentar frames hasta la próxima superficie")
            }
            .onFailure {
                Log.w("EmuSurface", "setSurfaceStatus(false) falló: $it")
                LauncherLog.append(context, "surface", "setSurfaceStatus(false) falló: $it; el flag de superficie puede quedar encendido en el núcleo")
            }
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
        val overlay = findTouchOverlay()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
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
        // Ronda 0.2.65 (O-P2-4): el toque libre (DOWN) llega dos veces
        // al juego por dos caminos (el overlay lo declina y la
        // superficie lo vuelve a ofrecer). Si este DOWN es casi
        // idéntico al anterior (mismo puntero, menos de 100 ms, casi
        // misma posición), se descarta sin entregarlo a SDL ni
        // contarlo. No se aplica a los reenvíos sintéticos (segundo
        // dedo, zona de Igniculus): esos ya se cuentan en su origen y
        // dos dedos pueden tocar el mismo punto a la vez.
        // Contrapartida: un doble toque rapidísimo (menos de 100 ms)
        // en el mismo punto pierde el segundo toque.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && overlay?.forwardingSyntheticTouch != true) {
            val downX = if (event.pointerCount > 0) event.getX(0) else TouchStats.NO_POSITION
            val downY = if (event.pointerCount > 0) event.getY(0) else TouchStats.NO_POSITION
            if (shouldDedupeFreeDown(event.eventTime, downX, downY, lastFreeDownEventTimeMillis, lastFreeDownX, lastFreeDownY)) {
                Log.i("EmuSurface", "toque libre DOWN duplicado en ($downX, $downY) descartado (menos de 100 ms del anterior)")
                return true
            }
            lastFreeDownEventTimeMillis = event.eventTime
            lastFreeDownX = downX
            lastFreeDownY = downY
        }
        // 0.2.63 (D): el toque que llega aquí es táctil libre del
        // juego (el overlay ya lo declinó): se registra en TouchStats
        // para el reporte de bug. Los reenvíos sintéticos (segundo
        // dedo, zona de Igniculus) pasan por aquí con
        // forwardingSyntheticTouch activo y NO se registran: ya se
        // cuentan en su origen (forwardPointerDownToSdl / forwardTouch).
        val delivered = super.dispatchTouchEvent(event)
        if (shouldRecordFreeTouch(overlay?.forwardingSyntheticTouch == true)) {
            val x = if (event.pointerCount > 0) event.getX(0) else TouchStats.NO_POSITION
            val y = if (event.pointerCount > 0) event.getY(0) else TouchStats.NO_POSITION
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (TouchStats.recordFreeDown(delivered, x, y)) {
                        Log.i("EmuSurface", "toque libre DOWN en ($x, $y) entregado a SDL")
                    } else if (!delivered) {
                        Log.w("EmuSurface", "toque libre DOWN en ($x, $y) PERDIDO (SDL no lo consumió)")
                    }
                }
                // Ronda 0.2.65 (M-P2-5): el 2.º dedo también cuenta:
                // POINTER_DOWN/UP son toques libres nuevos, no MOVEs
                // del primero. La posición es la del puntero que
                // baja/sube (actionIndex), no la del puntero 0.
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val pointerIndex = event.actionIndex
                    val px = if (pointerIndex < event.pointerCount) event.getX(pointerIndex) else TouchStats.NO_POSITION
                    val py = if (pointerIndex < event.pointerCount) event.getY(pointerIndex) else TouchStats.NO_POSITION
                    if (TouchStats.recordFreeDown(delivered, px, py)) {
                        Log.i("EmuSurface", "toque libre POINTER_DOWN en ($px, $py) entregado a SDL")
                    } else if (!delivered) {
                        Log.w("EmuSurface", "toque libre POINTER_DOWN en ($px, $py) PERDIDO (SDL no lo consumió)")
                    }
                }
                MotionEvent.ACTION_UP -> TouchStats.recordFreeUp(delivered, x, y)
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val px = if (pointerIndex < event.pointerCount) event.getX(pointerIndex) else TouchStats.NO_POSITION
                    val py = if (pointerIndex < event.pointerCount) event.getY(pointerIndex) else TouchStats.NO_POSITION
                    TouchStats.recordFreeUp(delivered, px, py)
                }
                MotionEvent.ACTION_MOVE -> TouchStats.recordFreeMove(x, y)
            }
        }
        return delivered
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

/**
 * 0.2.63 (D): un toque sintético en vuelo (segundo dedo, zona de
 * Igniculus) ya se contó en su origen (forwardPointerDownToSdl /
 * forwardTouch): EmuSurface no lo vuelve a contar como toque libre.
 * Solo se registra el toque que NO es sintético. Pura, testeable en
 * JVM (FreeTouchRegressionTest).
 */
internal fun shouldRecordFreeTouch(forwardingSyntheticTouch: Boolean): Boolean = !forwardingSyntheticTouch

/**
 * Ronda 0.2.65 (O-P2-4): ventana y radio del dedupe de DOWN libre.
 * Menos de 100 ms y a menos de 20 px del DOWN anterior del mismo
 * puntero = el mismo toque enviado dos veces. Valores ajustables:
 * un doble toque legítimo más rápido que la ventana se pierde.
 */
private const val FREE_DOWN_DEDUPE_WINDOW_MS = 100L
private const val FREE_DOWN_DEDUPE_RADIUS_PX = 20f

/**
 * Ronda 0.2.65 (O-P2-4): decisión pura del dedupe de DOWN libre
 * (sin Views, probada en JVM en Ronda065SurfaceWatchdogTest). Sin
 * DOWN anterior no hay duplicado; fuera de la ventana o lejos del
 * punto anterior, es un toque nuevo.
 */
internal fun shouldDedupeFreeDown(
    eventTimeMillis: Long,
    x: Float,
    y: Float,
    lastEventTimeMillis: Long,
    lastX: Float,
    lastY: Float,
): Boolean {
    if (lastEventTimeMillis < 0 || lastX == TouchStats.NO_POSITION || lastY == TouchStats.NO_POSITION) return false
    if (eventTimeMillis - lastEventTimeMillis >= FREE_DOWN_DEDUPE_WINDOW_MS) return false
    val dx = x - lastX
    val dy = y - lastY
    return dx * dx + dy * dy < FREE_DOWN_DEDUPE_RADIUS_PX * FREE_DOWN_DEDUPE_RADIUS_PX
}
