package com.danielsnagustin.childoflight.launcher.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.controlRect
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.dpadForStick
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxes
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.stickAxesFor
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.toSdlButton
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.visibleControls
import com.danielsnagustin.childoflight.launcher.data.ControlShape
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
 * mientras se mueve el stick. La zona Igniculus se DIBUJA pero nunca
 * consume toques: el arrastre sobre ella es táctil directo del juego.
 *
 * Dibujo: LA MISMA MATEMÁTICA que la vista previa del editor
 * (TouchOverlayMapping.controlRect): posición por fracción, tamaño en
 * dp, opacidad por botón multiplicada por la opacidad general. El
 * estilo es cristal premium (degradado radial, borde luminoso azul
 * hielo, ornamentos florales, icono del botón en vez de letra,
 * brillo turquesa al pulsar): la vista previa del editor sigue
 * siendo esquemática y no refleja este estilo, solo la posición y
 * el tamaño.
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && builtForSize != w to h) rebuild()
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
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
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, SWORD, SPIRAL, STAR, PAUSE, IGNICULUS
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

        /** Icono del botón según su VitaButton. null = sin icono: se dibuja la etiqueta en plata. */
        protected fun iconFor(button: VitaButton): IconKind? = when (button) {
            VitaButton.CROSS -> IconKind.ARROW_UP
            VitaButton.SQUARE -> IconKind.SWORD
            VitaButton.CIRCLE -> IconKind.SPIRAL
            VitaButton.TRIANGLE -> IconKind.STAR
            VitaButton.START -> IconKind.PAUSE
            VitaButton.DPAD_UP -> IconKind.ARROW_UP
            VitaButton.DPAD_DOWN -> IconKind.ARROW_DOWN
            VitaButton.DPAD_LEFT -> IconKind.ARROW_LEFT
            VitaButton.DPAD_RIGHT -> IconKind.ARROW_RIGHT
            else -> null
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
                IconKind.SWORD -> drawSwordIcon(canvas, cx, cy, rr)
                IconKind.SPIRAL -> drawSpiralIcon(canvas, cx, cy, rr)
                IconKind.STAR -> drawStarIcon(canvas, cx, cy, rr)
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

        private fun drawSwordIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            iconStrokePaint.strokeWidth = 0.13f * r
            // Hoja: diagonal de abajo-izquierda a arriba-derecha.
            canvas.drawLine(cx - 0.30f * r, cy + 0.30f * r, cx + 0.42f * r, cy - 0.42f * r, iconStrokePaint)
            // Guarda: perpendicular a la hoja.
            iconStrokePaint.strokeWidth = 0.10f * r
            canvas.drawLine(cx - 0.42f * r, cy + 0.12f * r, cx - 0.12f * r, cy + 0.42f * r, iconStrokePaint)
            // Empuñadura.
            canvas.drawLine(cx - 0.30f * r, cy + 0.30f * r, cx - 0.48f * r, cy + 0.48f * r, iconStrokePaint)
            // Destello en la punta.
            drawStarIcon(canvas, cx + 0.48f * r, cy - 0.48f * r, r * 0.22f)
        }

        private fun drawSpiralIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            iconStrokePaint.strokeWidth = 0.09f * r
            for (i in 0..2) {
                val inset = r * (0.15f + i * 0.22f)
                val rect = RectF(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
                canvas.drawArc(rect, i * 40f, 250f, false, iconStrokePaint)
            }
            canvas.drawCircle(cx, cy, r * 0.10f, iconPaint)
        }

        private fun drawStarIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val path = Path().apply {
                moveTo(cx, cy - r)
                quadTo(cx + 0.10f * r, cy - 0.10f * r, cx + r, cy)
                quadTo(cx + 0.10f * r, cy + 0.10f * r, cx, cy + r)
                quadTo(cx - 0.10f * r, cy + 0.10f * r, cx - r, cy)
                quadTo(cx - 0.10f * r, cy - 0.10f * r, cx, cy - r)
                close()
            }
            canvas.drawPath(path, iconPaint)
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

        override fun onDraw(canvas: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            drawGlassBody(canvas, rect, effectiveAlpha())
            drawIconOrLabel(canvas, rect, effectiveAlpha())
        }

        override fun release() {
            if (pressed) {
                pressed = false
                sdlButton?.let { listener?.onButton(it, false) }
                animatePress(0f)
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val sdl = sdlButton ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    listener?.onButton(sdl, true)
                    animatePress(1f)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // El dedo se salió del botón: soltar (no queda atascado).
                    val margin = 8f * density
                    if (pressed && (event.x < -margin || event.x > width + margin ||
                            event.y < -margin || event.y > height + margin)
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
     * pero NUNCA consume toques. El toque directo y el arrastre sobre
     * ella bajan a la superficie SDL y el juego los recibe como táctil
     * frontal: la luciérnaga va donde toca el dedo, con la
     * sensibilidad del perfil aplicada por el propio juego al leer el
     * táctil (el overlay no reinterpreta ese gesto).
     */
    private inner class ZoneView(context: Context, control: TouchControl) :
        OverlayControlView(context, control) {

        override fun onDraw(canvas: Canvas) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            drawGlassBody(canvas, rect, effectiveAlpha())
            drawIcon(canvas, IconKind.IGNICULUS, rect.centerX(), rect.centerY(), rect.width() * 0.18f, effectiveAlpha())
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }
}
