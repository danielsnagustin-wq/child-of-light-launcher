package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielsnagustin.childoflight.launcher.core.TouchOverlayMapping.sizeFractionForReferenceDp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

// Crash al abrir (run #80): mismo arreglo que UiPreferencesRepository
// (ver su comentario): archivo preferences_pb corrupto -> el
// corruptionHandler lo reemplaza por vacío y el catch cubre otros
// fallos de lectura con los perfiles de fábrica.
private val Context.touchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "col_touch_layout",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Botón de Vita al que se puede asignar un botón táctil. */
enum class VitaButton(val label: String) {
    CROSS("Cruz (X)"),
    CIRCLE("Círculo (O)"),
    SQUARE("Cuadrado (□)"),
    TRIANGLE("Triángulo (△)"),
    L1("L"),
    // R4-P2-11: L2/R2 no son botones SDL: el núcleo los lee como
    // ejes (trigger > 0x3FFF) y el overlay de upstream los manda con
    // botón negativo (input_overlay.cpp: button < 0 ->
    // SDL_SetJoystickVirtualAxis(-button, ...)). toSdlButton() los
    // codifica así (-4/-5); el editor los lista porque itera
    // VitaButton.entries. Child of Light no los usa en el título:
    // solo están disponibles para asignar, nunca en el layout por
    // defecto.
    L2("L2"),
    R1("R"),
    R2("R2"),
    START("Start"),
    SELECT("Select"),
    // R4-P2-13: botón guía (PS): SDL 5. Pulsarlo manda el botón
    // GUIDE del mando virtual y el bucle del núcleo (session.cpp:
    // SDL_EVENT_GAMEPAD_BUTTON_DOWN + GUIDE -> on_guide_button ->
    // main_android.cpp:101 openPauseMenuFromController) abre el menú
    // de pausa. También disponible solo en el editor, no por defecto.
    GUIDE("Guía (PS)"),
    DPAD_UP("D-Pad arriba"),
    DPAD_DOWN("D-Pad abajo"),
    DPAD_LEFT("D-Pad izquierda"),
    DPAD_RIGHT("D-Pad derecha"),
    LEFT_STICK("Stick izquierdo"),
    RIGHT_STICK("Stick derecho"),
    L3("L3"),
    R3("R3"),
    NONE("Sin asignar"),
}

enum class ControlShape { CIRCLE, SQUARE, PILL }
enum class StickMode(val label: String) { ANALOG("Stick analógico"), DPAD("D-Pad"), BOTH("Ambos") }
enum class IgniculusMode(val label: String) { DIRECT_TOUCH("Toque directo"), JOYSTICK("Joystick") }

/**
 * Un control táctil del layout de Child of Light.
 * x/y son fracciones de la pantalla (0..1), size en dp, opacity 0..1.
 * 0.2.64 (A-7): `sizeFraction` es el tamaño como FRACCIÓN de la
 * altura del rect del juego (0..1): en el teléfono de referencia
 * (1080x2340, densidad 2.80, juego 1080 px de alto) un stick de
 * 174 dp es 0.4511 del alto. Con fracción, el tamaño escala con la
 * pantalla en vez de quedarse en dp fijos calibrados para un solo
 * teléfono. null = modo legado (sizeDp x densidad), que es lo que
 * guardan los perfiles ya existentes y los controles que crea o
 * redimensiona el usuario en el editor (el slider de tamaño pone
 * sizeFraction a null: un tamaño editado a mano es dp, no fracción).
 * Los perfiles de fábrica sí llevan fracción (ver
 * TouchOverlayMapping.sizeFractionForReferenceDp).
 * - stick: stick izquierdo para Aurora (stickMode: analógico / D-Pad / ambos).
 * - button: botón asignable a cualquier botón de Vita (vitaButton).
 * - igniculus: zona táctil para la luciérnaga (modo toque directo o joystick, sensibilidad).
 * visible=false = oculto (no aparece en el overlay ni estorba).
 * colorHex: color del control, p. ej. "#8CCBEB".
 */
data class TouchControl(
    val id: String,
    val label: String,
    val kind: String, // "stick" | "button" | "igniculus"
    val vitaButton: VitaButton = VitaButton.NONE,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val sizeDp: Int = 64,
    val opacity: Float = 0.8f,
    val colorHex: String = "#8CCBEB",
    val shape: ControlShape = ControlShape.CIRCLE,
    val visible: Boolean = true,
    val stickMode: StickMode = StickMode.ANALOG,
    val igniculusMode: IgniculusMode = IgniculusMode.DIRECT_TOUCH,
    /** Sensibilidad de la zona Igniculus: 0.5 (lenta) .. 2.0 (rápida). */
    val sensitivity: Float = 1.0f,
    /**
     * 0.2.64 (A-7): tamaño como fracción de la altura del rect del
     * juego (0..1). null = modo legado en dp ([sizeDp] x densidad).
     * Campo 15 del formato de guardado (encodeControl): los perfiles
     * viejos de 14 campos se leen con null (sin migrar: su tamaño en
     * dp no cambia en ningún teléfono).
     */
    val sizeFraction: Float? = null,
    /**
     * 0.2.68 (neón): ancho como fracción de la altura del rect del
     * juego (0..1), SOLO para forma PILL (píldoras L/R/START, más
     * anchas que altas). null = cuadrado (ancho = tamaño, como
     * antes). Campo 16 del formato de guardado (encodeControl).
     * Se ignora si la forma no es PILL (un botón circular con
     * ancho distinto dibujaría un círculo del ancho, fuera de su
     * rect): controlWidthPx solo lo aplica con PILL.
     */
    val widthFraction: Float? = null,
)

data class ControlProfile(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val controls: List<TouchControl>,
)

/**
 * Layout neón 0.2.68 (10 controles): EXACTAMENTE la imagen de
 * referencia 52710_13_ua6f.png (1847x851, mismo archivo que
 * 52710_3_c4cw.png), por decisión del usuario 2026-11-12 18:21 /
 * 18:23 / 18:26 ("Quiero estos tal cuál y no cambies nada"). Los
 * controles viejos (espada, salto, Volver) se van; el rombo de
 * botones de cara vuelve (△ arriba, □ izquierda, ○ derecha,
 * ✕ abajo) con sus glifos, L/R son píldoras en las esquinas,
 * START es píldora abajo al centro, la pausa (START, icono II +
 * alas florales) sigue arriba al centro, el stick grande abajo
 * a la izquierda y el orbe de Igniculus abajo a la derecha.
 *
 * Medidas por umbral de brillo (fracciones de la VENTANA
 * 1847x851 = 2340x1080 del teléfono en apaisado; verificadas
 * por Aurora con PIL contra la imagen: coinciden dentro del
 * ruido del umbral): L centro (0.083, 0.094) 0.128x0.132; R
 * (0.920, 0.094) 0.130x0.132; Pausa+alas (0.498, 0.071)
 * 0.277x0.141; Stick (0.121, 0.700) Ø 0.212W, pomo 0.082W;
 * START (0.308, 0.891) 0.160x0.099; Igniculus (0.729, 0.813)
 * Ø 0.145W; △ (0.874, 0.376), □ (0.818, 0.496), ○ (0.930,
 * 0.496), ✕ (0.874, 0.613), Ø 0.089W cada uno (huella con
 * adornos; el cristal es 60 dp, ver abajo).
 *
 * Conversión a fracciones del JUEGO (rect 1906x1080, izquierda
 * 217 en el teléfono 2340x1080, densidad 2.80), misma fórmula
 * que 0.2.54: fracJuegoX = (fracVentana * 2340 - 217) / 1906,
 * igual en y. Tamaños en dp = px / 2.80; la fracción de tamaño
 * es del ALTO del juego (sizeFractionForReferenceDp). Las
 * píldoras llevan ADEMÁS widthFraction (ancho como fracción
 * del alto del juego): L 0.278, R 0.281, START 0.346.
 *
 * Los botones de cara son 60 dp de CRISTAL. El 0.089W medido por
 * umbral (208 px) es la HUELLA con los adornos de diamante, no el
 * cristal: el perfil radial PIL muestra el anillo brillante a
 * r 57-64 px en la imagen (Ø ~128 px = 58-60 dp en el teléfono)
 * y la diagonal entre centros es oscura (hueco, no solape). Con
 * 74 dp de cristal los cuatro círculos se solaparían 23 px
 * (centros a 184 px): el mismo error de la 0.2.62 con otros
 * números. La medida 60 dp es del agente de controles (ronda
 * 0.2.68) y se adopta por verificada. La huella con adornos
 * queda en ~213 px ≈ 0.089W. El orbe de Igniculus se dibuja a
 * 101 dp: la zona es 120 dp y el orbe se dibuja a 0.84 x zona
 * (0.84 x 120 = 101). El bbox de la gota en la imagen es
 * 268x264 px = 121.5x119.7 dp: ESO es la zona (con su marco y
 * sus destellos), no el orbe; el cuerpo de la gota mide 190-225
 * px en la imagen (86-102 dp) y el orbe dibujado de 101 dp cae
 * dentro. Una zona de 144 dp dibujaría un orbe de 121 dp, tan
 * grande como el bbox entero con marco: 19% de más. Medido con
 * PIL en la segunda pasada (perfil radial desde (1346, 700):
 * media 167 en r=100, 82 en r=110, 13 en r=130; cuerpo por
 * filas 190-225 px). La medida 120/101 dp es del agente de
 * controles (ronda 0.2.68) y se adopta por verificada (OpenCode
 * APTO 10/10 en su árbol). El pomo del stick
 * es 0.39 x radio (0.082 / 0.212 = 0.387). Las alas de la pausa
 * son 4.7 x de ancho y 1.07 x de alto del botón (medido 4.63 x
 * 1.09; la vista lleva un poco de margen para los destellos de
 * las puntas).
 *
 * Huecos medidos en la imagen (no se corrigen: SON el diseño):
 * los botones de cara NO se tocan: hueco de 14-17 px entre
 * vecinos (centros a 184 px con Ø 168 px de cristal); el borde
 * derecho del stick queda a 3 px de la píldora START. El centro
 * de L (-0.012) y de ○ (1.028) cae
 * fuera del rect del juego, sobre la barra negra: el clamp de
 * controlRectInGameRect se generalizó a la vista entera (antes
 * solo stick y salto podían pisarla).
 *
 * La pausa (btn_pause) va ANTES que la píldora START en la
 * lista: las alas se atan al PRIMER botón START
 * (pauseControlFor) y el icono II es de ese botón; la píldora
 * dibuja su etiqueta "START", no el icono de pausa.
 */
