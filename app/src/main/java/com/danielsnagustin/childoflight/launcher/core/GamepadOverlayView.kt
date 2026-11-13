package com.danielsnagustin.childoflight.launcher.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.danielsnagustin.childoflight.launcher.core.FaceGlyph
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.childLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.dpadForStick
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.faceGlyphFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.glowSdlButtons
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.iconOverrideFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.overlayChildOrder
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.pauseControlFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.pauseWingsLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxes
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxesFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.data.ControlShape
import com.danielsnagustin.childoflight.launcher.data.IgniculusMode
import com.danielsnagustin.childoflight.launcher.data.StickMode
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import org.libsdl.app.SDLSurface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Overlay táctil del juego embebido: los botones del perfil activo del
 * usuario, dibujados SOBRE la vista SDL del juego.
 *
 * Por qué es un FrameLayout con UNA VISTA POR CONTROL (y no una sola
 * vista a pantalla completa): cada dedo se asigna al control que toca.
 * Un toque FUERA de cualquier botón no lo recibe este layout (el
 * contenedor no es clicable y cada vista hija devuelve false si el
 * toque no es suyo), así que baja a la SDLSurface y llega al juego
 * como táctil frontal de Vita (Igniculus: tocar la pantalla mueve la
 * luciérnaga). Eso cubre el PRIMER dedo. R4-P1-16: NO cubre el
 * segundo dedo. En cuanto un hijo consume ACTION_DOWN, el ViewGroup
 * es dueño del gesto completo: un ACTION_POINTER_DOWN posterior que
 * no cae en ningún botón se entrega solo al hijo que ya es destino
 * del gesto (si el punto cae en sus límites) y, si ningún hijo lo
 * consume, el framework lo descarta: el padre nunca lo reintenta en
 * la superficie SDL. Devolver false no basta. Por eso
 * dispatchTouchEvent reenvía ACTIVAMENTE cada puntero no consumido
 * a la superficie SDL como su propio toque de un solo dedo
 * (MultiTouchRouter decide, el reenvío es sintético): el segundo
 * dedo en zona vacía llega al juego aunque otro dedo mantenga el
 * stick o un botón. Sin esto, Igniculus mientras se mueve el stick
 * — el gameplay central de Child of Light — queda roto por diseño.
 * La zona Igniculus se DIBUJA (orbe a 0.42 x ancho, anillo a 0.45 x
 * ancho, zona táctil de 115 dp en el layout limpio 0.2.57) y en los
 * dos modos de Igniculus consume el toque y lo reenvía a la
 * superficie SDL como toque sintético (P1-12), ADEMÁS de mandar el
 * brillo al mantener (hombro L + eje L2, ver glowSdlButtons):
 * - Toque directo: el toque se reenvía 1:1, sin traducir (el
 *   destino es el punto del dedo, no ancla + arrastre x
 *   sensibilidad): la luciérnaga va donde toca el dedo, igual que
 *   antes, pero ahora la zona SÍ consume el toque para poder
 *   soltar el brillo al levantar el dedo. Antes devolvía false y
 *   el toque bajaba nativo a SDL; sin consumirlo no hay ACTION_UP
 *   que soltar y el brillo quedaría atascado. El juego recibe el
 *   mismo toque (misma posición, misma secuencia abajo/mover/
 *   arriba) reenviado por forwardTouch, igual que en modo
 *   Joystick. Un segundo dedo sobre la zona mientras hay uno no
 *   se sigue: lo reenvía el router de multi-toque a SDL
 *   (hasActivePointer sigue en false en este modo, a propósito).
 * - Joystick: SÍ consume el toque y lo traduce: el punto donde se
 *   apoya el dedo es el ancla; el destino es ancla + arrastre ×
 *   sensibilidad, reenviado a la superficie SDL como un toque
 *   sintético en esa posición (el juego lo recibe como táctil
 *   frontal y la luciérnaga vuela al destino). El orbe sigue al
 *   destino mientras se arrastra.
 * En los dos modos, la zona es la hija de MÁS ABAJO (rebuild la
 * añade primera): un botón que se solape con ella (espada y salto
 * pisan su esquina) recibe el toque antes que ella.
 *
 * PROBAR EN EL TELÉFONO (R4-P1-16): con el juego en marcha,
 * mantener el stick izquierdo con un dedo y tocar una zona vacía
 * con otro: la luciérnaga (Igniculus) debe moverse al segundo toque
 * mientras el stick sigue moviendo al personaje. Repetir con un
 * botón de acción mantenido en vez del stick, y con dos dedos en
 * zona vacía a la vez. Si el segundo toque no llega, el sospechoso
 * es el reenvío de dispatchTouchEvent, no el táctil de un solo dedo.
 *
 * Dibujo: LA MISMA MATEMÁTICA que la vista previa del editor
 * (TouchOverlayMapping.controlRect): posición por fracción DEL RECT
 * DEL JUEGO 960x544 (R4-P1-1), tamaño en dp o en fracción del alto
 * del juego (0.2.64 A-7), opacidad por botón multiplicada por la
 * opacidad general. El estilo es el cristal azul hielo de la imagen
 * de referencia 51025 (fondo negro, cristal azul hielo/lavanda con
 * brillo): degradado radial, doble borde luminoso, adornos de
 * diamante y cristal en los 4 puntos del stick, alas de hielo con
 * estrellitas en la pausa (0.2.64 A-2: vista aparte PauseWingsView,
 * más ancha que el botón), orbe de Igniculus con corona floral y
 * destellos (0.2.64 A-3: drawFloralCrown), espada con destellos y
 * flecha fina de salto, brillo turquesa al pulsar. La vista previa
 * del editor sigue siendo esquemática y no refleja todos estos
 * adornos, solo la posición, el tamaño y el orbe interior.
 *
 * Entrada: cada botón manda su botón SDL al mando virtual del núcleo
 * (Vita3kCoreBridge.overlaySetButton) y cada stick sus ejes
 * (overlaySetAxis). Sin núcleo, el puente devuelve false y no pasa
 * nada: el overlay solo se monta cuando el núcleo está inicializado.
 */
class GamepadOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Destino de los eventos del overlay (la Activity los reenvía al puente JNI). */
    interface Listener {
        fun onButton(sdlButton: Int, pressed: Boolean)
        fun onAxis(sdlAxis: Int, value: Short)
    }

    var listener: Listener? = null

    /**
     * Mientras se reenvía un toque sintético de Igniculus (modo
     * Joystick), ningún control del overlay debe consumirlo: si el
     * destino cae sobre un botón, lo pulsaría. dispatchTouchEvent
     * devuelve false mientras vale true y el toque baja a la
     * superficie SDL, que es su destino.
     */
    internal var forwardingSyntheticTouch: Boolean = false

    /** Opacidad general del overlay (0.1..1): multiplica la opacidad de cada botón. */
    var generalOpacity: Float = 1f
        set(value) {
            val coerced = value.coerceIn(0.1f, 1f)
            // 0.2.65 (O-P2-5): si no cambió, no invalidar: la
            // Activity asigna opacidad y neón en cada onResume
            // aunque sean los mismos y antes eso redibujaba los 7
            // hijos dos veces por volver al juego.
            if (field == coerced) return
            field = coerced
            refreshChildren()
        }

    /** Halo azul hielo (neón) alrededor de los botones. */
    var neonGlow: Boolean = false
        set(value) {
            // 0.2.65 (O-P2-5): igual que generalOpacity.
            if (field == value) return
            field = value
            refreshChildren()
        }

    private var controls: List<TouchControl> = emptyList()
    private var builtForSize: Pair<Int, Int>? = null
    /**
     * 0.2.63 (G): id del perfil activo que montó [controls] (lo pasa
     * la Activity en setControls; null si nadie lo pasó). Solo para
     * la línea de diagnóstico de rebuild(): el perfil + el tamaño
     * de la vista + las fracciones del stick permiten distinguir,
     * ante un botón desplazado, si cambió el dato guardado o si la
     * vista horneó márgenes de un tamaño viejo.
     */
    private var activeProfileId: String? = null
    /**
     * 0.2.64 (G): controles montados en el mismo orden que los
     * hijos (rebuild los añade en el orden de overlayChildOrder).
     * onLayout() los usa para re-derivar los márgenes de cada hijo
     * desde sus fracciones sin tocar el TouchControl del hijo
     * (protected en OverlayControlView).
     */
    private var mountedControls: List<TouchControl> = emptyList()

    /**
     * 0.2.64 (A-2): vista de alas de la pausa (PauseWingsView) y
     * el control de pausa que centra. La vista NO es un control:
     * no está en mountedControls (repositionChildrenFromFractions
     * la actualiza aparte) ni consume toques. null si el perfil
     * no trae botón de pausa (START).
     */
    private var pauseWingsView: PauseWingsView? = null
    private var pauseWingsControl: TouchControl? = null

    /**
     * 0.2.64 (D-P1-1): punteros que el overlay encaminó A MANO a
     * un botón o stick (segundo dedo sobre un botón mientras otro
     * dedo mantiene un control). El framework solo entrega el
     * ACTION_POINTER_DOWN a los hijos que ya son destino del gesto
     * (el control mantenido); el botón bajo el segundo dedo nunca
     * lo ve. Sin este mapa, ese dedo se reenviaba a SDL como toque
     * libre: fantasma en el juego sobre el botón + pulsación
     * perdida. La clave es el pointerId real del dedo; el valor,
     * el hijo que lo recibió con un DOWN sintético. Sus MOVE/UP
     * se le reenvían igual (sintéticos de un solo puntero, en
     * coordenadas de la vista hija). Se vacía al soltar, cancelar
     * o reconstruir (releaseAll).
     */
    private val routedPointers = LinkedHashMap<Int, OverlayControlView>()

    /**
     * 0.2.65 (R-P1-16): última posición reenviada de cada dedo
     * encaminado a mano (coordenadas del overlay). Un MOVE que no
     * movió el dedo ≥ 1 px desde el último reenviado no se le
     * vuelve a mandar al hijo: a 60-120 Hz de muestreo táctil la
     * mayoría de MOVEs son sub-píxel y solo crean un MotionEvent
     * sintético por nada. La posición real se acumula (no se
     * pierde deriva: en cuanto el total desde el último envío
     * llega a 1 px, se reenvía).
     */
    private val routedLastForwarded = HashMap<Int, Pair<Float, Float>>()

    /**
     * 0.2.65 (O-P2-4): huella del último ACTION_DOWN ofrecido a
     * los hijos y declinado. EmuSurface re-ofrece a este overlay
     * cada DOWN que llega a la superficie con una copia idéntica
     * (mismo downTime, puntero y coordenadas): en el camino normal
     * los hijos ya lo declinaron una vez con el mismo estado, así
     * que la copia solo les haría procesar el DOWN dos veces. Si
     * la copia coincide exactamente con el último DOWN declinado,
     * se descarta aquí; un DOWN nuevo siempre trae otro downTime.
     * No es el dedupe principal (ese vive en EmuSurface): esto
     * solo evita que el overlay vea el mismo DOWN dos veces.
     */
    private var lastDeclinedDownTime: Long = -1L
    private var lastDeclinedDownPointerId: Int = -1
    private var lastDeclinedDownX: Float = Float.NaN
    private var lastDeclinedDownY: Float = Float.NaN

    /**
     * R4-P1-16: decide qué punteros no consumidos por ningún control
     * se reenvían a la superficie SDL (lógica pura, sin Android:
     * MultiTouchRouter, probada en MultiTouchRouterTest).
     */
    private val multiTouchRouter = MultiTouchRouter()

    /**
     * Superficie SDL (EmuSurface) buscada entre los hermanos del
     * overlay: el overlay y la superficie son hijos del mismo
     * contenedor de contenido, ambos a pantalla completa, así que
     * comparten coordenadas. Se cachea mientras siga conectada.
     */
    private var sdlSurfaceCache: View? = null

    init {
        // Sin fondo y sin interceptar: los toques que no son de ningún
        // botón bajan a la vista SDL de debajo.
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        // 0.2.65 (O-P2-3): aceleración por hardware explícita: los
        // onDraw usan clipPath, RadialGradient y rotaciones, todo
        // soportado en HW, pero nada lo garantizaba y una caída a
        // software los haría mucho más caros por frame.
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // 0.2.65 (M-P2-3): las alas de la pausa llevan topMargin
        // negativo (-34 px): con el recorte por defecto del padre
        // sus puntas superiores sobrevivían por ~2 px y cualquier
        // cambio futuro las recortaría sin que ningún test lo
        // cazara. Sin recorte, las alas se dibujan enteras.
        clipChildren = false
    }

    companion object {
        // P-C2: colores del cristal de drawGlassBody como constantes
        // Int (paleta, sin amarillo/dorado): antes se parseaban con
        // Color.parseColor en cada frame y por botón. El valor es el
        // entero con signo del ARGB 0xFF...... (verificado: vuelve al
        // mismo hex).
        /** Azul hielo #8CCBEB. */
        private const val GLASS_ICE_BLUE: Int = -7549973
        /** Azul hielo pálido #BFE6F7 (borde luminoso). */
        private const val GLASS_PALE_BLUE: Int = -4200713
        /** Plata #DCE8F5 (filo interior). */
        private const val GLASS_SILVER: Int = -2299659
        /** Turquesa #75D6CE (brillo al pulsar). */
        private const val GLASS_TURQUOISE: Int = -9054514
        /** Azul profundo #182644 (ojos y sonrisa de Igniculus). */
        private const val IGNICULUS_DEEP_BLUE: Int = -15194556
        /** Plata pálida #EAF4FB (texto e iconos). */
        private const val GLASS_PALE_WHITE: Int = -1379077
    }

    /** Controles del perfil activo (solo se muestran los visibles). Suelta todo lo pulsado antes de reconstruir. */
    fun setControls(newControls: List<TouchControl>, profileId: String? = null) {
        releaseAll()
        controls = newControls
        activeProfileId = profileId
        builtForSize = null
        rebuild()
    }

    /**
     * R6-P2-25: true si los controles montados son estructuralmente
     * los mismos que [controls] (TouchControl es data class, la
     * igualdad es por valor). EmbeddedGameActivity lo usa para no
     * llamar a setControls() — y soltar todo lo pulsado — cuando
     * nada cambió.
     */
    fun hasSameControls(controls: List<TouchControl>): Boolean = this.controls == controls

    /** Suelta todos los botones y centra todos los sticks (sin botones atascados al pausar/ocultar/salir). */
    fun releaseAll() {
        // R4-P1-16: un dedo reenviado a SDL es un toque del juego:
        // si el overlay se reconstruye u oculta a mitad de ese
        // toque, el juego seguiría creyendo que el dedo está
        // apoyado. Terminarlo aquí, antes de soltar los controles.
        endForwardedTouchesToSdl()
        // 0.2.64 (D-P1-1): los dedos encaminados a mano se sueltan
        // con el release() de cada hijo (botón/stick); el mapa solo
        // guarda a quién pertenecían: se vacía para no reenviarles
        // más MOVE/UP de un gesto que ya no existe.
        routedPointers.clear()
        routedLastForwarded.clear()
        for (i in 0 until childCount) {
            (getChildAt(i) as? OverlayControlView)?.release()
        }
    }

    private fun refreshChildren() {
        for (i in 0 until childCount) {
            (getChildAt(i) as? OverlayControlView)?.refreshStyle()
        }
        // 0.2.64 (A-2): las alas siguen la opacidad del botón de
        // pausa (su opacidad x la general).
        val wings = pauseWingsView
        val pause = pauseWingsControl
        if (wings != null && pause != null) {
            wings.ornamentAlpha = pause.opacity * generalOpacity
        }
    }

    /**
     * R4-P1-16: el multi-toque que el framework no entrega solo.
     *
     * El primer dedo (ACTION_DOWN) no se toca: si ningún hijo lo
     * consume, este método devuelve false y el padre lo reintenta en
     * la superficie SDL; reenviarlo aquí lo duplicaría. El problema
     * es el SEGUNDO dedo en adelante: una vez que un hijo consume el
     * DOWN, el gesto es de este ViewGroup y el padre ya no prueba
     * otros hijos para los ACTION_POINTER_DOWN. Si ningún hijo
     * consume el puntero nuevo, se reenvía a la superficie SDL como
     * un toque propio de un solo dedo (ACTION_DOWN sintético con el
     * pointerId real) y sus MOVE/UP posteriores se reenvían igual;
     * devolver true deja al overlay como destino del gesto para
     * seguir recibiendo esos eventos. No se toca el multi-toque que
     * ya funciona (P1-9/10/11): un puntero que un hijo consume sigue
     * su camino normal (el framework lo entrega al hijo como
     * ACTION_DOWN al dividir el evento), y el modo JOYSTICK de
     * ZoneView sigue reenviando lo suyo vía activity.dispatchTouchEvent.
     *
     * 0.2.64 (D-P1-1): ANTES de reenviar a SDL, el puntero nuevo se
     * ofrece al botón o stick que tenga debajo (tryRoutePointerToChild):
     * el framework no lo hace (solo entrega el POINTER_DOWN a los
     * hijos que ya son destino del gesto) y sin esto un segundo dedo
     * sobre salto/espada se convertía en un toque fantasma en el
     * juego (Igniculus saltaba al botón) y la pulsación se perdía.
     * Un puntero encaminado a mano se sigue con MOVE/UP sintéticos
     * (routedPointers); uno en zona vacía sigue el camino viejo a SDL.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (forwardingSyntheticTouch) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 0.2.65 (O-P2-4): copia re-ofrecida por EmuSurface
                // del DOWN que los hijos ya declinaron: no se les
                // vuelve a ofrecer. El primer dedo consumido nunca
                // llega aquí (la superficie no lo ve), así que un
                // DOWN consumido no puede duplicarse por este camino.
                val pointerId = event.getPointerId(0)
                val x = event.getX(0)
                val y = event.getY(0)
                if (event.downTime == lastDeclinedDownTime &&
                    pointerId == lastDeclinedDownPointerId &&
                    x == lastDeclinedDownX && y == lastDeclinedDownY
                ) {
                    return false
                }
                lastDeclinedDownTime = event.downTime
                lastDeclinedDownPointerId = pointerId
                lastDeclinedDownX = x
                lastDeclinedDownY = y
                val handled = super.dispatchTouchEvent(event)
                if (handled) {
                    lastDeclinedDownTime = -1L
                    lastDeclinedDownPointerId = -1
                }
                return handled
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val handled = super.dispatchTouchEvent(event)
                if (handled) return true
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                // 0.2.64 (D-P1-1): segundo dedo sobre un botón o
                // stick: que lo pulse él, no que se vaya a SDL como
                // toque fantasma. Si ningún hijo lo quiere, el camino
                // viejo (router -> SDL) sigue igual.
                if (tryRoutePointerToChild(event, index, pointerId)) return true
                val decision = multiTouchRouter.onPointerDown(
                    pointerId = pointerId,
                    downTime = event.downTime,
                    x = x,
                    y = y,
                    handledByChild = false,
                    insideActiveControl = isPointInsideActiveControl(x, y),
                )
                if (decision != MultiTouchRouter.PointerAction.FORWARD_DOWN) return false
                if (!forwardPointerDownToSdl(event, index, pointerId)) {
                    // Sin superficie a la que reenviar, no reclamar
                    // el puntero: se descarta como antes del arreglo.
                    multiTouchRouter.onPointerUp(pointerId)
                    Log.w("GamepadOverlayView", "R4-P1-16: sin superficie SDL para reenviar el puntero $pointerId")
                    return false
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val handled = super.dispatchTouchEvent(event)
                val forwarded = forwardPointerMovesToSdl(event)
                val routed = forwardRoutedMoves(event)
                return handled || forwarded || routed
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val handled = super.dispatchTouchEvent(event)
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val downTime = multiTouchRouter.downTimeOf(pointerId)
                val decision = multiTouchRouter.onPointerUp(pointerId)
                var forwarded = false
                if (decision == MultiTouchRouter.PointerAction.FORWARD_UP && downTime != null) {
                    forwarded = forwardPointerUpToSdl(event, index, pointerId, downTime)
                }
                // 0.2.64 (D-P1-1): el dedo encaminado a mano termina
                // con su UP sintético en su hijo (no con el del router).
                val routed = endRoutedPointer(event, index, pointerId, MotionEvent.ACTION_UP)
                return handled || forwarded || routed
            }
            MotionEvent.ACTION_UP -> {
                lastDeclinedDownTime = -1L
                lastDeclinedDownPointerId = -1
                val handled = super.dispatchTouchEvent(event)
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val downTime = multiTouchRouter.downTimeOf(pointerId)
                val decision = multiTouchRouter.onPointerUp(pointerId)
                var forwarded = false
                if (decision == MultiTouchRouter.PointerAction.FORWARD_UP && downTime != null) {
                    forwarded = forwardPointerUpToSdl(event, index, pointerId, downTime)
                }
                val routed = endRoutedPointer(event, index, pointerId, MotionEvent.ACTION_UP)
                // El gesto termina: si quedara un puntero encaminado
                // sin su UP (no debería: los demás ya pasaron por
                // POINTER_UP), se termina aquí para no dejar un botón
                // pulsado en el núcleo.
                endAllRoutedPointers(MotionEvent.ACTION_UP)
                return handled || forwarded || routed
            }
            MotionEvent.ACTION_CANCEL -> {
                lastDeclinedDownTime = -1L
                lastDeclinedDownPointerId = -1
                val handled = super.dispatchTouchEvent(event)
                endForwardedTouchesToSdl()
                endAllRoutedPointers(MotionEvent.ACTION_CANCEL)
                return handled
            }
            else -> return super.dispatchTouchEvent(event)
        }
    }

    /**
     * R4-P1-16: un puntero que cae dentro de un control que YA tiene
     * un dedo (stick mantenido, botón pulsado, arrastre Joystick en
     * curso) no se reenvía a SDL aunque el control no consuma el
     * ACTION_POINTER_DOWN (ButtonView/StickView no gestionan
     * POINTER_DOWN: el framework ya les dio el puntero por ser
     * destino del gesto). Reenviarlo crearía un dedo fantasma en el
     * juego justo sobre ese control. En cambio, un puntero dentro de
     * la zona Igniculus en modo Toque directo SÍ se reenvía: esa zona
     * nunca tiene un dedo activo y su toque es táctil del juego.
     */
    private fun isPointInsideActiveControl(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? OverlayControlView ?: continue
            if (!child.hasActivePointer) continue
            if (x >= child.left && x < child.left + child.width &&
                y >= child.top && y < child.top + child.height
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 0.2.64 (D-P1-1): ofrece el puntero nuevo al botón o stick que
     * tenga debajo. Causa exacta del toque fantasma: el framework
     * entrega ACTION_POINTER_DOWN solo a los hijos que YA son destino
     * del gesto (el control mantenido), así que un segundo dedo sobre
     * salto/espada nunca llegaba a su botón: el router lo reenviaba
     * a SDL como toque libre (Igniculus saltaba al botón) y la
     * pulsación se perdía. Aquí se le entrega a mano con un DOWN
     * sintético de un solo puntero (pointerId real del dedo); si el
     * hijo lo consume, el puntero queda en routedPointers y sus
     * MOVE/UP se le reenvían igual. La zona Igniculus NO entra
     * (isZone): su toque es táctil del juego y lo sigue el router.
     * Tampoco un control que ya tiene un dedo activo: el framework
     * ya se lo dio y reenviarlo lo duplicaría. El sintético nace
     * con coordenadas LOCALES de la hija: dispatchTouchEvent
     * directo no desplaza el evento (eso lo hace el ViewGroup al
     * repartir) y el hijo calcula desde event.x/event.y.
     */
    private fun tryRoutePointerToChild(event: MotionEvent, index: Int, pointerId: Int): Boolean {
        val x = event.getX(index)
        val y = event.getY(index)
        val candidates = ArrayList<OverlayControlView>(childCount)
        val hits = ArrayList<RoutedChildHit>(childCount)
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? OverlayControlView ?: continue
            candidates += child
            hits += RoutedChildHit(
                left = child.left.toFloat(),
                top = child.top.toFloat(),
                right = (child.left + child.width).toFloat(),
                bottom = (child.top + child.height).toFloat(),
                isZone = child is ZoneView,
                hasActivePointer = child.hasActivePointer,
            )
        }
        val hitIndex = findRoutableChildIndex(hits, x, y)
        if (hitIndex < 0) return false
        val child = candidates[hitIndex]
        val synthetic = obtainSinglePointerEvent(
            event.downTime, event.eventTime, MotionEvent.ACTION_DOWN,
            pointerId, x - child.left, y - child.top,
        )
        var consumed = false
        try {
            consumed = child.dispatchTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
        if (!consumed) return false
        routedPointers[pointerId] = child
        return true
    }

    /** 0.2.64 (D-P1-1): MOVE de cada dedo encaminado a mano, a su hijo. */
    private fun forwardRoutedMoves(event: MotionEvent): Boolean {
        if (routedPointers.isEmpty()) return false
        var routed = false
        val it = routedPointers.entries.iterator()
        while (it.hasNext()) {
            val (pointerId, owner) = it.next()
            val index = event.findPointerIndex(pointerId)
            if (index < 0) continue
            // 0.2.65 (R-P1-16): coalescar MOVEs sub-píxel (ver
            // routedLastForwarded): sin esto cada MOVE crea un
            // MotionEvent sintético aunque el dedo no se moviera.
            val x = event.getX(index) - owner.left
            val y = event.getY(index) - owner.top
            val last = routedLastForwarded[pointerId]
            if (last != null && abs(x - last.first) < 1f && abs(y - last.second) < 1f) {
                continue
            }
            routedLastForwarded[pointerId] = x to y
            val synthetic = obtainSinglePointerEvent(
                event.downTime, event.eventTime, MotionEvent.ACTION_MOVE,
                pointerId, x, y,
            )
            try {
                if (owner.dispatchTouchEvent(synthetic)) routed = true
            } finally {
                synthetic.recycle()
            }
            // El hijo soltó el dedo (se salió del botón): ya no es
            // suyo; el siguiente MOVE no se le reenvía.
            if (!owner.hasActivePointer) {
                it.remove()
                routedLastForwarded.remove(pointerId)
            }
        }
        return routed
    }

    /** 0.2.64 (D-P1-1): UP de un dedo encaminado a mano, a su hijo. */
    private fun endRoutedPointer(event: MotionEvent, index: Int, pointerId: Int, action: Int): Boolean {
        val owner = routedPointers.remove(pointerId) ?: return false
        routedLastForwarded.remove(pointerId)
        val x = if (index >= 0) event.getX(index) - owner.left else owner.width / 2f
        val y = if (index >= 0) event.getY(index) - owner.top else owner.height / 2f
        val synthetic = obtainSinglePointerEvent(
            event.downTime, event.eventTime, action, pointerId, x, y,
        )
        try {
            return owner.dispatchTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    /**
     * 0.2.64 (D-P1-1): termina TODOS los dedos encaminados a mano
     * (gesto cancelado o terminado sin su UP). El UP/CANCEL no usa
     * coordenadas en el hijo (release() no las mira): centro de la
     * vista. Sin esto, un botón encaminado quedaría pulsado en el
     * núcleo tras una cancelación.
     */
    private fun endAllRoutedPointers(action: Int) {
        if (routedPointers.isEmpty()) return
        routedLastForwarded.clear()
        val now = SystemClock.uptimeMillis()
        for ((pointerId, owner) in routedPointers) {
            val synthetic = obtainSinglePointerEvent(
                now, now, action, pointerId,
                owner.width / 2f, owner.height / 2f,
            )
            try {
                owner.dispatchTouchEvent(synthetic)
            } finally {
                synthetic.recycle()
            }
        }
        routedPointers.clear()
    }

    private fun findSdlSurface(): View? {
        val cached = sdlSurfaceCache
        if (cached != null && cached.isAttachedToWindow) return cached
        val parentGroup = parent as? android.view.ViewGroup ?: return null
        val found = findSdlSurfaceIn(parentGroup)
        sdlSurfaceCache = found
        return found
    }

    private fun findSdlSurfaceIn(root: android.view.ViewGroup): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is SDLSurface) return child
            if (child === this) continue
            if (child is android.view.ViewGroup) {
                val found = findSdlSurfaceIn(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Evento sintético de UN SOLO puntero, con el pointerId real del
     * dedo. No se reenvía el MotionEvent original tal cual: el
     * onTouch de SDLSurface recorre TODOS los punteros en ACTION_MOVE
     * (SDLSurface.java:258-322), así que un MOVE con varios dedos
     * mandaría al juego también el movimiento del dedo del stick o
     * del botón como si fuera un dedo táctil. Con un evento por dedo,
     * SDL solo ve ese dedo. Coordenadas en el overlay = en la
     * superficie: ambas a pantalla completa en el mismo contenedor.
     */
    private fun obtainSinglePointerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerId: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        // R4-P1-16: la llamada directa a MotionEvent.obtain(...) con
        // matrices NO compila en Kotlin: el compilador resuelve la
        // sobrecarga de un solo puntero (x/y Float) y rechaza las
        // matrices, incluso con tipo explícito (runs #159 y #160).
        // MotionEventHelper (Java) sí resuelve la sobrecarga de 12
        // argumentos y conserva el pointerId real del dedo.
        return MotionEventHelper.obtainSinglePointer(
            downTime, eventTime, action, pointerId, x, y,
        )
    }

    /**
     * Envía un toque sintético a la superficie SDL con la misma
     * protección que ZoneView.forwardTouch: mientras dura el envío,
     * forwardingSyntheticTouch hace que dispatchTouchEvent devuelva
     * false, así que si el destino cae sobre un botón del overlay el
     * sintético no lo pulsa (y EmuSurface tampoco lo re-ofrece al
     * overlay: R4-P1-17).
     */
    private fun dispatchSyntheticToSdl(surface: View, synthetic: MotionEvent): Boolean {
        var handled = false
        try {
            forwardingSyntheticTouch = true
            handled = surface.dispatchTouchEvent(synthetic)
        } finally {
            forwardingSyntheticTouch = false
            synthetic.recycle()
        }
        return handled
    }

    private fun forwardPointerDownToSdl(event: MotionEvent, index: Int, pointerId: Int): Boolean {
        val x = event.getX(index)
        val y = event.getY(index)
        val surface = findSdlSurface()
        if (surface == null) {
            // 0.2.63 (D): sin superficie no hay a dónde reenviar:
            // cuenta como DOWN perdido en TouchStats.
            TouchStats.recordForwardedDown(false, x, y)
            return false
        }
        val synthetic = obtainSinglePointerEvent(
            event.downTime, event.eventTime, MotionEvent.ACTION_DOWN,
            pointerId, x, y,
        )
        // 0.2.63 (D): segundo dedo reenviado como táctil del juego:
        // se registra en TouchStats (el reporte de bug lo muestra).
        val delivered = dispatchSyntheticToSdl(surface, synthetic)
        if (TouchStats.recordForwardedDown(delivered, x, y)) {
            Log.i("GamepadOverlayView", "toque reenviado DOWN dedo $pointerId en ($x, $y) entregado a SDL")
        } else if (!delivered) {
            Log.w("GamepadOverlayView", "toque reenviado DOWN dedo $pointerId en ($x, $y) PERDIDO")
        }
        return delivered
    }

    private fun forwardPointerMovesToSdl(event: MotionEvent): Boolean {
        if (!multiTouchRouter.hasForwardedPointers) return false
        val surface = findSdlSurface() ?: return false
        var forwarded = false
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val decision = multiTouchRouter.onMove(pointerId, event.getX(i), event.getY(i))
            if (decision != MultiTouchRouter.PointerAction.FORWARD_MOVE) continue
            val downTime = multiTouchRouter.downTimeOf(pointerId) ?: continue
            val synthetic = obtainSinglePointerEvent(
                downTime, event.eventTime, MotionEvent.ACTION_MOVE,
                pointerId, event.getX(i), event.getY(i),
            )
            if (dispatchSyntheticToSdl(surface, synthetic)) forwarded = true
        }
        return forwarded
    }

    private fun forwardPointerUpToSdl(event: MotionEvent, index: Int, pointerId: Int, downTime: Long): Boolean {
        val surface = findSdlSurface() ?: return false
        val x = if (index >= 0) event.getX(index) else 0f
        val y = if (index >= 0) event.getY(index) else 0f
        val synthetic = obtainSinglePointerEvent(
            downTime, event.eventTime, MotionEvent.ACTION_UP,
            pointerId, x, y,
        )
        // 0.2.63 (D): UP del segundo dedo reenviado (cierra el toque
        // contado en forwardPointerDownToSdl).
        val delivered = dispatchSyntheticToSdl(surface, synthetic)
        TouchStats.recordForwardedUp(delivered, x, y)
        return delivered
    }

    private fun endForwardedTouchesToSdl() {
        val forwarded = multiTouchRouter.endAll()
        if (forwarded.isEmpty()) return
        val surface = findSdlSurface() ?: return
        val now = SystemClock.uptimeMillis()
        for (touch in forwarded) {
            val synthetic = obtainSinglePointerEvent(
                touch.downTime, now, MotionEvent.ACTION_UP,
                touch.pointerId, touch.x, touch.y,
            )
            dispatchSyntheticToSdl(surface, synthetic)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && builtForSize != w to h) rebuild()
    }

    /**
     * 0.2.63 (G): en CADA layout se re-derivan los márgenes de los
     * hijos desde las fracciones de sus controles. rebuild() solo
     * corre cuando cambia el tamaño (onSizeChanged) o se cambian los
     * controles (setControls): si un rebuild corrió con un tamaño
     * transitorio, sus márgenes quedaban horneados y nada los
     * corregía (el stick desplazado de la captura 52166 tras
     * "Corregir audio"). onLayout() es la red de seguridad: si un
     * margen ya no corresponde al tamaño actual, se corrige aquí.
     * Solo se escribe el LayoutParams si difiere: escribirlo
     * dispara otro layout, y sin la guarda el onLayout se llamaría
     * a sí mismo en bucle; con ella, la segunda pasada ya no
     * cambia nada y el bucle termina.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        repositionChildrenFromFractions()
    }

    private fun repositionChildrenFromFractions() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        for (i in 0 until childCount) {
            val control = mountedControls.getOrNull(i) ?: continue
            val child = getChildAt(i) as? OverlayControlView ?: continue
            val expected = childLayout(control, width.toFloat(), height.toFloat(), density)
            val params = child.layoutParams as? LayoutParams ?: continue
            if (params.width != expected.widthPx || params.height != expected.sizePx ||
                params.leftMargin != expected.leftMargin || params.topMargin != expected.topMargin
            ) {
                params.width = expected.widthPx
                params.height = expected.sizePx
                params.leftMargin = expected.leftMargin
                params.topMargin = expected.topMargin
                child.layoutParams = params
            }
        }
        // 0.2.64 (A-2): las alas no están en mountedControls (no son
        // un control): su posición se re-deriva del botón de pausa,
        // igual que los hijos. Sin esto, un cambio de tamaño movía
        // el botón y dejaba las alas en su sitio viejo.
        val wings = pauseWingsView
        val pause = pauseWingsControl
        if (wings != null && pause != null) {
            val pauseLayout = childLayout(pause, width.toFloat(), height.toFloat(), density)
            val expected = pauseWingsLayout(pauseLayout)
            val params = wings.layoutParams as? LayoutParams
            if (params != null && (params.width != expected.widthPx || params.height != expected.heightPx ||
                    params.leftMargin != expected.leftMargin || params.topMargin != expected.topMargin)
            ) {
                params.width = expected.widthPx
                params.height = expected.heightPx
                params.leftMargin = expected.leftMargin
                params.topMargin = expected.topMargin
                wings.layoutParams = params
            }
            updateWingsExclusion()
        }
    }

    /**
     * 0.2.65 (M-P2-3): recalcula el rect que las alas de la pausa
     * no deben pintar (el botón Volver, en coordenadas locales de
     * la vista de alas). Se llama al reconstruir y en cada layout:
     * si cambia el tamaño o gira el teléfono, pausa y círculo se
     * mueven y la exclusión debe moverse con ellos.
     */
    private fun updateWingsExclusion() {
        val wings = pauseWingsView ?: return
        val pause = pauseWingsControl ?: return
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val circle = mountedControls.firstOrNull { it.id == "btn_circle" }
        if (circle == null) {
            wings.excludedRect = null
            return
        }
        val wingsLayout = pauseWingsLayout(childLayout(pause, width.toFloat(), height.toFloat(), density))
        val circleLayout = childLayout(circle, width.toFloat(), height.toFloat(), density)
        wings.excludedRect = RectF(
            (circleLayout.leftMargin - wingsLayout.leftMargin).toFloat(),
            (circleLayout.topMargin - wingsLayout.topMargin).toFloat(),
            (circleLayout.leftMargin + circleLayout.sizePx - wingsLayout.leftMargin).toFloat(),
            (circleLayout.topMargin + circleLayout.sizePx - wingsLayout.topMargin).toFloat(),
        )
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        // P1-11: al girar el teléfono onSizeChanged reconstruye los
        // controles (la Activity no se recrea: configChanges incluye
        // orientation|screenSize). Sin soltar antes, un botón pulsado
        // o un stick desplazado quedaban atascados en el núcleo: las
        // Views viejas se descartan con removeAllViews() sin release().
        releaseAll()
        removeAllViews()
        val density = resources.displayMetrics.density
        // La zona Igniculus PRIMERO (detrás), como en el editor (B14):
        // si no, taparía el botón Start y parte de los botones de acción.
        // El orden vive en TouchOverlayMapping.overlayChildOrder (puro,
        // testeable): la zona detrás y los botones encima, así que un
        // botón que se solape con la zona recibe su toque.
        val ordered = overlayChildOrder(controls)
        for (control in ordered) {
            // 0.2.63 (G): los márgenes salen de childLayout (el mismo
            // cálculo puro que usa onLayout para re-verificarlos).
            val layout = childLayout(control, width.toFloat(), height.toFloat(), density)
            val child: OverlayControlView = when (control.kind) {
                "stick" -> StickView(context, control)
                "igniculus" -> ZoneView(context, control)
                else -> ButtonView(context, control)
            }
            val params = LayoutParams(layout.widthPx, layout.sizePx)
            params.leftMargin = layout.leftMargin
            params.topMargin = layout.topMargin
            addView(child, params)
        }
        mountedControls = ordered
        builtForSize = width to height
        // 0.2.64 (A-2): las alas de la pausa como vista APARTE, más
        // ancha que el botón (4,7 x de ancho, 1,07 x de alto, centrada
        // en él): dentro del botón se recortaban al cuadrado y no
        // podían extenderse como en el mockup. Es una vista sin
        // toque (no consume nada: los toques la atraviesan hacia el
        // botón de pausa o el de círculo) y va LA ÚLTIMA, encima de
        // los controles, para que las hojas se dibujen sobre el
        // cristal. Sin botón de pausa en el perfil, no hay alas.
        // 0.2.68: el botón de pausa es el de id de fábrica
        // (pauseControlFor): la píldora START posterior no roba
        // las alas; sin pausa visible no hay alas (0.2.70, P2-1).
        pauseWingsControl = pauseControlFor(ordered)
        val pauseControl = pauseWingsControl
        if (pauseControl != null) {
            val pauseLayout = childLayout(pauseControl, width.toFloat(), height.toFloat(), density)
            val wingsLayout = pauseWingsLayout(pauseLayout)
            val wings = PauseWingsView(context)
            wings.ornamentAlpha = pauseControl.opacity * generalOpacity
            val wingsParams = LayoutParams(wingsLayout.widthPx, wingsLayout.heightPx)
            wingsParams.leftMargin = wingsLayout.leftMargin
            wingsParams.topMargin = wingsLayout.topMargin
            addView(wings, wingsParams)
            pauseWingsView = wings
        } else {
            pauseWingsView = null
        }
        updateWingsExclusion()
        // 0.2.63 (G): diagnóstico del montaje (logcat, sin E/S de
        // fichero en el hilo de UI: LauncherLog.append es síncrono
        // y R5-A1-11 lo sacó de Main a propósito). Con perfil +
        // tamaño de vista + fracciones del stick, un botón
        // desplazado se diagnostica sin adivinar: si las fracciones
        // son las de fábrica (0.05, 0.675) y la vista es 2340x1080,
        // el stick va en (68, 485); si aparece en otro sitio, los
        // márgenes se hornearon con otro tamaño.
        val stick = controls.firstOrNull { it.kind == "stick" }
        Log.i(
            "GamepadOverlay",
            "overlay construido: perfil ${activeProfileId ?: "desconocido"}, vista ${width}x${height}, ${controls.size} controles, stick ${stick?.let { "(${it.xFraction}, ${it.yFraction}) ${it.sizeDp} dp" } ?: "sin stick"}",
        )
    }

    private fun parseHex(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        Color.parseColor("#8CCBEB")
    }

    /**
     * Iconos que se dibujan dentro de los botones de cristal. Vive
     * aquí (clase externa, no inner): Kotlin PROHÍBE las enum class
     * dentro de una inner class ("'Enum class' is prohibited here",
     * run #86, 2026-11-08) y por eso no puede vivir en
     * OverlayControlView.
     */
    private enum class IconKind {
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, GLYPH_CROSS, GLYPH_CIRCLE, GLYPH_SQUARE, GLYPH_TRIANGLE, PAUSE, IGNICULUS, TEXT_PS, SWORD
    }

    /**
     * P-C2: clave del gradiente cacheado de drawGlassBody (radio
     * + tinte + alfa). 0.2.65 (R-P1-14): la clave ya NO incluye el
     * centro: el gradiente se crea en coordenadas locales (centro
     * (0,0)) y se traslada con setLocalMatrix a cada rect. Antes
     * la clave llevaba (cx, cy) y el pomo del stick y el orbe de
     * Igniculus, que se mueven en cada MOVE, reconstruían el
     * RadialGradient + 2 arrays en cada frame. Vive aquí (clase
     * externa, no inner): Kotlin PROHÍBE las class dentro de una
     * inner class ("'Class' is prohibited here", run #296,
     * 2026-11-11) igual que las enum class (run #86). Por eso no
     * puede vivir en OverlayControlView.
     */
    private data class GlassGradientKey(val radius: Float, val tint: Int, val alpha: Float)

    private abstract inner class OverlayControlView(
        context: Context,
        protected val control: TouchControl,
    ) : View(context) {
        protected val density: Float = resources.displayMetrics.density
        protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EAF4FB")
            textAlign = Paint.Align.CENTER
            textSize = 14f * density
            isFakeBoldText = true
        }
        // R5-A1-16: pintura propia del glifo "PS" de GUIDE: el
        // texto escala con el botón (textSize por dibujado) y no
        // puede compartir textPaint, cuyo tamaño es fijo para las
        // etiquetas.
        protected val psPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EAF4FB")
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        protected val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EAF4FB")
            style = Paint.Style.FILL
        }
        protected val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EAF4FB")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        protected val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        protected val ornamentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A99ACD")
            style = Paint.Style.FILL
        }

        /** Cantidad de pulsación 0..1: anima el brillo al pulsar y soltar. */
        protected var pressAmount: Float = 0f
            private set
        /**
         * 0.2.65 (O-P2-2): UN solo animador por vista para el brillo
         * de pulsación y la gota. Antes eran dos (animatePress 120/
         * 180 ms + ripple 350 ms) que invalidaban la vista en cada
         * vsync cada uno, y animatePress se recreaba en cada MOVE
         * del stick mientras el brillo aún no llegaba a 1. Ahora un
         * único ValueAnimator de 350 ms (lo que dura la gota, lo
         * más largo) invalida por frame y el brillo se interpola
         * por tiempo (pressFrom -> pressTarget en 120 ms al pulsar
         * o 180 ms al soltar) desde pressStartUptime. Si cambia el
         * objetivo a mitad de animación, el animador en curso lo
         * recoge sin recrearse; si al terminar sigue la gota o el
         * brillo sin completar, se relanza solo.
         */
        private var motionAnimator: ValueAnimator? = null
        private var pressTarget: Float = 0f
        private var pressFrom: Float = 0f
        private var pressStartUptime: Long = 0L
        private var pressDurationMs: Long = 120L

        protected fun animatePress(to: Float) {
            if (to == pressTarget && pressAmount == to) return
            if (to == pressTarget && motionAnimator?.isRunning == true) return
            pressFrom = pressAmount
            pressTarget = to
            pressStartUptime = SystemClock.uptimeMillis()
            pressDurationMs = if (to > pressFrom) 120L else 180L
            ensureMotionAnimator()
        }

        private fun ensureMotionAnimator() {
            if (motionAnimator?.isRunning == true) return
            motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = RippleState.DURATION_MS
                addUpdateListener {
                    val elapsed = SystemClock.uptimeMillis() - pressStartUptime
                    val progress = if (pressDurationMs > 0L) {
                        (elapsed.toFloat() / pressDurationMs).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    pressAmount = pressFrom + (pressTarget - pressFrom) * progress
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val now = SystemClock.uptimeMillis()
                        val pressDone = pressAmount == pressTarget ||
                            now - pressStartUptime >= pressDurationMs
                        val rippleDone = !rippleState.isActive || rippleState.isDone(now)
                        if (pressDone && rippleDone) {
                            pressAmount = pressTarget
                            motionAnimator = null
                            invalidate()
                        } else {
                            // Brillo sin completar o gota empezada
                            // tarde: otra vuelta con los mismos
                            // pressFrom/pressTarget/pressStartUptime.
                            motionAnimator = null
                            ensureMotionAnimator()
                        }
                    }
                })
                start()
            }
        }

        /**
         * Gota al presionar (0.2.54): una onda que nace en el punto del
         * toque, se expande y se desvanece en 350 ms. Se SUMA a
         * animatePress (brillo turquesa y escala del icono se quedan
         * como estan) y, al reves que pressAmount, NO invierte al
         * soltar: dura sus 350 ms completos aunque el dedo se levante
         * antes. El estado vive en RippleState (logica pura, probada
         * en RippleStateTest); el animator solo pide redibujar cada
         * frame y la vista calcula radio y alfa con uptimeMillis.
         */
        protected val rippleState = RippleState()
        protected val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#75D6CE")
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        protected val rippleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8CCBEB")
            style = Paint.Style.FILL
        }
        /** Recorte reutilizable a la forma del control: sin Path nuevo por frame. */
        protected val rippleClipPath = Path()
        /** Rect reutilizable del orbe Igniculus (ZoneView): sin RectF nuevo por frame. */
        protected val rippleRect = RectF()
        /** P-C2: tinte del control parseado una sola vez al crear la vista (el color solo cambia con rebuild). */
        private val tintColor: Int = parseHex(control.colorHex)
        /** P-C2: rect reutilizable de drawGlassBody (halo, brillo y anillos): sin RectF nuevo por frame. */
        private val tmpRect = RectF()
        /** P-C2: gradientes radiales cacheados de drawGlassBody, por (radio, tinte, alfa); null hasta el primer dibujado. */
        private val gradientCache = LinkedHashMap<GlassGradientKey, RadialGradient>()
        /** 0.2.65 (R-P1-14): matriz reutilizable para trasladar el gradiente local a cada rect, sin Matrix nueva por frame. */
        private val gradientMatrix = Matrix()
        /** 0.2.65 (O-P2-1): rects reutilizables de onDraw (cuerpo, pomo, orbe y glifos): sin RectF nuevo por dibujado. */
        protected val bodyRect = RectF()
        protected val knobRect = RectF()
        protected val orbRectF = RectF()
        protected val glyphRect = RectF()
        /** 0.2.65 (O-P2-1): lados de la corona floral, sin floatArrayOf nuevo por dibujado. */
        protected val crownSides = floatArrayOf(-1f, 1f)
        /** 0.2.65 (O-P2-6): alfa efectivo cacheado; refreshStyle() lo invalida cuando cambia la opacidad. */
        private var cachedEffectiveAlpha: Float? = null
        /** P-C3: Paths reutilizables de adornos e iconos (reset() antes de reconstruir, como rippleClipPath). */
        private val diamondPath = Path()
        private val leftLeafPath = Path()
        private val rightLeafPath = Path()
        private val arrowPath = Path()
        private val trianglePath = Path()
        private val upperLeafPath = Path()
        private val bladePath = Path()
        private val starPath = Path()
        private val bodyPath = Path()
        private val wispPath = Path()

        protected fun startRipple(x: Float, y: Float) {
            rippleState.start(x, y, SystemClock.uptimeMillis())
            // 0.2.65 (O-P2-2): la gota ya no tiene animador propio:
            // el animador único (ensureMotionAnimator) invalida por
            // frame mientras dura; RippleState calcula radio y alfa
            // por tiempo, así que solo hace falta despertarlo.
            ensureMotionAnimator()
            invalidate()
        }

        /**
         * Dibuja la gota recortada a la forma del control (clipRect):
         * relleno suave azul hielo que se desvanece + anillo expansivo
         * turquesa, centrados en el punto del toque. El radio llega a
         * 1.15 veces el radio del control. Se llama DESPUES del cuerpo
         * de cristal y ANTES del icono o del pomo, para que el icono
         * quede nitido encima. Cuando la gota termina no dibuja nada.
         * Sin asignaciones: solo se reutilizan ripplePaint,
         * rippleFillPaint y rippleClipPath.
         */
        protected fun drawRipple(canvas: Canvas, clipRect: RectF, alpha: Float) {
            if (!rippleState.isActive) return
            val now = SystemClock.uptimeMillis()
            if (rippleState.isDone(now)) {
                rippleState.clear()
                return
            }
            val fade = rippleState.alphaAt(now) * alpha
            if (fade <= 0.01f) return
            val maxRadius = clipRect.width() / 2f * 1.15f
            val radius = rippleState.radiusAt(now, maxRadius)
            if (radius <= 0f) return
            rippleClipPath.reset()
            if (control.shape == ControlShape.SQUARE) {
                rippleClipPath.addRoundRect(clipRect, 8f * density, 8f * density, Path.Direction.CW)
            } else {
                rippleClipPath.addCircle(clipRect.centerX(), clipRect.centerY(), clipRect.width() / 2f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(rippleClipPath)
            rippleFillPaint.alpha = (fade * 0.20f * 255).toInt()
            canvas.drawCircle(rippleState.originX, rippleState.originY, radius, rippleFillPaint)
            ripplePaint.alpha = (fade * 255).toInt()
            ripplePaint.strokeWidth = 2f * density
            canvas.drawCircle(rippleState.originX, rippleState.originY, radius, ripplePaint)
            canvas.restore()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // Al reconstruir el overlay la gota ya no tiene vista que
            // redibujar: cancelarla evita invalidar una vista suelta.
            // No se cancela en release(): la gota debe durar sus
            // 350 ms completos aunque el dedo se levante antes.
            motionAnimator?.cancel()
            motionAnimator = null
        }

        init {
            isClickable = false
            isFocusable = false
        }

        /** Opacidad efectiva del control: la suya por la general. */
        protected fun effectiveAlpha(): Float {
            // 0.2.65 (O-P2-6): cacheada por vista: la opacidad del
            // control no cambia en la vida de la vista y la general
            // solo cambia por su setter, que pasa por refreshStyle()
            // e invalida esta caché. Sin esto se recalculaba en
            // cada onDraw, hasta 6 veces por dibujado del stick.
            cachedEffectiveAlpha?.let { return it }
            val alpha = (control.opacity * generalOpacity).coerceIn(0f, 1f)
            cachedEffectiveAlpha = alpha
            return alpha
        }

        fun refreshStyle() {
            cachedEffectiveAlpha = null
            invalidate()
        }

        /** Soltar lo que este control tenga pulsado (botón o stick). Por defecto nada. */
        open fun release() {}

        /**
         * R4-P1-16: true mientras este control tiene un dedo asignado
         * (el framework le dio el puntero del gesto). dispatchTouchEvent
         * lo usa para no reenviar a SDL un segundo dedo que caiga
         * sobre un control ya activo (sería un dedo fantasma en el
         * juego justo sobre ese control).
         */
        open val hasActivePointer: Boolean get() = false

        /**
         * Cuerpo de cristal (diseno 51025): degradado radial (centro
         * plata luminosa, medio el color del control, borde azul
         * profundo), doble borde luminoso azul hielo (anillo suave
         * exterior + filo interior) y adornos de diamante y cristal
         * arriba y abajo del anillo — en los 4 puntos si
         * fourWayOrnaments (el stick grande). Translúcido: el
         * escenario se ve a través. Al pulsar, el brillo turquesa
         * crece (pressAmount).
         */
        protected fun drawGlassBody(canvas: Canvas, rect: RectF, alpha: Float, ornaments: Boolean = true, fourWayOrnaments: Boolean = false) {
            // 0.2.67 (fix crash reporte 0.2.66): un rect degenerado (ancho o alto 0,
            // p. ej. onDraw antes del primer layout o durante la rotación) daba
            // radius 0 y RadialGradient tiraba IllegalArgumentException
            // ("ending radius must be > 0"), matando la app al tocar JUGAR.
            // Con tamaño 0 no hay nada visible que dibujar: salir antes.
            if (rect.width() <= 0f || rect.height() <= 0f) return
            val cx = rect.centerX()
            val cy = rect.centerY()
            val radius = rect.width() / 2f
            // Halo neón: un poco más grande y muy translúcido, detrás.
            if (neonGlow) {
                glowPaint.color = tintColor
                glowPaint.alpha = (alpha * 0.16f * 255).toInt()
                tmpRect.set(rect)
                tmpRect.inset(-6f * density, -6f * density)
                drawShapeInto(canvas, tmpRect, glowPaint)
            }
            // Brillo al pulsar: turquesa, crece con pressAmount.
            val glowAlpha = alpha * (0.10f + 0.55f * pressAmount)
            if (glowAlpha > 0.01f) {
                glowPaint.color = GLASS_TURQUOISE
                glowPaint.alpha = (glowAlpha * 255).toInt()
                tmpRect.set(rect)
                tmpRect.inset(-4f * density * pressAmount, -4f * density * pressAmount)
                drawShapeInto(canvas, tmpRect, glowPaint)
            }
            // Cristal: degradado radial con el color del control en medio.
            // P-C2: el gradiente se cachea por (radio, tinte, alfa):
            // un botón quieto no reconstruye RadialGradient ni sus arrays
            // en cada frame. 0.2.65 (R-P1-14): el gradiente vive en
            // coordenadas LOCALES (centro (0,0)) y se traslada con
            // setLocalMatrix al centro de cada rect: el pomo del stick
            // y el orbe de Igniculus se mueven en cada MOVE y antes la
            // clave incluía (cx, cy), así que reconstruían el gradiente
            // en cada frame. Ahora solo cambia si cambia el radio, el
            // tinte o el alfa. La matriz se reutiliza (el shader copia
            // sus valores al fijarla). La caché guarda hasta 4
            // gradientes: el stick dibuja base y pomo con radios
            // distintos en el mismo onDraw y con un solo hueco
            // alternaría recreándolos en cada frame.
            // 0.2.63 (A-8): el borde era demasiado oscuro sobre el juego
            // (alfa 0,62 sobre fondo oscuro se leía como disco azul
            // marino opaco; el mockup es hielo translúcido): borde
            // 0,62 -> 0,40 y medio 0,30 -> 0,40.
            val gradientKey = GlassGradientKey(radius, tintColor, alpha)
            var gradient = gradientCache[gradientKey]
            if (gradient == null) {
                gradient = RadialGradient(
                    0f, 0f, radius,
                    intArrayOf(
                        Color.argb((alpha * 0.34f * 255).toInt(), 226, 238, 248),
                        Color.argb((alpha * 0.40f * 255).toInt(), Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor)),
                        Color.argb((alpha * 0.40f * 255).toInt(), 24, 38, 68),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                if (gradientCache.size >= 4) {
                    val eldest = gradientCache.keys.firstOrNull()
                    if (eldest != null) gradientCache.remove(eldest)
                }
                gradientCache[gradientKey] = gradient
            }
            gradientMatrix.setTranslate(cx, cy)
            gradient.setLocalMatrix(gradientMatrix)
            fillPaint.shader = gradient
            drawShapeInto(canvas, rect, fillPaint)
            fillPaint.shader = null
            // Anillo suave exterior: halo azul hielo que ensancha el
            // borde luminoso, como el resplandor del cristal 51025.
            strokePaint.color = GLASS_ICE_BLUE
            strokePaint.alpha = (alpha * 0.30f * 255).toInt()
            strokePaint.strokeWidth = 3.5f * density
            tmpRect.set(rect)
            tmpRect.inset(-1.5f * density, -1.5f * density)
            drawShapeInto(canvas, tmpRect, strokePaint)
            // Borde luminoso azul hielo.
            strokePaint.color = GLASS_PALE_BLUE
            strokePaint.alpha = (alpha * 0.95f * 255).toInt()
            strokePaint.strokeWidth = 2f * density
            drawShapeInto(canvas, rect, strokePaint)
            // Filo interior: linea fina plata que separa el cristal
            // del borde y da el brillo de bola de cristal.
            strokePaint.color = GLASS_SILVER
            strokePaint.alpha = (alpha * 0.45f * 255).toInt()
            strokePaint.strokeWidth = 1f * density
            tmpRect.set(rect)
            tmpRect.inset(3f * density, 3f * density)
            drawShapeInto(canvas, tmpRect, strokePaint)
            if (ornaments) drawOrnaments(canvas, cx, cy, radius, alpha, fourWayOrnaments)
        }

        private fun drawShapeInto(canvas: Canvas, rect: RectF, paint: Paint) {
            if (control.shape == ControlShape.PILL) {
                // 0.2.68 (neón): píldora (estadio): radio = mitad
                // de la altura, como en la imagen (L/R/START). Con
                // el radio fijo de SQUARE (8 dp) sería un rectángulo
                // con las puntas apenas redondeadas.
                val r = rect.height() / 2f
                canvas.drawRoundRect(rect, r, r, paint)
            } else if (control.shape == ControlShape.SQUARE) {
                canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
            } else {
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
            }
        }

        /**
         * Adornos de cristal (diseno 51025): un rombo de diamante
         * lavanda con dos hojitas laterales y un punto plata en el
         * centro, pegado al borde del anillo. Arriba y abajo siempre;
         * a izquierda y derecha tambien si fourWay (el stick grande
         * lleva los 4, como en la imagen de referencia).
         */
        protected fun drawOrnaments(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float, fourWay: Boolean = false) {
            ornamentPaint.alpha = (alpha * 0.85f * 255).toInt()
            iconPaint.alpha = (alpha * 0.95f * 255).toInt()
            // 0.2.63 (A-3): los rombos eran 6x más pequeños que en el
            // mockup (0,09r, ~5 % del diámetro) y quedaban medio
            // recortados (centros a 1,0r, encima del anillo): ahora
            // 0,18r centrados a 0,92r, enteros dentro del cristal.
            val s = (radius * 0.18f).coerceAtLeast(3f * density)
            val dist = radius * 0.92f
            drawDiamondOrnament(canvas, cx, cy - dist, s)
            drawDiamondOrnament(canvas, cx, cy + dist, s)
            if (fourWay) {
                drawDiamondOrnament(canvas, cx - dist, cy, s)
                drawDiamondOrnament(canvas, cx + dist, cy, s)
            }
        }

        /** Un adorno de diamante: rombo vertical, dos hojitas laterales y punto plata central. */
        protected fun drawDiamondOrnament(canvas: Canvas, x: Float, y: Float, s: Float) {
            diamondPath.reset()
            diamondPath.apply {
                moveTo(x, y - s)
                lineTo(x + 0.55f * s, y)
                lineTo(x, y + s)
                lineTo(x - 0.55f * s, y)
                close()
            }
            canvas.drawPath(diamondPath, ornamentPaint)
            val leaf = 0.45f * s
            leftLeafPath.reset()
            leftLeafPath.apply {
                moveTo(x - s, y - leaf)
                lineTo(x - s + leaf, y)
                lineTo(x - s, y + leaf)
                lineTo(x - s - leaf, y)
                close()
            }
            canvas.drawPath(leftLeafPath, ornamentPaint)
            rightLeafPath.reset()
            rightLeafPath.apply {
                moveTo(x + s, y - leaf)
                lineTo(x + s + leaf, y)
                lineTo(x + s, y + leaf)
                lineTo(x + s - leaf, y)
                close()
            }
            canvas.drawPath(rightLeafPath, ornamentPaint)
            canvas.drawCircle(x, y, 0.22f * s, iconPaint)
        }

        /**
         * 0.2.64 (A-3): corona floral del orbe de Igniculus (diseno
         * 51025): NO es el rombo genérico de drawOrnaments (ese era
         * lo único que había: un diamante arriba y otro abajo). La
         * corona del mockup es una tiara sobre el orbe: diamante
         * central arriba, dos hojas que se abren a los lados
         * siguiendo la curva del orbe, estrellita en cada punta, y
         * diamantitos pequeños a izquierda, derecha y abajo. Se
         * dibuja sobre el orbe (cx, cy, orbR) DESPUÉS del cristal
         * (drawGlassBody con ornaments = false, para no duplicar
         * los rombos) y se mueve con él en modo Joystick.
         */
        protected fun drawFloralCrown(canvas: Canvas, cx: Float, cy: Float, orbR: Float, alpha: Float) {
            ornamentPaint.alpha = (alpha * 0.85f * 255).toInt()
            iconPaint.alpha = (alpha * 0.95f * 255).toInt()
            // Diamante central de la corona, sobre la parte superior del orbe.
            val crownS = (orbR * 0.16f).coerceAtLeast(3f * density)
            drawDiamondOrnament(canvas, cx, cy - orbR * 0.98f, crownS)
            // Hojas que se abren a los lados siguiendo la curva del orbe.
            for (s in crownSides) {
                upperLeafPath.reset()
                upperLeafPath.apply {
                    moveTo(cx + s * 0.10f * orbR, cy - 0.96f * orbR)
                    quadTo(cx + s * 0.55f * orbR, cy - 1.14f * orbR, cx + s * 0.78f * orbR, cy - 0.86f * orbR)
                    quadTo(cx + s * 0.45f * orbR, cy - 0.80f * orbR, cx + s * 0.10f * orbR, cy - 0.96f * orbR)
                    close()
                }
                canvas.drawPath(upperLeafPath, ornamentPaint)
                drawSparkle(canvas, cx + s * 0.78f * orbR, cy - 0.86f * orbR, 0.05f * orbR)
            }
            // Diamantitos pequeños a izquierda, derecha y abajo.
            val sideS = (orbR * 0.10f).coerceAtLeast(2.5f * density)
            drawDiamondOrnament(canvas, cx - orbR * 0.96f, cy, sideS)
            drawDiamondOrnament(canvas, cx + orbR * 0.96f, cy, sideS)
            drawDiamondOrnament(canvas, cx, cy + orbR * 0.96f, sideS)
        }

        /**
         * Icono del botón según su VitaButton. null = sin icono: se dibuja la etiqueta en plata.
         * R4-P0-2: los 4 botones de cara llevan su glifo PlayStation
         * (X, círculo, cuadrado, triángulo) en vez de iconos
         * temáticos. CROSS ya no comparte icono con DPAD_UP: CROSS
         * es GLYPH_CROSS y DPAD_UP sigue siendo ARROW_UP. El D-Pad
         * sigue con flechas y START con barras de pausa.
         * R5-A1-16: GUIDE lleva el texto "PS" como glifo (TEXT_PS).
         * 0.2.54: ANTES del glifo va el icono propio por id de
         * control (iconOverrideFor): btn_sword lleva espada y
         * btn_jump flecha arriba, aunque SQUARE y CROSS tengan
         * glifo. Un botón de cara añadido por el usuario en el
         * editor (otro id) NO pasa por ahí y sigue con su glifo.
         */
        protected fun iconFor(button: VitaButton): IconKind? {
            when (iconOverrideFor(control.id)) {
                TouchOverlayMapping.ControlIconOverride.SWORD -> return IconKind.SWORD
                TouchOverlayMapping.ControlIconOverride.ARROW_UP -> return IconKind.ARROW_UP
                null -> { }
            }
            when (faceGlyphFor(button)) {
                FaceGlyph.CROSS -> return IconKind.GLYPH_CROSS
                FaceGlyph.CIRCLE -> return IconKind.GLYPH_CIRCLE
                FaceGlyph.SQUARE -> return IconKind.GLYPH_SQUARE
                FaceGlyph.TRIANGLE -> return IconKind.GLYPH_TRIANGLE
                null -> { }
            }
            return when (button) {
                VitaButton.START -> IconKind.PAUSE
                VitaButton.GUIDE -> IconKind.TEXT_PS
                VitaButton.DPAD_UP -> IconKind.ARROW_UP
                VitaButton.DPAD_DOWN -> IconKind.ARROW_DOWN
                VitaButton.DPAD_LEFT -> IconKind.ARROW_LEFT
                VitaButton.DPAD_RIGHT -> IconKind.ARROW_RIGHT
                else -> null
            }
        }

        /** Dibuja el icono centrado en (cx, cy) con radio r. La escala crece un poco al pulsar. */
        protected fun drawIcon(canvas: Canvas, kind: IconKind, cx: Float, cy: Float, r: Float, alpha: Float) {
            iconPaint.alpha = (alpha * 0.95f * 255).toInt()
            iconStrokePaint.alpha = (alpha * 0.95f * 255).toInt()
            val rr = r * (1f + 0.08f * pressAmount)
            when (kind) {
                IconKind.ARROW_UP -> drawArrowIcon(canvas, cx, cy, rr, 0f)
                IconKind.ARROW_DOWN -> drawArrowIcon(canvas, cx, cy, rr, 180f)
                IconKind.ARROW_LEFT -> drawArrowIcon(canvas, cx, cy, rr, -90f)
                IconKind.ARROW_RIGHT -> drawArrowIcon(canvas, cx, cy, rr, 90f)
                IconKind.GLYPH_CROSS -> drawGlyphCross(canvas, cx, cy, rr)
                IconKind.GLYPH_CIRCLE -> drawGlyphCircle(canvas, cx, cy, rr)
                IconKind.GLYPH_SQUARE -> drawGlyphSquare(canvas, cx, cy, rr)
                IconKind.GLYPH_TRIANGLE -> drawGlyphTriangle(canvas, cx, cy, rr)
                IconKind.PAUSE -> drawPauseIcon(canvas, cx, cy, rr, alpha)
                IconKind.IGNICULUS -> drawIgniculusIcon(canvas, cx, cy, rr, alpha)
                IconKind.TEXT_PS -> drawPsIcon(canvas, cx, cy, rr, alpha)
                IconKind.SWORD -> drawSwordIcon(canvas, cx, cy, rr)
            }
        }

        /**
         * R5-A1-16: glifo PS para GUIDE. El logo de PlayStation
         * como formas sería ilegible a este tamaño, así que se
         * dibuja el texto "PS" en negrita, escalado al botón (la
         * mitad del radio, centrado vertical con fontMetrics como
         * las etiquetas). L2/R2/L3/R3 siguen solo con su etiqueta.
         */
        private fun drawPsIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
            psPaint.alpha = (alpha * 0.95f * 255).toInt()
            psPaint.textSize = r * 0.52f
            val metrics = psPaint.fontMetrics
            val y = cy - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText("PS", cx, y, psPaint)
        }

        /**
         * Flecha fina de salto (diseno 51025): punta de lanza con
         * lados curvos y asta delgada, en vez de la flecha gruesa
         * de antes. Gira con degrees para las demas direcciones.
         */
        private fun drawArrowIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, degrees: Float) {
            canvas.save()
            canvas.rotate(degrees, cx, cy)
            arrowPath.reset()
            arrowPath.apply {
                moveTo(cx, cy - 0.58f * r)
                quadTo(cx + 0.10f * r, cy - 0.10f * r, cx + 0.42f * r, cy + 0.02f * r)
                lineTo(cx + 0.14f * r, cy + 0.02f * r)
                lineTo(cx + 0.14f * r, cy + 0.52f * r)
                lineTo(cx - 0.14f * r, cy + 0.52f * r)
                lineTo(cx - 0.14f * r, cy + 0.02f * r)
                lineTo(cx - 0.42f * r, cy + 0.02f * r)
                quadTo(cx - 0.10f * r, cy - 0.10f * r, cx, cy - 0.58f * r)
                close()
            }
            canvas.drawPath(arrowPath, iconPaint)
            canvas.restore()
        }

        /**
         * Glifos PlayStation dibujados como formas (no como texto:
         * no dependen de la fuente del sistema). R4-P0-2: son los
         * símbolos que el jugador busca — X para confirmar, círculo
         * para atrás — en vez de iconos temáticos.
         */
        private fun drawGlyphCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            iconStrokePaint.strokeWidth = 0.13f * r
            val a = 0.42f * r
            canvas.drawLine(cx - a, cy - a, cx + a, cy + a, iconStrokePaint)
            canvas.drawLine(cx - a, cy + a, cx + a, cy - a, iconStrokePaint)
        }

        private fun drawGlyphCircle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            iconStrokePaint.strokeWidth = 0.12f * r
            canvas.drawCircle(cx, cy, 0.45f * r, iconStrokePaint)
        }

        private fun drawGlyphSquare(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            iconStrokePaint.strokeWidth = 0.12f * r
            val a = 0.42f * r
            glyphRect.set(cx - a, cy - a, cx + a, cy + a)
            canvas.drawRoundRect(glyphRect, 0.08f * r, 0.08f * r, iconStrokePaint)
        }

        private fun drawGlyphTriangle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            iconStrokePaint.strokeWidth = 0.12f * r
            trianglePath.reset()
            trianglePath.apply {
                moveTo(cx, cy - 0.48f * r)
                lineTo(cx + 0.45f * r, cy + 0.36f * r)
                lineTo(cx - 0.45f * r, cy + 0.36f * r)
                close()
            }
            canvas.drawPath(trianglePath, iconStrokePaint)
        }

        private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
            val w = 0.16f * r
            val h = 0.72f * r
            val radius = 0.08f * r
            glyphRect.set(cx - 0.24f * r - w / 2, cy - h / 2, cx - 0.24f * r + w / 2, cy + h / 2)
            canvas.drawRoundRect(glyphRect, radius, radius, iconPaint)
            glyphRect.set(cx + 0.24f * r - w / 2, cy - h / 2, cx + 0.24f * r + w / 2, cy + h / 2)
            canvas.drawRoundRect(glyphRect, radius, radius, iconPaint)
            // 0.2.64 (A-2): las alas ya NO se dibujan aquí: vivían
            // pegadas al círculo, dentro del cuadrado del botón, y
            // se recortaban. Ahora son PauseWingsView, una vista
            // aparte más ancha que el botón (ver rebuild()).
        }

        /**
         * Espada del botón btn_sword (0.2.54, layout limpio): hoja
         * diagonal con punta, guarda, empuñadura y pomo, en plata
         * #EAF4FB como los demás iconos (iconPaint), más dos
         * destellos de 4 puntas. La hoja se dibuja vertical y se
         * gira 45 grados alrededor del centro, como la espada de
         * Aurora en la captura de referencia.
         */
        private fun drawSwordIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            canvas.save()
            canvas.rotate(45f, cx, cy)
            // Hoja: rectángulo con punta triangular arriba.
            bladePath.reset()
            bladePath.apply {
                moveTo(cx, cy - 0.58f * r)
                lineTo(cx + 0.10f * r, cy - 0.30f * r)
                lineTo(cx + 0.10f * r, cy + 0.02f * r)
                lineTo(cx - 0.10f * r, cy + 0.02f * r)
                lineTo(cx - 0.10f * r, cy - 0.30f * r)
                close()
            }
            canvas.drawPath(bladePath, iconPaint)
            // Guarda: barra horizontal bajo la hoja.
            glyphRect.set(cx - 0.26f * r, cy + 0.04f * r, cx + 0.26f * r, cy + 0.12f * r)
            canvas.drawRoundRect(
                glyphRect,
                0.04f * r, 0.04f * r, iconPaint,
            )
            // Empuñadura y pomo.
            glyphRect.set(cx - 0.05f * r, cy + 0.12f * r, cx + 0.05f * r, cy + 0.42f * r)
            canvas.drawRoundRect(
                glyphRect,
                0.03f * r, 0.03f * r, iconPaint,
            )
            canvas.drawCircle(cx, cy + 0.47f * r, 0.06f * r, iconPaint)
            canvas.restore()
            // Tres destellos de 4 puntas, fuera de la rotación: dos
            // a los lados y uno junto a la punta de la hoja.
            drawSparkle(canvas, cx + 0.38f * r, cy - 0.34f * r, 0.10f * r)
            drawSparkle(canvas, cx - 0.40f * r, cy + 0.30f * r, 0.08f * r)
            drawSparkle(canvas, cx + 0.30f * r, cy - 0.48f * r, 0.07f * r)
        }

        /** Estrella de 4 puntas (destello) centrada en (x, y) con radio s. */
        private fun drawSparkle(canvas: Canvas, x: Float, y: Float, s: Float) {
            starPath.reset()
            starPath.apply {
                moveTo(x, y - s)
                lineTo(x + 0.25f * s, y - 0.25f * s)
                lineTo(x + s, y)
                lineTo(x + 0.25f * s, y + 0.25f * s)
                lineTo(x, y + s)
                lineTo(x - 0.25f * s, y + 0.25f * s)
                lineTo(x - s, y)
                lineTo(x - 0.25f * s, y - 0.25f * s)
                close()
            }
            canvas.drawPath(starPath, iconPaint)
        }

        private fun drawIgniculusIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
            // Halo turquesa detrás.
            // 0.2.65 (R-P1-15): colores como const Int, sin
            // Color.parseColor en cada dibujado (aquí iban 5).
            glowPaint.color = GLASS_TURQUOISE
            glowPaint.alpha = (alpha * 0.35f * 255).toInt()
            canvas.drawCircle(cx, cy, r * 0.85f, glowPaint)
            // Cuerpo: gotita/llama azul hielo.
            bodyPath.reset()
            bodyPath.apply {
                moveTo(cx, cy - 0.62f * r)
                quadTo(cx + 0.34f * r, cy - 0.10f * r, cx + 0.30f * r, cy + 0.18f * r)
                quadTo(cx + 0.24f * r, cy + 0.48f * r, cx, cy + 0.48f * r)
                quadTo(cx - 0.24f * r, cy + 0.48f * r, cx - 0.30f * r, cy + 0.18f * r)
                quadTo(cx - 0.34f * r, cy - 0.10f * r, cx, cy - 0.62f * r)
                close()
            }
            canvas.drawPath(bodyPath, iconPaint)
            // Llamita turquesa encima de la cabeza (diseno 51025).
            glowPaint.color = GLASS_TURQUOISE
            glowPaint.alpha = (alpha * 0.85f * 255).toInt()
            wispPath.reset()
            wispPath.apply {
                moveTo(cx, cy - 0.88f * r)
                quadTo(cx + 0.11f * r, cy - 0.68f * r, cx, cy - 0.58f * r)
                quadTo(cx - 0.11f * r, cy - 0.68f * r, cx, cy - 0.88f * r)
                close()
            }
            canvas.drawPath(wispPath, glowPaint)
            // Ojos: dos puntitos azul profundo.
            glowPaint.color = IGNICULUS_DEEP_BLUE
            glowPaint.alpha = (alpha * 0.95f * 255).toInt()
            canvas.drawCircle(cx - 0.12f * r, cy + 0.02f * r, 0.055f * r, glowPaint)
            canvas.drawCircle(cx + 0.12f * r, cy + 0.02f * r, 0.055f * r, glowPaint)
            // Sonrisa.
            iconStrokePaint.strokeWidth = 0.045f * r
            iconStrokePaint.color = IGNICULUS_DEEP_BLUE
            glyphRect.set(cx - 0.12f * r, cy + 0.08f * r, cx + 0.12f * r, cy + 0.28f * r)
            canvas.drawArc(glyphRect, 20f, 140f, false, iconStrokePaint)
            iconStrokePaint.color = GLASS_PALE_WHITE
            // Destellos dentro del orbe, alrededor de la carita.
            iconPaint.alpha = (alpha * 0.95f * 255).toInt()
            drawSparkle(canvas, cx + 0.48f * r, cy - 0.35f * r, 0.07f * r)
            drawSparkle(canvas, cx - 0.50f * r, cy + 0.28f * r, 0.06f * r)
            drawSparkle(canvas, cx + 0.42f * r, cy + 0.42f * r, 0.05f * r)
        }

        /** Icono del botón (o la etiqueta en plata si no tiene icono diseñado). */
        protected fun drawIconOrLabel(canvas: Canvas, rect: RectF, alpha: Float) {
            // 0.2.68 (neón): una píldora SIEMPRE dibuja su etiqueta
            // ("L", "R", "START"), no el icono de su botón: la
            // píldora START lleva VitaButton.START y su icono sería
            // la pausa (II). El texto escala con la altura de la
            // píldora (0,42 x alto, negrita como el resto).
            if (control.shape == ControlShape.PILL) {
                if (control.label.isBlank()) return
                textPaint.alpha = (alpha * 255).toInt()
                textPaint.textSize = rect.height() * 0.42f
                val metrics = textPaint.fontMetrics
                val y = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(control.label, rect.centerX(), y, textPaint)
                return
            }
            val kind = iconFor(control.vitaButton)
            if (kind != null) {
                // 0.2.63 (A-5): el icono se dibujaba a 0,30 x Ø
                // (~28-33 % del botón; en el mockup ocupa ~50 %):
                // 0,45 x Ø (espada ~47 %, flecha ~50 %; los
                // destellos crecen en la misma proporción).
                drawIcon(canvas, kind, rect.centerX(), rect.centerY(), rect.width() * 0.45f, alpha)
                return
            }
            if (control.label.isBlank()) return
            textPaint.alpha = (alpha * 255).toInt()
            // El tamaño fijo de la etiqueta (14 dp) se repone aquí:
            // una píldora dibujada antes deja textSize escalado.
            textPaint.textSize = 14f * density
            val metrics = textPaint.fontMetrics
            val y = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(control.label, rect.centerX(), y, textPaint)
        }
    }

    private inner class ButtonView(context: Context, control: TouchControl) :
        OverlayControlView(context, control) {

        private val sdlButton: Int? = control.vitaButton.toSdlButton()
        private var pressed = false
        // P1-10 (2026-11-09): el dedo que pulsó el botón. Sin él, el
        // botón solo se soltaba con ACTION_UP (el ÚLTIMO dedo en
        // pantalla): si el dedo del botón se levantaba mientras otro
        // seguía pulsado, el botón quedaba atascado. Y ACTION_MOVE
        // leía event.x/event.y (puntero 0), no el dedo del botón.
        private var pointerId: Int = -1

        override val hasActivePointer: Boolean get() = pointerId != -1

        override fun onDraw(canvas: Canvas) {
            bodyRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val rect = bodyRect
            if (sdlButton == null) {
                // P2-23: botón "Sin asignar": no es pulsable (su toque
                // pasa a la superficie SDL, ver onTouchEvent) y no debe
                // dibujarse como si lo fuera: fantasma atenuado, sin
                // icono ni etiqueta, solo para localizarlo.
                drawGlassBody(canvas, rect, effectiveAlpha() * 0.25f)
                return
            }
            if (control.shape == ControlShape.PILL) {
                // 0.2.68 (neón): la píldora NO lleva los rombos
                // genéricos de drawGlassBody (arriba/abajo a 0,92r
                // con r = ancho/2 caerían fuera de la píldora):
                // cristal sin adornos + un rombo en cada extremo
                // del eje horizontal, como en la imagen, y la
                // etiqueta centrada (drawIconOrLabel, rama PILL).
                drawGlassBody(canvas, rect, effectiveAlpha(), ornaments = false)
                drawRipple(canvas, rect, effectiveAlpha())
                val diamondSize = rect.height() * 0.13f
                val diamondInset = rect.height() * 0.30f
                drawDiamondOrnament(canvas, rect.left + diamondInset, rect.centerY(), diamondSize)
                drawDiamondOrnament(canvas, rect.right - diamondInset, rect.centerY(), diamondSize)
                drawIconOrLabel(canvas, rect, effectiveAlpha())
                return
            }
            drawGlassBody(canvas, rect, effectiveAlpha())
            drawRipple(canvas, rect, effectiveAlpha())
            drawIconOrLabel(canvas, rect, effectiveAlpha())
        }

        override fun release() {
            if (pressed) {
                pressed = false
                pointerId = -1
                sdlButton?.let { listener?.onButton(it, false) }
                animatePress(0f)
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val sdl = sdlButton ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = event.getPointerId(0)
                    pressed = true
                    listener?.onButton(sdl, true)
                    animatePress(1f)
                    startRipple(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // El dedo de ESTE botón se levanta mientras otro
                    // sigue en pantalla: soltarlo ahora, no al levantar
                    // el último dedo.
                    if (event.getPointerId(event.actionIndex) == pointerId) {
                        release()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // El dedo se salió del botón: soltar (no queda atascado).
                    // Se usan las coordenadas del dedo que pulsó el botón,
                    // no las del puntero 0.
                    val index = event.findPointerIndex(pointerId)
                    if (index < 0) return pressed
                    val margin = 8f * density
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (pressed && (x < -margin || x > width + margin ||
                            y < -margin || y > height + margin)
                    ) {
                        release()
                    }
                    return pressed
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressed) release()
                    return true
                }
            }
            return false
        }
    }

    private inner class StickView(context: Context, control: TouchControl) :
        OverlayControlView(context, control) {

        private val rightStick: Boolean = control.vitaButton == VitaButton.RIGHT_STICK
        private val axisX: Int
        private val axisY: Int
        private var pointerId: Int = -1
        private var knobNx: Float = 0f
        private var knobNy: Float = 0f
        /**
         * 0.2.65 (R-P1-13): última posición del pomo ya DIBUJADA.
         * updateFromPoint compara contra ella: un MOVE que no mueve
         * el pomo ≥ 0.5 px no redibuja el stick entero (~35-40 ops
         * de canvas). Los ejes se mandan igual (sendAxes ya
         * deduplica por valor) y el brillo se anima igual.
         */
        private var drawnKnobNx: Float = 0f
        private var drawnKnobNy: Float = 0f
        private var lastAxes: Pair<Short, Short> = 0.toShort() to 0.toShort()
        private var lastDpad: Set<VitaButton> = emptySet()

        override val hasActivePointer: Boolean get() = pointerId != -1

        init {
            val axes = stickAxesFor(rightStick)
            axisX = axes.first
            axisY = axes.second
        }

        override fun onDraw(canvas: Canvas) {
            bodyRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val rect = bodyRect
            // Stick grande (diseno 51025): cristal con adornos de
            // diamante en los 4 puntos, anillo interior luminoso y
            // dos diamantitos dentro del anillo (este y oeste).
            // 0.2.63 (A-9): el anillo era demasiado pequeño (0,78r)
            // y el pomo demasiado grande (0,42r) frente al mockup
            // (anillo ~0,85r, pomo ~0,34r): el pomo dejaba de verse
            // como una bola de cristal pequeña sobre su guía.
            drawGlassBody(canvas, rect, effectiveAlpha(), fourWayOrnaments = true)
            val radius = rect.width() / 2f
            strokePaint.color = GLASS_PALE_BLUE
            strokePaint.alpha = (effectiveAlpha() * 0.5f * 255).toInt()
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(rect.centerX(), rect.centerY(), radius * 0.85f, strokePaint)
            ornamentPaint.alpha = (effectiveAlpha() * 0.7f * 255).toInt()
            iconPaint.alpha = (effectiveAlpha() * 0.9f * 255).toInt()
            val innerDiamond = (radius * 0.05f).coerceAtLeast(2.5f * density)
            drawDiamondOrnament(canvas, rect.centerX() - radius * 0.85f, rect.centerY(), innerDiamond)
            drawDiamondOrnament(canvas, rect.centerX() + radius * 0.85f, rect.centerY(), innerDiamond)
            // Gota sobre la base, antes del pomo: el pomo queda nitido encima.
            drawRipple(canvas, rect, effectiveAlpha())
            // Pomo del stick: círculo de cristal más pequeño desplazado con el dedo.
            // 0.2.68 (neón): 0,39 x radio (medido en la imagen:
            // pomo 0.082W / stick 0.212W = 0,387), no 0,34.
            val knobRadius = radius * 0.39f
            val cx = rect.centerX() + knobNx * radius * 0.55f
            val cy = rect.centerY() + knobNy * radius * 0.55f
            knobRect.set(cx - knobRadius, cy - knobRadius, cx + knobRadius, cy + knobRadius)
            drawGlassBody(canvas, knobRect, effectiveAlpha(), ornaments = false)
        }

        override fun release() {
            if (pointerId != -1 || lastAxes != (0.toShort() to 0.toShort()) || lastDpad.isNotEmpty()) {
                pointerId = -1
                knobNx = 0f
                knobNy = 0f
                drawnKnobNx = 0f
                drawnKnobNy = 0f
                sendAxes(0.toShort(), 0.toShort())
                sendDpad(emptySet())
                animatePress(0f)
                invalidate()
            }
        }

        private fun sendAxes(x: Short, y: Short) {
            if (control.stickMode == StickMode.DPAD) return
            if (x to y == lastAxes) return
            lastAxes = x to y
            listener?.onAxis(axisX, x)
            listener?.onAxis(axisY, y)
        }

        private fun sendDpad(dpad: Set<VitaButton>) {
            if (control.stickMode == StickMode.ANALOG) return
            if (dpad == lastDpad) return
            for (button in lastDpad - dpad) {
                button.toSdlButton()?.let { listener?.onButton(it, false) }
            }
            for (button in dpad - lastDpad) {
                button.toSdlButton()?.let { listener?.onButton(it, true) }
            }
            lastDpad = dpad
        }

        private fun updateFromPoint(x: Float, y: Float) {
            val radius = width / 2f
            if (radius <= 0f) return
            var nx = (x - width / 2f) / radius
            var ny = (y - height / 2f) / radius
            val mag = sqrt(nx * nx + ny * ny)
            if (mag > 1f) {
                nx /= mag
                ny /= mag
            }
            knobNx = nx
            knobNy = ny
            val axes = stickAxes(nx, ny)
            sendAxes(axes.first, axes.second)
            sendDpad(dpadForStick(nx, ny))
            animatePress(1f)
            // 0.2.65 (R-P1-13): el pomo se desplaza radius * 0.55
            // por unidad de nx/ny; si desde el último dibujado no
            // se movió ni 0.5 px, no hay nada nuevo que dibujar.
            val dx = nx - drawnKnobNx
            val dy = ny - drawnKnobNy
            val movedPx = sqrt(dx * dx + dy * dy) * radius * 0.55f
            if (movedPx < 0.5f) return
            drawnKnobNx = nx
            drawnKnobNy = ny
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = event.getPointerId(0)
                    startRipple(event.x, event.y)
                    updateFromPoint(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // P1-9: el dedo del stick se levanta mientras otro
                    // sigue en pantalla: sin esta rama el stick quedaba
                    // pegado (ejes y pomo desplazados) hasta que se
                    // levantaba el ÚLTIMO dedo.
                    if (event.getPointerId(event.actionIndex) == pointerId) {
                        release()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index < 0) return pointerId != -1
                    updateFromPoint(event.getX(index), event.getY(index))
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    release()
                    return true
                }
            }
            return false
        }
    }

    /**
     * Zona Igniculus: se dibuja (orbe + anillo tenue, el resto del
     * área transparente) y su táctil depende del modo del perfil
     * (P1-12), pero en los DOS modos consume el toque, lo reenvía
     * a la superficie SDL como toque sintético (forwardTouch) y
     * manda el brillo al mantener (glowSdlButtons: hombro L 9 +
     * eje L2 -4, ver TouchOverlayMapping.glowSdlButtons):
     * - Toque directo: el reenvío es 1:1 (destino = punto del
     *   dedo), sin traducir: la luciérnaga va donde toca el dedo,
     *   igual que antes, pero la zona SÍ consume el toque. Antes
     *   devolvía false y el toque bajaba nativo a SDL; sin
     *   consumirlo la vista nunca ve el ACTION_UP y el brillo
     *   quedaría atascado en pulsado. El juego recibe el mismo
     *   toque (misma posición, misma secuencia) reenviado.
     * - Joystick: el punto donde se apoya el dedo es el ancla; el
     *   destino es ancla + arrastre × sensibilidad. El orbe sigue
     *   al destino mientras se arrastra.
     * hasActivePointer sigue en false en los DOS modos, a
     * propósito: un segundo dedo sobre la zona mientras hay uno
     * lo reenvía el router de multi-toque a SDL (su toque es
     * táctil del juego, no de un control activo); si fuera true,
     * el router lo descartaría por caer sobre un control activo
     * (R6-P2-22: en modo Joystick el segundo dedo se perdía del
     * todo). Ese segundo dedo no mueve el brillo (lo sigue el
     * primer dedo); en modo Toque directo el brillo solo se
     * suelta cuando se levanta el ÚLTIMO dedo de la zona
     * (R6-P2-23, zoneFingerIds).
     * Desde 0.2.14 se dibuja solo como orbe + anillo tenue:
     * dibujar el cristal sobre todo el rect (220 dp) era una rueda
     * oscura gigante en el centro de la pantalla. En 0.2.57 la
     * zona es de 115 dp y el orbe se dibuja a 0.42 x ancho (2 x
     * 0.42 x 115 = 97 dp de diámetro, como en la captura de
     * referencia) con el anillo a 0.45 x ancho, fuera del halo
     * del icono (que llega a 0.357 x ancho).
     */
    private inner class ZoneView(context: Context, control: TouchControl) :
        OverlayControlView(context, control) {

        private var pointerId: Int = -1
        private var anchorOverlayX: Float = 0f
        private var anchorOverlayY: Float = 0f
        private var targetOverlayX: Float = 0f
        private var targetOverlayY: Float = 0f
        private var downTime: Long = 0L
        private var joystickActive: Boolean = false
        private var directTouchActive: Boolean = false
        /**
         * 0.2.65 (R-P1-16): último destino reenviado al juego. Un
         * MOVE que no mueve el destino ≥ 1 px no se reenvía: el
         * toque sintético por la Activity completa en cada MOVE
         * era el camino más caro del overlay. DOWN/UP siempre se
         * reenvían; solo el MOVE se coalesca.
         */
        private var lastForwardedTargetX: Float = Float.NaN
        private var lastForwardedTargetY: Float = Float.NaN
        /**
         * 0.2.65 (R-P1-15): último centro del orbe ya dibujado
         * (-1 = aún no se dibujó). invalidateOrbIfMoved compara
         * contra él para invalidar solo el rect del orbe.
         */
        private var lastDrawnOrbCx: Float = -1f
        private var lastDrawnOrbCy: Float = -1f

        /**
         * R6-P2-23: dedos apoyados AHORA sobre la zona en modo Toque
         * directo (el seguido + los adicionales). El brillo solo se
         * suelta cuando el conjunto queda vacío: antes se apagaba al
         * levantar el primer dedo aunque un segundo siguiera sobre
         * la zona. En modo Joystick no se usa (el brillo lo sigue
         * solo el primer dedo).
         */
        private val zoneFingerIds = mutableSetOf<Int>()

        /**
         * R6-P2-23: el brillo es UNA sesión desde el primer dedo
         * hasta el último: un dedo adicional que se convierte en
         * seguido (el primero se levantó antes) NO vuelve a
         * encenderlo. Sin esta guarda, su true sumaría un
         * mantenedor en el refcount de la Activity sin su false
         * correspondiente y el brillo quedaría atascado en el
         * núcleo tras levantar el último dedo.
         */
        private var glowOn: Boolean = false

        /**
         * R6-P2-22: false en los DOS modos. En Toque directo ya lo
         * era: un segundo dedo sobre la zona lo reenvía el router
         * de multi-toque a SDL como táctil del juego. En Joystick
         * era joystickActive y el router lo DESCARTABA (segundo dedo
         * perdido del todo). La zona es táctil del juego en ambos
         * modos, no un botón: un segundo dedo sobre ella es un toque
         * de Igniculus más, no un dedo fantasma sobre un control.
         * El brillo lo sigue solo el primer dedo en ambos modos.
         */
        override val hasActivePointer: Boolean get() = false

        override fun onDraw(canvas: Canvas) {
            // Orbe de Igniculus (0.2.56, diseno 51025): bola de
            // cristal con corona floral arriba y adorno abajo, sobre
            // un anillo tenue (0.45 x ancho). Antes (0.2.14-0.2.55)
            // solo se dibujaban el icono y el anillo, sin cristal:
            // el orbe se perdia sobre el juego. El cristal va SOLO
            // sobre el orbe (0.42 x ancho), nunca sobre todo el rect
            // de la zona: dibujarlo entero era una rueda oscura
            // gigante en el centro de la pantalla. El resto del area
            // queda transparente.
            val cx = width / 2f
            val cy = height / 2f
            val alpha = effectiveAlpha()
            strokePaint.color = GLASS_PALE_BLUE
            strokePaint.alpha = (alpha * 0.5f * 255).toInt()
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(cx, cy, width * 0.45f, strokePaint)
            // En modo Joystick el orbe sigue al destino mientras se
            // arrastra (recortado a la zona; fuera de ella el destino
            // real está en el juego, no aquí). En Toque directo el
            // orbe queda centrado: el destino es el dedo mismo.
            val iconCx = if (joystickActive) {
                (targetOverlayX - left).coerceIn(0f, width.toFloat())
            } else {
                cx
            }
            val iconCy = if (joystickActive) {
                (targetOverlayY - top).coerceIn(0f, height.toFloat())
            } else {
                cy
            }
            val orbR = width * 0.42f
            orbRectF.set(iconCx - orbR, iconCy - orbR, iconCx + orbR, iconCy + orbR)
            val orbRect = orbRectF
            // 0.2.65 (R-P1-15): el orbe dibujado queda registrado
            // para que invalidateOrbIfMoved sepa dónde estaba.
            lastDrawnOrbCx = iconCx
            lastDrawnOrbCy = iconCy
            // 0.2.63 (A-4): la zona tiene opacidad 0,25 para que el
            // juego se vea a través, pero el orbe heredaba ese
            // 0,25 y salía gris apagado; el mockup lo muestra como
            // un cristal brillante. El orbe se dibuja con alfa
            // propio 0,80 (respetando la opacidad general del
            // overlay si el usuario la baja); solo el anillo tenue
            // exterior de la zona y la gota conservan el 0,25
            // original.
            val orbAlpha = maxOf(alpha, 0.8f * generalOpacity)
            // 0.2.64 (A-3): el cristal va SIN los rombos genéricos
            // (ornaments = false): la corona floral se dibuja aparte
            // (drawFloralCrown) para no duplicar el rombo de arriba.
            drawGlassBody(canvas, orbRect, orbAlpha, ornaments = false)
            drawFloralCrown(canvas, iconCx, iconCy, orbR, orbAlpha)
            // Gota sobre el orbe (recortada a su anillo): nace donde
            // toca el dedo, en los dos modos. El rect es el del orbe
            // (0.42 x ancho en 0.2.54).
            rippleRect.set(orbRect)
            drawRipple(canvas, rippleRect, alpha)
            drawIcon(canvas, IconKind.IGNICULUS, iconCx, iconCy, width * 0.42f, orbAlpha)
        }

        /**
         * 0.2.65 (R-P1-15): invalida solo el rect del orbe (viejo +
         * nuevo, con margen para la corona floral, que llega a
         * 1.14 x orbR por arriba) y solo si el orbe se movió
         * ≥ 0.5 px desde el último dibujado. En Toque directo el
         * orbe no se mueve nunca: el llamador ni siquiera llama.
         */
        private fun invalidateOrbIfMoved() {
            if (lastDrawnOrbCx < 0f || lastDrawnOrbCy < 0f) {
                invalidate()
                return
            }
            val orbR = width * 0.42f
            if (orbR <= 0f) return
            val iconCx = if (joystickActive) {
                (targetOverlayX - left).coerceIn(0f, width.toFloat())
            } else {
                width / 2f
            }
            val iconCy = if (joystickActive) {
                (targetOverlayY - top).coerceIn(0f, height.toFloat())
            } else {
                height / 2f
            }
            val dx = iconCx - lastDrawnOrbCx
            val dy = iconCy - lastDrawnOrbCy
            if (sqrt(dx * dx + dy * dy) < 0.5f) return
            val margin = orbR * 1.2f
            val l = min(lastDrawnOrbCx, iconCx) - margin
            val t = min(lastDrawnOrbCy, iconCy) - margin
            val r = max(lastDrawnOrbCx, iconCx) + margin
            val b = max(lastDrawnOrbCy, iconCy) + margin
            invalidate(l.toInt(), t.toInt(), r.toInt() + 1, b.toInt() + 1)
        }

        override fun release() {
            // Si el overlay se oculta o se reconstruye a mitad de un
            // arrastre, el toque sintético debe terminar y el brillo
            // soltarse: si no, el juego seguiría creyendo que el
            // dedo está apoyado y el brillo quedaría encendido.
            // R6-P2-23: el brillo puede estar encendido con el dedo
            // seguido ya levantado (otro dedo sigue sobre la zona):
            // vaciar el conjunto antes de endTouch para que el
            // brillo se suelte, y si no hay toque activo soltarlo
            // igual (el refcount de la Activity ignora un false sin
            // mantenedor, así que no se envía nada de más).
            zoneFingerIds.clear()
            if (joystickActive || directTouchActive) endTouch() else sendGlow(false)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Un toque sintético reenviado nunca se vuelve a consumir:
            // su destino es la superficie SDL (ver dispatchTouchEvent).
            if (forwardingSyntheticTouch) return false
            // Gota al tocar el orbe, en los dos modos. En Toque
            // directo el toque se consume y se reenvía como
            // sintético (ver KDoc de la clase); en Joystick igual,
            // traducido. La gota no cambia ese flujo.
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                val rippleIndex = if (event.actionMasked == MotionEvent.ACTION_DOWN) 0 else event.actionIndex
                startRipple(event.getX(rippleIndex), event.getY(rippleIndex))
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = if (event.actionMasked == MotionEvent.ACTION_DOWN) 0 else event.actionIndex
                    val fingerId = event.getPointerId(index)
                    // Segundo dedo sobre la zona mientras ya hay uno
                    // seguido: no se sigue (ese dedo lo reenvía el
                    // router de multi-toque a SDL, ver KDoc). En modo
                    // Toque directo sí se cuenta para el brillo
                    // (R6-P2-23): el brillo no se suelta hasta que se
                    // levante el último dedo de la zona.
                    if (pointerId != -1) {
                        if (control.igniculusMode != IgniculusMode.JOYSTICK) {
                            zoneFingerIds.add(fingerId)
                        }
                        return false
                    }
                    pointerId = fingerId
                    anchorOverlayX = left + event.getX(index)
                    anchorOverlayY = top + event.getY(index)
                    targetOverlayX = anchorOverlayX
                    targetOverlayY = anchorOverlayY
                    // R6-P1-16: el downTime del DEDO DE LA ZONA, no
                    // el del gesto: si la zona se tocó como segundo
                    // dedo, event.downTime es el del primer dedo.
                    // El eventTime de su propio DOWN es su downTime
                    // (en ACTION_DOWN coincide con event.downTime).
                    downTime = event.eventTime
                    if (control.igniculusMode != IgniculusMode.JOYSTICK) {
                        zoneFingerIds.add(pointerId)
                    }
                    if (control.igniculusMode == IgniculusMode.JOYSTICK) {
                        joystickActive = true
                    } else {
                        directTouchActive = true
                    }
                    forwardTouch(MotionEvent.ACTION_DOWN, event.eventTime)
                    sendGlow(true)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index < 0) return joystickActive || directTouchActive
                    if (control.igniculusMode == IgniculusMode.JOYSTICK) {
                        val overlayW = this@GamepadOverlayView.width.toFloat()
                        val overlayH = this@GamepadOverlayView.height.toFloat()
                        val dx = event.getX(index) + left - anchorOverlayX
                        val dy = event.getY(index) + top - anchorOverlayY
                        val s = control.sensitivity.coerceIn(0.5f, 2.0f)
                        targetOverlayX = (anchorOverlayX + dx * s).coerceIn(0f, overlayW)
                        targetOverlayY = (anchorOverlayY + dy * s).coerceIn(0f, overlayH)
                    } else {
                        // Toque directo: el destino es el punto del
                        // dedo, sin traducir (1:1).
                        targetOverlayX = left + event.getX(index)
                        targetOverlayY = top + event.getY(index)
                    }
                    forwardTouch(MotionEvent.ACTION_MOVE, event.eventTime)
                    // 0.2.65 (R-P1-15): en Toque directo el orbe no
                    // se mueve: no se redibuja nada por MOVE. En
                    // Joystick solo se invalida el rect del orbe si
                    // se movió ≥ 0.5 px (invalidateOrbIfMoved).
                    if (control.igniculusMode == IgniculusMode.JOYSTICK) {
                        invalidateOrbIfMoved()
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val upId = event.getPointerId(event.actionIndex)
                    if (upId == pointerId) {
                        // El dedo seguido se levanta: su toque
                        // sintético termina, pero el brillo solo se
                        // suelta si no queda ningún dedo sobre la zona
                        // (R6-P2-23, modo Toque directo).
                        zoneFingerIds.remove(upId)
                        endTouch()
                    } else if (control.igniculusMode != IgniculusMode.JOYSTICK &&
                        zoneFingerIds.remove(upId) && zoneFingerIds.isEmpty()
                    ) {
                        // Un dedo adicional se levanta y era el último
                        // que quedaba (el seguido ya se había levantado
                        // antes): apagar el brillo.
                        sendGlow(false)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        // Gesto cancelado: no queda ningún dedo.
                        zoneFingerIds.clear()
                    } else {
                        zoneFingerIds.remove(event.getPointerId(event.actionIndex))
                    }
                    if (joystickActive || directTouchActive) {
                        endTouch()
                    } else if (zoneFingerIds.isEmpty()) {
                        // El último dedo era uno adicional (el seguido
                        // ya se había levantado): apagar el brillo.
                        sendGlow(false)
                    }
                    return true
                }
            }
            return false
        }

        /**
         * Brillo de Igniculus al mantener (0.2.54): manda los dos
         * botones de [TouchOverlayMapping.glowSdlButtons] (hombro L
         * 9 + eje L2 -4) en el mismo sentido: en lecturas v1 el 9
         * basta y en lecturas v2 solo el eje llega al bit L. El
         * comentario anterior decía que enviar ambos era inocuo en
         * ambos modos y era FALSO: en v2 el 9 pone además el bit L1
         * (0x400) como colateral (R6-P1-13, ver el KDoc de
         * glowSdlButtons: el modo no se puede detectar desde el
         * launcher). El doble envío se mantiene a propósito para no
         * romper ningún modo; la prueba de teléfono Q4 decide si L1
         * hace algo en Child of Light. Se llama al apoyar el dedo y
         * al soltarlo, en los dos modos de Igniculus.
         */
        private fun sendGlow(pressed: Boolean) {
            // R6-P2-23: guarda de sesión (ver glowOn): si el brillo ya
            // está en el estado pedido, no se reenvía nada. Sin ella,
            // un dedo que se convierte en seguido re-encendería un
            // brillo ya encendido y su false final no equilibraría el
            // refcount de la Activity.
            if (pressed == glowOn) return
            glowOn = pressed
            // R6-P1-13: constancia en el log cada vez que se envía el
            // brillo (el doble envío 9 + -4 y su L1 colateral en v2).
            Log.i("GamepadOverlayView", "brillo Igniculus pressed=$pressed (SDL 9 + eje -4; en lecturas v2 del juego el 9 es L1 colateral)")
            for (sdlButton in glowSdlButtons()) {
                listener?.onButton(sdlButton, pressed)
            }
        }

        private fun endTouch() {
            forwardTouch(MotionEvent.ACTION_UP)
            // R6-P2-23: en modo Toque directo el brillo solo se suelta
            // cuando el último dedo se levanta de la zona. En modo
            // Joystick el conjunto siempre está vacío (no se cuentan
            // los adicionales), así que se suelta como antes.
            if (zoneFingerIds.isEmpty()) sendGlow(false)
            pointerId = -1
            joystickActive = false
            directTouchActive = false
            invalidate()
        }

        /**
         * Reenvía el toque (ya traducido a la posición destino, en
         * coordenadas del overlay = de la superficie SDL, ambas a
         * pantalla completa) a la Activity: baja por la jerarquía
         * hasta la superficie SDL y el juego lo recibe como táctil
         * frontal. Mientras se reenvía, dispatchTouchEvent devuelve
         * false para que ningún botón del overlay consuma el toque
         * sintético (si el destino cae sobre un botón, lo pulsaría).
         */
        private fun forwardTouch(action: Int, eventTime: Long = SystemClock.uptimeMillis()): Boolean {
            val activity = context as? Activity ?: return false
            // 0.2.65 (R-P1-16): coalescar MOVEs: si el destino no
            // se movió ≥ 1 px desde el último reenviado, no se manda
            // otro sintético por la Activity completa. DOWN y UP
            // siempre se reenvían: el juego debe ver el toque
            // empezar y terminar exactamente una vez.
            if (action == MotionEvent.ACTION_MOVE &&
                abs(targetOverlayX - lastForwardedTargetX) < 1f &&
                abs(targetOverlayY - lastForwardedTargetY) < 1f
            ) {
                return false
            }
            lastForwardedTargetX = targetOverlayX
            lastForwardedTargetY = targetOverlayY
            // R6-P1-16: el obtain de 6 args ponía id siempre 0, sin
            // source y sin pressure, con el downTime del gesto. Ahora
            // se usa MotionEventHelper (14 args: pointerId real del
            // dedo de la zona, SOURCE_TOUCHSCREEN, pressure/size 1),
            // igual que el reenvío del router
            // (obtainSinglePointerEvent). downTime es el del dedo de
            // la zona (no el del gesto, ver onTouchEvent).
            val synthetic = obtainSinglePointerEvent(
                downTime, eventTime, action, pointerId,
                targetOverlayX, targetOverlayY,
            )
            val delivered: Boolean
            try {
                forwardingSyntheticTouch = true
                // 0.2.63 (D): toque de la zona reenviado como táctil
                // del juego: se registra en TouchStats (el reporte
                // de bug lo muestra). El reenvío pasa por EmuSurface
                // con forwardingSyntheticTouch activo, así que no se
                // cuenta dos veces.
                delivered = activity.dispatchTouchEvent(synthetic)
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (TouchStats.recordForwardedDown(delivered, targetOverlayX, targetOverlayY)) {
                            Log.i("GamepadOverlayView", "toque zona Igniculus DOWN en ($targetOverlayX, $targetOverlayY) entregado a SDL")
                        } else if (!delivered) {
                            Log.w("GamepadOverlayView", "toque zona Igniculus DOWN en ($targetOverlayX, $targetOverlayY) PERDIDO")
                        }
                    }
                    MotionEvent.ACTION_UP -> TouchStats.recordForwardedUp(delivered, targetOverlayX, targetOverlayY)
                    MotionEvent.ACTION_MOVE -> TouchStats.recordForwardedMove(targetOverlayX, targetOverlayY)
                }
            } finally {
                forwardingSyntheticTouch = false
                synthetic.recycle()
            }
            return delivered
        }
    }
}

