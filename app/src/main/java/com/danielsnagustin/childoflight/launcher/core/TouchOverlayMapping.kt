package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import kotlin.math.sqrt

/**
 * Rectángulo de un control en píxeles, Kotlin puro.
 *
 * NO es android.graphics.RectF a propósito: en los tests unitarios JVM
 * RectF es un stub sin mockear y su constructor NO asigna los campos
 * (todo queda a 0), así que el test controlRectMatchesEditorPreview
 * fallaba en CI (run #57) con left=0 aunque la matemática era correcta.
 * La vista del juego usa solo left/top/width de este rectángulo
 * (GamepadOverlayView), donde Android es real; los RectF de dibujo se
 * construyen allí desde los límites de cada vista.
 */
data class ControlRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

/**
 * Mapeo del overlay táctil del launcher al mando virtual SDL del núcleo.
 *
 * TODO LO DE AQUÍ ES PURO KOTLIN (sin Android) y está
 * verificado contra el código de upstream en el pin
 * 3e43bc803a04c775737a3474e0d54f3e10ca61bf:
 *
 * - El overlay nativo NO se configura por config.yml (no hay ninguna
 *   clave de overlay en vita3k/config/include/config/config.h; solo
 *   existe "performance-overlay", que es el HUD de FPS, no los
 *   botones). La vía real es vita3k/android/jni/input_overlay.cpp:
 *   un joystick virtual SDL ("Vita3K Virtual Controller",
 *   SDL_JOYSTICK_TYPE_GAMEPAD) que el núcleo lee como un mando más
 *   (vita3k/ctrl/src/ctrl.cpp: refresh_controllers + apply_controller).
 * - Los botones/ejes que recibe el núcleo son los de SDL3
 *   (include/SDL3/SDL_gamepad.h): los valores numéricos de abajo son
 *   el orden del enum (INVALID = -1, SOUTH = 0, EAST = 1, WEST = 2,
 *   NORTH = 3, BACK = 4, GUIDE = 5, START = 6, LEFT_STICK = 7,
 *   RIGHT_STICK = 8, LEFT_SHOULDER = 9, RIGHT_SHOULDER = 10,
 *   DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;
 *   ejes LEFTX = 0, LEFTY = 1, RIGHTX = 2, RIGHTY = 3,
 *   LEFT_TRIGGER = 4, RIGHT_TRIGGER = 5).
 * - El núcleo traduce botón SDL -> botón Vita con los binds de
 *   config (vita3k/ctrl/src/ctrl.cpp: get_controller_bindings /
 *   get_controller_bindings_ext): SOUTH -> CROSS, EAST -> CIRCLE,
 *   WEST -> SQUARE, NORTH -> TRIANGLE, BACK -> SELECT, START -> START,
 *   LEFT_SHOULDER -> L1, RIGHT_SHOULDER -> R1, LEFT_STICK -> L3,
 *   RIGHT_STICK -> R3, DPAD_* -> D-Pad. Los binds por defecto son la
 *   identidad (vita3k/app/src/app.cpp: reset_controller_binding) y
 *   app::init los restaura si config.yml no los trae (vita3k/app/src/
 *   app_init.cpp): nuestro config.yml NO necesita emitirlos.
 * - L2/R2 no existen en el modelo del launcher (VitaButton no los
 *   tiene): el núcleo los lee como ejes (trigger > 0x3FFF) y el
 *   overlay de upstream los manda con botón negativo (input_overlay.
 *   cpp: button < 0 -> SDL_SetJoystickVirtualAxis(-button, ...)).
 *   Como no hay botón Vita L2/R2 que asignar, esta vía no se usa.
 *
 * La personalización (posición, tamaño, opacidad, color, forma,
 * visibilidad, perfil activo) vive TODA en el launcher: el núcleo
 * solo recibe eventos botón/eje y no sabe nada del layout. Por eso
 * la vía nativa SÍ permite personalizar por botón: el layout lo
 * dibuja y lo interpreta nuestra vista (GamepadOverlayView) con los
 * datos del perfil activo de TouchLayoutRepository.
 */
object TouchOverlayMapping {