fun defaultTouchLayout(): List<TouchControl> = listOf(
    // 0.2.64 (A-7): cada control lleva ADEMÁS su tamaño como
    // fracción del alto del juego (sizeFractionForReferenceDp):
    // en el teléfono de referencia es exactamente el mismo tamaño
    // en px que el dp de antes; en otra pantalla escala con el
    // juego. sizeDp se conserva como referencia en dp (la muestra
    // el slider del editor). Las píldoras llevan ADEMÁS
    // widthFraction (ancho como fracción del alto del juego).
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.035f, 0.700f, 177, 0.70f, sizeFraction = sizeFractionForReferenceDp(177)),
    TouchControl("btn_pause", "Pausa", "button", VitaButton.START, 0.498f, 0.071f, 50, 0.70f, sizeFraction = sizeFractionForReferenceDp(50)),
    TouchControl("btn_l", "L", "button", VitaButton.L1, -0.012f, 0.094f, 51, 0.80f, shape = ControlShape.PILL, sizeFraction = sizeFractionForReferenceDp(51), widthFraction = 0.278f),
    TouchControl("btn_r", "R", "button", VitaButton.R1, 1.016f, 0.094f, 51, 0.80f, shape = ControlShape.PILL, sizeFraction = sizeFractionForReferenceDp(51), widthFraction = 0.281f),
    TouchControl("btn_start_pill", "START", "button", VitaButton.START, 0.264f, 0.891f, 38, 0.80f, shape = ControlShape.PILL, sizeFraction = sizeFractionForReferenceDp(38), widthFraction = 0.346f),
    TouchControl("btn_tri", "Triángulo", "button", VitaButton.TRIANGLE, 0.959f, 0.376f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_sq", "Cuadrado", "button", VitaButton.SQUARE, 0.890f, 0.496f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_circ", "Círculo", "button", VitaButton.CIRCLE, 1.028f, 0.496f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_cross", "Cruz", "button", VitaButton.CROSS, 0.959f, 0.613f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.781f, 0.813f, 120, 0.25f, colorHex = "#7FB3D5", sizeFraction = sizeFractionForReferenceDp(120)),
)

fun oneHandLayout(): List<TouchControl> = listOf(
    // Una mano, 0.2.68 segunda pasada: el MISMO set de 10 controles
    // que el default (píldoras L/R, pausa, stick, píldora START,
    // rombo de cara △□○✕ de 60 dp de cristal, Igniculus), con sus
    // propias anclas de pulgar. Antes llevaba los 6 controles viejos
    // (espada/salto/círculo junto a la pausa): al cambiar de perfil
    // el usuario volvía a los controles que llamó "horribles".
    // - Cuadrado y cruz se quedan EXACTAMENTE donde estaban espada
    //   y salto ((0.83, 0.63) y (0.91, 0.79)): son los dos botones
    //   que el pulgar aprieta de memoria; solo cambia el id (el
    //   icono pasa de espada/flecha a su glifo) y el tamaño (77 ->
    //   60 dp de cristal, como en la imagen).
    // - Triángulo y círculo se derivan simétricos alrededor del
    //   centro del rombo (0.91, 0.63): △ (0.91, 0.47), ○ (0.99,
    //   0.63). El rombo queda más abierto que en la imagen (hueco
    //   +62 px entre adyacentes, frente a +15 px): es la apertura
    //   del pulgar, no un botón más grande (60 dp en los cuatro).
    // - L/R/START entran en sus posiciones de la imagen, visibles.
    // - Stick (0.05, 0.70) 174 dp, pausa (0.50, 0.07) 52 dp y zona
    //   (0.78, 0.80) 104 dp no se mueven: son los valores de una
    //   mano desde 0.2.63. Todos visibles.
    // Huecos verificados en el teléfono 2340x1080 (script réplica
    // 2026-11-12): cuadrado-cruz +62.4 px, triángulo-cuadrado
    // +62.4 px, cuadrado-orbe dibujado +0.6 px (las cajas táctiles
    // se solapan ~23 px como antes, pero los círculos dibujados no
    // se tocan), cruz-orbe dibujado +41.7 px.
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.70f, 174, 0.75f, sizeFraction = sizeFractionForReferenceDp(174)),
    TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 52, 0.70f, sizeFraction = sizeFractionForReferenceDp(52)),
    TouchControl("btn_l", "L", "button", VitaButton.L1, -0.012f, 0.094f, 51, 0.80f, shape = ControlShape.PILL, sizeFraction = sizeFractionForReferenceDp(51), widthFraction = 0.278f),
    TouchControl("btn_r", "R", "button", VitaButton.R1, 1.016f, 0.094f, 51, 0.80f, shape = ControlShape.PILL, sizeFraction = sizeFractionForReferenceDp(51), widthFraction = 0.281f),
    TouchControl("btn_start_pill", "START", "button", VitaButton.START, 0.264f, 0.891f, 38, 0.80f, shape = ControlShape.PILL, sizeFraction = sizeFractionForReferenceDp(38), widthFraction = 0.346f),
    TouchControl("btn_tri", "Triángulo", "button", VitaButton.TRIANGLE, 0.91f, 0.47f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_sq", "Cuadrado", "button", VitaButton.SQUARE, 0.83f, 0.63f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_circ", "Círculo", "button", VitaButton.CIRCLE, 0.99f, 0.63f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_cross", "Cruz", "button", VitaButton.CROSS, 0.91f, 0.79f, 60, 0.80f, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.78f, 0.80f, 104, 0.25f, colorHex = "#7FB3D5", sizeFraction = sizeFractionForReferenceDp(104)),
)

fun externalGamepadLayout(): List<TouchControl> = listOf(
    // Mando externo: mismas posiciones que el default neón 0.2.68;
    // stick, L/R, píldora START y rombo ocultos (los pone el mando
    // físico), pausa e Igniculus visibles, como antes. Los controles
    // ocultos espejan el default nuevo: si el usuario los vuelve a
    // mostrar, quedan donde los deja el default.
    TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.035f, 0.700f, 177, 0.70f, visible = false, sizeFraction = sizeFractionForReferenceDp(177)),
    TouchControl("btn_pause", "Pausa", "button", VitaButton.START, 0.498f, 0.071f, 50, 0.70f, sizeFraction = sizeFractionForReferenceDp(50)),
    TouchControl("btn_l", "L", "button", VitaButton.L1, -0.012f, 0.094f, 51, 0.80f, shape = ControlShape.PILL, visible = false, sizeFraction = sizeFractionForReferenceDp(51), widthFraction = 0.278f),
    TouchControl("btn_r", "R", "button", VitaButton.R1, 1.016f, 0.094f, 51, 0.80f, shape = ControlShape.PILL, visible = false, sizeFraction = sizeFractionForReferenceDp(51), widthFraction = 0.281f),
    TouchControl("btn_start_pill", "START", "button", VitaButton.START, 0.264f, 0.891f, 38, 0.80f, shape = ControlShape.PILL, visible = false, sizeFraction = sizeFractionForReferenceDp(38), widthFraction = 0.346f),
    TouchControl("btn_tri", "Triángulo", "button", VitaButton.TRIANGLE, 0.959f, 0.376f, 60, 0.80f, visible = false, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_sq", "Cuadrado", "button", VitaButton.SQUARE, 0.890f, 0.496f, 60, 0.80f, visible = false, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_circ", "Círculo", "button", VitaButton.CIRCLE, 1.028f, 0.496f, 60, 0.80f, visible = false, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("btn_cross", "Cruz", "button", VitaButton.CROSS, 0.959f, 0.613f, 60, 0.80f, visible = false, sizeFraction = sizeFractionForReferenceDp(60)),
    TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.781f, 0.813f, 120, 0.25f, colorHex = "#7FB3D5", sizeFraction = sizeFractionForReferenceDp(120)),
)

fun builtInProfiles(): List<ControlProfile> = listOf(
    ControlProfile("default-g99", "Por defecto G99", true, defaultTouchLayout()),
    ControlProfile("one-hand", "Una mano", true, oneHandLayout()),
    ControlProfile("external-gamepad", "Mando externo + táctil mínimo", true, externalGamepadLayout()),
)

/**
 * Perfiles de control guardados en DataStore.
 * Formato: perfiles separados por "\n###\n"; cada perfil es
 * "id|nombre|isBuiltIn\n<controles>"; controles separados por ";"
 * y con 16 campos separados por "|" (0.2.64, A-7: el 15º es
 * sizeFraction, vacío = null; 0.2.68: el 16º es widthFraction,
 * vacío = null). Los nombres se sanejan
 * (sin |, ;, # ni saltos de línea) al guardar.
 * Compatible hacia atrás: el formato antiguo de 7 campos por control
 * se lee con valores por defecto para los campos nuevos.
 */
class TouchLayoutRepository(private val context: Context) {

    private object Keys {
        val PROFILES = stringPreferencesKey("control_profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("active_control_profile")
    }

    // B6 (auditoría panel táctil): todos los guardados son
    // read-modify-write sobre DataStore (leer perfiles -> modificar
    // -> guardar). Sin mutex, dos guardados concurrentes — p. ej.
    // cada tick de un slider de tamaño/opacidad, o el soltar de un
    // arrastre que llegaba a la vez que un tick — se basaban en la
    // misma lectura y el último en terminar pisaba al otro: se
    // perdía la edición final (el slider "no persistía" o volvía
    // a un valor anterior). Todas las mutaciones públicas pasan
    // por este mutex; los helpers privados asumen que ya se tiene.
    private val mutationMutex = Mutex()

    // R4-P0-1: la migración del rombo se dispara una sola vez por
    // instancia del repositorio, al cargar los perfiles por primera
    // vez (onStart de los flujos). El guardado en sí es atómico en
    // DataStore (ver migrateSavedBuiltInProfilesOnce): dos
    // instancias que migren a la vez escriben lo mismo.
    private val diamondMigrationStarted = AtomicBoolean(false)

    val profilesFlow: Flow<List<ControlProfile>> = context.touchDataStore.data.map { prefs ->
        decodeProfiles(prefs[Keys.PROFILES] ?: "") ?: builtInProfiles()
    }.onStart {
        migrateSavedBuiltInProfilesOnce()
    }.map { profiles ->
        // En memoria el layout limpio 0.2.54 ya está aplicado
        // aunque el guardado en DataStore aún no haya aterrizado:
        // el juego y el editor nunca ven el rombo viejo. Los
        // perfiles que el usuario modificó siguen con sus
        // migraciones viejas (rombo + valores de fábrica).
        migrateBuiltInProfiles(profiles)
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore touch (perfiles) ilegible, usando fábrica: ${e.message}")
        emit(builtInProfiles())
    }