/**
 * 0.2.64 (A-2): alas de hielo de la pausa como vista APARTE, más
 * ancha que el botón (ver TouchOverlayMapping.pauseWingsLayout:
 * 4,4 x de ancho, 1,5 x de alto, centrada en el botón de pausa).
 * Dentro del botón se recortaban al cuadrado y no podían
 * extenderse como en el mockup 51025: dos hojas de hielo por lado
 * que barren hacia las esquinas, nudito floral donde nacen del
 * círculo y estrellita en cada punta (lavanda #A99ACD, puntas
 * plata #EAF4FB, como los demás adornos). Es una vista de adorno:
 * no consume ningún toque (onTouchEvent false, ni clicable ni
 * enfocable): los toques la atraviesan hacia el botón de pausa o
 * el de círculo. La monta rebuild() de GamepadOverlayView encima
 * de los controles y le sigue la opacidad del botón de pausa.
 *
 * Clase a nivel de archivo (no inner): Kotlin prohíbe tipos dentro
 * de una inner class (runs #86 y #296) y esta vista no es un control
 * del overlay (no es OverlayControlView).
 */
private class PauseWingsView(context: Context) : View(context) {

    private val ornamentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A99ACD")
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF4FB")
        style = Paint.Style.FILL
    }
    private val upperLeafPath = Path()
    private val lowerLeafPath = Path()
    private val starPath = Path()
    /** 0.2.65 (O-P2-1): lados de las alas, sin floatArrayOf nuevo por dibujado. */
    private val wingSides = floatArrayOf(-1f, 1f)

    /** Opacidad del adorno: la del botón de pausa (su opacidad x la general). */
    var ornamentAlpha: Float = 1f
        set(value) {
            val coerced = value.coerceIn(0f, 1f)
            // 0.2.65 (O-P2-5): si no cambió, no invalidar.
            if (field == coerced) return
            field = coerced
            invalidate()
        }

    /**
     * 0.2.65 (M-P2-3): rect del botón Volver (○) en coordenadas
     * LOCALES de esta vista, o null si el perfil no lo trae. Las
     * alas son más anchas que el botón de pausa y sus hojas
     * cruzaban los bordes del círculo vecino: esa zona se recorta
     * del dibujo (clipRect con DIFFERENCE) para que el ala pase
     * POR DETRÁS del botón en vez de pintar sobre su cristal. Lo
     * fija GamepadOverlayView (rebuild y onLayout) desde los
     * layouts de pausa y círculo.
     */
    var excludedRect: RectF? = null
        set(value) {
            val old = field
            if (old == null && value == null) return
            if (old != null && value != null &&
                old.left == value.left && old.top == value.top &&
                old.right == value.right && old.bottom == value.bottom
            ) {
                return
            }
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
    }

    /** Nunca consume: un toque sobre las alas es del botón de debajo. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Radio del círculo de cristal de la pausa: la vista mide
        // PAUSE_WINGS_HEIGHT_FACTOR x el botón de alto.
        val r = height / TouchOverlayMapping.PAUSE_WINGS_HEIGHT_FACTOR / 2f
        if (r <= 0f) return
        ornamentPaint.alpha = (ornamentAlpha * 0.85f * 255).toInt()
        iconPaint.alpha = (ornamentAlpha * 0.95f * 255).toInt()
        // 0.2.65 (M-P2-3): recortar el ala al rect del botón Volver
        // (excludedRect): el ala no pinta sobre su cristal. El
        // clipRect con DIFFERENCE sí está soportado en canvas con
        // aceleración por hardware (el que no lo está es clipPath
        // con ops distintas de INTERSECT).
        val exclude = excludedRect
        if (exclude != null) {
            canvas.save()
            canvas.clipRect(exclude, Region.Op.DIFFERENCE)
        }
        for (s in wingSides) {
            // Hoja superior: nace en el borde del círculo y barre
            // hacia arriba y hacia fuera, con la punta en ~4,55r
            // (el ancho total de la vista es 4,7 x el botón).
            // 0.2.68 (neón): hojas más extendidas y más planas que
            // en 0.2.64 (punta 4,15r -> 4,55r, altura 1,30r ->
            // 1,10r): en la imagen las alas son más anchas y menos
            // altas (4,63 x 1,09 del botón).
            upperLeafPath.reset()
            upperLeafPath.apply {
                moveTo(cx + s * 0.85f * r, cy - 0.55f * r)
                quadTo(cx + s * 2.65f * r, cy - 1.10f * r, cx + s * 4.55f * r, cy - 0.64f * r)
                quadTo(cx + s * 2.85f * r, cy - 0.43f * r, cx + s * 0.85f * r, cy - 0.55f * r)
                close()
            }
            canvas.drawPath(upperLeafPath, ornamentPaint)
            lowerLeafPath.reset()
            lowerLeafPath.apply {
                moveTo(cx + s * 0.85f * r, cy + 0.55f * r)
                quadTo(cx + s * 2.65f * r, cy + 1.10f * r, cx + s * 4.55f * r, cy + 0.64f * r)
                quadTo(cx + s * 2.85f * r, cy + 0.43f * r, cx + s * 0.85f * r, cy + 0.55f * r)
                close()
            }
            canvas.drawPath(lowerLeafPath, ornamentPaint)
            // Nudito floral donde el ala nace del círculo.
            drawWingDiamond(canvas, cx + s * 0.95f * r, cy, 0.14f * r)
            // Estrellitas en cada punta.
            drawWingSparkle(canvas, cx + s * 4.55f * r, cy - 0.64f * r, 0.10f * r)
            drawWingSparkle(canvas, cx + s * 4.55f * r, cy + 0.64f * r, 0.08f * r)
        }
        if (exclude != null) canvas.restore()
    }

    /** Rombo pequeño con punto plata central (como drawDiamondOrnament). */
    private fun drawWingDiamond(canvas: Canvas, x: Float, y: Float, s: Float) {
        starPath.reset()
        starPath.apply {
            moveTo(x, y - s)
            lineTo(x + 0.55f * s, y)
            lineTo(x, y + s)
            lineTo(x - 0.55f * s, y)
            close()
        }
        canvas.drawPath(starPath, ornamentPaint)
        canvas.drawCircle(x, y, 0.22f * s, iconPaint)
    }

    /** Estrella de 4 puntas (como drawSparkle). */
    private fun drawWingSparkle(canvas: Canvas, x: Float, y: Float, s: Float) {
        starPath.reset()
        starPath.apply {
            moveTo(x, y - s)
            lineTo(x + 0.25f * s, y - 0.25f * s)
            lineTo(x + s, y)
            lineTo(x + 0.25f * s, y + 0.25f * s)
            lineTo(x, y + s)
            lineTo(x - 0.25f * s, y + 0.25f * s)
            lineTo(x - s, y)
            lineTo(x - 0.25f * s, y - 0.25f * s)
            close()
        }
        canvas.drawPath(starPath, iconPaint)
    }
}

