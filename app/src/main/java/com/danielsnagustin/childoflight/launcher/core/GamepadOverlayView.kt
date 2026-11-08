package com.danielsnagustin.childoflight.launcher.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
 * dp, opacidad por botón multiplicada por la opacidad general, color
 * y forma del control, etiqueta en negro como en el editor. Lo que se
 * ve en el editor es lo que aparece en el juego.
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

    /** Color de acento del launcher (azul hielo, como el resto de la app). */
    private val accentColor: Int get() = Color.parseColor("#8CCBEB")

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
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 14f * density
            isFakeBoldText = true
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

        protected fun drawShape(canvas: Canvas, rect: RectF, alpha: Float) {
            val color = parseHex(control.colorHex)
            // Halo neón: un poco más grande y muy translúcido, detrás.
            if (neonGlow) {
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = color
                fillPaint.alpha = (alpha * 0.16f * 255).toInt()
                drawShapeInto(canvas, RectF(rect).apply { inset(-6f * density, -6f * density) }, fillPaint)
                strokePaint.color = color
                strokePaint.alpha = (alpha * 0.75f * 255).toInt()
                drawShapeInto(canvas, rect, strokePaint)
            }
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = color
            fillPaint.alpha = (alpha * 255).toInt()
            drawShapeInto(canvas, rect, fillPaint)
        }

        private fun drawShapeInto(canvas: Canvas, rect: RectF, paint: Paint) {
            if (control.shape == ControlShape.SQUARE) {
                canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
            } else {
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
            }
        }

        protected fun drawLabel(canvas: Canvas, rect: RectF, alpha: Float) {
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
            drawShape(canvas, rect, effectiveAlpha())
            drawLabel(canvas, rect, effectiveAlpha())
        }

        override fun release() {
            if (pressed) {
                pressed = false
                sdlButton?.let { listener?.onButton(it, false) }
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val sdl = sdlButton ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    listener?.onButton(sdl, true)
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
            drawShape(canvas, rect, effectiveAlpha())
            // Pomo del stick: círculo más pequeño desplazado con el dedo.
            val radius = rect.width() / 2f
            val knobRadius = radius * 0.42f
            val cx = rect.centerX() + knobNx * radius * 0.55f
            val cy = rect.centerY() + knobNy * radius * 0.55f
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = parseHex(control.colorHex)
            fillPaint.alpha = (effectiveAlpha() * 255).toInt()
            canvas.drawCircle(cx, cy, knobRadius, fillPaint)
            strokePaint.color = accentColor
            strokePaint.alpha = (effectiveAlpha() * 255).toInt()
            canvas.drawCircle(cx, cy, knobRadius, strokePaint)
            drawLabel(canvas, rect, effectiveAlpha())
        }

        override fun release() {
            if (pointerId != -1 || lastAxes != (0.toShort() to 0.toShort()) || lastDpad.isNotEmpty()) {
                pointerId = -1
                knobNx = 0f
                knobNy = 0f
                sendAxes(0.toShort(), 0.toShort())
                sendDpad(emptySet())
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
            drawShape(canvas, rect, effectiveAlpha())
            drawLabel(canvas, rect, effectiveAlpha())
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }
}