    val activeProfileIdFlow: Flow<String> = context.touchDataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_PROFILE] ?: "default-g99"
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore touch (activo) ilegible, usando default-g99: ${e.message}")
        emit("default-g99")
    }

    /** Layout del perfil activo (compatibilidad con la API anterior). */
    val layoutFlow: Flow<List<TouchControl>> = context.touchDataStore.data.map { prefs ->
        val profiles = decodeProfiles(prefs[Keys.PROFILES] ?: "") ?: builtInProfiles()
        val activeId = prefs[Keys.ACTIVE_PROFILE] ?: "default-g99"
        val active = profiles.firstOrNull { it.id == activeId }
        if (active != null) migrateBuiltInProfile(active).controls else defaultTouchLayout()
    }.onStart {
        migrateSavedBuiltInProfilesOnce()
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore touch (layout) ilegible, usando default: ${e.message}")
        emit(defaultTouchLayout())
    }

    /**
     * R4-P0-1/R5-A1-1/0.2.54/0.2.57/0.2.58: reescribe en DataStore los perfiles
     * predefinidos guardados con su layout viejo de fábrica (los
     * sustituye por el layout limpio) y, si el usuario los
     * modificó, con el rombo transpuesto (lo deja con el rombo
     * estándar) y con los valores viejos de fábrica del stick y
     * la Igniculus (los normaliza a 104 dp y a y 0.55, o a los
     * tamaños nuevos 0.2.57: stick 186 dp, zona 115 dp, o a los
     * valores nuevos 0.2.58: espada 0.914 y salto 1.012, 92 dp).
     * Solo corre una vez por instancia; es idempotente
     * (la segunda vez ya no hay nada que migrar y no escribe).
     * La lectura, la migración y la escritura ocurren dentro de un
     * solo edit de DataStore, así que dos migraciones concurrentes
     * no pueden pisar una edición del usuario hecha entre medias.
     * Los perfiles personalizados (isBuiltIn == false) no se tocan.
     */
    private suspend fun migrateSavedBuiltInProfilesOnce() {
        if (!diamondMigrationStarted.compareAndSet(false, true)) return
        mutationMutex.withLock {
            safeEdit("migración layout limpio predefinido") { prefs ->
                val raw = prefs[Keys.PROFILES] ?: return@safeEdit
                val profiles = decodeProfiles(raw) ?: return@safeEdit
                val migrated = migrateBuiltInProfiles(profiles)
                if (migrated != profiles) {
                    prefs[Keys.PROFILES] = migrated.joinToString("\n###\n") { encodeProfile(it) }
                    Log.i("ChildOfLight", "0.2.58: perfil predefinido migrado (layout limpio + posiciones y tamaños nuevos) en DataStore")
                }
            }
        }
    }

    /**
     * P1-18: un fallo de E/S al escribir (disco lleno, archivo corrupto)
     * no puede tumbar la app: viewModelScope tiene SupervisorJob y una
     * excepción no capturada en un hijo mata el proceso. Toda escritura
     * DataStore pasa por aquí: registra el fallo y devuelve false para
     * que el llamador pueda avisar.
     */
    private suspend fun safeEdit(tag: String, transform: suspend (MutablePreferences) -> Unit): Boolean {
        return try {
            context.touchDataStore.edit(transform)
            true
        } catch (e: Exception) {
            Log.w("ChildOfLight", "DataStore $tag no se pudo escribir: ${e.message}")
            false
        }
    }

    suspend fun save(layout: List<TouchControl>): Boolean {
        return mutationMutex.withLock { saveLocked(layout) }
    }

    /**
     * Añade un control al perfil activo de forma atómica. Antes el
     * ViewModel leía el layout, añadía y guardaba en tres pasos: dos
     * toques rápidos a "+ Añadir botón" leían el mismo layout y el
     * segundo guardado pisaba al primero (un botón no aparecía).
     */
    suspend fun addControl(control: TouchControl) {
        mutationMutex.withLock { saveLocked(currentActiveControls() + control) }
    }

    /**
     * Borra un control del perfil activo de forma atómica. Devuelve
     * false si ese id no estaba (no se toca nada).
     */
    suspend fun deleteControl(controlId: String): Boolean {
        return mutationMutex.withLock {
            val current = currentActiveControls()
            val updated = current.filter { it.id != controlId }
            if (updated.size == current.size) return@withLock false
            // P1-18: si el guardado falla, el borrado no ocurrió: no
            // se puede devolver true.
            saveLocked(updated)
        }
    }

    private suspend fun saveLocked(layout: List<TouchControl>): Boolean {
        val profiles = currentProfiles()
        val activeId = currentActiveId()
        if (profiles.none { it.id == activeId }) {
            // BUG (auditoría): si el perfil activo había sido borrado (antes
            // del fix de deleteProfile), save() no guardaba nada en silencio.
            // Auto-reparación: se guarda en el primer perfil y se activa.
            if (profiles.isEmpty()) return false
            val updated = profiles.mapIndexed { index, p ->
                if (index == 0) p.copy(controls = layout) else p
            }
            if (!saveProfiles(updated)) return false
            return setActiveProfileLocked(profiles.first().id)
        }
        val updated = profiles.map { if (it.id == activeId) it.copy(controls = layout) else it }
        return saveProfiles(updated)
    }

    /** Controles del perfil activo (o del primero si el activo no existe). */
    private suspend fun currentActiveControls(): List<TouchControl> {
        val profiles = currentProfiles()
        val activeId = currentActiveId()
        return profiles.firstOrNull { it.id == activeId }?.controls
            ?: profiles.firstOrNull()?.controls ?: emptyList()
    }

    private suspend fun setActiveProfileLocked(id: String): Boolean {
        return safeEdit("perfil activo") { prefs -> prefs[Keys.ACTIVE_PROFILE] = id }
    }

    suspend fun reset() {
        mutationMutex.withLock {
            saveProfiles(builtInProfiles())
            setActiveProfileLocked("default-g99")
        }
    }

    suspend fun setActiveProfile(id: String) {
        mutationMutex.withLock { setActiveProfileLocked(id) }
    }

    suspend fun createProfile(name: String, fromControls: List<TouchControl>): ControlProfile {
        return mutationMutex.withLock {
            val cleanName = sanitizeName(name).ifBlank { "Perfil nuevo" }
            val profile = ControlProfile(
                id = newProfileId(),
                name = cleanName,
                isBuiltIn = false,
                controls = fromControls.map { it.copy() },
            )
            saveProfiles(currentProfiles() + profile)
            setActiveProfileLocked(profile.id)
            profile
        }
    }

    suspend fun duplicateProfile(id: String): ControlProfile? {
        return mutationMutex.withLock {
            val source = currentProfiles().firstOrNull { it.id == id } ?: return@withLock null
            val copy = source.copy(
                id = newProfileId(),
                name = sanitizeName("${source.name} (copia)"),
                isBuiltIn = false,
            )
            saveProfiles(currentProfiles() + copy)
            copy
        }
    }

    suspend fun renameProfile(id: String, newName: String): Boolean {
        return mutationMutex.withLock {
            val clean = sanitizeName(newName)
            if (clean.isBlank()) return@withLock false
            val profiles = currentProfiles()
            if (profiles.none { it.id == id }) return@withLock false
            saveProfiles(profiles.map { if (it.id == id) it.copy(name = clean) else it })
            true
        }
    }

    /**
     * Borra un perfil. No se puede borrar el último perfil que quede;
     * si se borra el activo, el activo pasa al primer perfil que queda.
     */
    suspend fun deleteProfile(id: String): Boolean {
        return mutationMutex.withLock {
            val profiles = currentProfiles()
            if (profiles.size <= 1) return@withLock false
            if (profiles.none { it.id == id }) return@withLock false
            val remaining = profiles.filter { it.id != id }
            // R5-A4-5: si el guardado falla, el borrado no ocurrió:
            // no se puede devolver true ni tocar el perfil activo.
            if (!saveProfiles(remaining)) return@withLock false
            // BUG (auditoría): antes se forzaba "default-g99" como activo; si
            // ese perfil ya había sido borrado, el activo apuntaba a un perfil
            // inexistente y save() no guardaba nada en silencio.
            if (currentActiveId() == id) {
                // R5-A4-5: el cambio de activo también puede fallar
                // (disco lleno): su Boolean decide el resultado.
                return@withLock setActiveProfileLocked(remaining.first().id)
            }
            return@withLock true
        }
    }

    /** Exporta un perfil como texto (mismo formato de guardado, un solo perfil). */
    fun exportProfileText(profile: ControlProfile): String = encodeProfile(profile)

    /** Importa un perfil desde texto exportado. Devuelve el perfil creado o null si el texto no es válido. */
    suspend fun importProfileText(raw: String): ControlProfile? {
        return mutationMutex.withLock {
            val parsed = decodeProfile(raw.trim()) ?: return@withLock null
            val profile = parsed.copy(
                id = newProfileId(),
                isBuiltIn = false,
                name = sanitizeName(parsed.name).ifBlank { "Perfil importado" },
            )
            saveProfiles(currentProfiles() + profile)
            profile
        }
    }

    suspend fun restoreBuiltIns() {
        mutationMutex.withLock {
            val current = currentProfiles()
            val missing = builtInProfiles().filter { built -> current.none { it.id == built.id } }
            if (missing.isNotEmpty()) saveProfiles(current + missing)
        }
    }

    private suspend fun currentProfiles(): List<ControlProfile> {
        val raw = context.touchDataStore.data.first()[Keys.PROFILES] ?: ""
        val profiles = decodeProfiles(raw) ?: builtInProfiles()
        // R4-P0-1/0.2.54: en memoria los predefinidos ya llevan el
        // layout limpio (el guardado lo hace
        // migrateSavedBuiltInProfilesOnce al cargar, y saveProfiles
        // al escribir). Así duplicar un predefinido aún sin migrar
        // en disco no hereda el layout viejo.
        return migrateBuiltInProfiles(profiles)
    }

    private suspend fun currentActiveId(): String {
        return context.touchDataStore.data.first()[Keys.ACTIVE_PROFILE] ?: "default-g99"
    }

    private suspend fun saveProfiles(profiles: List<ControlProfile>): Boolean {
        // R4-P0-1/0.2.54: red de seguridad: cualquier guardado de
        // perfiles deja los predefinidos con el layout limpio (la
        // migración en sí es pura e idempotente; los personalizados
        // no se tocan).
        val normalized = migrateBuiltInProfiles(profiles)
        return safeEdit("perfiles de controles") { prefs ->
            prefs[Keys.PROFILES] = normalized.joinToString("\n###\n") { encodeProfile(it) }
        }
    }

    companion object {
        // BUG (auditoría): "custom-" + currentTimeMillis() colisiona si se
        // crean dos perfiles en el mismo milisegundo. Se añade nanoTime.
        private fun newProfileId(): String =
            "custom-${System.currentTimeMillis()}-${System.nanoTime() % 100000}"

        /**
         * 0.2.54: layouts viejos de fábrica de cada perfil
         * predefinido, en sus DOS formas guardadas: el rombo
         * transpuesto de 0.2.49 (stick 140/120 dp, Igniculus en y
         * 0.35 / (0.55, 0.28)) y el rombo estándar posterior
         * (stick 104 dp, Igniculus en y 0.55 / 0.50). Un perfil
         * guardado puede estar en cualquiera de las dos: la
         * migración del rombo ya lo habría normalizado a la
         * segunda al cargar, pero esta pasada corre ANTES que ella
         * y no puede contar con eso.
         */
        private fun legacyFactoryLayouts(profileId: String): List<List<TouchControl>> {
            val transposed: List<TouchControl>
            val standard: List<TouchControl>
            when (profileId) {
                "one-hand" -> {
                    transposed = listOf(
                        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 120, 0.75f),
                        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
                        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
                        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.72f, 0.87f, 64, 0.80f),
                        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.84f, 0.78f, 72, 0.85f),
                        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
                        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.55f, 0.28f, 160, 0.25f, colorHex = "#7FB3D5"),
                    )
                    standard = listOf(
                        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.20f, 0.75f, 104, 0.75f),
                        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.76f, 0.65f, 64, 0.80f, visible = false),
                        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.69f, 0.74f, 64, 0.80f, visible = false),
                        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.83f, 0.74f, 64, 0.80f),
                        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.76f, 0.83f, 72, 0.85f),
                        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f, visible = false),
                        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.50f, 160, 0.25f, colorHex = "#7FB3D5"),
                    )
                }
                "external-gamepad" -> {
                    transposed = listOf(
                        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f, visible = false),
                        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f, visible = false),
                        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f, visible = false),
                        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f, visible = false),
                        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f, visible = false),
                        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
                        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
                    )
                    standard = listOf(
                        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 104, 0.70f, visible = false),
                        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.80f, 0.62f, 64, 0.80f, visible = false),
                        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.73f, 0.71f, 64, 0.80f, visible = false),
                        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.87f, 0.71f, 64, 0.80f, visible = false),
                        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f, visible = false),
                        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
                        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.55f, 220, 0.25f, colorHex = "#7FB3D5"),
                    )
                }
                else -> {
                    transposed = listOf(
                        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 140, 0.70f),
                        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.70f, 0.70f, 64, 0.80f),
                        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.78f, 0.62f, 64, 0.80f),
                        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.78f, 0.80f, 64, 0.80f),
                        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.87f, 0.71f, 64, 0.85f),
                        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
                        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.35f, 220, 0.25f, colorHex = "#7FB3D5"),
                    )
                    standard = listOf(
                        TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.18f, 0.72f, 104, 0.70f),
                        TouchControl("btn_y", "Y", "button", VitaButton.TRIANGLE, 0.80f, 0.62f, 64, 0.80f),
                        TouchControl("btn_x", "X", "button", VitaButton.SQUARE, 0.73f, 0.71f, 64, 0.80f),
                        TouchControl("btn_b", "B", "button", VitaButton.CIRCLE, 0.87f, 0.71f, 64, 0.80f),
                        TouchControl("btn_a", "A", "button", VitaButton.CROSS, 0.80f, 0.80f, 64, 0.85f),
                        TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.06f, 56, 0.70f),
                        TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.50f, 0.55f, 220, 0.25f, colorHex = "#7FB3D5"),
                    )
                }
            }
            return listOf(transposed, standard)
        }

        /**
         * 0.2.65 (M-P2-4): igualdad de layout por campos
         * relevantes, no por igualdad total de data class. El
         * formato antiguo de 7 campos no guardaba colorHex ni
         * visible: al decodificar, Igniculus queda en "#8CCBEB"
         * (default) en vez de "#7FB3D5" y todos los controles en
         * visible = true, así que un perfil de 7 campos no
         * coincidía con NINGÚN snapshot y nunca llegaba al layout
         * limpio. Esos dos campos son los únicos que se ignoran:
         * el formato de 7 campos no los podía guardar, así que en
         * un perfil así nunca son una edición del usuario; todo
         * lo demás (id, kind, botón, posición, tamaño, fracción,
         * etiqueta, opacidad, forma y modos) sigue siendo guarda:
         * si el usuario movió, redimensionó, remapeó o renombró
         * aunque sea un control, el perfil es suyo y no migra.
         */
        private fun matchesFactoryLayout(snapshot: List<TouchControl>?, controls: List<TouchControl>): Boolean {
            if (snapshot == null) return false
            if (snapshot.size != controls.size) return false
            return snapshot.zip(controls).all { (a, b) ->
                a.id == b.id &&
                    a.kind == b.kind &&
                    a.vitaButton == b.vitaButton &&
                    a.xFraction == b.xFraction &&
                    a.yFraction == b.yFraction &&
                    a.sizeDp == b.sizeDp &&
                    a.sizeFraction == b.sizeFraction &&
                    a.widthFraction == b.widthFraction &&
                    a.label == b.label &&
                    a.opacity == b.opacity &&
                    a.shape == b.shape &&
                    a.stickMode == b.stickMode &&
                    a.igniculusMode == b.igniculusMode &&
                    a.sensitivity == b.sensitivity
            }
        }

        /**
         * 0.2.54: migra UN perfil predefinido de su layout viejo de
         * fábrica (rombo, transpuesto o estándar) al layout limpio
         * de fábrica. También migra un perfil que ya lleva el
         * layout limpio de 5 controles (sin btn_circle, forma en
         * la que pudo quedar guardado en 0.2.54 antes del círculo):
         * recibe el ○ junto a la pausa. 0.2.57: también migra un
         * perfil que ya lleva el layout limpio de 0.2.54/0.2.55/
         * 0.2.56 con los tamaños viejos (stick 210 dp, zona
         * Igniculus 156 dp, en 6 controles o en 5 sin círculo) a
         * los tamaños nuevos (stick 186 dp, zona 115 dp; espada,
         * salto, pausa y círculo no cambian). 0.2.58: también migra un
         * perfil que ya lleva el layout limpio de 0.2.57 (espada y
         * salto en 82 dp en (0.864, 0.576) y (0.93, 0.752)) a las
         * posiciones y tamaños nuevos (espada 0.914 y salto 1.012,
         * 92 dp; solo el default y el externo cambian: una mano
         * conserva espada/salto en 82 dp). 0.2.63 (A-1/A-6): también
         * migra un perfil que ya lleva el layout limpio de 0.2.62
         * (stick 186 dp, espada/salto 92 dp en default y externo y
         * 82 dp en una mano, pausa 56 dp, círculo 44 dp, zona
         * 115 dp) a los tamaños nuevos recalibrados con la densidad
         * real del teléfono (1080x2340, factor 2.80; stick 174 dp,
         * espada 86 dp, salto 87 dp, pausa 52 dp, círculo 41 dp,
         * zona 104 dp; una mano 77 dp en espada/salto). Sin este
         * snapshot, cambiar defaultTouchLayout() no cambiaría nada
         * en el teléfono: el perfil guardado ya es el "nuevo" de
         * 0.2.62 y no coincidiría con ningún layout viejo. Solo actúa si el
         * perfil es predefinido Y sus controles son EXACTAMENTE
         * uno de los layouts viejos de fábrica de su id o el limpio
         * de 5 o el limpio con tamaños viejos o el limpio de 0.2.57
         * o el limpio de 0.2.62:
         * si el usuario movió,
         * redimensionó, remapeó o añadió aunque sea un
         * control, el perfil es suyo y no se toca (y siguen sus
         * migraciones actuales: rombo + valores de fábrica, que se
         * mantienen). Ocultar un control o cambiar su color ya no
         * bloquea la migración (0.2.65 M-P2-4): el formato de 7
         * campos no guardaba esos dos campos y sin ignorarlos un
         * perfil así quedaba atascado para siempre. Contrapartida:
         * un perfil que solo difiera de fábrica en color o
         * visibilidad migrará y los reseteará a fábrica.
         * Idempotente: un perfil ya limpio con los
         * tamaños nuevos no coincide con ningún layout viejo y se
         * devuelve tal cual. Puro Kotlin, sin Context: testeable.
         */
        fun migrateBuiltInToCleanLayout(profile: ControlProfile): ControlProfile {
            if (!profile.isBuiltIn) return profile
            val clean = when (profile.id) {
                "default-g99" -> defaultTouchLayout()
                "one-hand" -> oneHandLayout()
                "external-gamepad" -> externalGamepadLayout()
                else -> return profile
            }
            val withoutCircle = cleanLayoutWithoutCircle(profile.id)
            val oldSizesClean = cleanLayoutWithOldSizes(profile.id)
            val oldSizesWithoutCircle = oldSizesClean?.filter { it.id != "btn_circle" }
            val oldSizes057 = cleanLayoutWith057SwordJump(profile.id)
            val oldSizes062 = cleanLayoutWith062Sizes(profile.id)
            if (legacyFactoryLayouts(profile.id).none { matchesFactoryLayout(it, profile.controls) } &&
                !matchesFactoryLayout(withoutCircle, profile.controls) &&
                !matchesFactoryLayout(oldSizesClean, profile.controls) &&
                !matchesFactoryLayout(oldSizesWithoutCircle, profile.controls) &&
                !matchesFactoryLayout(oldSizes057, profile.controls) &&
                !matchesFactoryLayout(oldSizes062, profile.controls)
            ) return profile
            return profile.copy(controls = clean)
        }

        /**
         * 0.2.68 (neón): el layout limpio de 0.2.63-0.2.67 (6
         * controles), base de los snapshots viejos de fábrica
         * (cleanLayoutWithoutCircle, cleanLayoutWithOldSizes,
         * cleanLayoutWith057SwordJump, cleanLayoutWith062Sizes).
         * ANTES estos builders partían del layout nuevo de
         * fábrica; con el layout neón (10 controles, otros ids)
         * devolvían snapshots de 10 controles que ningún perfil
         * guardado tiene y los perfiles de 0.2.54-0.2.62 dejaban
         * de migrar. La base es ahora el layout 0.2.67: para
         * default es neonPredecessorLayout, para una mano es
         * neonPredecessorOneHandLayout (el layout 0.2.67 de 6
         * controles de una mano; oneHandLayout() ya es el neón
         * de 10 desde la segunda pasada) y para externo es
         * el mismo layout 0.2.67 con los controles de acción
         * ocultos. Null para ids desconocidos.
         */
        private fun cleanLayout067(profileId: String): List<TouchControl>? = when (profileId) {
            "default-g99" -> neonPredecessorLayout()
            "one-hand" -> neonPredecessorOneHandLayout()
            "external-gamepad" -> neonPredecessorLayout().map {
                if (it.id == "stick" || it.id == "btn_sword" || it.id == "btn_jump" || it.id == "btn_circle") it.copy(visible = false) else it
            }
            else -> null
        }

        /**
         * 0.2.54 (círculo): el layout limpio de 5 controles, sin
         * btn_circle — la forma en la que un perfil predefinido
         * pudo quedar guardado si migró en 0.2.54 antes del
         * círculo. Se construye desde el layout nuevo quitando
         * btn_circle, así no se duplica la lista: si el usuario
         * tocó aunque sea un control, ya no coincide y la
         * migración no lo toca. Null para ids desconocidos (nunca
         * coincide con un perfil guardado).
         * 0.2.64 (A-7, run #384): el snapshot lleva sizeFraction
         * a null: un perfil guardado antes de la 0.2.64 no trae
         * fracción (formato de 14 campos) y con la fracción del
         * layout nuevo la igualdad nunca coincidía.
         */
        private fun cleanLayoutWithoutCircle(profileId: String): List<TouchControl>? {
            val clean = cleanLayout067(profileId) ?: return null
            return clean.filter { it.id != "btn_circle" }.map { it.copy(sizeFraction = null) }
        }

        /**
         * 0.2.57: el layout limpio de 6 controles con los tamaños
         * viejos de 0.2.54/0.2.55/0.2.56 (stick 210 dp, zona
         * Igniculus 156 dp). 0.2.58: también con espada y salto
         * en sus valores viejos de 0.2.57 (82 dp en (0.864, 0.576)
         * y (0.93, 0.752)). 0.2.63 (run #337): la recalibración
         * cambió los tamaños nuevos (espada 86 dp, salto 87 dp,
         * pausa 52 dp, círculo 41 dp; una mano 77 dp), y las
         * vueltas atrás de espada/salto estaban condicionadas a
         * `sizeDp == 92` (el tamaño viejo de 0.2.62): con 86/87 dp
         * la condición ya no se cumplía nunca y el builder
         * devolvía el layout nuevo con stick 210 y zona 156, que
         * ningún perfil guardado tiene — la guarda de
         * migrateBuiltInToCleanLayout no coincidía y los perfiles
         * limpios de 0.2.54-0.2.56 no migraban (4 tests rojos en
         * el run #337). Ahora revierte TODOS los tamaños (y las
         * posiciones de espada/salto del default/externo) de forma
         * explícita por id, como cleanLayoutWith062Sizes: pausa 56
         * dp y círculo 44 dp también vuelven atrás (en 0.2.63 son
         * 52/41 dp), y en una mano espada/salto vuelven a 82 dp
         * en sus posiciones (0.83, 0.63) y (0.91, 0.79), que nunca
         * cambiaron. Pausa y círculo iguales que ahora. Se
         * construye desde el layout nuevo con los valores vueltos
         * atrás, así no se duplica la lista: visibilidades y
         * opacidades son las mismas en las dos versiones. Null
         * para ids desconocidos (nunca coincide con un perfil
         * guardado).
         */
        private fun cleanLayoutWithOldSizes(profileId: String): List<TouchControl>? {
            val clean = cleanLayout067(profileId) ?: return null
            // 0.2.64 (A-7, run #384): cada copia lleva
            // sizeFraction a null: un perfil guardado antes de la
            // 0.2.64 no trae fracción (formato de 14 campos) y con
            // la fracción del layout nuevo la igualdad nunca
            // coincidía.
            return clean.map { control ->
                when (control.id) {
                    "stick" -> control.copy(sizeDp = 210, sizeFraction = null)
                    "igniculus" -> control.copy(sizeDp = 156, sizeFraction = null)
                    "btn_sword" -> if (control.xFraction == 0.914f) {
                        control.copy(xFraction = 0.864f, sizeDp = 82, sizeFraction = null)
                    } else {
                        control.copy(sizeDp = 82, sizeFraction = null)
                    }
                    "btn_jump" -> if (control.xFraction == 1.012f) {
                        control.copy(xFraction = 0.93f, sizeDp = 82, sizeFraction = null)
                    } else {
                        control.copy(sizeDp = 82, sizeFraction = null)
                    }
                    "btn_start" -> control.copy(sizeDp = 56, sizeFraction = null)
                    "btn_circle" -> control.copy(sizeDp = 44, sizeFraction = null)
                    else -> control.copy(sizeFraction = null)
                }
            }
        }

        /**
         * 0.2.58: el layout limpio de 6 controles con los valores
         * de 0.2.57 para espada y salto (82 dp en (0.864, 0.576)
         * y (0.93, 0.752)): la forma en la que queda guardado un
         * perfil predefinido de una instalación 0.2.57. Solo el
         * default y el externo cambian en 0.2.58: una mano lleva
         * espada/salto en (0.83, 0.63) y (0.91, 0.79) con 82 dp y
         * no coincide con los valores nuevos, así que para ella
         * este layout ES el nuevo y la coincidencia es un no-op.
         * 0.2.63 (run #337): misma podredumbre que
         * cleanLayoutWithOldSizes — las vueltas atrás de
         * espada/salto estaban condicionadas a `sizeDp == 92` y
         * stick/zona/pausa/círculo no se revertían, así que el
         * builder devolvía el layout nuevo tal cual (rama muerta:
         * ningún perfil guardado coincide con el layout nuevo).
         * Ahora revierte TODOS los valores a los de 0.2.57 de
         * forma explícita por id, como cleanLayoutWith062Sizes:
         * stick 186 dp, zona 115 dp, pausa 56 dp, círculo 44 dp,
         * espada/salto 82 dp (en (0.864, 0.576)/(0.93, 0.752) en
         * default/externo y en (0.83, 0.63)/(0.91, 0.79) en una
         * mano). Se construye desde el layout nuevo revirtiendo
         * solo esos valores, así no se duplica la lista. Null para
         * ids desconocidos (nunca coincide con un perfil guardado).
         */
        private fun cleanLayoutWith057SwordJump(profileId: String): List<TouchControl>? {
            val clean = cleanLayout067(profileId) ?: return null
            // 0.2.64 (A-7, run #384): cada copia lleva
            // sizeFraction a null: un perfil guardado antes de la
            // 0.2.64 no trae fracción (formato de 14 campos) y con
            // la fracción del layout nuevo la igualdad nunca
            // coincidía.
            return clean.map { control ->
                when (control.id) {
                    "stick" -> control.copy(sizeDp = 186, sizeFraction = null)
                    "igniculus" -> control.copy(sizeDp = 115, sizeFraction = null)
                    "btn_sword" -> if (control.xFraction == 0.914f) {
                        control.copy(xFraction = 0.864f, sizeDp = 82, sizeFraction = null)
                    } else {
                        control.copy(sizeDp = 82, sizeFraction = null)
                    }
                    "btn_jump" -> if (control.xFraction == 1.012f) {
                        control.copy(xFraction = 0.93f, sizeDp = 82, sizeFraction = null)
                    } else {
                        control.copy(sizeDp = 82, sizeFraction = null)
                    }
                    "btn_start" -> control.copy(sizeDp = 56, sizeFraction = null)
                    "btn_circle" -> control.copy(sizeDp = 44, sizeFraction = null)
                    else -> control.copy(sizeFraction = null)
                }
            }
        }

        /**
         * 0.2.63 (A-1/A-6): el layout limpio de 6 controles con los
         * tamaños de 0.2.62 (stick 186 dp, espada/salto 92 dp en
         * default y externo y 82 dp en una mano, pausa 56 dp,
         * círculo 44 dp, zona 115 dp): la forma en la que queda
         * guardado un perfil predefinido de una instalación 0.2.62.
         * Las posiciones son las mismas que las nuevas (la
         * recalibración 0.2.63 solo cambia tamaños: la densidad
         * supuesta era 1.75 y la real es 2.80). Se construye desde
         * el layout nuevo revirtiendo solo los tamaños, así no se
         * duplica la lista: visibilidades, opacidades y posiciones
         * son las mismas en las dos versiones. Null para ids
         * desconocidos (nunca coincide con un perfil guardado).
         */
        private fun cleanLayoutWith062Sizes(profileId: String): List<TouchControl>? {
            val clean = cleanLayout067(profileId) ?: return null
            // 0.2.64 (A-7, run #384): cada copia lleva
            // sizeFraction a null: un perfil guardado antes de la
            // 0.2.64 no trae fracción (formato de 14 campos) y con
            // la fracción del layout nuevo la igualdad nunca
            // coincidía.
            return clean.map { control ->
                when (control.id) {
                    "stick" -> control.copy(sizeDp = 186, sizeFraction = null)
                    "igniculus" -> control.copy(sizeDp = 115, sizeFraction = null)
                    "btn_sword" -> control.copy(sizeDp = if (profileId == "one-hand") 82 else 92, sizeFraction = null)
                    "btn_jump" -> control.copy(sizeDp = if (profileId == "one-hand") 82 else 92, sizeFraction = null)
                    "btn_start" -> control.copy(sizeDp = 56, sizeFraction = null)
                    "btn_circle" -> control.copy(sizeDp = 44, sizeFraction = null)
                    else -> control.copy(sizeFraction = null)
                }
            }
        }

        /**
         * R4-P0-1: un hueco del rombo de botones de cara: id del
         * control, botón de Vita que lleva de fábrica, posición
         * antigua (rombo transpuesto) y posición nueva (estándar).
         */
        private data class FaceSlot(
            val id: String,
            val button: VitaButton,
            val oldX: Float,
            val oldY: Float,
            val newX: Float,
            val newY: Float,
        )

        // Rombo transpuesto anterior (TRIANGLE izquierda, SQUARE
        // arriba, CIRCLE abajo, CROSS derecha) y rombo estándar
        // nuevo, centrados en (0.80, 0.71). Lo comparten el perfil
        // por defecto y el de mando externo (mismas posiciones).
        private val DEFAULT_FACE_SLOTS = listOf(
            FaceSlot("btn_y", VitaButton.TRIANGLE, 0.70f, 0.70f, 0.80f, 0.62f),
            FaceSlot("btn_x", VitaButton.SQUARE, 0.78f, 0.62f, 0.73f, 0.71f),
            FaceSlot("btn_b", VitaButton.CIRCLE, 0.78f, 0.80f, 0.87f, 0.71f),
            FaceSlot("btn_a", VitaButton.CROSS, 0.87f, 0.71f, 0.80f, 0.80f),
        )

        // "Una mano" tenía la misma transposición, con el rombo
        // centrado en (0.76, 0.74) y el mismo radio (0.07 en X,
        // 0.09 en Y): se conserva su carácter compacto.
        private val ONE_HAND_FACE_SLOTS = listOf(
            FaceSlot("btn_y", VitaButton.TRIANGLE, 0.70f, 0.70f, 0.76f, 0.65f),
            FaceSlot("btn_x", VitaButton.SQUARE, 0.78f, 0.62f, 0.69f, 0.74f),
            FaceSlot("btn_b", VitaButton.CIRCLE, 0.72f, 0.87f, 0.83f, 0.74f),
            FaceSlot("btn_a", VitaButton.CROSS, 0.84f, 0.78f, 0.76f, 0.83f),
        )

        // Tolerancia al comparar posiciones guardadas: el formato
        // escribe el Float con toString y lo relee exacto, pero un
        // arrastre o un redondeo no debe impedir migrar por una
        // milésima. Un botón movido de verdad se aleja mucho más.
        private const val FACE_SLOT_EPSILON = 0.001f

        /**
         * R4-P0-1: migra UN perfil predefinido del rombo transpuesto
         * al estándar. Solo actúa si el perfil es predefinido
         * (isBuiltIn) Y los 4 botones de cara siguen en su hueco de
         * fábrica (mismo id, mismo botón de Vita y misma posición
         * antigua): si el usuario movió o remapeó aunque sea uno,
         * el perfil es suyo y no se toca. Idempotente: un perfil ya
         * migrado no coincide con las posiciones antiguas y se
         * devuelve tal cual. Puro Kotlin, sin Context: testeable.
         */
        fun migrateBuiltInFaceDiamond(profile: ControlProfile): ControlProfile {
            if (!profile.isBuiltIn) return profile
            val slots = when (profile.id) {
                "one-hand" -> ONE_HAND_FACE_SLOTS
                else -> DEFAULT_FACE_SLOTS
            }
            val byId = profile.controls.associateBy { it.id }
            for (slot in slots) {
                val control = byId[slot.id] ?: return profile
                if (control.vitaButton != slot.button) return profile
                if (abs(control.xFraction - slot.oldX) > FACE_SLOT_EPSILON) return profile
                if (abs(control.yFraction - slot.oldY) > FACE_SLOT_EPSILON) return profile
            }
            val newPositions = slots.associate { it.id to (it.newX to it.newY) }
            return profile.copy(
                controls = profile.controls.map { control ->
                    val pos = newPositions[control.id]
                    if (pos != null) control.copy(xFraction = pos.first, yFraction = pos.second) else control
                },
            )
        }

        /**
         * R5-A1-1/R5-A1-2: segunda pasada de la migración de
         * perfiles predefinidos guardados: normaliza el stick y la
         * Igniculus a los valores de fábrica actuales. Solo actúa
         * si el perfil es predefinido Y el control sigue en su
         * valor viejo de fábrica (misma guarda que el rombo, pero
         * por control: el stick y la Igniculus se juzgan por
         * separado, así que mover un botón de cara no bloquea esta
         * pasada ni al revés).
         * - stick 140 dp (default/externo) o 120 dp (una mano) ->
         *   104 dp, con su posición de fábrica intacta.
         * - Igniculus (0.50, 0.35) -> y 0.55 (default/externo);
         *   (0.55, 0.28) -> (0.50, 0.50) (una mano).
         * 0.2.57: tercera pasada, misma guarda por control: el
         * stick del layout limpio que sigue en 210 dp en su
         * posición limpia (0.05, 0.675; una mano 0.70) -> 186 dp,
         * y la zona Igniculus que sigue en 156 dp en su posición
         * limpia (0.82, 0.808; una mano 0.78, 0.80) -> 115 dp. Es
         * para el perfil predefinido que el usuario editó a medias
         * (movió un botón pero dejó el stick o la zona en fábrica):
         * el perfil entero ya no coincide con el layout viejo y no
         * pasa por la primera pasada, pero sus controles en fábrica
         * sí se achican.
         * Guarda 0.2.57: las dos ramas de arriba solo actúan si el
         * perfil YA trae btn_circle (layout limpio de 6 controles).
         * Un limpio de 5 controles sin círculo es un perfil
         * pre-círculo de 0.2.54: si el usuario lo editó, es suyo y
         * no se toca nada; si no lo editó, la primera pasada ya lo
         * convierte entero al default nuevo con los tamaños nuevos.
         * Las ramas viejas (stick 140/120 dp, Igniculus) no llevan
         * esta guarda: los layouts viejos del rombo no tienen
         * btn_circle y deben seguir normalizándose.
         * Si el usuario movió o redimensionó el control, no se
         * toca. Idempotente y puro: testeable.
         * 0.2.58: cuarta pasada, misma guarda por control y misma
         * guarda hasCircle: la espada del layout limpio que sigue
         * en (0.864, 0.576) con 82 dp -> (0.914, 0.576) con 92 dp,
         * y el salto que sigue en (0.93, 0.752) con 82 dp ->
         * (1.012, 0.752) con 92 dp. Solo default y externo (una
         * mano lleva espada y salto en otras posiciones y no
         * cambia en 0.2.58). Es para el perfil predefinido que el
         * usuario editó a medias (movió un botón pero dejó
         * espada/salto en fábrica): el perfil entero ya no
         * coincide con el layout viejo y no pasa por la primera
         * pasada, pero sus controles en fábrica sí se mueven.
         * 0.2.63 (A-1/A-6): quinta pasada, misma guarda por control
         * y misma guarda hasCircle: los tamaños de fábrica de
         * 0.2.62 (stick 186 dp, zona 115 dp, espada 92 dp, salto
         * 92 dp, pausa 56 dp, círculo 44 dp) en su posición limpia
         * se achican a los nuevos (stick 174 dp, zona 104 dp,
         * espada 86 dp, salto 87 dp, pausa 52 dp, círculo 41 dp).
         * Las ramas viejas 0.2.57/0.2.58 ahora apuntan también a
         * los tamaños nuevos (210 dp -> 174 dp, 156 dp -> 104 dp,
         * (0.864, 0.576) 82 dp -> (0.914, 0.576) 86 dp,
         * (0.93, 0.752) 82 dp -> (1.012, 0.752) 87 dp): un perfil
         * con el stick en 210 dp no debe quedarse en 186 dp, que
         * ya es un tamaño viejo. Una mano usa los mismos tamaños
         * nuevos en stick/zona/pausa/círculo (174/104/52/41 dp) y
         * 77 dp en espada/salto; sus ramas de espada/salto
         * (0.2.65, M-P2-2) bajan 82 dp -> 77 dp en (0.83, 0.63)
         * y (0.91, 0.79), igual que el resto de la pasada.
         */
        fun migrateBuiltInFactoryValues(profile: ControlProfile): ControlProfile {
            if (!profile.isBuiltIn) return profile
            // Guarda 0.2.57: solo los layouts limpios que ya pasaron
            // por la adición del círculo (6 controles) reciben el
            // achique por control de stick 210 -> 174 y zona
            // 156 -> 104. Un limpio de 5 sin btn_circle es
            // pre-círculo de 0.2.54 y, si el usuario lo editó, es
            // suyo y no se toca. 0.2.63: también el stick 186 dp y
            // la zona 115 dp de 0.2.62 en su posición limpia se
            // achican a 174 dp y 104 dp.
            val hasCircle = profile.controls.any { it.id == "btn_circle" }
            return profile.copy(
                controls = profile.controls.map { control ->
                    when (control.id) {
                        "stick" -> {
                            val oldSize = if (profile.id == "one-hand") 120 else 140
                            val oldX = if (profile.id == "one-hand") 0.20f else 0.18f
                            val oldY = if (profile.id == "one-hand") 0.75f else 0.72f
                            val cleanX = 0.05f
                            val cleanY = if (profile.id == "one-hand") 0.70f else 0.675f
                            if (control.kind == "stick" && control.sizeDp == oldSize &&
                                abs(control.xFraction - oldX) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - oldY) <= FACE_SLOT_EPSILON
                            ) {
                                control.copy(sizeDp = 104, sizeFraction = null)
                            } else if (hasCircle && control.kind == "stick" && control.sizeDp == 210 &&
                                abs(control.xFraction - cleanX) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - cleanY) <= FACE_SLOT_EPSILON
                            ) {
                                // 0.2.57: stick del layout limpio con
                                // el tamaño viejo de fábrica (210 dp)
                                // en su posición limpia: 174 dp
                                // (0.2.63: el destino ya no es 186 dp,
                                // que es el tamaño viejo de 0.2.62).
                                control.copy(sizeDp = 174, sizeFraction = null)
                            } else if (hasCircle && control.kind == "stick" && control.sizeDp == 186 &&
                                abs(control.xFraction - cleanX) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - cleanY) <= FACE_SLOT_EPSILON
                            ) {
                                // 0.2.63: stick del layout limpio de
                                // 0.2.62 (186 dp) en su posición
                                // limpia, en un perfil editado a
                                // medias: 174 dp.
                                control.copy(sizeDp = 174, sizeFraction = null)
                            } else {
                                control
                            }
                        }
                        "igniculus" -> {
                            if (control.kind != "igniculus") {
                                control
                            } else if (profile.id == "one-hand") {
                                if (abs(control.xFraction - 0.55f) <= FACE_SLOT_EPSILON &&
                                    abs(control.yFraction - 0.28f) <= FACE_SLOT_EPSILON
                                ) {
                                    control.copy(xFraction = 0.50f, yFraction = 0.50f)
                                } else if (hasCircle && control.sizeDp == 156 &&
                                    abs(control.xFraction - 0.78f) <= FACE_SLOT_EPSILON &&
                                    abs(control.yFraction - 0.80f) <= FACE_SLOT_EPSILON
                                ) {
                                    // 0.2.57: zona del layout limpio
                                    // con el tamaño viejo (156 dp).
                                    // 0.2.63: el destino ya no es
                                    // 115 dp, que es el tamaño viejo
                                    // de 0.2.62: 104 dp.
                                    control.copy(sizeDp = 104, sizeFraction = null)
                                } else if (hasCircle && control.sizeDp == 115 &&
                                    abs(control.xFraction - 0.78f) <= FACE_SLOT_EPSILON &&
                                    abs(control.yFraction - 0.80f) <= FACE_SLOT_EPSILON
                                ) {
                                    // 0.2.63: zona del layout limpio
                                    // de 0.2.62 (115 dp) en su posición
                                    // limpia, en un perfil editado a
                                    // medias: 104 dp.
                                    control.copy(sizeDp = 104, sizeFraction = null)
                                } else {
                                    control
                                }
                            } else {
                                if (abs(control.xFraction - 0.50f) <= FACE_SLOT_EPSILON &&
                                    abs(control.yFraction - 0.35f) <= FACE_SLOT_EPSILON
                                ) {
                                    control.copy(yFraction = 0.55f)
                                } else if (hasCircle && control.sizeDp == 156 &&
                                    abs(control.xFraction - 0.82f) <= FACE_SLOT_EPSILON &&
                                    abs(control.yFraction - 0.808f) <= FACE_SLOT_EPSILON
                                ) {
                                    // 0.2.57: zona del layout limpio
                                    // con el tamaño viejo (156 dp).
                                    // 0.2.63: el destino ya no es
                                    // 115 dp, que es el tamaño viejo
                                    // de 0.2.62: 104 dp.
                                    control.copy(sizeDp = 104, sizeFraction = null)
                                } else if (hasCircle && control.sizeDp == 115 &&
                                    abs(control.xFraction - 0.82f) <= FACE_SLOT_EPSILON &&
                                    abs(control.yFraction - 0.808f) <= FACE_SLOT_EPSILON
                                ) {
                                    // 0.2.63: zona del layout limpio
                                    // de 0.2.62 (115 dp) en su posición
                                    // limpia, en un perfil editado a
                                    // medias: 104 dp.
                                    control.copy(sizeDp = 104, sizeFraction = null)
                                } else {
                                    control
                                }
                            }
                        }
                        "btn_sword" -> {
                            // 0.2.58: espada del layout limpio con
                            // el valor viejo de fábrica 0.2.57
                            // ((0.864, 0.576), 82 dp) -> (0.914,
                            // 0.576) con 86 dp (0.2.63: el destino
                            // ya no es 92 dp, que es el tamaño viejo
                            // de 0.2.62). Solo default y
                            // externo: una mano lleva la espada en
                            // (0.83, 0.63) y no coincide. Con guarda
                            // hasCircle como la pasada 0.2.57.
                            if (hasCircle &&
                                (profile.id == "default-g99" || profile.id == "external-gamepad") &&
                                abs(control.xFraction - 0.864f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.576f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 82
                            ) {
                                control.copy(xFraction = 0.914f, sizeDp = 86, sizeFraction = null)
                            } else if (hasCircle &&
                                (profile.id == "default-g99" || profile.id == "external-gamepad") &&
                                abs(control.xFraction - 0.914f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.576f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 92
                            ) {
                                // 0.2.63: espada del layout limpio
                                // de 0.2.62 (92 dp) en su posición
                                // limpia, en un perfil editado a
                                // medias: 86 dp.
                                control.copy(sizeDp = 86, sizeFraction = null)
                            } else if (hasCircle &&
                                profile.id == "one-hand" &&
                                abs(control.xFraction - 0.83f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.63f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 82
                            ) {
                                // 0.2.65 (M-P2-2): una mano no tenía
                                // rama de espada: un perfil de una
                                // mano editado a medias se quedaba
                                // en 82 dp mientras la fábrica es
                                // 77 dp desde la 0.2.63.
                                control.copy(sizeDp = 77, sizeFraction = null)
                            } else {
                                control
                            }
                        }
                        "btn_jump" -> {
                            // 0.2.58: salto del layout limpio con
                            // el valor viejo de fábrica 0.2.57
                            // ((0.93, 0.752), 82 dp) -> (1.012,
                            // 0.752) con 87 dp (0.2.63: el destino
                            // ya no es 92 dp, que es el tamaño viejo
                            // de 0.2.62). Solo default y
                            // externo: una mano lleva el salto en
                            // (0.91, 0.79) y no coincide. Con guarda
                            // hasCircle como la pasada 0.2.57.
                            if (hasCircle &&
                                (profile.id == "default-g99" || profile.id == "external-gamepad") &&
                                abs(control.xFraction - 0.93f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.752f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 82
                            ) {
                                control.copy(xFraction = 1.012f, sizeDp = 87, sizeFraction = null)
                            } else if (hasCircle &&
                                (profile.id == "default-g99" || profile.id == "external-gamepad") &&
                                abs(control.xFraction - 1.012f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.752f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 92
                            ) {
                                // 0.2.63: salto del layout limpio
                                // de 0.2.62 (92 dp) en su posición
                                // limpia, en un perfil editado a
                                // medias: 87 dp.
                                control.copy(sizeDp = 87, sizeFraction = null)
                            } else if (hasCircle &&
                                profile.id == "one-hand" &&
                                abs(control.xFraction - 0.91f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.79f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 82
                            ) {
                                // 0.2.65 (M-P2-2): una mano no tenía
                                // rama de salto: igual que la espada,
                                // un perfil editado a medias se
                                // quedaba en 82 dp en vez de 77 dp.
                                control.copy(sizeDp = 77, sizeFraction = null)
                            } else {
                                control
                            }
                        }
                        "btn_start" -> {
                            // 0.2.63: pausa del layout limpio de
                            // 0.2.62 (56 dp) en su posición limpia,
                            // en un perfil editado a medias: 52 dp.
                            // Misma guarda hasCircle que las demás
                            // ramas de esta pasada.
                            if (hasCircle &&
                                abs(control.xFraction - 0.50f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.07f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 56
                            ) {
                                control.copy(sizeDp = 52, sizeFraction = null)
                            } else {
                                control
                            }
                        }
                        "btn_circle" -> {
                            // 0.2.63: círculo del layout limpio de
                            // 0.2.62 (44 dp) en su posición limpia,
                            // en un perfil editado a medias: 41 dp.
                            if (hasCircle &&
                                abs(control.xFraction - 0.59f) <= FACE_SLOT_EPSILON &&
                                abs(control.yFraction - 0.07f) <= FACE_SLOT_EPSILON &&
                                control.sizeDp == 44
                            ) {
                                control.copy(sizeDp = 41, sizeFraction = null)
                            } else {
                                control
                            }
                        }
                        else -> control
                    }
                },
            )
        }

        /**
         * R5-A1-1: migración completa de un perfil predefinido
         * guardado: primero el layout limpio 0.2.54 (si sigue
         * exactamente en su layout viejo de fábrica o en el limpio
         * de 5 controles sin círculo) o el limpio con los tamaños
         * viejos de 0.2.57 (stick 210 dp, zona 156 dp) o el limpio
         * de 0.2.57 (espada/salto 82 dp en sus posiciones viejas),
         * y después las pasadas viejas (rombo de cara + valores de
         * fábrica del stick y la Igniculus, incluida la pasada
         * 0.2.57 por control y la pasada 0.2.58 por control para
         * espada/salto), que se mantienen para los perfiles que el
         * usuario modificó y no pasan por la primera. Las pasadas
         * son independientes: un perfil con el rombo ya migrado
         * pero el stick viejo sigue normalizando el stick. La
         * pasada 0.2.57 por control solo achica stick/zona si el
         * perfil ya trae btn_circle: un limpio de 5 controles
         * editado por el usuario queda intacto. La pasada 0.2.58
         * por control tiene la misma guarda: solo mueve
         * espada/salto si el perfil ya trae btn_circle.
         */
        /**
         * 0.2.68 (neón): migra UN perfil predefinido de su layout
         * limpio de 0.2.63-0.2.67 (6 controles: stick, espada,
         * salto, pausa, círculo, Igniculus) al layout neón de
         * fábrica. Solo actúa si el perfil es predefinido
         * ("default-g99", "external-gamepad" o "one-hand", cada
         * uno con SU predecessor 0.2.67: el de una mano lleva
         * otras posiciones y tamaños) Y sus controles son el
         * layout 0.2.67 o ese mismo layout con COMO MUCHO un
         * control borrado (0.2.70, P1-1: un perfil de 5 controles
         * sin btn_circle, con sizeFraction, tambien migra): si el
         * usuario movió, redimensionó, remapeó o añadió aunque
         * sea un control, el perfil es suyo y no se toca. La comparación ignora sizeFraction y
         * widthFraction (el layout 0.2.67 existe guardado con
         * fracción —0.2.64+— y sin ella —pre-0.2.64—), colorHex y
         * visible (como matchesFactoryLayout, 0.2.65 M-P2-4).
         * Idempotente: un perfil ya neón no coincide con el layout
         * viejo y se devuelve tal cual. Puro Kotlin, sin Context:
         * testeable.
         */
        fun migrateBuiltInToNeonLayout(profile: ControlProfile): ControlProfile {
            if (!profile.isBuiltIn) return profile
            // 0.2.68 segunda pasada: una mano también migra, con SU
            // propio predecessor (el layout 0.2.67 de 6 controles de
            // una mano, no el del default: otras posiciones y otros
            // tamaños). Un perfil editado no coincide con ningún
            // predecessor y no se toca, como en default/externo.
            val (neon, predecessor) = when (profile.id) {
                "default-g99" -> defaultTouchLayout() to neonPredecessorLayout()
                "external-gamepad" -> externalGamepadLayout() to neonPredecessorLayout()
                "one-hand" -> oneHandLayout() to neonPredecessorOneHandLayout()
                else -> return profile
            }
            if (!matchesNeonPredecessor(profile.controls, predecessor)) return profile
            return profile.copy(controls = neon)
        }

        /** El layout limpio de 0.2.67 (6 controles), guarda de la migración neón. */
        private fun neonPredecessorLayout(): List<TouchControl> = listOf(
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.675f, 174, 0.70f),
            TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.914f, 0.576f, 86, 0.80f),
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 1.012f, 0.752f, 87, 0.80f),
            TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 52, 0.70f),
            TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 41, 0.70f),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.82f, 0.808f, 104, 0.25f, colorHex = "#7FB3D5"),
        )

        /** El layout limpio de una mano de 0.2.67 (6 controles), guarda de la migración neón para ese perfil. */
        private fun neonPredecessorOneHandLayout(): List<TouchControl> = listOf(
            TouchControl("stick", "Stick", "stick", VitaButton.LEFT_STICK, 0.05f, 0.70f, 174, 0.75f),
            TouchControl("btn_sword", "Espada", "button", VitaButton.SQUARE, 0.83f, 0.63f, 77, 0.80f),
            TouchControl("btn_jump", "Salto", "button", VitaButton.CROSS, 0.91f, 0.79f, 77, 0.80f),
            TouchControl("btn_start", "Start", "button", VitaButton.START, 0.50f, 0.07f, 52, 0.70f),
            TouchControl("btn_circle", "Volver", "button", VitaButton.CIRCLE, 0.59f, 0.07f, 41, 0.70f),
            TouchControl("igniculus", "Igniculus", "igniculus", VitaButton.NONE, 0.78f, 0.80f, 104, 0.25f, colorHex = "#7FB3D5"),
        )

        private fun matchesNeonPredecessor(controls: List<TouchControl>, snapshot: List<TouchControl>): Boolean {
            // 0.2.70 (ronda 0.2.70, area 3, P1-1): coincidencia por
            // SUBCONJUNTO con como mucho UN control borrado, no por
            // igualdad de tamano: un perfil predefinido 0.2.67 al
            // que se le BORRO un control (5 controles, con
            // sizeFraction) nunca migaba al neon porque 6 != 5.
            // Cada control presente debe coincidir con la entrada
            // del predecessor con su MISMO id (busqueda por id, no
            // por posicion: al borrar un control los demas se
            // desplazan) y ningun control puede sobrar. Un perfil
            // editado (un control movido, redimensionado o
            // remapeado) sigue sin migrar, porque ese control ya
            // no coincide; y un perfil podado a conciencia (2 o
            // mas controles borrados, o vacio) tampoco migra: no
            // se resucita lo borrado a proposito. El control
            // borrado no resucita como tal: la migracion sustituye
            // el perfil entero por el neon de fabrica (10
            // controles), igual que ToCleanLayout ya hacia con el
            // caso pre-0.2.64 de 5 controles.
            if (controls.isEmpty() || controls.size < snapshot.size - 1) return false
            if (controls.size > snapshot.size) return false
            val byId = snapshot.associateBy { it.id }
            return controls.all { b ->
                val a = byId[b.id] ?: return@all false
                a.kind == b.kind &&
                    a.vitaButton == b.vitaButton &&
                    a.xFraction == b.xFraction &&
                    a.yFraction == b.yFraction &&
                    a.sizeDp == b.sizeDp &&
                    a.label == b.label &&
                    a.opacity == b.opacity &&
                    a.shape == b.shape &&
                    a.stickMode == b.stickMode &&
                    a.igniculusMode == b.igniculusMode &&
                    a.sensitivity == b.sensitivity
            }
        }

        fun migrateBuiltInProfile(profile: ControlProfile): ControlProfile =
            migrateBuiltInToNeonLayout(migrateBuiltInFactoryValues(migrateBuiltInFaceDiamond(migrateBuiltInToCleanLayout(profile))))

        /** R4-P0-1: migra todos los perfiles predefinidos de la lista. */
        fun migrateBuiltInProfiles(profiles: List<ControlProfile>): List<ControlProfile> =
            profiles.map { migrateBuiltInProfile(it) }


        fun sanitizeName(name: String): String =
            name.replace('|', ' ').replace(';', ' ').replace('#', ' ')
                .replace('/', ' ').replace('\\', ' ')
                .replace('\n', ' ').replace('\r', ' ').trim().take(40)

        /** Saneado para campos del formato (sin quitar '#': colorHex lo usa). */
        private fun sanitizeField(value: String): String =
            value.replace('|', ' ').replace(';', ' ')
                .replace('\n', ' ').replace('\r', ' ').trim()

        fun encodeControl(c: TouchControl): String = listOf(
            // BUG (auditoría): label y colorHex no se saneaban, pero el
            // formato usa '|' y ';' como separadores; una etiqueta con '|'
            // o ';' (editable por el usuario) corrompía el perfil y el
            // control desaparecía silenciosamente al decodificar.
            c.id, sanitizeField(c.label), c.kind, c.vitaButton.name,
            c.xFraction.toString(), c.yFraction.toString(), c.sizeDp.toString(),
            c.opacity.toString(), sanitizeField(c.colorHex), c.shape.name, c.visible.toString(),
            c.stickMode.name, c.igniculusMode.name, c.sensitivity.toString(),
            // 0.2.64 (A-7): campo 15, la fracción de tamaño; vacío =
            // null (modo dp). Los decodificadores viejos ignoran los
            // campos de más, así que un perfil nuevo no se corrompe
            // al leerlo una versión anterior (pierde la fracción y
            // usa sizeDp, que sigue guardado).
            c.sizeFraction?.toString() ?: "",
            // 0.2.68 (neón): campo 16, la fracción de ancho (solo
            // PILL); vacío = null (cuadrado). Un decodificador
            // viejo lo ignora y dibuja la píldora cuadrada.
            c.widthFraction?.toString() ?: "",
        ).joinToString("|")

        /**
         * Centro de un control en fracción de pantalla, con el control
         * ENTERO dentro de la pantalla: [half, 1 - half], donde half es
         * la mitad del tamaño del control en fracción. Si el control es
         * mayor que la pantalla, el centro es 0.5. Lo usa el arrastre
         * de la vista previa (B7): antes el arrastre fijaba la fracción
         * en 0..1 y el dibujado la recortaba por arriba, así que al
         * arrastrar a un borde el botón dejaba de moverse en pantalla
         * pero la fracción seguía bajando hasta 0 — y al volver, los
         * primeros píxeles del dedo no movían nada. Fracción guardada
         * y posición dibujada ahora coinciden siempre.
         *
         * 0.2.58 (R7): [maxFraction] relaja solo el tope derecho para
         * el salto (btn_jump): su centro de fábrica es 1.012 (mockup,
         * sobre la barra negra derecha) y el editor debe poder
         * producirlo y conservarlo al arrastrar o mover el slider X;
         * con el tope en 1 - half el editor lo devolvería a 0.937 y
         * perdería el arreglo del mockup. El mismo tope que
         * decodeControl (JUMP_MAX_X_FRACTION): el dibujado sigue
         * capado al borde de la vista (controlRectInGameRect).
         *
         * 0.2.68 (neón): [minFraction] relaja el tope izquierdo, en
         * espejo: la píldora L tiene su centro de fábrica en -0.012
         * (sobre la barra negra izquierda) y sin este tope el primer
         * arrastre o el slider X la devolverían a +half y perderían
         * el diseño. El editor pasa los dos topes desde la barra
         * (barFractionForAspect): centro entre half - barra y
         * 1 + barra - half, es decir, el control entero dentro de
         * la VISTA, no solo del rect del juego.
         */
        fun clampCenterFraction(fraction: Float, halfSizeFraction: Float, maxFraction: Float? = null, minFraction: Float? = null): Float {
            val lo = (minFraction ?: halfSizeFraction).coerceAtMost(0.5f)
            val hi = (maxFraction ?: (1f - halfSizeFraction)).coerceAtLeast(0.5f)
            return fraction.coerceIn(lo, hi)
        }

        /**
         * 0.2.58 (R7): tope de x guardada para el salto (btn_jump).
         * Su centro de fábrica era 1.012 (mockup); decodeControl y el
         * editor comparten este tope para no deshacer la migración.
         * 0.2.68 (neón): el mismo tope vale en AMBOS lados: el
         * mínimo de decodeControl es 1 - 1.1 = -0.1 (la píldora L
         * vive en -0.012) y el máximo 1.1 (○ en 1.028, R en 1.016).
         */
        const val JUMP_MAX_X_FRACTION = 1.1f

        /**
         * R5-A1-7: un perfil guardado en <=0.2.49 con el verde o el
         * rojo viejos de la paleta se renderizaba igual: decode
         * tomaba el color tal cual. Al leer, esos dos hex se
         * normalizan a su equivalente de la identidad actual
         * (case-insensitive); cualquier otro color se deja tal cual.
         * Puro Kotlin, sin Context: testeable.
         */
        fun normalizePaletteColor(hex: String): String = when (hex.uppercase()) {
            "#4CAF50" -> "#75D6CE"
            "#E57373" -> "#A99ACD"
            else -> hex
        }

        private fun finiteFloat(raw: String?, default: Float): Float =
            raw?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default

        fun decodeControl(raw: String): TouchControl? {
            val p = raw.split("|")
            if (p.size < 7) return null
            val parsed = runCatching {
                if (p.size >= 14) {
                    // B8 (auditoría panel táctil): esta rama usaba
                    // toFloat()/toInt()/valueOf() directos dentro de
                    // runCatching: UN solo campo corrupto (un enum
                    // renombrado, un número roto) descartaba el
                    // control ENTERO en silencio al decodificar, y el
                    // botón desaparecía del layout. Ahora cada campo
                    // se lee con valor por defecto, igual que la rama
                    // de 8-13 campos (M28).
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = runCatching { VitaButton.valueOf(p[3]) }.getOrDefault(VitaButton.NONE),
                        xFraction = finiteFloat(p[4], 0.5f),
                        yFraction = finiteFloat(p[5], 0.5f),
                        sizeDp = p[6].toIntOrNull() ?: 64,
                        opacity = finiteFloat(p[7], 0.8f),
                        colorHex = p[8],
                        shape = runCatching { ControlShape.valueOf(p[9]) }.getOrNull() ?: ControlShape.CIRCLE,
                        visible = p[10].toBooleanStrictOrNull() ?: true,
                        stickMode = runCatching { StickMode.valueOf(p[11]) }.getOrNull() ?: StickMode.ANALOG,
                        igniculusMode = runCatching { IgniculusMode.valueOf(p[12]) }.getOrNull() ?: IgniculusMode.DIRECT_TOUCH,
                        sensitivity = finiteFloat(p[13], 1.0f),
                        // 0.2.64 (A-7): campo 15 opcional; los perfiles
                        // de 14 campos (o con el campo vacío) quedan
                        // en null = modo dp.
                        sizeFraction = p.getOrNull(14)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f },
                        // 0.2.68 (neón): campo 16 opcional, la
                        // fracción de ancho (solo PILL); vacío o
                        // ausente = null = cuadrado.
                        widthFraction = p.getOrNull(15)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f },
                    )
                } else if (p.size >= 8) {
                    // M28: formato intermedio (8-13 campos): los campos
                    // presentes se leen con los índices del formato de 14;
                    // lo que falta toma el valor por defecto. Antes caía en
                    // la rama antigua (7 campos) y p[3] (nombre de botón
                    // Vita) se parseaba como Float -> el control se
                    // descartaba en silencio.
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = runCatching { VitaButton.valueOf(p[3]) }.getOrDefault(VitaButton.NONE),
                        xFraction = finiteFloat(p[4], 0.5f),
                        yFraction = finiteFloat(p[5], 0.5f),
                        sizeDp = p[6].toIntOrNull() ?: 64,
                        opacity = finiteFloat(p[7], 0.8f),
                        colorHex = p.getOrNull(8) ?: "#8CCBEB",
                        shape = p.getOrNull(9)?.let { runCatching { ControlShape.valueOf(it) }.getOrNull() } ?: ControlShape.CIRCLE,
                        visible = p.getOrNull(10)?.toBooleanStrictOrNull() ?: true,
                        stickMode = p.getOrNull(11)?.let { runCatching { StickMode.valueOf(it) }.getOrNull() } ?: StickMode.ANALOG,
                        igniculusMode = p.getOrNull(12)?.let { runCatching { IgniculusMode.valueOf(it) }.getOrNull() } ?: IgniculusMode.DIRECT_TOUCH,
                        sensitivity = finiteFloat(p.getOrNull(13), 1.0f),
                    )
                } else {
                    // Formato antiguo (7 campos): valores por defecto para lo nuevo.
                    TouchControl(
                        id = p[0], label = p[1], kind = p[2],
                        vitaButton = defaultButtonFor(p[0], p[2]),
                        xFraction = p[3].toFloat(), yFraction = p[4].toFloat(),
                        sizeDp = p[5].toInt(), opacity = p[6].toFloat(),
                    )
                }
            }.getOrNull() ?: return null
            // B9: normalizar al leer. Un valor corrupto fuera de rango
            // (fracción > 1, tamaño negativo, opacidad > 1, "NaN") no
            // puede sacar el botón de la pantalla ni romper el layout.
            // R5-A1-7: el color también se normaliza a la paleta de
            // la identidad (verde/rojo viejos -> turquesa/lavanda).
            // 0.2.58 (R7): SOLO btn_jump admitía x > 1, hasta
            // JUMP_MAX_X_FRACTION: su centro de fábrica era 1.012
            // (mockup, fuera del rect del juego, sobre la barra negra
            // derecha) y con el tope en 1 la migración a 1.012 se
            // desharía al releer el perfil guardado.
            // 0.2.68 (neón): el tope ya no es solo para btn_jump: la
            // píldora L (-0.012) vive sobre la barra izquierda y ○
            // (1.028) y R (1.016) sobre la derecha; CUALQUIER control
            // admite hasta 0.1 fuera del rect en ambos lados
            // (1 - 1.1 = -0.1), o el layout neón se corrompería al
            // releerlo (L a 0.0, ○ y R a 1.0). El dibujado sigue
            // capado al borde de la vista (controlRectInGameRect), así
            // que un 1.1 guardado a mano tampoco se sale de la pantalla.
            return parsed.copy(
                xFraction = parsed.xFraction.takeIf { it.isFinite() }
                    ?.coerceIn(1f - JUMP_MAX_X_FRACTION, JUMP_MAX_X_FRACTION) ?: 0.5f,
                yFraction = parsed.yFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                sizeDp = parsed.sizeDp.coerceIn(16, 400),
                opacity = parsed.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.8f,
                colorHex = normalizePaletteColor(parsed.colorHex),
                sensitivity = parsed.sensitivity.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                // 0.2.64 (A-7): la fracción fuera de rango no puede
                // hacer gigante ni invisible el botón.
                sizeFraction = parsed.sizeFraction?.takeIf { it.isFinite() }?.coerceIn(0.01f, 1f),
                // 0.2.70 (ronda 0.2.70, area 3, P2-2): la fracción
                // de ancho tampoco puede salirse por arriba: un
                // widthFraction = 5.0 corrupto o editado a mano
                // dibujaba una pildora de 5400 px. Simetrico a
                // sizeFraction (el maximo real de fabrica es 0.346).
                widthFraction = parsed.widthFraction?.takeIf { it.isFinite() }?.coerceIn(0.01f, 1f),
            )
        }

        private fun defaultButtonFor(id: String, kind: String): VitaButton = when (id) {
            "btn_a" -> VitaButton.CROSS
            "btn_b" -> VitaButton.CIRCLE
            "btn_x" -> VitaButton.SQUARE
            "btn_y" -> VitaButton.TRIANGLE
            "btn_start" -> VitaButton.START
            "stick" -> VitaButton.LEFT_STICK
            else -> if (kind == "igniculus") VitaButton.NONE else VitaButton.NONE
        }

        fun encodeProfile(profile: ControlProfile): String {
            val header = listOf(profile.id, sanitizeName(profile.name), profile.isBuiltIn.toString()).joinToString("|")
            val controls = profile.controls.joinToString(";") { encodeControl(it) }
            return "$header\n$controls"
        }

        fun decodeProfile(raw: String): ControlProfile? {
            val lines = raw.split("\n", limit = 2)
            if (lines.isEmpty()) return null
            val header = lines[0].split("|")
            if (header.size < 3) return null
            val controlsRaw = if (lines.size > 1) lines[1] else ""
            val controls = if (controlsRaw.isBlank()) emptyList() else {
                controlsRaw.split(";").mapNotNull { decodeControl(it) }
            }
            // B10 (auditoría panel táctil): un perfil SIN controles es
            // válido (el usuario borró todos los botones para jugar
            // solo con mando físico). Antes se devolvía null y el
            // perfil entero desaparecía al leer: el layout volvía a
            // los predefinidos y los botones borrados "resucitaban".
            // Tampoco se podía importar un perfil vacío exportado.
            // Ids duplicados (p. ej. en un archivo importado a mano):
            // se queda el primero, para que borrar/editar por id no
            // afecte a dos controles a la vez.
            return ControlProfile(header[0], header[1], header[2].toBoolean(), controls.distinctBy { it.id })
        }

        fun decodeProfiles(raw: String): List<ControlProfile>? {
            if (raw.isBlank()) return null
            val profiles = raw.split("\n###\n").mapNotNull { decodeProfile(it) }
            return profiles.ifEmpty { null }
        }

        // Compatibilidad: funciones del formato antiguo (un solo layout).
        fun encode(layout: List<TouchControl>): String =
            layout.joinToString(";") { encodeControl(it) }

        fun decode(raw: String): List<TouchControl>? {
            if (raw.isBlank()) return null
            return runCatching {
                raw.split(";").mapNotNull { decodeControl(it) }
            }.getOrNull()?.ifEmpty { null }
        }
    }
}