/**
 * R4-P1-16: qué hacer con cada puntero de un gesto multi-toque.
 *
 * Lógica PURA (sin MotionEvent ni Views): GamepadOverlayView le pasa
 * los hechos (qué acción, qué pointerId, si algún hijo consumió el
 * puntero, si el punto cae dentro de un control ya activo) y el
 * router decide si ese puntero se reenvía a la superficie SDL como
 * toque propio o se descarta. La vista ejecuta la decisión (crea el
 * evento sintético de un solo dedo y lo despacha a la superficie).
 * Al ser pura, se prueba en JVM sin Robolectric (MultiTouchRouterTest).
 *
 * Reglas:
 * - Un puntero consumido por un hijo nunca se reenvía (es un botón
 *   o un stick, no táctil del juego).
 * - Un puntero dentro de un control ya activo tampoco: el framework
 *   ya se lo dio a ese control por ser destino del gesto y
 *   reenviarlo crearía un dedo fantasma en el juego sobre el control.
 * - Un puntero reenviado se sigue (downTime + última posición) hasta
 *   su UP: sus MOVE se reenvían y su UP lo termina en SDL. Si el
 *   overlay se reconstruye u oculta, endAll() devuelve los que
 *   quedan para terminarlos con un UP sintético (sin dedo atascado
 *   en el juego).
 */
