package com.danielsnagustin.childoflight.launcher.core

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.danielsnagustin.childoflight.launcher.core.FaceGlyph
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.dpadForStick
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.faceGlyphFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.glowSdlButtons
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.iconOverrideFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.overlayChildOrder
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxes
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxesFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.data.ControlShape
import com.danielsnagustin.childoflight.launcher.data.IgniculusMode
import com.danielsnagustin.childoflight.launcher.data.StickMode
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import org.libsdl.app.SDLSurface
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
 * DEL JUEGO 960x544 (R4-P1-1), tamaño en dp, opacidad por botón
 * multiplicada por la opacidad general. El estilo es el cristal
 * azul hielo de la imagen de referencia 51025 (fondo negro,
 * cristal azul hielo/lavanda con brillo): degradado radial, doble
 * borde luminoso, adornos de diamante y cristal en los 4 puntos
 * del stick, alas de hielo con estrellitas en la pausa, orbe de
 * Igniculus con corona floral y destellos, espada con destellos y
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
            field = value.coerceIn(0.1f, 1f)
            refreshChildren()
        }

    /** Halo azul hielo (neón) alrededor de los botones. */
    var neonGlow: Boolean = false
        set(value) {
            field = value
            refreshChildren()
        }

    private var controls: List<TouchControl> = emptyList()
    private var builtForSize: Pair<Int, Int>? = null

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
    }

    /** Controles del perfil activo (solo se muestran los visibles). Suelta todo lo pulsado antes de reconstruir. */
    fun setControls(newControls: List<TouchControl>) {
        releaseAll()
        controls = newControls
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
        for (i in 0 until childCount) {
            (getChildAt(i) as? OverlayControlView)?.release()
        }
    }

    private fun refreshChildren() {
        for (i in 0 until childCount) {
            (getChildAt(i) as? OverlayControlView)?.refreshStyle()
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
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (forwardingSyntheticTouch) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val handled = super.dispatchTouchEvent(event)
                if (handled) return true
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
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
                return handled || forwarded
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
                return handled || forwarded
            }
            MotionEvent.ACTION_UP -> {
                val handled = super.dispatchTouchEvent(event)
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val downTime = multiTouchRouter.downTimeOf(pointerId)
                val decision = multiTouchRouter.onPointerUp(pointerId)
                var forwarded = false
                if (decision == MultiTouchRouter.PointerAction.FORWARD_UP && downTime != null) {
                    forwarded = forwardPointerUpToSdl(event, index, pointerId, downTime)
                }
                return handled || forwarded
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = super.dispatchTouchEvent(event)
                endForwardedTouchesToSdl()
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
        val surface = findSdlSurface() ?: return false
        val synthetic = obtainSinglePointerEvent(
            event.downTime, event.eventTime, MotionEvent.ACTION_DOWN,
            pointerId, event.getX(index), event.getY(index),
        )
        return dispatchSyntheticToSdl(surface, synthetic)
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
        return dispatchSyntheticToSdl(surface, synthetic)
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
            val rect = controlRect(control, width.toFloat(), height.toFloat(), density)
            val child: OverlayControlView = when (control.kind) {
                "stick" -> StickView(context, control)
                "igniculus" -> ZoneView(context, control)
                else -> ButtonView(context, control)
            }
            val sizePx = (rect.width()).toInt().coerceAtLeast(1)
            val params = LayoutParams(sizePx, sizePx)
            params.leftMargin = rect.left.toInt()
            params.topMargin = rect.top.toInt()
            addView(child, params)
        }
        builtForSize = width to height
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
     * P-C2: clave del gradiente cacheado de drawGlassBody (centro + radio
     * + tinte + alfa). Vive aquí (clase externa, no inner): Kotlin
     * PROHÍBE las class dentro de una inner class ("'Class' is
     * prohibited here", run #296, 2026-11-11) igual que las enum class
     * (run #86). Por eso no puede vivir en OverlayControlView.
     */
    private data class GlassGradientKey(val cx: Float, val cy: Float, val radius: Float, val tint: Int, val alpha: Float)

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
        private var pressAnimator: ValueAnimator? = null

        protected fun animatePress(to: Float) {
            pressAnimator?.cancel()
            val from = pressAmount
            if (from == to) return
            pressAnimator = ValueAnimator.ofFloat(from, to).apply {
                duration = if (to > from) 120L else 180L
                addUpdateListener { pressAmount = it.animatedValue as Float; invalidate() }
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
        private var rippleAnimator: ValueAnimator? = null
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
        /** P-C2: gradiente radial cacheado de drawGlassBody; null hasta el primer dibujado. */
        private var cachedGradient: RadialGradient? = null
        private var cachedGradientKey: GlassGradientKey? = null
        /** P-C3: Paths reutilizables de adornos e iconos (reset() antes de reconstruir, como rippleClipPath). */
        private val diamondPath = Path()
        private val leftLeafPath = Path()
        private val rightLeafPath = Path()
        private val arrowPath = Path()
        private val trianglePath = Path()
        private val upperLeafPath = Path()
        private val lowerLeafPath = Path()
        private val bladePath = Path()
        private val starPath = Path()
        private val bodyPath = Path()
        private val wispPath = Path()

        protected fun startRipple(x: Float, y: Float) {
            rippleState.start(x, y, SystemClock.uptimeMillis())
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = RippleState.DURATION_MS
                addUpdateListener { invalidate() }
                start()
            }
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
            rippleAnimator?.cancel()
            rippleAnimator = null
        }

        init {
            isClickable = false
            isFocusable = false
        }

        /** Opacidad efectiva del control: la suya por la general. */
        protected fun effectiveAlpha(): Float =
            (control.opacity * generalOpacity).coerceIn(0f, 1f)

        fun refreshStyle() {
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
            // P-C2: el gradiente se cachea por (centro, radio, tinte, alfa):
            // un botón quieto no reconstruye RadialGradient ni sus arrays
            // en cada frame. La clave incluye cx/cy: al girar el teléfono
            // (rebuild) o al moverse el pomo del stick / el orbe de
            // Igniculus, el cristal se recentra en vez de quedar descentrado.
            val gradientKey = GlassGradientKey(cx, cy, radius, tintColor, alpha)
            var gradient = cachedGradient
            if (cachedGradientKey != gradientKey || gradient == null) {
                gradient = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.argb((alpha * 0.34f * 255).toInt(), 226, 238, 248),
                        Color.argb((alpha * 0.30f * 255).toInt(), Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor)),
                        Color.argb((alpha * 0.62f * 255).toInt(), 24, 38, 68),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                cachedGradient = gradient
                cachedGradientKey = gradientKey
            }
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
            if (control.shape == ControlShape.SQUARE) {
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
            val s = (radius * 0.09f).coerceAtLeast(3f * density)
            val dist = radius
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
            canvas.drawRoundRect(RectF(cx - a, cy - a, cx + a, cy + a), 0.08f * r, 0.08f * r, iconStrokePaint)
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
            canvas.drawRoundRect(RectF(cx - 0.24f * r - w / 2, cy - h / 2, cx - 0.24f * r + w / 2, cy + h / 2), radius, radius, iconPaint)
            canvas.drawRoundRect(RectF(cx + 0.24f * r - w / 2, cy - h / 2, cx + 0.24f * r + w / 2, cy + h / 2), radius, radius, iconPaint)
            drawPauseWings(canvas, cx, cy, r, alpha)
        }

        /**
         * Alas de hielo de la pausa (diseno 51025): a cada lado del
         * circulo, dos hojitas de hielo que se abren hacia las
         * esquinas con una estrellita en cada punta. La vista del
         * boton recorta todo lo que sale de su cuadrado, asi que las
         * alas no pueden extenderse como en la imagen: viven pegadas
         * al circulo, dentro del hueco de las esquinas.
         */
        private fun drawPauseWings(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
            ornamentPaint.alpha = (alpha * 0.85f * 255).toInt()
            iconPaint.alpha = (alpha * 0.95f * 255).toInt()
            for (s in floatArrayOf(-1f, 1f)) {
                upperLeafPath.reset()
                upperLeafPath.apply {
                    moveTo(cx + s * 0.80f * r, cy - 0.70f * r)
                    quadTo(cx + s * 1.30f * r, cy - 1.15f * r, cx + s * 1.52f * r, cy - 0.85f * r)
                    quadTo(cx + s * 1.15f * r, cy - 0.62f * r, cx + s * 0.80f * r, cy - 0.70f * r)
                    close()
                }
                canvas.drawPath(upperLeafPath, ornamentPaint)
                lowerLeafPath.reset()
                lowerLeafPath.apply {
                    moveTo(cx + s * 0.80f * r, cy + 0.70f * r)
                    quadTo(cx + s * 1.30f * r, cy + 1.15f * r, cx + s * 1.52f * r, cy + 0.85f * r)
                    quadTo(cx + s * 1.15f * r, cy + 0.62f * r, cx + s * 0.80f * r, cy + 0.70f * r)
                    close()
                }
                canvas.drawPath(lowerLeafPath, ornamentPaint)
                drawSparkle(canvas, cx + s * 1.52f * r, cy - 0.85f * r, 0.09f * r)
                drawSparkle(canvas, cx + s * 1.52f * r, cy + 0.85f * r, 0.07f * r)
            }
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
            canvas.drawRoundRect(
                RectF(cx - 0.26f * r, cy + 0.04f * r, cx + 0.26f * r, cy + 0.12f * r),
                0.04f * r, 0.04f * r, iconPaint,
            )
            // Empuñadura y pomo.
            canvas.drawRoundRect(
                RectF(cx - 0.05f * r, cy + 0.12f * r, cx + 0.05f * r, cy + 0.42f * r),
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
            glowPaint.color = Color.parseColor("#75D6CE")
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
            glowPaint.color = Color.parseColor("#75D6CE")
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
            glowPaint.color = Color.parseColor("#182644")
            glowPaint.alpha = (alpha * 0.95f * 255).toInt()
            canvas.drawCircle(cx - 0.12f * r, cy + 0.02f * r, 0.055f * r, glowPaint)
            canvas.drawCircle(cx + 0.12f * r, cy + 0.02f * r, 0.055f * r, glowPaint)
            // Sonrisa.
            iconStrokePaint.strokeWidth = 0.045f * r
            iconStrokePaint.color = Color.parseColor("#182644")
            val smile = RectF(cx - 0.12f * r, cy + 0.08f * r, cx + 0.12f * r, cy + 0.28f * r)
            canvas.drawArc(smile, 20f, 140f, false, iconStrokePaint)
            iconStrokePaint.color = Color.parseColor("#EAF4FB")
            // Destellos dentro del orbe, alrededor de la carita.
            iconPaint.alpha = (alpha * 0.95f * 255).toInt()
            drawSparkle(canvas, cx + 0.48f * r, cy - 0.35f * r, 0.07f * r)
            drawSparkle(canvas, cx - 0.50f * r, cy + 0.28f * r, 0.06f * r)
            drawSparkle(canvas, cx + 0.42f * r, cy + 0.42f * r, 0.05f * r)
        }

        /** Icono del botón (o la etiqueta en plata si no tiene icono diseñado). */
        protected fun drawIconOrLabel(canvas: Canvas, rect: RectF, alpha: Float) {
            val kind = iconFor(control.vitaButton)
            if (kind != null) {
                drawIcon(canvas, kind, rect.centerX(), rect.centerY(), rect.width() * 0.30f, alpha)
                return
            }
            if (control.label.isBlank()) return
            textPaint.alpha = (alpha * 255).toInt()
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
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            if (sdlButton == null) {
                // P2-23: botón "Sin asignar": no es pulsable (su toque
                // pasa a la superficie SDL, ver onTouchEvent) y no debe
                // dibujarse como si lo fuera: fantasma atenuado, sin
                // icono ni etiqueta, solo para localizarlo.
                drawGlassBody(canvas, rect, effectiveAlpha() * 0.25f)
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
        private var lastAxes: Pair<Short, Short> = 0.toShort() to 0.toShort()
        private var lastDpad: Set<VitaButton> = emptySet()

        override val hasActivePointer: Boolean get() = pointerId != -1

        init {
            val axes = stickAxesFor(rightStick)
            axisX = axes.first
            axisY = axes.second
        }

        override fun onDraw(canvas: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            // Stick grande (diseno 51025): cristal con adornos de
            // diamante en los 4 puntos, anillo interior luminoso y
            // dos diamantitos dentro del anillo (este y oeste).
            drawGlassBody(canvas, rect, effectiveAlpha(), fourWayOrnaments = true)
            val radius = rect.width() / 2f
            strokePaint.color = Color.parseColor("#BFE6F7")
            strokePaint.alpha = (effectiveAlpha() * 0.5f * 255).toInt()
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(rect.centerX(), rect.centerY(), radius * 0.78f, strokePaint)
            ornamentPaint.alpha = (effectiveAlpha() * 0.7f * 255).toInt()
            iconPaint.alpha = (effectiveAlpha() * 0.9f * 255).toInt()
            val innerDiamond = (radius * 0.05f).coerceAtLeast(2.5f * density)
            drawDiamondOrnament(canvas, rect.centerX() - radius * 0.78f, rect.centerY(), innerDiamond)
            drawDiamondOrnament(canvas, rect.centerX() + radius * 0.78f, rect.centerY(), innerDiamond)
            // Gota sobre la base, antes del pomo: el pomo queda nitido encima.
            drawRipple(canvas, rect, effectiveAlpha())
            // Pomo del stick: círculo de cristal más pequeño desplazado con el dedo.
            val knobRadius = radius * 0.42f
            val cx = rect.centerX() + knobNx * radius * 0.55f
            val cy = rect.centerY() + knobNy * radius * 0.55f
            val knobRect = RectF(cx - knobRadius, cy - knobRadius, cx + knobRadius, cy + knobRadius)
            drawGlassBody(canvas, knobRect, effectiveAlpha(), ornaments = false)
        }

        override fun release() {
            if (pointerId != -1 || lastAxes != (0.toShort() to 0.toShort()) || lastDpad.isNotEmpty()) {
                pointerId = -1
                knobNx = 0f
                knobNy = 0f
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
            strokePaint.color = Color.parseColor("#BFE6F7")
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
            val orbRect = RectF(iconCx - orbR, iconCy - orbR, iconCx + orbR, iconCy + orbR)
            drawGlassBody(canvas, orbRect, alpha)
            // Gota sobre el orbe (recortada a su anillo): nace donde
            // toca el dedo, en los dos modos. El rect es el del orbe
            // (0.42 x ancho en 0.2.54).
            rippleRect.set(orbRect)
            drawRipple(canvas, rippleRect, alpha)
            drawIcon(canvas, IconKind.IGNICULUS, iconCx, iconCy, width * 0.42f, alpha)
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
                    invalidate()
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
        private fun forwardTouch(action: Int, eventTime: Long = SystemClock.uptimeMillis()) {
            val activity = context as? Activity ?: return
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
            try {
                forwardingSyntheticTouch = true
                activity.dispatchTouchEvent(synthetic)
            } finally {
                forwardingSyntheticTouch = false
                synthetic.recycle()
            }
        }
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

    private data class TrackedPointer(val downTime: Long, var x: Float, var y: Float)

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
