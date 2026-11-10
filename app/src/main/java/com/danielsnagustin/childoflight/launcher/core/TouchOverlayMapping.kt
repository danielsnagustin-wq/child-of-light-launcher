package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.data.TouchControl
import com.danielsnagustin.childoflight.launcher.data.VitaButton
import kotlin.math.min
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
 * Rectángulo del juego 960x544 dentro de la ventana, en píxeles.
 * El juego no llena la ventana: se encaja centrado con pillarbox
 * (barras a los lados, en horizontal) o letterbox (barras arriba y
 * abajo, en vertical). Ver [TouchOverlayMapping.gameRect].
 */
data class GameRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

/**
 * Glifo PlayStation de un botón de cara: la forma que se dibuja
 * dentro del botón de cristal (X, círculo, cuadrado, triángulo).
 * R4-P0-2: antes CROSS se dibujaba como una flecha arriba, el mismo
 * icono que D-Pad arriba, y los demás botones llevaban iconos
 * temáticos (espada, espiral, estrella). El D-Pad y START no tienen
 * glifo: siguen con flechas y pausa.
 */
enum class FaceGlyph { CROSS, CIRCLE, SQUARE, TRIANGLE }

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
 *   config (vita3k/ctrl/src/ctrl.cpp: get_controller_bindings
 *   (:220-236) / get_controller_bindings_ext (:240-260), verificado
 *   contra la tabla real del pin: SOUTH -> CROSS, EAST -> CIRCLE,
 *   WEST -> SQUARE, NORTH -> TRIANGLE, BACK -> SELECT, START -> START,
 *   LEFT_SHOULDER -> L1, RIGHT_SHOULDER -> R1, LEFT_STICK -> L3,
 *   RIGHT_STICK -> R3, DPAD_* -> D-Pad. Los binds por defecto son la
 *   identidad (vita3k/app/src/app.cpp: reset_controller_binding) y
 *   app::init los restaura si config.yml no los trae (vita3k/app/src/
 *   app_init.cpp): nuestro config.yml NO necesita emitirlos.
 * - L2/R2 SÍ existen en el modelo del launcher desde R4-P2-11
 *   (VitaButton.L2/R2): el núcleo los lee como ejes (trigger >
 *   0x3FFF, ctrl.cpp: apply_controller, vía ext) y el overlay de
 *   upstream los manda con botón negativo (input_overlay.cpp:104-108:
 *   button < 0 -> SDL_SetJoystickVirtualAxis(-button, value ?
 *   SDL_MAX_SINT16 : 0). Los binds de ejes por defecto son la
 *   identidad (app.cpp:105-112 reset_controller_binding:
 *   axis_binds[4] = LEFT_TRIGGER, [5] = RIGHT_TRIGGER), así que
 *   -4 es L2 y -5 es R2. [toSdlButton] los codifica así; el puente
 *   (Vita3kCoreBridge.overlaySetButton -> input_overlay_bridge.cpp)
 *   pasa el entero tal cual al setButton nativo, que aplica la vía
 *   negativa. Child of Light no los usa en el título: solo están
 *   disponibles para asignar en el editor, nunca en el layout por
 *   defecto.
 * - GUIDE (botón PS, SDL 5) existe desde R4-P2-13: el bucle del
 *   núcleo (session.cpp:401-403: SDL_EVENT_GAMEPAD_BUTTON_DOWN +
 *   GUIDE -> hooks.on_guide_button; main_android.cpp:101 ->
 *   openPauseMenuFromController) abre el menú de pausa. Un botón
 *   táctil asignado a GUIDE manda el botón 5 del mando virtual; que
 *   SDL genere el evento de gamepad desde el joystick virtual es lo
 *   que falta por probar en el teléfono (TODO-VERIFY, sin CI que lo
 *   cubra). También solo en el editor, nunca por defecto.
 * - L3/R3 (R4-P2-12) ya existían como asignación en el editor:
 *   SDL 7/8 (LEFT_STICK/RIGHT_STICK -> SCE_CTRL_L3/R3 en
 *   ctrl.cpp: get_controller_bindings_ext). Los StickView no tienen
 *   click (no se añade): L3/R3 se asignan a un botón táctil normal.
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

    /** Resolución interna del juego: 960x544 (16:9 Vita). */
    const val GAME_ASPECT_WIDTH = 960f
    const val GAME_ASPECT_HEIGHT = 544f

    /**
     * R4-P0-2: glifo PlayStation del botón, o null si no es un botón
     * de cara (D-Pad, START, L1/R1, L2/R2, GUIDE, sticks y NONE se
     * dibujan con flechas, pausa, etiqueta o nada, no con un glifo).
     * CROSS tiene su glifo propio y ya no comparte icono con DPAD_UP.
     * 0.2.54: el layout limpio NO usa estos glifos para sus dos
     * botones de acción (btn_sword lleva espada y btn_jump flecha
     * arriba, por id de control, ver [iconOverrideFor]); un botón
     * de cara que el usuario añada en el editor SÍ sigue con su
     * glifo (CROSS -> X, etc.): el rombo sigue disponible en el
     * editor, solo deja de ser el layout por defecto.
     */
    fun faceGlyphFor(button: VitaButton): FaceGlyph? = when (button) {
        VitaButton.CROSS -> FaceGlyph.CROSS
        VitaButton.CIRCLE -> FaceGlyph.CIRCLE
        VitaButton.SQUARE -> FaceGlyph.SQUARE
        VitaButton.TRIANGLE -> FaceGlyph.TRIANGLE
        else -> null
    }

    /**
     * Icono propio de un control del layout limpio 0.2.54, por id
     * de control (NO por botón de Vita): btn_sword lleva una espada
     * y btn_jump una flecha arriba, aunque sus botones de Vita
     * (SQUARE y CROSS) tengan glifo propio en [faceGlyphFor].
     * GamepadOverlayView.iconFor consulta esto ANTES que el glifo,
     * así que un SQUARE o un CROSS añadidos por el usuario en el
     * editor (con otro id) siguen dibujándose con su glifo. Puro
     * Kotlin, sin Android: testeable.
     */
    enum class ControlIconOverride { SWORD, ARROW_UP }

    fun iconOverrideFor(controlId: String): ControlIconOverride? = when (controlId) {
        "btn_sword" -> ControlIconOverride.SWORD
        "btn_jump" -> ControlIconOverride.ARROW_UP
        else -> null
    }

    /**
     * Botones SDL que manda la zona Igniculus mientras se mantiene
     * apoyada (brillo de Igniculus, hombro L del juego original):
     * el botón 9 (LEFT_SHOULDER) Y el eje -4 (LEFT_TRIGGER, L2).
     * Por qué AMBOS: en el pin, ctrl.h:41-42 dice que SCE_CTRL_L2
     * es alias de SCE_CTRL_L, y ctrl.cpp:226 (lecturas v1,
     * SceCtrlData) traduce el hombro SDL 9 a SCE_CTRL_L, pero
     * ctrl.cpp:246 (lecturas v2, SceCtrlData2) traduce ese mismo 9
     * a SCE_CTRL_L1 (bit aparte, 0x400) y solo el eje 4 > 0x3FFF
     * llega a SCE_CTRL_L2 (= SCE_CTRL_L, ctrl.cpp:281-290). En
     * modo normal el 9 basta y en modo ext solo el eje llega al
     * bit L; pero enviar ambos NO es inocuo: en lecturas v2 el 9
     * pone ADEMÁS el bit L1 (0x400) como colateral. El comentario
     * anterior afirmaba que era inocuo en ambos modos y era FALSO
     * en v2 (R6-P1-13). No se puede enviar solo el botón válido
     * por modo porque el modo NO se puede detectar desde el
     * launcher: "v1 vs v2" no es un modo persistente del juego,
     * es qué función sceCtrl* invoca en cada lectura
     * (sceCtrlReadBufferPositive* frente a *2 / *Ext2:
     * ctrl.cpp: retrieve_ctrl_data pasa is_v2 como `ext` a
     * apply_controller, y las variantes *Ext sin 2 usan los
     * binds normales); CtrlState no guarda cuál usó el juego por
     * última vez, el pin no expone ningún JNI para leerlo
     * (launcher_bridge.h no tiene ninguna función de ctrl, y
     * ctrl_peek_buttons siempre lee en v1/no-ext) y leer
     * state.controllers exigiría modificar las fuentes del pin,
     * que el launcher no toca (el núcleo se clona en el build
     * desde upstream). Así que el doble envío se mantiene a
     * propósito: garantiza L en ambos modos y deja L1 colateral
     * en v2; quitar uno de los dos a ciegas rompería un modo
     * (solo-9 rompe v2, solo-eje rompe v1). La prueba de teléfono
     * Q4 del informe de la ronda 6 decide si L1 hace algo visible
     * en Child of Light. El puente traduce el valor negativo a
     * eje (input_overlay.cpp:104-108), igual que L2/R2. Puro
     * Kotlin: testeable, y ZoneView lo usa tal cual en los dos
     * modos de Igniculus.
     */
    fun glowSdlButtons(): List<Int> =
        listOf(SDL_BUTTON_LEFT_SHOULDER, -SDL_AXIS_LEFT_TRIGGER)

    /**
     * Orden de hijos del overlay en el juego (0.2.54): la zona
     * Igniculus PRIMERO (detrás) y el resto después (encima), en
     * su orden de lista. Android entrega el toque al hijo de más
     * arriba primero, así que un botón que se solape con la zona
     * (la espada y el salto pisan su esquina superior derecha)
     * recibe su toque aunque la zona esté debajo; la zona solo
     * recibe los toques que no caen en ningún botón. Solo los
     * visibles. Puro Kotlin: testeable.
     */
    fun overlayChildOrder(controls: List<TouchControl>): List<TouchControl> =
        visibleControls(controls).sortedBy { if (it.kind == "igniculus") 0 else 1 }

    /**
     * R4-P1-1: rectángulo del juego 960x544 encajado y centrado
     * dentro de una vista de [viewWidthPx] x [viewHeightPx]. Si la
     * vista es más ancha que 16:9, sobran barras a los lados
     * (pillarbox: en la captura 2048x945 el juego ocupa x 190-1857);
     * si es más alta, sobran barras arriba y abajo (letterbox). Si
     * la vista es exactamente 960:544, el rect es la vista entera.
     * Con una vista vacía o invertida se devuelve la vista tal cual.
     */
    fun gameRect(viewWidthPx: Float, viewHeightPx: Float): GameRect {
        if (viewWidthPx <= 0f || viewHeightPx <= 0f) {
            return GameRect(0f, 0f, viewWidthPx.coerceAtLeast(0f), viewHeightPx.coerceAtLeast(0f))
        }
        val scale = min(viewWidthPx / GAME_ASPECT_WIDTH, viewHeightPx / GAME_ASPECT_HEIGHT)
        val gameW = GAME_ASPECT_WIDTH * scale
        val gameH = GAME_ASPECT_HEIGHT * scale
        val left = (viewWidthPx - gameW) / 2f
        val top = (viewHeightPx - gameH) / 2f
        return GameRect(left, top, left + gameW, top + gameH)
    }

    /**
     * Botón de Vita -> botón SDL del mando virtual. null = no hay botón
     * SDL para esa asignación: NONE (sin asignar) y LEFT_STICK /
     * RIGHT_STICK como *botón* (son ejes; el stick usa
     * [stickAxesFor] y no pasa por aquí). Un botón mapeado a null no
     * se dibuja como pulsable en el juego: se dibuja atenuado
     * (fantasma, sin icono) y el toque lo recibe la superficie SDL
     * (táctil frontal del juego) en vez de quedarse en un botón que
     * no hace nada (P2-23).
     *
     * R4-P2-11: L2/R2 se codifican como botón NEGATIVO (-eje): es la
     * convención de upstream (input_overlay.cpp:104-108, button < 0
     * -> SDL_SetJoystickVirtualAxis(-button, ...)). L2 = -4 (eje
     * LEFT_TRIGGER), R2 = -5 (eje RIGHT_TRIGGER). El valor negativo
     * NO es un botón SDL real: solo lo entiende el setButton nativo.
     * R4-P2-13: GUIDE = SDL_BUTTON_GUIDE (5), el botón PS.
     */
    fun VitaButton.toSdlButton(): Int? = when (this) {
        VitaButton.CROSS -> SDL_BUTTON_SOUTH
        VitaButton.CIRCLE -> SDL_BUTTON_EAST
        VitaButton.SQUARE -> SDL_BUTTON_WEST
        VitaButton.TRIANGLE -> SDL_BUTTON_NORTH
        VitaButton.SELECT -> SDL_BUTTON_BACK
        VitaButton.GUIDE -> SDL_BUTTON_GUIDE
        VitaButton.START -> SDL_BUTTON_START
        VitaButton.L1 -> SDL_BUTTON_LEFT_SHOULDER
        VitaButton.L2 -> -SDL_AXIS_LEFT_TRIGGER
        VitaButton.R1 -> SDL_BUTTON_RIGHT_SHOULDER
        VitaButton.R2 -> -SDL_AXIS_RIGHT_TRIGGER
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

    /**
     * R4-P2-11: true si el valor que devuelve [VitaButton.toSdlButton]
     * es un gatillo codificado como botón negativo (L2/R2). El
     * setButton nativo lo traduce a eje (input_overlay.cpp:104-108);
     * cualquier otro consumidor que espere un botón SDL real (0..14)
     * debe tratarlo aparte, no indexar con él.
     */
    fun isTriggerSdlButton(sdlButton: Int): Boolean = sdlButton < 0

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
     * R4-P1-1: las fracciones x/y son fracciones del RECT DEL JUEGO
     * 960x544 ([gameRect]), NO de la ventana completa. Antes se
     * multiplicaban por el ancho/alto de la ventana y, con el juego
     * pillarboxed, un botón en 0.87 de ventana caía en 0.959 del
     * juego, con parte del botón sobre la barra negra. Ahora el
     * centro es (gameLeft + xFraction * gameW, gameTop + yFraction
     * * gameH) y el control ENTERO queda dentro del rect del juego
     * (el centro se limita a [mitad, tamaño - mitad] del rect).
     *
     * Coherencia con el editor (ui/TouchLayoutScreen.kt): la
     * fracción significa lo mismo en el juego y en la vista previa
     * SOLO si la previa tiene el mismo aspecto 16:9 que el juego.
     * Hoy la previa es fillMaxWidth x 200/300 dp (no 16:9), así que
     * el mismo valor numérico se ve con otro espaciado en el
     * editor que en el juego; eso lo arregla la previa 16:9 real
     * (R4-P1-4, fuera de este lote) y no se toca aquí.
     */
    fun controlRect(control: TouchControl, viewWidthPx: Float, viewHeightPx: Float, density: Float): ControlRect =
        controlRectInGameRect(control, gameRect(viewWidthPx, viewHeightPx), density)

    /**
     * Rectángulo en píxeles de un control dentro del rect del juego
     * [gameRect] (densidad [density]). Es la misma matemática que
     * [controlRect], con el rect ya calculado: útil para tests y
     * para no recalcular el rect por control.
     */
    fun controlRectInGameRect(control: TouchControl, gameRect: GameRect, density: Float): ControlRect {
        val sizePx = control.sizeDp * density
        val half = sizePx / 2f
        val left = (gameRect.left + control.xFraction * gameRect.width() - half)
            .coerceIn(gameRect.left, (gameRect.right - sizePx).coerceAtLeast(gameRect.left))
        val top = (gameRect.top + control.yFraction * gameRect.height() - half)
            .coerceIn(gameRect.top, (gameRect.bottom - sizePx).coerceAtLeast(gameRect.top))
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
