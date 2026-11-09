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
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.danielsnagustin.childoflight.launcher.core.FaceGlyph
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.dpadForStick
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.faceGlyphFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxes
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxesFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.visibleControls
import com.danielsnagustin.childoflight.launcher.data.ControlShape
import com.danielsnagustin.childoflight.launcher.data.IgniculusMode
import com.danielsnagustin.childoflight.launcher.data.StickMode
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
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
 * luciérnaga). Con una sola vista a pantalla completa, en cuanto un
 * dedo tocara un botón, el resto de dedos de ese gesto quedarían
 * atrapados en el overlay y la luciérnaga dejaría de responder
 * mientras se mueve el stick. La zona Igniculus se DIBUJA y en modo
 * Toque directo no consume toques: el arrastre sobre ella es táctil
 * directo del juego. En modo Joystick sí los consume y los traduce
 * (ancla + arrastre × sensibilidad) reenviándolos a la superficie
 * SDL como toques sintéticos (P1-12).
 *
 * Dibujo: LA MISMA MATEMÁTICA que la vista previa del editor
 * (TouchOverlayMapping.controlRect): posición por fracción DEL RECT
 * DEL JUEGO 960x544 (R4-P1-1), tamaño en dp, opacidad por botón
 * multiplicada por la opacidad general. El estilo es cristal
 * premium (degradado radial, borde luminoso azul hielo, ornamentos
 * florales, glifo PlayStation del botón — X, círculo, cuadrado,
 * triángulo —, brillo turquesa al pulsar): la vista previa del
 * editor sigue siendo esquemática y no refleja este estilo, solo
 * la posición y el tamaño.
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

    init {
        // Sin fondo y sin interceptar: los toques que no son de ningún
        // botón bajan a la vista SDL de debajo.
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    /** Controles del perfil activo (solo se muestran los visibles). Suelta todo lo pulsado antes de reconstruir. */
    fun setControls(newControls: List<TouchControl>) {
        releaseAll()
        controls = newControls
        builtForSize = null
        rebuild()
    }

    /** Suelta todos los botones y centra todos los sticks (sin botones atascados al pausar/ocultar/salir). */
    fun releaseAll() {
        for (i in 0 until childCount) {
            (getChildAt(i) as? OverlayControlView)?.release()
        }
    }

    private fun refreshChildren() {
        for (i in 0 until childCount) {
            (getChildAt(i) as? OverlayControlView)?.refreshStyle()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (forwardingSyntheticTouch) return false
        return super.dispatchTouchEvent(event)
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
        val ordered = visibleControls(controls)
            .sortedBy { if (it.kind == "igniculus") 0 else 1 }
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
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, GLYPH_CROSS, GLYPH_CIRCLE, GLYPH_SQUARE, GLYPH_TRIANGLE, PAUSE, IGNICULUS
    }

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
         * Cuerpo de cristal: degradado radial (centro plata luminosa,
         * medio el color del control, borde azul profundo), borde
         * luminoso azul hielo y ornamentos florales discretos arriba y
         * abajo del anillo. Translúcido: el escenario se ve a través.
         * Al pulsar, el brillo turquesa crece (pressAmount).
         */
        protected fun drawGlassBody(canvas: Canvas, rect: RectF, alpha: Float, ornaments: Boolean = true) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val radius = rect.width() / 2f
            // Halo neón: un poco más grande y muy translúcido, detrás.
            if (neonGlow) {
                glowPaint.color = parseHex(control.colorHex)
                glowPaint.alpha = (alpha * 0.16f * 255).toInt()
                drawShapeInto(canvas, RectF(rect).apply { inset(-6f * density, -6f * density) }, glowPaint)
            }
            // Brillo al pulsar: turquesa, crece con pressAmount.
            val glowAlpha = alpha * (0.10f + 0.55f * pressAmount)
            if (glowAlpha > 0.01f) {
                glowPaint.color = Color.parseColor("#75D6CE")
                glowPaint.alpha = (glowAlpha * 255).toInt()
                drawShapeInto(canvas, RectF(rect).apply { inset(-4f * density * pressAmount, -4f * density * pressAmount) }, glowPaint)
            }
            // Cristal: degradado radial con el color del control en medio.
            val tint = parseHex(control.colorHex)
            val gradient = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb((alpha * 0.34f * 255).toInt(), 226, 238, 248),
                    Color.argb((alpha * 0.30f * 255).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint)),
                    Color.argb((alpha * 0.62f * 255).toInt(), 24, 38, 68),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            fillPaint.shader = gradient
            drawShapeInto(canvas, rect, fillPaint)
            fillPaint.shader = null
            // Borde luminoso azul hielo.
            strokePaint.color = Color.parseColor("#BFE6F7")
            strokePaint.alpha = (alpha * 0.95f * 255).toInt()
            strokePaint.strokeWidth = 2f * density
            drawShapeInto(canvas, rect, strokePaint)
            if (ornaments) drawOrnaments(canvas, cx, cy, radius, alpha)
        }

        private fun drawShapeInto(canvas: Canvas, rect: RectF, paint: Paint) {
            if (control.shape == ControlShape.SQUARE) {
                canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
            } else {
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
            }
        }

        /** Ornamentos florales discretos: una florecita de 4 pétalos arriba y abajo del anillo. */
        protected fun drawOrnaments(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
            ornamentPaint.alpha = (alpha * 0.85f * 255).toInt()
            val petal = (radius * 0.04f).coerceAtLeast(2.2f * density)
            val dist = radius * 0.97f
            for (oy in listOf(cy - dist, cy + dist)) {
                canvas.drawCircle(cx - petal * 1.6f, oy, petal, ornamentPaint)
                canvas.drawCircle(cx + petal * 1.6f, oy, petal, ornamentPaint)
                canvas.drawCircle(cx, oy - petal * 1.6f, petal, ornamentPaint)
                canvas.drawCircle(cx, oy + petal * 1.6f, petal, ornamentPaint)
                canvas.drawCircle(cx, oy, petal * 0.7f, ornamentPaint)
            }
        }

        /**
         * Icono del botón según su VitaButton. null = sin icono: se dibuja la etiqueta en plata.
         * R4-P0-2: los 4 botones de cara llevan su glifo PlayStation
         * (X, círculo, cuadrado, triángulo) en vez de iconos
         * temáticos. CROSS ya no comparte icono con DPAD_UP: CROSS
         * es GLYPH_CROSS y DPAD_UP sigue siendo ARROW_UP. El D-Pad
         * sigue con flechas y START con barras de pausa.
         */
        protected fun iconFor(button: VitaButton): IconKind? {
            when (faceGlyphFor(button)) {
                FaceGlyph.CROSS -> return IconKind.GLYPH_CROSS
                FaceGlyph.CIRCLE -> return IconKind.GLYPH_CIRCLE
                FaceGlyph.SQUARE -> return IconKind.GLYPH_SQUARE
                FaceGlyph.TRIANGLE -> return IconKind.GLYPH_TRIANGLE
                null -> { }
            }
            return when (button) {
                VitaButton.START -> IconKind.PAUSE
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
                IconKind.PAUSE -> drawPauseIcon(canvas, cx, cy, rr)
                IconKind.IGNICULUS -> drawIgniculusIcon(canvas, cx, cy, rr, alpha)
            }
        }

        private fun drawArrowIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, degrees: Float) {
            canvas.save()
            canvas.rotate(degrees, cx, cy)
            val path = Path().apply {
                moveTo(cx, cy - 0.45f * r)
                lineTo(cx + 0.40f * r, cy + 0.10f * r)
                lineTo(cx + 0.16f * r, cy + 0.10f * r)
                lineTo(cx + 0.16f * r, cy + 0.45f * r)
                lineTo(cx - 0.16f * r, cy + 0.45f * r)
                lineTo(cx - 0.16f * r, cy + 0.10f * r)
                lineTo(cx - 0.40f * r, cy + 0.10f * r)
                close()
            }
            canvas.drawPath(path, iconPaint)
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
            val path = Path().apply {
                moveTo(cx, cy - 0.48f * r)
                lineTo(cx + 0.45f * r, cy + 0.36f * r)
                lineTo(cx - 0.45f * r, cy + 0.36f * r)
                close()
            }
            canvas.drawPath(path, iconStrokePaint)
        }

        private fun drawPauseIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val w = 0.16f * r
            val h = 0.72f * r
            val radius = 0.08f * r
            canvas.drawRoundRect(RectF(cx - 0.24f * r - w / 2, cy - h / 2, cx - 0.24f * r + w / 2, cy + h / 2), radius, radius, iconPaint)
            canvas.drawRoundRect(RectF(cx + 0.24f * r - w / 2, cy - h / 2, cx + 0.24f * r + w / 2, cy + h / 2), radius, radius, iconPaint)
        }

        private fun drawIgniculusIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
            // Halo turquesa detrás.
            glowPaint.color = Color.parseColor("#75D6CE")
            glowPaint.alpha = (alpha * 0.35f * 255).toInt()
            canvas.drawCircle(cx, cy, r * 0.85f, glowPaint)
            // Cuerpo: gotita/llama azul hielo.
            val body = Path().apply {
                moveTo(cx, cy - 0.62f * r)
                quadTo(cx + 0.34f * r, cy - 0.10f * r, cx + 0.30f * r, cy + 0.18f * r)
                quadTo(cx + 0.24f * r, cy + 0.48f * r, cx, cy + 0.48f * r)
                quadTo(cx - 0.24f * r, cy + 0.48f * r, cx - 0.30f * r, cy + 0.18f * r)
                quadTo(cx - 0.34f * r, cy - 0.10f * r, cx, cy - 0.62f * r)
                close()
            }
            canvas.drawPath(body, iconPaint)
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

        init {
            val axes = stickAxesFor(rightStick)
            axisX = axes.first
            axisY = axes.second
        }

        override fun onDraw(canvas: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            drawGlassBody(canvas, rect, effectiveAlpha())
            // Pomo del stick: círculo de cristal más pequeño desplazado con el dedo.
            val radius = rect.width() / 2f
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
     * Zona Igniculus: se dibuja (translúcida, detrás de los botones)
     * y su táctil depende del modo del perfil (P1-12):
     * - Toque directo (por defecto): NO consume toques. El toque y el
     *   arrastre bajan a la superficie SDL y el juego los recibe como
     *   táctil frontal: la luciérnaga va donde toca el dedo, con la
     *   sensibilidad del perfil aplicada por el propio juego al leer el
     *   táctil (el overlay no reinterpreta ese gesto).
     * - Joystick: SÍ consume el toque y lo traduce: el punto donde se
     *   apoya el dedo es el ancla; el destino es ancla + arrastre ×
     *   sensibilidad, reenviado a la superficie SDL como un toque
     *   sintético en esa posición (el juego lo recibe como táctil
     *   frontal y la luciérnaga vuela al destino). El orbe sigue al
     *   destino mientras se arrastra.
     * Desde 0.2.14 se dibuja solo como orbe pequeño + anillo tenue:
     * dibujar el cristal sobre todo el rect (220 dp) era una rueda
     * oscura gigante en el centro de la pantalla.
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

        override fun onDraw(canvas: Canvas) {
            // Rueda de Igniculus (2026-11-09, 0.2.14): antes se
            // llamaba drawGlassBody sobre TODO el rect de 220 dp y
            // el degradado con el borde azul oscuro salía como un
            // disco gigante en el centro. Ahora solo un orbe pequeño
            // (el icono a 0.30 × ancho) + un anillo tenue alrededor
            // (0.32 × ancho, fuera del halo del icono, que llega a
            // 0.255 × ancho). El resto del área queda transparente.
            val cx = width / 2f
            val cy = height / 2f
            val alpha = effectiveAlpha()
            strokePaint.color = Color.parseColor("#BFE6F7")
            strokePaint.alpha = (alpha * 0.5f * 255).toInt()
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(cx, cy, width * 0.32f, strokePaint)
            // En modo Joystick el orbe sigue al destino mientras se
            // arrastra (recortado a la zona; fuera de ella el destino
            // real está en el juego, no aquí).
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
            drawIcon(canvas, IconKind.IGNICULUS, iconCx, iconCy, width * 0.30f, alpha)
        }

        override fun release() {
            // Si el overlay se oculta o se reconstruye a mitad de un
            // arrastre, el toque sintético debe terminar: si no, el
            // juego seguiría creyendo que el dedo está apoyado.
            if (joystickActive) endJoystickTouch()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Un toque sintético reenviado nunca se vuelve a consumir:
            // su destino es la superficie SDL (ver dispatchTouchEvent).
            if (forwardingSyntheticTouch) return false
            if (control.igniculusMode != IgniculusMode.JOYSTICK) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = event.getPointerId(0)
                    anchorOverlayX = left + event.x
                    anchorOverlayY = top + event.y
                    targetOverlayX = anchorOverlayX
                    targetOverlayY = anchorOverlayY
                    downTime = event.downTime
                    joystickActive = true
                    forwardTouch(MotionEvent.ACTION_DOWN)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index < 0) return joystickActive
                    val overlayW = this@GamepadOverlayView.width.toFloat()
                    val overlayH = this@GamepadOverlayView.height.toFloat()
                    val dx = event.getX(index) + left - anchorOverlayX
                    val dy = event.getY(index) + top - anchorOverlayY
                    val s = control.sensitivity.coerceIn(0.5f, 2.0f)
                    targetOverlayX = (anchorOverlayX + dx * s).coerceIn(0f, overlayW)
                    targetOverlayY = (anchorOverlayY + dy * s).coerceIn(0f, overlayH)
                    forwardTouch(MotionEvent.ACTION_MOVE)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == pointerId) {
                        endJoystickTouch()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (joystickActive) endJoystickTouch()
                    return true
                }
            }
            return false
        }

        private fun endJoystickTouch() {
            forwardTouch(MotionEvent.ACTION_UP)
            pointerId = -1
            joystickActive = false
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
        private fun forwardTouch(action: Int) {
            val activity = context as? Activity ?: return
            val synthetic = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                targetOverlayX,
                targetOverlayY,
                0,
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