internal class MultiTouchRouter {

    enum class PointerAction {
        FORWARD_DOWN, FORWARD_MOVE, FORWARD_UP, DROP
    }

    /** Un dedo reenviado a SDL: cuándo empezó y dónde está ahora. */
    data class ForwardedTouch(
        val pointerId: Int,
        val downTime: Long,
        val x: Float,
        val y: Float,
    )

    private data class TrackedPointer(
        val downTime: Long,
        var x: Float,
        var y: Float,
        /** Última posición reenviada a SDL (0.2.65 R-P1-16: coalescencia de MOVEs). */
        var forwardedX: Float = x,
        var forwardedY: Float = y,
    )

    private val forwarded = LinkedHashMap<Int, TrackedPointer>()

    /** Punteros que ahora mismo son toques del juego (no de botones). */
    val forwardedPointerIds: Set<Int> get() = forwarded.keys.toSet()

    /** P-C5: true si hay algún puntero reenviado, sin copiar el conjunto por cada ACTION_MOVE. */
    val hasForwardedPointers: Boolean get() = forwarded.isNotEmpty()

    fun onPointerDown(
        pointerId: Int,
        downTime: Long,
        x: Float,
        y: Float,
        handledByChild: Boolean,
        insideActiveControl: Boolean,
    ): PointerAction {
        if (handledByChild || insideActiveControl) return PointerAction.DROP
        forwarded[pointerId] = TrackedPointer(downTime, x, y)
        return PointerAction.FORWARD_DOWN
    }