    // --- Botones SDL (SDL_GamepadButton) ---
    const val SDL_BUTTON_SOUTH = 0 // Cruz (X)
    const val SDL_BUTTON_EAST = 1 // Círculo (O)
    const val SDL_BUTTON_WEST = 2 // Cuadrado
    const val SDL_BUTTON_NORTH = 3 // Triángulo
    const val SDL_BUTTON_BACK = 4 // Select
    const val SDL_BUTTON_GUIDE = 5 // PS (botón guía)
    const val SDL_BUTTON_START = 6 // Start
    const val SDL_BUTTON_LEFT_STICK = 7 // L3
    const val SDL_BUTTON_RIGHT_STICK = 8 // R3
    const val SDL_BUTTON_LEFT_SHOULDER = 9 // L1
    const val SDL_BUTTON_RIGHT_SHOULDER = 10 // R1
    const val SDL_BUTTON_DPAD_UP = 11
    const val SDL_BUTTON_DPAD_DOWN = 12
    const val SDL_BUTTON_DPAD_LEFT = 13
    const val SDL_BUTTON_DPAD_RIGHT = 14

    // --- Ejes SDL (SDL_GamepadAxis) ---
    const val SDL_AXIS_LEFTX = 0
    const val SDL_AXIS_LEFTY = 1
    const val SDL_AXIS_RIGHTX = 2
    const val SDL_AXIS_RIGHTY = 3
    const val SDL_AXIS_LEFT_TRIGGER = 4
    const val SDL_AXIS_RIGHT_TRIGGER = 5

    /** Valor máximo de un eje SDL (SDL_MAX_SINT16). */
    const val SDL_AXIS_MAX = 32767

    /**
     * Zona muerta del stick (fracción del recorrido, 0..1). Por debajo
     * no se manda nada: el dedo apoyado sin querer no mueve a Aurora.
     */
    const val STICK_DEAD_ZONE = 0.08f

    /** Umbral de dirección para el modo D-Pad del stick (fracción del recorrido). */
    const val STICK_DPAD_THRESHOLD = 0.5f

    /**
     * Botón de Vita -> botón SDL del mando virtual. null = no hay botón
     * SDL para esa asignación: NONE (sin asignar) y LEFT_STICK /
     * RIGHT_STICK como *botón* (son ejes; el stick usa
     * [stickAxesFor] y no pasa por aquí). Un botón mapeado a null no
     * se dibuja como pulsable en el juego: el toque lo recibe la
     * superficie SDL (táctil frontal del juego) en vez de quedarse
     * en un botón que no hace nada.
     */
    fun VitaButton.toSdlButton(): Int? = when (this) {
        VitaButton.CROSS -> SDL_BUTTON_SOUTH
        VitaButton.CIRCLE -> SDL_BUTTON_EAST
        VitaButton.SQUARE -> SDL_BUTTON_WEST
        VitaButton.TRIANGLE -> SDL_BUTTON_NORTH
        VitaButton.SELECT -> SDL_BUTTON_BACK
        VitaButton.START -> SDL_BUTTON_START
        VitaButton.L1 -> SDL_BUTTON_LEFT_SHOULDER
        VitaButton.R1 -> SDL_BUTTON_RIGHT_SHOULDER
        VitaButton.L3 -> SDL_BUTTON_LEFT_STICK
        VitaButton.R3 -> SDL_BUTTON_RIGHT_STICK
        VitaButton.DPAD_UP -> SDL_BUTTON_DPAD_UP
        VitaButton.DPAD_DOWN -> SDL_BUTTON_DPAD_DOWN
        VitaButton.DPAD_LEFT -> SDL_BUTTON_DPAD_LEFT
        VitaButton.DPAD_RIGHT -> SDL_BUTTON_DPAD_RIGHT
        VitaButton.LEFT_STICK -> null
        VitaButton.RIGHT_STICK -> null
        VitaButton.NONE -> null
    }

    /** Ejes SDL del stick: izquierdo (LEFTX, LEFTY) o derecho (RIGHTX, RIGHTY). */
    fun stickAxesFor(rightStick: Boolean): Pair<Int, Int> =
        if (rightStick) SDL_AXIS_RIGHTX to SDL_AXIS_RIGHTY else SDL_AXIS_LEFTX to SDL_AXIS_LEFTY