    fun onMove(pointerId: Int, x: Float, y: Float): PointerAction {
        val pointer = forwarded[pointerId] ?: return PointerAction.DROP
        pointer.x = x
        pointer.y = y
        // 0.2.65 (R-P1-16): coalescar MOVEs: si el dedo no se movió
        // ≥ 1 px desde el último MOVE reenviado, no se crea otro
        // MotionEvent sintético. La posición real se guarda siempre,
        // así que una deriva lenta en pasos sub-píxel sí se reenvía
        // en cuanto el total llega a 1 px (nada se pierde).
        if (abs(x - pointer.forwardedX) < 1f && abs(y - pointer.forwardedY) < 1f) {
            return PointerAction.DROP
        }
        pointer.forwardedX = x
        pointer.forwardedY = y
        return PointerAction.FORWARD_MOVE
    }

    fun downTimeOf(pointerId: Int): Long? {
        return forwarded[pointerId]?.downTime
    }

    fun onPointerUp(pointerId: Int): PointerAction {
        return if (forwarded.remove(pointerId) != null) {
            PointerAction.FORWARD_UP
        } else {
            PointerAction.DROP
        }
    }

    /** Termina el seguimiento de todos los reenviados (gesto cancelado, overlay oculto o reconstruido). */
    fun endAll(): List<ForwardedTouch> {
        val result = forwarded.map { (pointerId, pointer) ->
            ForwardedTouch(pointerId, pointer.downTime, pointer.x, pointer.y)
        }
        forwarded.clear()
        return result
    }
}

/**
 * 0.2.64 (D-P1-1): rectángulo de un hijo del overlay para decidir el
 * encaminado manual del segundo dedo (puro, testeable en JVM, como
 * MultiTouchRouter). La lista va en orden de hijos (el último es el
 * de más arriba): la búsqueda va de atrás hacia delante para que un
 * botón que se solape con la zona gane el toque.
 */
internal data class RoutedChildHit(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Zona Igniculus: su toque es táctil del juego, no de un control. */
    val isZone: Boolean,
    /** El control ya tiene un dedo: el framework ya se lo dio. */
    val hasActivePointer: Boolean,
)

/**
 * 0.2.64 (D-P1-1): índice del hijo al que encaminar el puntero
 * (x, y), o -1 si ninguno lo quiere (entonces sigue el camino viejo:
 * el router lo reenvía a SDL como toque libre). Se saltan la zona
 * Igniculus y los controles con un dedo activo.
 */
internal fun findRoutableChildIndex(children: List<RoutedChildHit>, x: Float, y: Float): Int {
    for (i in children.indices.reversed()) {
        val child = children[i]
        if (child.isZone || child.hasActivePointer) continue
        if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) return i
    }
    return -1
}