    /**
     * Desplazamiento del stick (nx, ny en -1..1, con +y hacia abajo
     * en pantalla, igual que SDL: LEFTY positivo = hacia abajo) ->
     * valores de eje SDL (-32767..32767). La magnitud se recorta al
     * círculo unidad (la diagonal no llega más lejos que los ejes) y
     * la zona muerta se reescala para no perder recorrido.
     */
    fun stickAxes(nx: Float, ny: Float): Pair<Short, Short> {
        val mag = sqrt(nx * nx + ny * ny)
        if (mag < STICK_DEAD_ZONE) return 0.toShort() to 0.toShort()
        // La magnitud se recorta al círculo unidad ANTES de reescalar:
        // si no, una diagonal (mag > 1) reescalaba por encima de 1 y
        // ambos ejes llegaban a 32767 en vez de ~23169.
        val clamped = if (mag > 1f) 1f / mag else 1f
        val effectiveMag = mag.coerceAtMost(1f)
        val scaled = (effectiveMag - STICK_DEAD_ZONE) / (1f - STICK_DEAD_ZONE)
        val ux = nx * clamped * scaled
        val uy = ny * clamped * scaled
        return (ux * SDL_AXIS_MAX).toInt().coerceIn(-SDL_AXIS_MAX, SDL_AXIS_MAX).toShort() to
            (uy * SDL_AXIS_MAX).toInt().coerceIn(-SDL_AXIS_MAX, SDL_AXIS_MAX).toShort()
    }

    /**
     * Desplazamiento del stick -> botones de D-Pad pulsados (modo
     * StickMode.DPAD / BOTH). Las diagonales pulsan dos direcciones,
     * como un D-Pad real.
     */
    fun dpadForStick(nx: Float, ny: Float, threshold: Float = STICK_DPAD_THRESHOLD): Set<VitaButton> {
        val out = mutableSetOf<VitaButton>()
        if (ny < -threshold) out += VitaButton.DPAD_UP
        if (ny > threshold) out += VitaButton.DPAD_DOWN
        if (nx < -threshold) out += VitaButton.DPAD_LEFT
        if (nx > threshold) out += VitaButton.DPAD_RIGHT
        return out
    }

    /**
     * Rectángulo en píxeles de un control dentro de una vista de
     * [viewWidthPx] x [viewHeightPx] (densidad [density]).
     *
     * ES LA MISMA MATEMÁTICA que la vista previa del editor
     * (ui/TouchLayoutScreen.kt): centro en (xFraction * ancho,
     * yFraction * alto), tamaño sizeDp, y el control ENTERO dentro
     * de la vista (el centro se limita a [mitad, tamaño - mitad]).
     * Por eso lo que se ve en el editor es lo que aparece en el
     * juego, al mismo tamaño y en la misma posición relativa: la
     * única diferencia es que la vista previa es más pequeña que la
     * pantalla real. Si la fórmula del editor cambia, este test
     * (TouchOverlayMappingTest.controlRectMatchesEditorPreview) debe
     * actualizarse a la vez; si esta cambia, igual.
     */
    fun controlRect(control: TouchControl, viewWidthPx: Float, viewHeightPx: Float, density: Float): ControlRect {
        val sizePx = control.sizeDp * density
        val half = sizePx / 2f
        val left = (control.xFraction * viewWidthPx - half)
            .coerceIn(0f, (viewWidthPx - sizePx).coerceAtLeast(0f))
        val top = (control.yFraction * viewHeightPx - half)
            .coerceIn(0f, (viewHeightPx - sizePx).coerceAtLeast(0f))
        return ControlRect(left, top, left + sizePx, top + sizePx)
    }

    /** Controles que aparecen en el juego: solo los visibles. Un botón borrado no está en la lista; uno oculto tampoco. */
    fun visibleControls(controls: List<TouchControl>): List<TouchControl> =
        controls.filter { it.visible }
}

/**
 * Decisión pura de si el overlay se muestra en el juego.
 * - showInGame: interruptor general (Ajustes -> Controles en el juego).
 * - hiddenProfileIds: perfiles en los que el usuario ocultó el overlay.
 * - hideWithGamepad + gamepadConnected: el ajuste que ya existía
 *   ("Ocultar controles táctiles si hay mando conectado").
 * - controls: el perfil activo; sin controles visibles no hay nada
 *   que mostrar (p. ej. perfil "Mando externo + táctil mínimo" con
 *   todo oculto, o un perfil con todos los botones borrados).
 */
object TouchOverlayVisibility {
    fun shouldShow(
        showInGame: Boolean,
        activeProfileId: String,
        hiddenProfileIds: Set<String>,
        hideWithGamepad: Boolean,
        gamepadConnected: Boolean,
        controls: List<TouchControl>,
    ): Boolean {
        if (!showInGame) return false
        if (activeProfileId in hiddenProfileIds) return false
        if (hideWithGamepad && gamepadConnected) return false
        return TouchOverlayMapping.visibleControls(controls).isNotEmpty()
    }
}
