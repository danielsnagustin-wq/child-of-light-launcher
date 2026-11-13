package com.danielsnagustin.childoflight.launcher.core

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import org.libsdl.app.SDLSurface
import org.vita3k.emulator.EmuSurface
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.Toast
import com.danielsnagustin.childoflight.launcher.data.GameActivityState
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.TrophySyncPending
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.averageBenchmarkFps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.libsdl.app.SDLActivity

/**
 * Activity del juego con el núcleo Vita3K EMBEBIDO (Etapa 2).
 *
 * Es la SDLActivity que Vita3K espera, adaptada a este paquete
 * (modelo EmuCoreV; contrato verificado contra Vita3K upstream
 * master 3e43bc80, 2026-11-07):
 *
 * - Extiende org.libsdl.app.SDLActivity (pegamento Java de SDL3
 *   vendorizado en app/src/main/java/org/libsdl/app/, commit
 *   fa2c02b = SDL 3.4.16, el fijado por Vita3K).
 * - getLibraries() -> {"Vita3K"}: SDL carga libVita3K.so (el .so
 *   del núcleo, compilado desde el árbol Vita3K en CI; target CMake
 *   "vita3k", OUTPUT_NAME "Vita3K"). SDL3 va ESTÁTICO dentro de
 *   libVita3K.so (libSDL3.a, sin .so aparte ni DT_NEEDED): NO añadir
 *   "SDL3" a getLibraries().
 * - getArguments() -> el extra AppStartParameters si existe, si no
 *   {"-r", titleId}: es EXACTAMENTE lo que SDL_main
 *   (vita3k/android/jni/main_android.cpp) espera para arrancar el
 *   juego con AppLaunchRequest{.app_path = titleId}.
 * - onCreate: prepareFrontend() ANTES de super.onCreate (igual que
 *   upstream, que lo llama desde MainActivity antes de Emulator) y
 *   luego init(storagePath) DESPUÉS de super.onCreate y ANTES de
 *   que se cree la superficie (el hilo SDL no arranca hasta que la
 *   superficie está lista + resumed, así que init llega a tiempo;
 *   es el mismo orden que Emulator.ensureNativeSessionInitialized()).
 *
 * MÉTODOS QUE EL NATIVO LLAMA POR NOMBRE (main_android.cpp):
 * el hilo SDL busca estos métodos en la Activity con GetMethodID.
 * Si falta getNativeDisplayRotation, SDL_main SE CIERRA (no hay
 * comprobación de null en get_display_rotation()). Por eso existen
 * aquí con ESTAS firmas exactas:
 * - setCurrentGameId(String): el núcleo avisa qué juego arrancó.
 * - openPauseMenuFromController(): botón guía del mando. Abre el
 *   menú de pausa propio (PauseMenuActivity, Compose); nunca cierra
 *   el juego directamente.
 * - getNativeDisplayRotation(): Int: rotación de la pantalla
 *   (constantes Surface.ROTATION_*, 0-3, igual que el oficial)
 *   para el motion del núcleo.
 *
 * El botón atrás también abre el menú de pausa propio
 * (onBackPressed -> openPauseMenu). SDLActivity por defecto cerraría
 * la Activity; aquí atrás nunca cierra el juego sin preguntar.
 *
 * SIN núcleo embebido esta Activity no se lanza nunca (el launcher
 * decide con EmbeddedGameLauncher; sin núcleo, JUGAR bloquea con el
 * motivo exacto salvo la opción avanzada de Ajustes). Si init falla
 * aquí, se cierra con un mensaje y el usuario vuelve al launcher
 * (no se finge que el juego arrancó).
 *
 * Estado de la Etapa 2 (tras la primera build que arranque el juego):
 * - Overlay táctil: IMPLEMENTADO (GamepadOverlayView + puente JNI
 *   al mando virtual SDL de upstream, input_overlay.cpp). El perfil
 *   táctil activo del launcher (TouchLayoutRepository: posiciones,
 *   tamaños, opacidad, color, forma, visibilidad por botón) se dibuja
 *   sobre el juego con la misma matemática que la vista previa del
 *   editor, y cada botón/stick inyecta su botón/eje SDL en el núcleo.
 *   La vía por config.yml NO existe (verificado en el pin 3e43bc80:
 *   config.h no tiene ninguna clave de overlay). El táctil fuera de
 *   los botones baja a la superficie SDL como táctil frontal del
 *   juego (Igniculus). Con mando físico conectado y el ajuste activo,
 *   el overlay se oculta y el mando virtual se suelta.
 * - Menú de pausa con UI (reanudar / salir / ajustes).
 *   IMPLEMENTADO (PauseMenuActivity + PauseMenuScreen, Compose):
 *   se abre con atrás o con el botón guía; Continuar reanuda, Salir
 *   guarda la sesión y cierra el motor de forma limpia.
 * - Relanzamiento en proceso (requestAppRelaunch) para cambiar de
 *   Title ID: NO IMPLEMENTADO a propósito (P2-A5, oleada 2): upstream
 *   lo tiene (native_session.cpp:108) pero no está expuesto en el
 *   puente, y el launcher es de un solo juego, así que onNewIntent
 *   con otro Title ID se ignora y sigue la partida actual.
 */
class EmbeddedGameActivity : SDLActivity() {

    companion object {
        const val EXTRA_TITLE_ID = "title_id"
        const val EXTRA_GAME_TITLE = "game_title"
        /**
         * R4-P2-14: nombre del extra con argv propios para SDL_main
         * (String[]), igual que el oficial (Emulator.getArguments):
         * si existe, getArguments() lo devuelve tal cual; si no,
         * {"-r", titleId}. Nada en nuestro flujo lo envía hoy.
         */
        const val EXTRA_APP_START_PARAMETERS = "AppStartParameters"
        /** true = prueba de 30 s: muestrear el FPS del núcleo y guardarlo al salir. */
        const val EXTRA_BENCHMARK = "benchmark"
        /**
         * Ronda 0.2.64 (H-4 tiempo de carga, T1/T2): t0 del toque de
         * JUGAR (SystemClock.elapsedRealtime, el mismo reloj que
         * play()/startBenchmark y el lanzador). 0 = sin medir.
         */
        const val EXTRA_LAUNCH_STARTED_AT = "launch_started_at"
        /** Muestras válidas de la prueba de 30 s: 1 por segundo de juego real. */
        const val BENCHMARK_MAX_SAMPLES = 30

        /** Request code del menú de pausa (startActivityForResult). */
        const val REQUEST_PAUSE_MENU = 2001

        /**
         * Ronda 0.2.65 (P0-3): watchdog de pantalla negra. Tiempo máximo
         * sin señal de arranque (ni on_launch ni FPS entregado mayor
         * que 0) desde el primer onResume antes de avisar al usuario
         * y cerrar la Activity. 180 s, no menos: la precompilación de
         * shaders de la primera carga puede tardar unos 2 minutos en
         * este teléfono, y un tiempo corto mataría arranques legítimos.
         * Valor ajustable: si en el teléfono los arranques normales
         * superan los 3 minutos, subirlo; si el arranque normal nunca
         * pasa de 1 minuto, se puede bajar. Sin verificar en teléfono.
         */
        private const val BLACK_SCREEN_WATCHDOG_TIMEOUT_MS = 180_000L

        /**
         * Ronda 0.2.70 (Área 10, 10-P2-1): rebanada del sondeo del
         * watchdog de pantalla negra (1 s): solo el tiempo con la
         * Activity reanudada descuenta del plazo de 180 s.
         */
        private const val BLACK_SCREEN_WATCHDOG_SLICE_MS = 1_000L

        /**
         * Ronda 0.2.70 (Área 10, P1-9): plazo del temporizador de
         * vigilancia del init en frío (30 s): si el init síncrono en
         * el hilo UI no vuelve en este tiempo, se registra y se cierra
         * con mensaje. 30 s, no 5 s (ANR): el init normal tarda de
         * cientos de ms a segundos en este teléfono y no debe dar
         * falsos positivos; el temporizador solo cubre el colgado
         * real. El init sigue en el hilo UI (moverlo es una
         * reestructuración diferida): este temporizador corre en un
         * hilo propio porque una corrutina Main no avanzaría con el
         * hilo UI bloqueado.
         */
        private const val COLD_INIT_WATCHDOG_TIMEOUT_MS = 30_000L

        /**
         * Ronda 0.2.70 (Área 10, P1-10): sondeo acotado tras cada
         * onResume para la reentrada rápida: si el hilo SDL viejo
         * muere después del onResume, se relanza SDL_main. 30 s de
         * tope, sondeo cada 500 ms; el sondeo sale en cuanto hay
         * partida en curso o el hilo está vivo y arrancó el juego.
         */
        private const val SDL_RELAUNCH_POLL_TIMEOUT_MS = 30_000L
        private const val SDL_RELAUNCH_POLL_INTERVAL_MS = 500L

        /**
         * Ronda 0.2.70 (Área 10, P1-11 / 10-P2-4): tope del teardown
         * de la sesión en onDestroy: destroySession() con el renderer
         * Vulkan puede tardar; pasado este tiempo se registra y se
         * cancela el ámbito (la llamada JNI bloqueante en curso no se
         * puede interrumpir, pero no retiene la Activity más allá de
         * su fin).
         */
        private const val DESTROY_SESSION_TIMEOUT_MS = 30_000L

        /**
         * Ronda 0.2.70 (9-P1-7): watchdog de SEGUNDA ETAPA. Tiempo
         * máximo tras on_launch sin NINGÚN frame presentado (contador
         * presentKHR del gancho nº 1) antes de avisar y cerrar. 180 s
         * como el de arranque: la precompilación de shaders de la
         * primera carga puede tardar unos 2 minutos, y un tiempo
         * corto mataría partidas legítimas que aún están cargando.
         * No toca el watchdog de arranque: este solo cubre lo que
         * se cuelga DESPUÉS de on_launch.
         */
        private const val SECOND_STAGE_WATCHDOG_TIMEOUT_MS = 180_000L

        /**
         * Ronda 0.2.70 (9-P1-7): aviso de "emula pero no presenta".
         * Segundos seguidos con presents/s < 1 y FPS emulado >= 1
         * antes de avisar una sola vez por lanzamiento (solo con
         * frame_profile ON, en el sondeo a 4 Hz).
         */
        private const val NO_PRESENT_WARN_AFTER_MS = 10_000L

        /**
         * Tercera pasada (R-2): detector de pantalla negra EN MITAD
         * DEL JUEGO, independiente de frame_profile (OFF por
         * defecto). Sondea el contador de presents cada 1 s tras el
         * primer present y solo descuenta con la Activity reanudada
         * (congelar en segundo plano, como la etapa 1). Umbrales
         * conservadores a propósito: aviso a los 15 s (generoso, por
         * encima del aviso de 10 s del perfil de frames) y diálogo
         * solo tras 60 s seguidos sin presents con el juego emulando
         * (FPS >= 1). 60 s, no 30 s (OpenCode proponía 30 s): una
         * carga larga dentro de la partida o una precompilación de
         * shaders en caliente pueden dejar el renderer mudo decenas
         * de segundos sin estar atascado, y el diálogo ofrece
         * "Seguir esperando" (rearma), así que un falso positivo
         * cuesta un toque y nunca mata la partida. El emulador
         * presenta cada vsync aunque la escena no cambie, así que
         * "sin presents" con el juego reanudado y emulando es
         * renderer atascado, no escena quieta.
         */
        private const val MID_GAME_NO_PRESENT_WARN_AFTER_MS = 15_000L
        private const val MID_GAME_NO_PRESENT_DIALOG_AFTER_MS = 60_000L
        private const val MID_GAME_WATCHDOG_TICK_MS = 1_000L

        /** Ronda 0.2.70 (9-P1-7): periodo del sondeo a 4 Hz del perfil de frames. */
        private const val FRAME_PROFILE_POLL_TICK_MS = 250L

        /**
         * Ronda 0.2.65 (S-P2-2): el sondeo del primer frame (T2) va
         * cada 200 ms durante los primeros 10 s tras on_launch (antes
         * 1 s siempre: sobrestimaba T2 hasta 2 s). Pasados los 10 s
         * vuelve a 1 s: un juego que tarda más de 10 s en entregar su
         * primer frame ya es anómalo y no necesita precisión de 200 ms.
         */
        private const val FIRST_FRAME_POLL_FAST_WINDOW_MS = 10_000L
        private const val FIRST_FRAME_POLL_FAST_DELAY_MS = 200L

        /**
         * R4-P0-3: intentos de re-crear el mando virtual en on_launch
         * (detach + attach + verificación), con 500 ms entre intentos.
         */
        private const val OVERLAY_REBIND_MAX_ATTEMPTS = 3
        private const val OVERLAY_REBIND_RETRY_DELAY_MS = 500L
        /**
         * R4-P0-4: intentos de quitar la pausa de fondo, con 200 ms
         * entre intentos (el núcleo descarta el clear si la fase aún
         * no es Running). Ronda 0.2.65 (S-P2-7): antes 500 ms, con
         * 1,5 s de botones sordos al volver en el peor caso; ahora
         * 0,6 s. El reintento es solo una lectura JNI barata.
         */
        private const val PAUSE_CLEAR_MAX_ATTEMPTS = 3
        private const val PAUSE_CLEAR_RETRY_DELAY_MS = 200L

        fun createLaunchIntent(context: Context, titleId: String, gameTitle: String, benchmark: Boolean = false, launchStartedAtMillis: Long = 0L): Intent {
            return Intent(context, EmbeddedGameActivity::class.java).apply {
                putExtra(EXTRA_TITLE_ID, titleId)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                putExtra(EXTRA_BENCHMARK, benchmark)
                putExtra(EXTRA_LAUNCH_STARTED_AT, launchStartedAtMillis)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    private var currentTitleId: String = ""

    // --- Prueba de 30 s (muestreo de FPS) ---
    private var benchmarkMode: Boolean = false
    private var benchmarkJob: Job? = null
    private val benchmarkSamples = mutableListOf<Float>()

    /**
     * Ronda 0.2.66 (Fase L, INST-P0-2/INST-P0-3): perfil de frames.
     * true mientras Choreographer + FrameMetrics + sondeo a 4 Hz
     * están activos (ajuste `frame_profile` ON). Se arranca en
     * onResume y se para en onPause; los agregados viven en
     * FrameProfileStats y sobreviven a la pausa (el menú de pausa
     * no reinicia la medición). `frameProfileStartedThisActivity`
     * marca si ya se reseteó en esta Activity: un JUGAR nuevo
     * (Activity nueva) empieza con contadores limpios, pero volver
     * del menú de pausa no los borra.
     */
    private var frameProfileActive: Boolean = false
    private var frameProfileJob: Job? = null
    private var frameProfileStartedThisActivity: Boolean = false

    /**
     * Callback de vsync: registra el tick y se re-registra. Solo
     * mide ticks de vsync del panel, NO frames presentados del
     * juego (ver FrameProfileStats). `object :` en vez de lambda
     * SAM para que el re-registro use `this` sin auto-referencia
     * del campo en su propio inicializador.
     */
    private val frameProfileChoreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameProfileActive) return
            FrameProfileStats.recordVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Oyente de métricas de Views: registra TOTAL_DURATION del
     * overlay. Solo la UI: la superficie del juego no se ve aquí
     * (ver FrameProfileStats).
     */
    private val frameProfileMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (!frameProfileActive) return@OnFrameMetricsAvailableListener
        FrameProfileStats.recordFrameMetrics(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
    }
    /**
     * Ronda 0.2.64 (H-4 tiempo de carga, T1/T2): t0 del toque de
     * JUGAR (elapsedRealtime), leído del extra del intent. 0 = sin
     * medir (lanzamiento directo o proceso frío sin extra).
     */
    private var launchStartedAtMillis: Long = 0L
    /** T2 ya registrado una vez en este lanzamiento (primer frame entregado por el juego, no presentado en pantalla: ver R-P1-1). */
    private var firstFrameLogged: Boolean = false
    /**
     * Momento (uptimeMillis) en que el núcleo confirmó el juego en
     * curso (setCurrentGameId, hook on_launch). 0 = aún no confirmado:
     * no se muestrea nada hasta entonces (R2-P2-29: menús y cargas
     * no son juego). Solo se fija una vez por lanzamiento.
     */
    private var gameRunningSinceMillis: Long = 0L
    /**
     * P1-3: una prueba completa ya guardada no se vuelve a muestrear
     * ni a guardar en este lanzamiento (sin esto, al limpiar
     * benchmarkSamples tras guardar, un onResume posterior
     * reiniciaría el muestreo y guardaría una segunda prueba).
     */
    private var benchmarkSaved: Boolean = false

    /**
     * Ronda 0.2.65 (R-P1-18): la prueba de 30 s solo muestrea cuando
     * el usuario confirma que ya está jugando (botón "Ya estoy
     * jugando"): sin auto-arranque tras on_launch. true = el usuario
     * ya pulsó el botón en este lanzamiento (el muestreo se reanuda
     * solo tras cada pausa).
     */
    private var benchmarkStartedByUser: Boolean = false
    /** Botón "Ya estoy jugando" de la prueba de 30 s; null = aún no mostrado. */
    private var benchmarkPromptButton: Button? = null

    /**
     * Ronda 0.2.65 (P0-3): watchdog de pantalla negra. Arranca una
     * sola vez por Activity (primer onResume) y dispara si a los
     * 180 s no hubo on_launch ni FPS entregado mayor que 0.
     */
    private var watchdogStarted: Boolean = false

    /**
     * Ronda 0.2.70, arreglo del run #637: la Activity está reanudada
     * entre onResume y onPause. Bandera propia porque android.app.Activity
     * (base de SDLActivity) NO tiene isResumed — eso es de Fragment — y
     * el watchdog y el sondeo de relanzamiento SDL lo usaban como si
     * existiera (no compilaba). Se pone en true tras super.onResume()
     * (no al inicio de onResume: es super.onResume() quien arranca
     * el hilo SDL) y en false al inicio de onPause; la leen
     * corrutinas de fondo, como las demás banderas de este archivo.
     * @Volatile (tercera pasada, R-6): hoy todos los lectores viven
     * en Main, pero la bandera es leída por sondeos que pueden
     * moverse de hilo y el coste de @Volatile aquí es nulo.
     * Se resetea a false en onDestroy.
     */
    @Volatile
    private var isActivityResumed: Boolean = false

    /**
     * Ronda 0.2.70 (Área 10, P1-10): sondeo acotado del hilo SDL tras
     * onResume (reentrada rápida); null = sin sondeo en curso. Se
     * cancela en cada onResume antes de arrancar uno nuevo y con
     * overlayScope en onDestroy.
     */
    private var sdlRelaunchPollJob: Job? = null

    /**
     * Ronda 0.2.70 (9-P1-7): watchdog de SEGUNDA ETAPA. Arranca una
     * sola vez por Activity, en on_launch (ver setCurrentGameId), y
     * dispara si a los 180 s no hubo NINGÚN frame presentado. Job
     * propio, separado del watchdog de arranque: no lo toca ni lo
     * reestructura (el área 10 edita ese bloque en esta misma ronda).
     */
    private var secondStageWatchdogStarted: Boolean = false

    /**
     * Ronda 0.2.70 (9-P1-7): aviso de "emula pero no presenta" ya
     * mostrado en este lanzamiento (una sola vez por lanzamiento).
     * Racha actual (ms) con presents/s < 1 y FPS emulado >= 1 en el
     * sondeo a 4 Hz del perfil de frames.
     */
    private var noPresentWarned: Boolean = false
    private var noPresentStreakMs: Long = 0L

    /**
     * Tercera pasada (R-2): detector de pantalla negra en mitad del
     * juego. Arranca una sola vez por Activity (primer onResume) y
     * sondea el contador de presents cada 1 s; solo descuenta con
     * la Activity reanudada. lastPresentedCount/TimeNs = línea base
     * del último present visto (-1 = aún sin línea base o contador
     * desconocido). dialogShown evita re-mostrar el diálogo sobre
     * la misma racha; "Seguir esperando" lo resetea y rearma.
     */
    private var midGameWatchdogStarted: Boolean = false
    private var midGameNoPresentStreakMs: Long = 0L
    private var midGameNoPresentWarned: Boolean = false
    private var midGameNoPresentDialogShown: Boolean = false
    private var midGameLastPresentedCount: Long = -1L
    private var midGameLastPresentTimeNs: Long = -1L

    /**
     * Ronda 0.2.65 (R-P1-3): true cuando el init del núcleo completó
     * en onCreate (en frío) o cuando la sesión ya existía (camino
     * normal). Guarda de onResume: sin sesión, el hilo SDL moriría
     * con SDL_main -1 en silencio.
     */
    private var coreInitReady: Boolean = false

    /**
     * Ronda 0.2.70 (Área 10, P1-9): true cuando el init en frío de
     * onCreate volvió (con éxito o con fallo). @Volatile porque la
     * lee el temporizador de vigilancia desde su propio hilo. No
     * confundir con coreInitReady (sesión lista): un init que falla
     * también "volvió" y el watchdog no debe disparar por él (el
     * fallo ya cierra con mensaje en onCreate).
     */
    @Volatile
    private var coldInitDone: Boolean = false

    /**
     * Ronda 0.2.65 (S-P2-3): última rotación de pantalla leída
     * (constantes Surface.ROTATION_*, 0-3). -1 = aún no leída. El
     * núcleo solo la lee en on_init: si cambia a mitad de partida,
     * el motion sigue con la rotación vieja hasta reiniciar.
     */
    private var lastKnownDisplayRotation: Int = -1

    /**
     * Ronda 0.2.65 (S-P2-4): ámbito IO propio para el trabajo de
     * parada al salir (destroySession). NO se cancela en onDestroy:
     * es el ámbito que hace ese trabajo después de super.onDestroy().
     * Vive solo lo que vive esta Activity: su único hijo es corto
     * (la destrucción de la sesión; la captura de trofeos sigue
     * síncrona en onDestroy, ver ahí por qué).
     */
    private val destroyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ronda 0.2.70 (Área 10, 10-P2-4): trabajo de destrucción de la
     * sesión lanzado en onDestroy; null = aún no lanzado. Al terminar
     * cancela destroyScope (antes el ámbito no se cancelaba nunca y
     * retenía la Activity si el teardown nativo se colgaba).
     */
    private var destroyJob: Job? = null

    // --- Overlay táctil (GamepadOverlayView + mando virtual SDL) ---
    private var overlayView: GamepadOverlayView? = null
    private var overlayAttached: Boolean = false
    /**
     * R6-P1-14: refcount por botón SDL en el punto único de paso
     * (forwardOverlayButton): varios controles pueden mantener el
     * mismo botón (brillo 9 + botón L1, brillo -4 + botón L2, dos
     * botones del editor en el mismo VitaButton). El false solo se
     * envía al soltar el último mantenedor.
     */
    private val overlayButtonRefCounter = SdlButtonRefCounter()
    private var overlayShouldShow: Boolean = false
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputManager: InputManager? = null
    private val gamepadListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshOverlay()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshOverlay()
        override fun onInputDeviceChanged(deviceId: Int) = refreshOverlay()
    }

    private fun refreshOverlay() {
        overlayScope.launch { refreshOverlayState() }
    }

    /**
     * Ronda 0.2.65 (S-P2-8): onResume con UNA sola lectura de
     * ajustes: el overlay y la bandera de pantalla comparten el
     * mismo settingsFlow.first(). Antes refreshOverlay() y
     * applyPerformanceScreenFlag() leían settingsFlow por separado
     * en cada reanudación.
     */
    private fun refreshOverlayAndScreenFlag() {
        overlayScope.launch {
            val settings = runCatching { SettingsRepository(this@EmbeddedGameActivity).settingsFlow.first() }.getOrNull()
            refreshOverlayState(settings)
            applyPerformanceScreenFlag(settings)
        }
    }

    /** SDL carga solo libVita3K.so; SDL3 va estático dentro (libSDL3.a), sin DT_NEEDED. */
    override fun getLibraries(): Array<String> {
        return arrayOf("Vita3K")
    }

    /**
     * REA 0.2.1 oficial (2026-11-09): la superficie del juego DEBE
     * ser EmuSurface. Su surfaceCreated enciende el interruptor
     * `has_surface` del renderizador Vulkan del núcleo (sin él, el
     * tamaño dibujable es {0, 0} y ningún frame se presenta: pantalla
     * negra con el juego corriendo). La SDLSurface plana no lo hace.
     */
    override fun createSDLSurface(context: Context): SDLSurface {
        return EmuSurface(context)
    }

    /** argv de SDL_main: el extra AppStartParameters si existe, si no {"-r", titleId} (ver [resolveLaunchArguments]). */
    override fun getArguments(): Array<String> {
        val extra = intent.getStringArrayExtra(EXTRA_APP_START_PARAMETERS)
        val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        // R5-A3-8: un extra sin "-r" con Title ID conocido se sustituye
        // por {"-r", titleId} en resolveLaunchArguments (SDL_main
        // devuelve -1 sin Title ID); el aviso queda aquí, en el log
        // del launcher, porque la función pura no puede registrarlo.
        if (extra != null && extra.isNotEmpty() && "-r" !in extra && titleId.isNotBlank()) {
            LauncherLog.append(this, "launch", "AppStartParameters sin '-r' ignorado: SDL_main exige '-r' para arrancar; se usa '-r' con el Title ID actual.")
        }
        return resolveLaunchArguments(extra, titleId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // prepareFrontend (SDL_SetMainReady + metadatos + cámara) ANTES
        // de que SDLActivity toque nada nativo. Best-effort y NO fatal
        // (ronda 0.2.65, J-P2-5): si falla, nativeInit sigue sin la
        // cámara (runtime_bridge.cpp: "se sigue sin ella"); solo la
        // cámara puede fallar ahí y el juego no la necesita. El
        // comentario anterior decía que init() lo detectaría y se
        // cerraría con mensaje: falso.
        runCatching { Vita3kCoreBridge.prepareFrontend() }
        super.onCreate(savedInstanceState)

        // REA 0.2.1 oficial: juego a pantalla completa real, como la
        // app oficial (setWindowStyle + inmersivo + cutout). Sin esto
        // las barras del sistema recortan la superficie. Solo API 30+
        // para el controlador de insets; el resto es best-effort.
        runCatching {
            SDLActivity.setWindowStyle(true)
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
            hideSystemBars()
        }

        val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        if (titleId.isEmpty()) {
            // P2-A4a: el ViewModel ya dijo "juego lanzado" al devolver
            // launch() LaunchedEmbedded; anotar el fallo para que
            // MainActivity.onResume corrija el mensaje al volver.
            Vita3KLauncher.noteEmbeddedLaunchFailed("Sin Title ID: no se puede arrancar el juego.")
            Toast.makeText(this, "Sin Title ID: no se puede arrancar el juego.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentTitleId = titleId
        benchmarkMode = intent.getBooleanExtra(EXTRA_BENCHMARK, false)
        launchStartedAtMillis = intent.getLongExtra(EXTRA_LAUNCH_STARTED_AT, 0L)
        val storageRoot = EmbeddedCoreLayout.storageRoot(this)
        // P2-B12: el flujo normal (JUGAR) ya inicializó el núcleo en IO
        // en tryLaunchEmbedded, así que aquí solo se VERIFICA que la
        // sesión existe (isInitialized) y se omite el init en el hilo
        // UI: el segundo init era idempotente en upstream y no aporta
        // nada. Solo en proceso frío (recreación tras muerte del
        // proceso, o lanzamiento directo) se hace el init aquí, en UI
        // como antes: NO se puede pasar a IO con pantalla de carga,
        // porque SDL_main exige emuenv antes de arrancar
        // (main_android.cpp: "Emulator not initialized" -> -1) y el
        // hilo SDL puede arrancar desde surfaceChanged en cuanto
        // onResume corre; si arranca sin sesión, SDLMain.run() termina
        // la Activity con finish(). No hay punto seguro para un init
        // asíncrono sin tocar el ciclo de superficie de SDLActivity.
        // Ronda 0.2.65 (R-P1-3): verificado el orden que hace seguro
        // este init síncrono en frío: SDL.initialize() (en
        // super.onCreate) pone mIsResumedCalled en false, y
        // handleNativeState solo arranca el hilo SDL en RESUMED con
        // mIsResumedCalled en true, que solo pone resumeNativeThread()
        // desde onResume; onResume corre siempre DESPUÉS de que
        // onCreate (con este init) retorne. surfaceChanged puede
        // llegar antes, pero sin mIsResumedCalled no arranca nada.
        // Mover el init a IO exigiría retrasar super.onResume() hasta
        // tener init (o el hilo arrancaría sin emuenv y moriría con
        // -1): reestructuración del ciclo SDL sin teléfono para
        // verificarla; queda DIFERIDA. Guarda mínima: coreInitReady
        // + la comprobación de onResume después de super.onResume().
        if (!Vita3kCoreBridge.isInitialized()) {
            // Ronda 0.2.70 (Área 10, P1-9): el watchdog del init en
            // frío arranca ANTES del init síncrono en el hilo UI (el
            // watchdog de pantalla negra solo existe desde onResume y
            // no cubre este tramo).
            startColdInitWatchdog()
            val coldInitStartedAtMillis = SystemClock.elapsedRealtime()
            val rc = Vita3kCoreBridge.init(storageRoot.absolutePath)
            coldInitDone = true
            if (rc != Vita3kCoreBridge.OK) {
                val failureMessage = Vita3KLauncher.initFailureMessage(rc, Vita3kCoreBridge.lastLoadError)
                Vita3KLauncher.noteEmbeddedLaunchFailed(failureMessage)
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            coreInitReady = true
            // Ronda 0.2.65 (R-P1-3): medir el coste del init en frío
            // en el hilo UI antes de moverlo a IO (cientos de ms a
            // segundos en un Helio G99, según la auditoría).
            LauncherLog.append(this, "launch", "Init en frío en el hilo UI: ${SystemClock.elapsedRealtime() - coldInitStartedAtMillis}ms")
        } else {
            coreInitReady = true
            LauncherLog.append(this, "launch", "Sesión del núcleo ya inicializada (init previo en JUGAR); se omite el init en onCreate")
        }
        // P3: deja constancia de que la Activity SDL se creó y el hilo
        // del juego está a punto de arrancar (el último paso antes de
        // SDL_main). Si el crash fuera aquí, esta sería la última línea.
        LauncherLog.append(this, "launch", "EmbeddedGameActivity creada: $titleId (init OK)")
        setupTouchOverlay()
    }

    /**
     * R4-P1-3: ocultar las barras del sistema (inmersivo). Antes solo
     * se hacía en onCreate y SDLActivity.setWindowStyle es asíncrono,
     * así que cualquier swipe de borde (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
     * las traía de vuelta y se quedaban visibles en la captura del
     * título. Se re-oculta en onResume y al recuperar el foco: el
     * menú de pausa es otra Activity, así que al volver de él onResume
     * y onWindowFocusChanged vuelven a ocultarlas sin tocar su
     * comportamiento. Best-effort y sin núcleo es no-op.
     *
     * Ronda 0.2.65 (S-P2-6): si no se pueden ocultar, queda en el
     * log en vez de tragarse en silencio: sin esto, las barras
     * recortan la superficie y el reporte de bug no dice por qué.
     * En API menor que 30 no hay controlador de insets y no se
     * registra nada (comportamiento esperado, no un fallo).
     */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val controller = runCatching { window.insetsController }.getOrNull()
        if (controller == null) {
            LauncherLog.append(this, "ui", "hideSystemBars: sin controlador de insets (API 30+); las barras del sistema pueden recortar la superficie")
            return
        }
        runCatching {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }.onFailure { e ->
            LauncherLog.append(this, "ui", "hideSystemBars falló: ${e.javaClass.simpleName}: ${e.message}; las barras del sistema pueden recortar la superficie")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Monta el overlay táctil sobre la vista SDL.
     *
     * SDLActivity pone su superficie en un RelativeLayout (mLayout) con
     * setContentView; addContentView añade nuestra vista ENCIMA, en el
     * mismo contenedor. GamepadOverlayView no consume los toques que
     * no son de ningún botón, así que el táctil frontal del juego
     * (Igniculus) sigue llegando a la superficie SDL de debajo.
     * No se usa Compose aquí: SDLActivity es una Activity plana (no
     * ComponentActivity) y el overlay es una vista clásica, sin
     * LifecycleOwner que inventar dentro del juego.
     */
    private fun setupTouchOverlay() {
        val view = GamepadOverlayView(this).apply {
            visibility = View.GONE
            listener = object : GamepadOverlayView.Listener {
                override fun onButton(sdlButton: Int, pressed: Boolean) {
                    forwardOverlayButton(sdlButton, pressed)
                }

                override fun onAxis(sdlAxis: Int, value: Short) {
                    forwardOverlayAxis(sdlAxis, value)
                }
            }
        }
        overlayView = view
        addContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        inputManager = getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(gamepadListener, null)
        refreshOverlay()
    }

    /**
     * Lee el perfil táctil activo + ajustes del overlay y decide si se
     * muestra. Se llama al arrancar, al volver a primer plano y cuando
     * se conecta/desconecta un mando. Sin perfil o sin ajustes leídos,
     * el overlay queda oculto (no se inventa un layout).
     */
    private suspend fun refreshOverlayState() {
        val settings = runCatching { SettingsRepository(this).settingsFlow.first() }.getOrNull()
        refreshOverlayState(settings)
    }

    /**
     * Ronda 0.2.65 (S-P2-8): variante con los ajustes ya leídos
     * (onResume los comparte con la bandera de pantalla). Los demás
     * llamadores usan la variante sin argumentos, que lee aquí.
     */
    private suspend fun refreshOverlayState(settings: LauncherSettings?) {
        val view = overlayView ?: return
        val touchRepo = TouchLayoutRepository(this)
        val controls = runCatching { touchRepo.layoutFlow.first() }.getOrNull() ?: emptyList()
        val activeProfileId = runCatching { touchRepo.activeProfileIdFlow.first() }.getOrNull()
            ?: "default-g99"
        if (settings == null) {
            applyOverlayVisibility(view, show = false, controls, settingsOpacity = 1f, neon = false)
            return
        }
        val gamepadConnected = Vita3KLauncher(this).isGamepadConnected()
        val show = TouchOverlayVisibility.shouldShow(
            showInGame = settings.showTouchOverlayInGame,
            activeProfileId = activeProfileId,
            hiddenProfileIds = settings.touchOverlayHiddenProfileIds,
            hideWithGamepad = settings.hideTouchWhenGamepadConnected,
            gamepadConnected = gamepadConnected,
            controls = controls,
        )
        applyOverlayVisibility(
            view,
            show = show,
            controls = controls,
            settingsOpacity = settings.touchOverlayOpacity,
            neon = settings.touchOverlayNeonGlow,
            profileId = activeProfileId,
        )
    }

    private fun applyOverlayVisibility(
        view: GamepadOverlayView,
        show: Boolean,
        controls: List<com.danielsnagustin.childoflight.launcher.data.TouchControl>,
        settingsOpacity: Float,
        neon: Boolean,
        // 0.2.63 (G): id del perfil activo, para la línea de
        // diagnóstico que GamepadOverlayView escribe al construir
        // el overlay (perfil + tamaño de vista + fracciones del
        // stick). null = desconocido (la vista lo dice así).
        profileId: String? = null,
    ) {
        overlayShouldShow = show
        if (!show) {
            view.releaseAll()
            view.visibility = View.GONE
            detachOverlayController()
            return
        }
        view.generalOpacity = settingsOpacity
        view.neonGlow = neon
        // R6-P2-25: si los controles son los mismos que los ya montados,
        // no llamar a setControls(): reconstruye el overlay y suelta
        // todo lo pulsado (releaseAll) en cada onResume y cada cambio
        // de mando. La opacidad y el neón se aplican igual (solo
        // invalidan, no reconstruyen). Si la vista aún no tiene hijos
        // (primer montaje con ancho 0), sí se monta.
        if (!view.hasSameControls(controls) || view.childCount == 0) {
            view.setControls(controls, profileId)
        }
        view.visibility = View.VISIBLE
        maybeAttachOverlayController()
    }

    /**
     * Ronda 0.2.65 (S-P2-1): LauncherLog.append hace E/S síncrona de
     * fichero. Los append del camino caliente van por aquí a
     * Dispatchers.IO: el hook on_launch (setCurrentGameId, hilo SDL),
     * los reintentos de quitar la pausa y de re-crear el mando, los
     * attach y la recuperación del mando a mitad de partida. Es el
     * patrón que R5-A1-11 ya estableció para el setTouchState. NO es
     * un buffer en memoria con flush en lote: setCurrentGameId se
     * llama UNA vez por lanzamiento (hook on_launch,
     * main_android.cpp:94-99), no por frame, así que un buffer solo
     * añadiría el riesgo de perder el registro si el proceso muere
     * justo cuando más falta (diagnóstico de negro); el envío a IO
     * quita la E/S del hilo SDL y del hilo UI sin retener nada. Los
     * append de onDestroy siguen síncronos a propósito: overlayScope
     * se cancela ahí y el registro de salida no debe perderse.
     */
    private fun appendLogAsync(tag: String, message: String) {
        overlayScope.launch(Dispatchers.IO) {
            LauncherLog.append(this@EmbeddedGameActivity, tag, message)
        }
    }

    /**
     * Ronda 0.2.70 (2-P2-6): variante perezosa de appendLogAsync: el
     * mensaje se calcula DENTRO del launch en Dispatchers.IO, no en el
     * hilo que llama. Para FrameProfileStats.summaryLine(), que ordena
     * hasta 3.600 muestras: ese ordenamiento ya no corre en Main.
     */
    private fun appendLogAsync(tag: String, message: () -> String) {
        overlayScope.launch(Dispatchers.IO) {
            LauncherLog.append(this@EmbeddedGameActivity, tag, message())
        }
    }

    /**
     * Crea el mando virtual SDL del overlay, solo con la partida en
     * curso. attach es idempotente en upstream; si SDL aún no está
     * listo, no crea nada y overlayIsAttached() lo dice: se reintenta
     * en setCurrentGameId / onResume. El táctil se deja en frontal
     * (false): es lo que usa Child of Light para Igniculus.
     *
     * R4-P2-16 (verificado-sin-cambio): el oficial solo llama a
     * setTouchState al pulsar su botón de cambio táctil (legacyId
     * 1024, InputOverlay.onTouch); nosotros lo forzamos a false tras
     * cada attach/rebind. En la práctica es equivalente: el launcher
     * no expone táctil trasero en ningún sitio, así que forzar
     * frontal solo fija el estado que el juego necesita, y el valor
     * por defecto del flag no se pudo verificar. CORRECCIÓN ronda
     * 0.2.66: el módulo touch/ SÍ está en el pin:
     * vita3k/touch/include/touch/functions.h:21 declara
     * set_rear_touchscreen (definida en touch.cpp:32) e
     * input_overlay.cpp:26 la incluye. No se toca el
     * comportamiento sin evidencia de diferencia real.
     */
    private fun maybeAttachOverlayController() {
        // R4-P1-2: cada early-return deja su motivo en el log. Antes
        // el primer intento (en onCreate, sin partida en curso) se
        // saltaba en silencio y el reporte no distinguía "no se
        // intentó" de "se intentó y falló".
        if (!overlayShouldShow) {
            appendLogAsync("overlay", "attach omitido: overlay oculto por ajustes")
            return
        }
        if (overlayAttached) {
            appendLogAsync("overlay", "attach omitido: mando virtual ya activo; el rebind solo ocurre al arrancar el juego (on_launch)")
            return
        }
        if (!Vita3kCoreBridge.isAppRunning()) {
            appendLogAsync("overlay", "attach omitido: sin partida en curso; se reintentará al arrancar el juego")
            return
        }
        if (!Vita3kCoreBridge.overlayAttach()) {
            appendLogAsync("overlay", "attach del mando virtual falló (sin núcleo o sin SDL)")
            return
        }
        if (!Vita3kCoreBridge.overlayIsAttached()) {
            appendLogAsync("overlay", "attach llamado pero el mando virtual no existe en SDL; se reintentará al arrancar el juego")
            return
        }
        overlayAttached = true
        // R4-P2-2: el retorno ya no se ignora: si el táctil no queda
        // en frontal, Igniculus (toque directo) puede no llegar al
        // juego y antes no quedaba ningún aviso. El default del
        // núcleo ya es frontal, así que es solo registro, no reintento.
        // R5-A1-18: el texto ya no afirma que el flag cambiara: el
        // booleano solo prueba que la llamada JNI no lanzó (el
        // setTouchState de upstream es void, sin readback).
        // R5-A1-11: el append va fuera de Main (Dispatchers.IO).
        if (!Vita3kCoreBridge.overlaySetTouchState(false)) {
            appendLogAsync("overlay", "táctil frontal no confirmado (sin readback del núcleo)")
        }
        appendLogAsync("overlay", "mando virtual del overlay activo")
    }

    private fun detachOverlayController() {
        if (!overlayAttached) return
        overlayAttached = false
        // R6-P1-14: el mando virtual destruido pierde todo el estado:
        // sin reset, el contador creería que botones ya soltados siguen
        // mantenidos y el mando nuevo no recibiría sus true.
        overlayButtonRefCounter.reset()
        Vita3kCoreBridge.overlayDetach()
    }

    /**
     * R4-P0-3 + R4-P0-4 (on_launch): el juego arrancó (hook
     * on_launch): SDL está arriba y la sesión activa. Se re-carga el
     * perfil táctil, se RE-CREA el mando virtual (el attach previo
     * pudo ocurrir antes de que el núcleo pudiera registrarlo) y se
     * quita la pausa de fondo con verificación (el clear de onResume
     * pudo descartarse durante la carga). Una sola corrutina para que
     * el rebind corra DESPUÉS de que el refresco decida si el overlay
     * se muestra (overlayShouldShow fresco).
     */
    private fun refreshOverlayRebindAndUnpauseOnLaunch() {
        overlayScope.launch {
            refreshOverlayState()
            rebindOverlayControllerOnLaunch()
            clearBackgroundPauseNow("on_launch")
        }
    }

    /**
     * R4-P0-3: re-crea el mando virtual del overlay al arrancar el
     * juego. Espejo de rebindController del oficial (releaseAllInputs
     * + detachVirtualController + updateVirtualControllerState):
     * soltar lo pulsado, soltar el mando, crearlo de nuevo y verificar
     * que existe en SDL. Sin esto, un attach anterior a on_launch
     * (p. ej. en un onResume durante la carga) dejaba overlayAttached
     * en true y maybeAttach no hacía nada, aunque el núcleo nunca
     * hubiera registrado el mando en state.controllers. 3 intentos
     * con 500 ms entre ellos; cada intento queda en el log.
     */
    private suspend fun rebindOverlayControllerOnLaunch() {
        if (!Vita3kCoreBridge.available) return
        if (!overlayShouldShow) {
            appendLogAsync("overlay", "on_launch: overlay oculto por ajustes; no se re-crea el mando virtual")
            return
        }
        // Soltar lo pulsado ANTES de soltar el mando (releaseAllInputs
        // del oficial): cada botón envía su "soltado" al mando viejo,
        // aún activo, y ningún botón queda atascado en el mando nuevo.
        overlayView?.releaseAll()
        var attached = false
        for (attempt in 1..OVERLAY_REBIND_MAX_ATTEMPTS) {
            overlayAttached = false
            Vita3kCoreBridge.overlayDetach()
            val attachOk = Vita3kCoreBridge.overlayAttach()
            val existsInSdl = Vita3kCoreBridge.overlayIsAttached()
            // R6-P1-15: overlayIsAttached() solo prueba que el mando
            // existe en la enumeración SDL, no que el núcleo lo
            // registrara en state.controllers (ese readback no existe
            // sin modificar las fuentes del pin). El nº de gamepads
            // que ve SDL distingue "no existe" (0) de "existe pero
            // quizá no registrado" (>0 con attach fallido).
            val gamepadCount = Vita3kCoreBridge.overlayGamepadCount()
            if (attachOk && existsInSdl) {
                overlayAttached = true
                // R4-P2-2: igual que en maybeAttachOverlayController:
                // un false queda en el log en vez de ignorarse.
                // R5-A1-18: texto suavizado (sin readback del núcleo)
                // y R5-A1-11: el append va fuera de Main (Dispatchers.IO).
                if (!Vita3kCoreBridge.overlaySetTouchState(false)) {
                    appendLogAsync("overlay", "on_launch: táctil frontal no confirmado (sin readback del núcleo)")
                }
                appendLogAsync("overlay", "on_launch: mando virtual re-creado (intento $attempt/$OVERLAY_REBIND_MAX_ATTEMPTS, gamepads SDL=$gamepadCount)")
                attached = true
                break
            }
            val retryNote = if (attempt < OVERLAY_REBIND_MAX_ATTEMPTS) "; reintentando en 500 ms" else ""
            appendLogAsync("overlay", "on_launch: rebind intento $attempt/$OVERLAY_REBIND_MAX_ATTEMPTS falló (attach=$attachOk, existeEnSdl=$existsInSdl, gamepads SDL=$gamepadCount)$retryNote")
            if (attempt < OVERLAY_REBIND_MAX_ATTEMPTS) delay(OVERLAY_REBIND_RETRY_DELAY_MS)
        }
        if (!attached) {
            appendLogAsync("overlay", "on_launch: no se pudo re-crear el mando virtual tras $OVERLAY_REBIND_MAX_ATTEMPTS intentos; los botones táctiles no llegarán al juego")
        }
    }

    /**
     * P1-14 (ronda 3): si el mando virtual deja de existir a mitad de
     * partida (el núcleo lo suelta, se recrea la sesión), cada pulsación
     * devolvía false y se descartaba en silencio: los botones morían
     * sin ningún aviso y parecía que el juego se había colgado. Ahora
     * un false reintenta el attach UNA vez, reenvía la entrada que se
     * perdió, y si sigue sin mando avisa con un Toast (como mucho uno
     * cada 5 s) y lo deja en el log. El reenvío no reintenta de nuevo:
     * un false ahí solo se registra.
     */
    private var lastOverlayLostToastMillis: Long = 0L

    /**
     * R4-P1-2: cada pulsación queda contada (éxito/fracaso) en
     * OverlayInputStats — el reporte de bug la lee — y el éxito deja
     * una línea de depuración con throttle (como mucho una por botón
     * y segundo). El fracaso nunca se throttlea: la línea "perdido"
     * sale siempre.
     *
     * Ronda 0.2.70 (2-P2-4): los 6 appends de este camino y de
     * forwardOverlayAxis van por appendLogAsync: ya no hay E/S
     * síncrona de fichero en el hilo UI al pulsar botones o mover ejes.
     */
    private fun forwardOverlayButton(sdlButton: Int, pressed: Boolean) {
        // R6-P1-14: refcount por botón SDL. Sin él, soltar un control
        // apagaba el botón que otro seguía manteniendo (brillo 9 +
        // botón L1, brillo -4 + botón L2, dos botones del editor en el
        // mismo VitaButton). Solo el primer mantenedor envía el true
        // y solo el último el false; los demás no tocan el núcleo.
        val shouldSend = if (pressed) {
            overlayButtonRefCounter.press(sdlButton)
        } else {
            overlayButtonRefCounter.release(sdlButton)
        }
        if (!shouldSend) return
        if (Vita3kCoreBridge.overlaySetButton(sdlButton, pressed)) {
            if (OverlayInputStats.recordButton(sdlButton, pressed, delivered = true)) {
                appendLogAsync("overlay", "botón SDL $sdlButton pressed=$pressed")
            }
            return
        }
        if (!recoverOverlayController()) {
            OverlayInputStats.recordButton(sdlButton, pressed, delivered = false)
            return
        }
        if (Vita3kCoreBridge.overlaySetButton(sdlButton, pressed)) {
            if (OverlayInputStats.recordButton(sdlButton, pressed, delivered = true)) {
                appendLogAsync("overlay", "botón SDL $sdlButton pressed=$pressed (tras reintentar el attach)")
            }
            return
        }
        OverlayInputStats.recordButton(sdlButton, pressed, delivered = false)
        appendLogAsync("overlay", "botón $sdlButton perdido tras reintentar el attach")
    }

    private fun forwardOverlayAxis(sdlAxis: Int, value: Short) {
        if (Vita3kCoreBridge.overlaySetAxis(sdlAxis, value)) {
            if (OverlayInputStats.recordAxis(sdlAxis, value, delivered = true)) {
                appendLogAsync("overlay", "eje SDL $sdlAxis valor=$value")
            }
            return
        }
        if (!recoverOverlayController()) {
            OverlayInputStats.recordAxis(sdlAxis, value, delivered = false)
            return
        }
        if (Vita3kCoreBridge.overlaySetAxis(sdlAxis, value)) {
            if (OverlayInputStats.recordAxis(sdlAxis, value, delivered = true)) {
                appendLogAsync("overlay", "eje SDL $sdlAxis valor=$value (tras reintentar el attach)")
            }
            return
        }
        OverlayInputStats.recordAxis(sdlAxis, value, delivered = false)
        appendLogAsync("overlay", "eje $sdlAxis perdido tras reintentar el attach")
    }

    /** Marca el mando como perdido y reintenta el attach. Devuelve true si el mando vuelve a existir. */
    private fun recoverOverlayController(): Boolean {
        overlayAttached = false
        appendLogAsync("overlay", "mando virtual perdido a mitad de partida: reintentando el attach")
        maybeAttachOverlayController()
        if (overlayAttached) return true
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayLostToastMillis > 5_000L) {
            lastOverlayLostToastMillis = now
            Toast.makeText(
                this,
                "El mando táctil se ha perdido. Si los botones no responden, sal y vuelve a entrar al juego.",
                Toast.LENGTH_LONG,
            ).show()
        }
        return false
    }

    // Al irse a segundo plano (Inicio, Recientes, llamada), pausar el
    // NÚCLEO, no solo SDL: PAUSE_REASON_BACKGROUND existe para esto.
    // Sin esto, el juego seguiría corriendo (CPU + audio) con la
    // pantalla apagada. Al volver se quita el motivo de fondo y el
    // juego se reanuda. El motivo de usuario (botón guía) ya no se
    // usa para pausar: el botón guía abre el menú de pausa y
    // openPauseMenu lo quita antes de abrirlo. Best-effort y sin
    // núcleo es no-op (el puente devuelve false).
    override fun onResume() {
        super.onResume()
        // Ronda 0.2.70, arreglo del run #637: marcar reanudada antes
        // de nada (el watchdog y el sondeo SDL leen esta bandera).
        isActivityResumed = true
        // Ronda 0.2.65 (R-P1-3): guarda mínima del init en frío. Sin
        // sesión, el hilo SDL que arranca super.onResume() moriría
        // con SDL_main -1 y la Activity se cerraría en silencio (ver
        // el comentario de onCreate). La comprobación va DESPUÉS de
        // super.onResume() porque el framework exige llamarlo (si no,
        // SuperNotCalledException) y es super.onResume() quien arranca
        // el hilo SDL: la guarda es red de diagnóstico (mensaje +
        // registro), no prevención. En el camino normal nunca dispara:
        // onCreate ya dejó coreInitReady en true.
        if (!coreInitReady && !Vita3kCoreBridge.isInitialized()) {
            appendLogAsync("launch", "onResume sin sesión del núcleo (init en frío no completado): se cierra sin arrancar el juego")
            Toast.makeText(this, "No se pudo preparar el motor del juego.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // R4-P1-3: re-ocultar las barras del sistema al volver a primer
        // plano (del menú de pausa, de Inicio/Recientes o de un swipe
        // que las mostró): en onCreate solo no basta.
        hideSystemBars()
        // R2-P1-11: el juego sigue vivo al volver de Inicio/Recientes
        // o del menú de pausa; MainActivity lo comprueba para no
        // cerrar la medición de tiempo en esos casos.
        GameActivityState.gameActivityAlive = true
        // R4-P0-4: quitar la pausa de fondo CON verificación (antes se
        // ignoraba el Boolean de setPauseReasonEnabled: el núcleo
        // descarta el clear si la fase aún no es Running y el juego
        // quedaba sordo a los botones). Si sigue pausado, se reintenta
        // con pausa y se registra; el reintento definitivo ocurre en
        // on_launch (setCurrentGameId).
        clearBackgroundPause("onResume")
        // R6-P2-20: al volver del menú de pausa o de Recientes, el
        // rebind solo corría en on_launch y se confiaba en el booleano
        // overlayAttached sin re-verificar. Si el núcleo soltó el
        // mando virtual entre medias, el primer botón fallaba y solo
        // entonces actuaba la recuperación por pulsación. Verificación
        // barata con overlayIsAttached() sin caché (la consulta
        // explícita, no la cacheada de SetButton/SetAxis): si ya no
        // existe, se reintenta el attach ahora. La pausa de fondo se
        // quita antes (clearBackgroundPause sigue primero).
        verifyOverlayAttachmentOnResume()
        // Ronda 0.2.65 (S-P2-8): overlay + bandera de pantalla con
        // UNA sola lectura de ajustes (antes dos: refreshOverlay y
        // applyPerformanceScreenFlag leían settingsFlow por separado).
        refreshOverlayAndScreenFlag()
        // Ronda 0.2.65 (S-P2-3): re-leer la rotación de motion.
        checkDisplayRotation("onResume")
        // Ronda 0.2.65 (P0-3): watchdog de pantalla negra.
        maybeStartBlackScreenWatchdog()
        // Ronda 0.2.70 (Área 10, P1-10): sondeo acotado por si el hilo
        // SDL viejo muere después de este onResume (reentrada rápida).
        maybePollDeadSDLThreadForRelaunch()
        // Ronda 0.2.66 (Fase L): perfil de frames (solo si el
        // ajuste frame_profile está ON).
        maybeStartFrameProfiling()
        // Tercera pasada (R-2): detector de pantalla negra en mitad
        // del juego, independiente de frame_profile (siempre activo
        // tras on_launch; la etapa 2 cubre el "sin primer present").
        maybeStartMidGameWatchdog()
        // Ronda 0.2.65 (R-P1-18): la prueba de 30 s ya no
        // auto-arranca 5 s después de on_launch. Si el usuario ya
        // confirmó que está jugando, se reanuda el muestreo; si no,
        // solo se muestra el botón "Ya estoy jugando".
        if (benchmarkStartedByUser) {
            startBenchmarkSamplingJob()
        } else {
            maybeShowBenchmarkPrompt()
        }
    }

    /**
     * Ronda 0.2.65 (S-P2-3): al girar la pantalla a mitad de partida
     * se re-lee la rotación (el núcleo solo la lee en on_init, así
     * que el giro no se aplica al motion hasta reiniciar el juego;
     * ver checkDisplayRotation). super hace lo suyo (locale, modo
     * noche) antes de la lectura.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkDisplayRotation("onConfigurationChanged")
    }

    /**
     * Ronda 0.2.65 (S-P2-3): re-lee la rotación de la pantalla y la
     * compara con la última leída. El núcleo la lee SOLO en on_init
     * (pin main_android.cpp:92): no hay setter JNI para empujarle la
     * rotación nueva, así que esto es detección + registro, no
     * corrección: si cambió, el motion apunta mal hasta reiniciar el
     * juego, y el reporte de bug lo muestra. El arreglo completo
     * (exponer set_display_rotation por JNI) necesita cambio en el
     * pin. La primera lectura solo se guarda, sin registrar nada.
     */
    private fun checkDisplayRotation(tag: String) {
        val current = runCatching { getNativeDisplayRotation() }.getOrNull() ?: return
        val previous = lastKnownDisplayRotation
        lastKnownDisplayRotation = current
        if (previous == -1 || previous == current) return
        LauncherLog.append(this, "motion", "[$tag] rotación de pantalla cambió de $previous a $current: el motion del núcleo se leyó solo en on_init y sigue con la rotación vieja hasta reiniciar el juego (sin setter JNI en el pin)")
    }

    /**
     * Ronda 0.2.65 (P0-3): watchdog de pantalla negra. Arranca una
     * sola vez por Activity (primer onResume, cuando el hilo SDL ya
     * puede arrancar) y espera BLACK_SCREEN_WATCHDOG_TIMEOUT_MS
     * (180 s). Si entonces no hay on_launch ni FPS entregado mayor
     * que 0, el arranque está atascado (init atascado, SDL_main que
     * no arranca sin cerrar la Activity, o renderer que nunca
     * entrega): avisa con Toast + diálogo y cierra la Activity, con
     * registro. Si el juego arrancó (on_launch o FPS mayor que 0),
     * no hace nada. No detecta "juego en curso con imagen negra"
     * (R-P1-1: el FPS del núcleo cuenta frames entregados, no
     * presentados). Se cancela con overlayScope en onDestroy.
     */
    private fun maybeStartBlackScreenWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        overlayScope.launch {
            // Ronda 0.2.70 (Área 10, 10-P2-1): el plazo solo corre con
            // la Activity en primer plano: irse a segundo plano (Inicio
            // / Recientes) durante una carga lenta ya no consume el
            // plazo ni mata una carga legítima. Se sondea en rebanadas
            // de 1 s; solo se descuenta el tiempo con isActivityResumed. No se
            // reestructura el watchdog: mismo launch, mismo timeout.
            var remainingMs = BLACK_SCREEN_WATCHDOG_TIMEOUT_MS
            while (remainingMs > 0L) {
                val sliceStartMillis = SystemClock.uptimeMillis()
                delay(minOf(remainingMs, BLACK_SCREEN_WATCHDOG_SLICE_MS))
                if (!isActive || isFinishing) return@launch
                remainingMs = nextWatchdogRemainingMs(
                    remainingMs,
                    SystemClock.uptimeMillis() - sliceStartMillis,
                    isActivityResumed,
                )
            }
            // La Activity pudo irse a segundo plano justo al agotarse
            // el plazo: no disparar el diálogo sobre una Activity
            // parada; esperar a que vuelva (el plazo ya está agotado).
            while (!isActivityResumed) {
                if (!isActive || isFinishing) return@launch
                delay(BLACK_SCREEN_WATCHDOG_SLICE_MS)
            }
            if (!isActive || isFinishing) return@launch
            val fps = runCatching { Vita3kCoreBridge.getFps() }.getOrDefault(-1f)
            if (!shouldFireBlackScreenWatchdog(gameRunningSinceMillis, firstFrameLogged, fps)) return@launch
            appendLogAsync("launch", "Watchdog de pantalla negra: sin on_launch ni FPS entregado mayor que 0 tras ${BLACK_SCREEN_WATCHDOG_TIMEOUT_MS / 1000} s de juego visible desde el primer onResume (el tiempo en segundo plano no cuenta); el arranque está atascado y se cierra la Activity")
            Toast.makeText(this@EmbeddedGameActivity, "El juego no ha arrancado: sin imagen tras 3 minutos de juego visible.", Toast.LENGTH_LONG).show()
            showBlackScreenDialogAndFinish()
        }
    }

    /**
     * Ronda 0.2.70 (9-P1-7): watchdog de SEGUNDA ETAPA. Arranca una
     * sola vez por Activity, en on_launch, y espera
     * SECOND_STAGE_WATCHDOG_TIMEOUT_MS (180 s). Si entonces el juego
     * confirmó on_launch pero el contador presentKHR del gancho nº 1
     * sigue a cero (ningún frame presentado: carga del módulo,
     * renderer o precompilado colgados, o negro con fps > 0), mismo
     * diálogo de "no arrancó" y cierre que el watchdog de arranque.
     * Si ya se presentó al menos un frame, no hace nada: la partida
     * muestra imagen. Si el contador es -1 (sin núcleo o sin parche),
     * tampoco dispara: es "desconocido", no "sin presents", y matar
     * el juego por un contador ausente sería un falso positivo.
     * Tercera pasada (R-1): el plazo congela en segundo plano como
     * la etapa 1 (mismo sondeo en rebanadas de 1 s con
     * nextWatchdogRemainingMs y misma espera a estar reanudada
     * antes de disparar): antes era un delay(180 s) de pared que
     * mataba cargas legítimas si el usuario se iba a Inicio o
     * abría el menú de pausa en plena carga. Los contadores se
     * re-leen justo antes de disparar, tras la espera a primer
     * plano, para no decidir con una lectura vieja. Job propio en
     * overlayScope (se cancela en onDestroy); no toca el bloque
     * del watchdog de arranque.
     */
    private fun maybeStartSecondStageWatchdog() {
        if (secondStageWatchdogStarted) return
        secondStageWatchdogStarted = true
        overlayScope.launch {
            var remainingMs = SECOND_STAGE_WATCHDOG_TIMEOUT_MS
            while (remainingMs > 0L) {
                val sliceStartMillis = SystemClock.uptimeMillis()
                delay(minOf(remainingMs, BLACK_SCREEN_WATCHDOG_SLICE_MS))
                if (!isActive || isFinishing) return@launch
                remainingMs = nextWatchdogRemainingMs(
                    remainingMs,
                    SystemClock.uptimeMillis() - sliceStartMillis,
                    isActivityResumed,
                )
            }
            // Igual que la etapa 1: no disparar el diálogo sobre una
            // Activity parada; esperar a que vuelva (el plazo ya está
            // agotado) y re-leer los contadores entonces.
            while (!isActivityResumed) {
                if (!isActive || isFinishing) return@launch
                delay(BLACK_SCREEN_WATCHDOG_SLICE_MS)
            }
            if (!isActive || isFinishing || isDestroyed) return@launch
            val presentedCount = runCatching { Vita3kCoreBridge.getPresentedFrameCount() }.getOrDefault(-1L)
            val lastPresentTimeNs = runCatching { Vita3kCoreBridge.getLastPresentTimeNs() }.getOrDefault(-1L)
            if (!shouldFireSecondStageWatchdog(gameRunningSinceMillis, presentedCount, lastPresentTimeNs)) return@launch
            appendLogAsync("launch", "Watchdog de segunda etapa: on_launch confirmado pero ningún frame presentado tras ${SECOND_STAGE_WATCHDOG_TIMEOUT_MS / 1000} s de juego visible (el tiempo en segundo plano no cuenta); el juego emula sin imagen y se cierra la Activity")
            Toast.makeText(this@EmbeddedGameActivity, "El juego no ha arrancado: sin imagen tras 3 minutos de juego visible.", Toast.LENGTH_LONG).show()
            showBlackScreenDialogAndFinish()
        }
    }

    /**
     * Tercera pasada (R-2): detector de pantalla negra EN MITAD DEL
     * JUEGO, independiente de frame_profile (OFF por defecto). Corre
     * en overlayScope (se cancela en onDestroy) y sondea el contador
     * de presents cada 1 s: solo descuenta con la Activity reanudada
     * (en segundo plano el tick se salta sin sumar ni resetear la
     * racha: congelación, como la etapa 1) y solo si el juego no
     * está pausado. Sin on_launch aún, no hace nada (lo cubre la
     * etapa 2); con contador -1, inerte (desconocido, no "sin
     * presents"). Aviso (Toast + log) a los 15 s de racha; diálogo
     * con recuperación a los 60 s, con re-chequeo de los contadores
     * justo antes de mostrarlo. El diálogo NUNCA mata la partida
     * por sí solo: "Seguir esperando" rearma el detector y "Salir"
     * cierra; si no se puede mostrar, se rearma sin cerrar nada.
     */
    private fun maybeStartMidGameWatchdog() {
        if (midGameWatchdogStarted) return
        midGameWatchdogStarted = true
        overlayScope.launch {
            while (isActive) {
                delay(MID_GAME_WATCHDOG_TICK_MS)
                if (!isActive || isFinishing || isDestroyed) return@launch
                // Congelar en segundo plano: ni suma ni resetea.
                if (!isActivityResumed) continue
                // Sin on_launch aún, la etapa 2 cubre el arranque.
                if (gameRunningSinceMillis == 0L) continue
                val presentedCount = runCatching { Vita3kCoreBridge.getPresentedFrameCount() }.getOrDefault(-1L)
                val lastPresentTimeNs = runCatching { Vita3kCoreBridge.getLastPresentTimeNs() }.getOrDefault(-1L)
                val fps = runCatching { Vita3kCoreBridge.getFps() }.getOrDefault(-1f)
                val paused = runCatching { Vita3kCoreBridge.isAppPaused() }.getOrDefault(false)
                val previousCount = midGameLastPresentedCount
                val previousTimeNs = midGameLastPresentTimeNs
                midGameNoPresentStreakMs = nextMidGameNoPresentStreakMs(
                    midGameNoPresentStreakMs,
                    previousCount,
                    presentedCount,
                    previousTimeNs,
                    lastPresentTimeNs,
                    fps,
                    paused,
                    MID_GAME_WATCHDOG_TICK_MS,
                )
                if (presentedCount >= 0L) {
                    midGameLastPresentedCount = presentedCount
                    midGameLastPresentTimeNs = lastPresentTimeNs
                }
                if (midGameNoPresentStreakMs == 0L) midGameNoPresentWarned = false
                if (shouldWarnNoPresent(midGameNoPresentStreakMs, midGameNoPresentWarned, MID_GAME_NO_PRESENT_WARN_AFTER_MS)) {
                    midGameNoPresentWarned = true
                    appendLogAsync("frameprof", "El juego emula a ${"%.1f".format(fps)} fps pero no presenta imagen desde hace ${MID_GAME_NO_PRESENT_WARN_AFTER_MS / 1000} s de juego visible: nada llega al panel")
                    Toast.makeText(this@EmbeddedGameActivity, "El juego corre pero no presenta imagen", Toast.LENGTH_LONG).show()
                }
                if (!shouldFireMidGameNoPresentDialog(midGameNoPresentStreakMs, MID_GAME_NO_PRESENT_DIALOG_AFTER_MS, midGameNoPresentDialogShown)) continue
                midGameNoPresentDialogShown = true
                // Re-chequeo justo antes del diálogo: un present
                // entre el tick y este punto cancela el diálogo y
                // resetea la racha (no se decide con lectura vieja).
                val freshCount = runCatching { Vita3kCoreBridge.getPresentedFrameCount() }.getOrDefault(-1L)
                val freshTimeNs = runCatching { Vita3kCoreBridge.getLastPresentTimeNs() }.getOrDefault(-1L)
                if (freshCount > presentedCount || freshTimeNs > lastPresentTimeNs) {
                    midGameNoPresentStreakMs = 0L
                    midGameNoPresentWarned = false
                    midGameNoPresentDialogShown = false
                    if (freshCount >= 0L) {
                        midGameLastPresentedCount = freshCount
                        midGameLastPresentTimeNs = freshTimeNs
                    }
                    continue
                }
                if (!isActive || isFinishing || isDestroyed || !isActivityResumed) continue
                appendLogAsync("frameprof", "Pantalla negra en mitad del juego: el juego emula pero no presenta imagen desde hace ${MID_GAME_NO_PRESENT_DIALOG_AFTER_MS / 1000} s de juego visible; se ofrece recuperación")
                showMidGameNoPresentDialog()
            }
        }
    }

    /**
     * Tercera pasada (R-2): diálogo de recuperación del detector de
     * mitad de partida. "Seguir esperando" rearma el detector
     * (racha a 0, aviso y diálogo reseteados: si sigue atascado,
     * volverá a avisar a los 15 s y a preguntar a los 60 s) y
     * "Salir" cierra la Activity. Cancelable: descartarlo equivale
     * a seguir esperando. Si no se puede mostrar, se rearma sin
     * cerrar nada: un diálogo fallido nunca mata la partida.
     */
    private fun showMidGameNoPresentDialog() {
        val rearm = {
            midGameNoPresentStreakMs = 0L
            midGameNoPresentWarned = false
            midGameNoPresentDialogShown = false
        }
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("El juego no muestra imagen")
                .setMessage("El juego sigue corriendo pero no presenta imagen desde hace 1 minuto de juego visible (el tiempo en segundo plano no cuenta). A veces una carga larga tarda: puedes seguir esperando o salir al inicio e intentarlo de nuevo.")
                .setPositiveButton("Seguir esperando") { _, _ -> rearm() }
                .setNegativeButton("Salir") { _, _ -> finish() }
                .setCancelable(true)
                .setOnCancelListener { rearm() }
                .show()
        }.onFailure {
            rearm()
        }
    }

    /**
     * Ronda 0.2.65 (P0-3): diálogo del watchdog. No cancelable: sin
     * arranque no hay partida a la que volver; "Salir" cierra la
     * Activity y el usuario puede reintentarlo desde el inicio. Si el
     * diálogo no se puede mostrar, se cierra directamente.
     */
    private fun showBlackScreenDialogAndFinish() {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("El juego no ha arrancado")
                .setMessage("Pasaron 3 minutos de juego visible sin que el juego confirmara el arranque ni entregara imágenes (el tiempo en segundo plano no cuenta). Puede que el arranque se haya quedado atascado (por ejemplo, cargando el juego o en la precompilación de shaders (si la tienes activada)). Puedes intentarlo de nuevo desde el inicio.")
                .setPositiveButton("Salir") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }.onFailure {
            finish()
        }
    }

    /**
     * Ronda 0.2.70 (Área 10, P1-9): temporizador de vigilancia del
     * init en frío. Corre en un hilo propio (demonio, 30 s): una
     * corrutina Main no avanzaría mientras el init síncrono bloquea
     * el hilo UI, así que no serviría de vigilancia. Si el init no
     * volvió en el plazo, registra el colgado y pide el cierre con
     * mensaje en el hilo principal. El post al hilo principal solo
     * se ejecuta cuando el hilo UI se libera, así que re-comprueba
     * coldInitDone ahí: un init lento pero sano no se cierra por
     * llegar tarde (el registro del hilo vigilante puede quedar como
     * falso positivo de segundos en ese caso).
     */
    private fun startColdInitWatchdog() {
        val startedAtMillis = SystemClock.elapsedRealtime()
        Thread(
            {
                val slept = runCatching { Thread.sleep(COLD_INIT_WATCHDOG_TIMEOUT_MS) }.isSuccess
                if (!slept) return@Thread
                if (!shouldFireColdInitWatchdog(coldInitDone, SystemClock.elapsedRealtime() - startedAtMillis, COLD_INIT_WATCHDOG_TIMEOUT_MS)) return@Thread
                LauncherLog.append(applicationContext, "launch", "Init en frío sin volver tras ${COLD_INIT_WATCHDOG_TIMEOUT_MS / 1000} s en el hilo UI: el init del núcleo parece colgado; se cierra la Activity")
                runOnUiThread {
                    if (coldInitDone || !isAliveForUiWork(isFinishing, isDestroyed)) return@runOnUiThread
                    Toast.makeText(this, "El motor no respondió al arrancar (init colgado).", Toast.LENGTH_LONG).show()
                    finish()
                }
            },
            "cold-init-watchdog",
        ).apply { isDaemon = true; start() }
    }

    /**
     * Ronda 0.2.70 (Área 10, P1-10): sondeo acotado tras onResume
     * para la reentrada rápida. Si el hilo SDL viejo sigue vivo, el
     * onResume llamó nativeResume() sobre ese hilo moribundo y,
     * cuando muera, nadie volverá a arrancar SDL_main (negro hasta
     * girar o ir a segundo plano y volver). El sondeo detecta esa
     * muerte y relanza SDL_main con restartSDLMainIfThreadDead().
     * Sale en cuanto hay partida en curso, la Activity deja de estar
     * reanudada o se agota el tope de 30 s; un sondeo anterior se
     * cancela al arrancar uno nuevo.
     */
    private fun maybePollDeadSDLThreadForRelaunch() {
        sdlRelaunchPollJob?.cancel()
        sdlRelaunchPollJob = overlayScope.launch {
            val deadlineMillis = SystemClock.uptimeMillis() + SDL_RELAUNCH_POLL_TIMEOUT_MS
            while (isActive) {
                if (isFinishing || isDestroyed) return@launch
                // Irse a segundo plano se reintenta en el próximo onResume.
                if (!isActivityResumed) return@launch
                // Partida en curso: nada que relanzar; el sondeo termina.
                val appRunning = Vita3kCoreBridge.isAppRunning()
                val gameConfirmed = gameRunningSinceMillis != 0L
                if (appRunning || gameConfirmed) return@launch
                if (shouldRelaunchDeadSDLThread(SDLActivity.isSDLThreadAlive(), appRunning, gameConfirmed)) {
                    appendLogAsync("launch", "Hilo SDL viejo murió tras el onResume (reentrada rápida): se relanza SDL_main")
                    SDLActivity.restartSDLMainIfThreadDead()
                    return@launch
                }
                if (SystemClock.uptimeMillis() >= deadlineMillis) return@launch
                delay(SDL_RELAUNCH_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * R6-P2-20: re-verifica el mando virtual al volver a primer plano.
     * Solo si overlayAttached era true: si el mando ya no existe en
     * SDL, se marca como perdido y se reintenta el attach (maybeAttach
     * exige partida en curso y overlay visible, como siempre).
     */
    private fun verifyOverlayAttachmentOnResume() {
        if (!overlayAttached) return
        if (Vita3kCoreBridge.overlayIsAttached()) return
        appendLogAsync("overlay", "onResume: el mando virtual ya no existe en SDL tras volver a primer plano; reintentando el attach")
        overlayAttached = false
        maybeAttachOverlayController()
    }

    override fun onPause() {
        // Ronda 0.2.70, arreglo del run #637: al pausar ya no está
        // reanudada (el watchdog congela su plazo hasta que vuelva).
        isActivityResumed = false
        // R2-P1-11: SOLO se limpia la bandera al salir de verdad
        // (finish() ya llamado, p. ej. desde onActivityResult tras
        // "Salir al inicio"): ir a segundo plano (Inicio, Recientes,
        // llamada, menú de pausa) deja la Activity viva y la bandera
        // en true, para que MainActivity no cierre la medición.
        if (isFinishing) GameActivityState.gameActivityAlive = false
        // Soltar todo lo pulsado al pausar: sin botones atascados al volver.
        overlayView?.releaseAll()
        stopBenchmarkSamplingAndSave()
        // Ronda 0.2.66 (Fase L): parar el perfil de frames al
        // pausar (se reanuda en onResume; los agregados se conservan).
        stopFrameProfiling()
        // R4-P2-15: el motivo de pausa BACKGROUND se fija ANTES de
        // super.onPause(), como el oficial (suspendForBackgroundIfNeeded
        // antes de super.onPause()): antes se fijaba después y el juego
        // corría unos frames sin pausar al salir a segundo plano.
        // R5-A3-5: el retorno del set ya no se ignora (igual que el
        // clear en R4-P0-4): el núcleo devuelve false sin tocar nada
        // si la fase no es Running, y sin registro el juego seguiría
        // corriendo en segundo plano sin que nadie lo supiera. No hay
        // getter de fase en el puente (no se inventa JNI): el log
        // registra lo observable (pausado / en curso).
        val pauseSetOk = runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_BACKGROUND,
                true,
            )
        }.getOrDefault(false)
        if (!pauseSetOk) {
            LauncherLog.append(
                this,
                "pause",
                "onPause: el núcleo rechazó fijar la pausa de fondo (fase distinta de Running o sin núcleo); el juego puede seguir corriendo en segundo plano (pausado=${Vita3kCoreBridge.isAppPaused()}, en curso=${Vita3kCoreBridge.isAppRunning()})",
            )
        }
        super.onPause()
    }

    /**
     * R4-P0-4: quita la pausa de fondo CON verificación. El núcleo
     * descarta el clear si la fase aún no es Running
     * (session_controller.cpp: set_pause_reason devuelve false sin
     * tocar nada), así que un clear sin verificar deja el juego
     * sordo a los botones (drop_inputs) con imagen y sonido intactos.
     * Lanza la corrutina que hace los intentos; el primer intento
     * corre en el acto (Main.immediate, ya en el hilo UI).
     */
    private fun clearBackgroundPause(tag: String) {
        overlayScope.launch { clearBackgroundPauseNow(tag) }
    }

    /**
     * R4-P0-4: intentos de quitar la pausa de fondo con verificación
     * (isAppPaused()==false). Si no hay nada que quitar, silencio:
     * onResume corre a cada vuelta a primer plano y no se ensucia el
     * log. Si el clear se rechaza (fase distinta de Running), se
     * reintenta hasta 3 veces con 200 ms (ronda 0.2.65, S-P2-7: antes
     * 500 ms, 1,5 s de botones sordos en el peor caso; ahora 0,6 s);
     * si el juego sigue pausado por OTRO motivo (usuario/menú), se
     * registra y no se toca ese motivo: no pertenece a este camino.
     */
    private suspend fun clearBackgroundPauseNow(tag: String) {
        if (!Vita3kCoreBridge.available) return
        if (!Vita3kCoreBridge.isAppPaused()) return
        for (attempt in 1..PAUSE_CLEAR_MAX_ATTEMPTS) {
            val setOk = runCatching {
                Vita3kCoreBridge.setPauseReasonEnabled(
                    Vita3kCoreBridge.PAUSE_REASON_BACKGROUND,
                    false,
                )
            }.getOrDefault(false)
            val stillPaused = Vita3kCoreBridge.isAppPaused()
            when (decidePauseClearAttempt(setOk, stillPaused, attempt, PAUSE_CLEAR_MAX_ATTEMPTS)) {
                PauseClearOutcome.CLEARED -> {
                    appendLogAsync("pause", "[$tag] pausa de fondo quitada (intento $attempt/$PAUSE_CLEAR_MAX_ATTEMPTS)")
                    return
                }
                PauseClearOutcome.STILL_PAUSED_OTHER_REASON -> {
                    appendLogAsync("pause", "[$tag] pausa de fondo quitada, pero el juego sigue pausado por otro motivo (usuario/menú); no se toca ese motivo")
                    return
                }
                PauseClearOutcome.CLEAR_REJECTED_RETRY -> {
                    // Ronda 0.2.65 (S-P2-7): mientras el clear se
                    // rechaza, el juego está pausado y sordo a los
                    // botones. Avisar al volver a primer plano (solo
                    // en onResume, solo el primer intento): sin esto
                    // el usuario ve "no responde" hasta 0,6 s sin
                    // saber por qué. En on_launch no se avisa: el
                    // juego está arrancando, no reanudando.
                    if (attempt == 1 && tag == "onResume") {
                        Toast.makeText(this, "Reanudando el juego.", Toast.LENGTH_SHORT).show()
                    }
                    appendLogAsync("pause", "[$tag] el núcleo rechazó quitar la pausa de fondo (intento $attempt/$PAUSE_CLEAR_MAX_ATTEMPTS, fase distinta de Running); reintentando en ${PAUSE_CLEAR_RETRY_DELAY_MS}ms")
                    delay(PAUSE_CLEAR_RETRY_DELAY_MS)
                }
                PauseClearOutcome.CLEAR_REJECTED_FAILED -> {
                    appendLogAsync("pause", "[$tag] no se pudo quitar la pausa de fondo tras $PAUSE_CLEAR_MAX_ATTEMPTS intentos (fase distinta de Running); el juego puede quedar sordo a los botones hasta el próximo arranque")
                }
            }
        }
    }

    /**
     * Prueba de 30 s (auditoría de funcionalidad 2026-11-08): si el
     * lanzamiento vino con benchmark=true, muestrea el FPS entregado
     * por el juego (Vita3kCoreBridge.getFps(), que lee
     * EmuEnvState::fps: frames EMULADOS, no presentados en pantalla)
     * cada segundo mientras el juego está en primer plano, hasta 30
     * muestras válidas (~30 s de juego real). Al salir (onPause) guarda
     * la media en DataStore; el launcher la consume
     * una sola vez al volver y registra la prueba solo. Sin muestras
     * válidas no se guarda nada: la prueba queda para registro
     * manual. El muestreo no toca el juego: es una lectura JNI por
     * segundo, sin escribir nada en el núcleo.
     *
     * Ronda 0.2.65 (R-P1-18): el muestreo SOLO arranca cuando el
     * usuario confirma que ya está jugando (botón "Ya estoy
     * jugando"). El auto-arranque 5 s después de on_launch medía la
     * pantalla de título, no el juego: entre on_launch y el gameplay
     * hay que pulsar Start, navegar el menú y esperar la carga
     * (15-40 s), así que las 30 muestras caían casi enteras en
     * título/menús/carga. Sin confirmación no se muestrea nada
     * (fallo seguro: mejor sin prueba que con una media de menús).
     * Si el núcleo nunca confirma el arranque, no se muestra ni el
     * botón. La nota de GpuDiagnosticsScreen la actualiza el agente
     * 6 para que coincida.
     */
    private fun maybeShowBenchmarkPrompt() {
        // Ronda 0.2.70 (Área 10, P1-8): segunda guarda de la misma
        // familia: addContentView sobre una Activity destruida tira
        // BadTokenException (puede llamarse también desde onResume).
        if (!isAliveForUiWork(isFinishing, isDestroyed)) return
        if (!benchmarkMode || benchmarkSaved || benchmarkStartedByUser) return
        if (!Vita3kCoreBridge.available) return
        // Juego aún no confirmado por el núcleo: no mostrar el botón;
        // cuando llegue setCurrentGameId se reintentará desde ahí.
        if (gameRunningSinceMillis == 0L) return
        if (benchmarkPromptButton != null) return
        val button = Button(this).apply {
            text = "Ya estoy jugando: medir 30 s"
            setOnClickListener { startBenchmarkSamplingByUser() }
        }
        val params = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            bottomMargin = (32 * resources.displayMetrics.density).toInt()
        }
        addContentView(button, params)
        benchmarkPromptButton = button
    }

    /**
     * Ronda 0.2.65 (R-P1-18): el usuario confirmó que ya está
     * jugando. Esconde el botón y arranca el muestreo de 30 s sin
     * margen: el usuario ya está en partida, no en el título. Si el
     * usuario pausa a mitad, el muestreo se reanuda solo al volver
     * (onResume llama a startBenchmarkSamplingJob sin volver a
     * preguntar).
     */
    private fun startBenchmarkSamplingByUser() {
        if (benchmarkStartedByUser) return
        benchmarkStartedByUser = true
        benchmarkPromptButton?.visibility = View.GONE
        Toast.makeText(this, "Midiendo FPS durante 30 s de juego.", Toast.LENGTH_SHORT).show()
        appendLogAsync("benchmark", "Prueba de 30 s iniciada por el usuario (ya está jugando); se muestrea el FPS entregado por el juego, no el presentado en pantalla")
        startBenchmarkSamplingJob()
    }

    /**
     * Arranca (o reanuda tras una pausa) el bucle de muestreo de la
     * prueba de 30 s: 1 muestra por segundo hasta 30 muestras
     * válidas. Sin margen inicial: quien llama ya confirmó que hay
     * partida en curso. Al completar las 30 muestras avisa con un
     * Toast; el guardado sigue en stopBenchmarkSamplingAndSave (al
     * salir, solo con las 30 muestras).
     */
    private fun startBenchmarkSamplingJob() {
        if (!benchmarkMode || benchmarkSaved || !Vita3kCoreBridge.available) return
        if (benchmarkJob?.isActive == true) return
        // Prueba completa: 30 muestras válidas. Sin el tope, el muestreo
        // medía toda la sesión en vez de 30 s.
        if (benchmarkSamples.size >= BENCHMARK_MAX_SAMPLES) return
        benchmarkJob = overlayScope.launch {
            while (isActive && benchmarkSamples.size < BENCHMARK_MAX_SAMPLES) {
                val fps = runCatching { Vita3kCoreBridge.getFps() }.getOrDefault(-1f)
                if (fps > 0f) benchmarkSamples += fps
                delay(1000)
            }
            if (benchmarkSamples.size >= BENCHMARK_MAX_SAMPLES) {
                Toast.makeText(this@EmbeddedGameActivity, "Prueba de 30 s completa.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Ronda 0.2.66 (Fase L, INST-P0-2/INST-P0-3): arranca el perfil
     * de frames si el ajuste `frame_profile` está ON: Choreographer
     * (cadencia de vsync), FrameMetrics (tiempo del overlay Views)
     * y sondeo a 4 Hz de getFps() + contador de presentados (gancho
     * nº 1, INST-P0-3: presents/s frente a fps emulado). El sondeo a
     * 4 Hz NO crea datos por frame: getFps() sigue siendo el
     * agregado de 1 s del núcleo (INST-P2-1, constancia), así que
     * 4 Hz solo suaviza la lectura. Una línea agregada por segundo
     * al log (tag frameprof, throttle estructural de 1 s en
     * FrameProfileStats). Sin núcleo no se arranca nada. La primera
     * vez en esta Activity se resetean los contadores (un JUGAR
     * nuevo mide limpio); volver del menú de pausa no los borra.
     */
    private fun maybeStartFrameProfiling() {
        if (frameProfileActive) return
        overlayScope.launch {
            if (isFinishing) return@launch
            val enabled = runCatching {
                SettingsRepository(this@EmbeddedGameActivity).settingsFlow.first()
            }.getOrNull()?.frameProfile ?: false
            if (!enabled || !Vita3kCoreBridge.available) return@launch
            if (frameProfileActive || isFinishing) return@launch
            if (!frameProfileStartedThisActivity) {
                FrameProfileStats.reset()
                frameProfileStartedThisActivity = true
            }
            frameProfileActive = true
            Choreographer.getInstance().postFrameCallback(frameProfileChoreographerCallback)
            runCatching {
                window.addOnFrameMetricsAvailableListener(
                    frameProfileMetricsListener,
                    Handler(Looper.getMainLooper()),
                )
            }
            frameProfileJob = overlayScope.launch {
                var ticks = 0
                while (isActive && frameProfileActive) {
                    val fps = runCatching { Vita3kCoreBridge.getFps() }.getOrDefault(-1f)
                    if (fps > 0f) FrameProfileStats.recordEmulatedFps(fps)
                    // Gancho nº 1 (INST-P0-3): presents/s junto a
                    // getFps(), en el mismo tick: la comparación
                    // emulado vs presentado es la prueba de pantalla
                    // negra. -1 = sin núcleo o sin parche: se ignora
                    // dentro de recordPresented.
                    val presentedCount = runCatching { Vita3kCoreBridge.getPresentedFrameCount() }.getOrDefault(-1L)
                    val presentedTimeNs = runCatching { Vita3kCoreBridge.getLastPresentTimeNs() }.getOrDefault(-1L)
                    FrameProfileStats.recordPresented(presentedCount, presentedTimeNs)
                    // Ronda 0.2.70 (9-P1-7): "emula pero no presenta"
                    // en caliente (solo con frame_profile ON, que es
                    // cuando corre este sondeo). La racha usa la
                    // ÚLTIMA muestra de presents/s, no la media de
                    // 60 s del snapshot: con la media, un juego que
                    // presentó bien y se queda en negro a mitad de
                    // partida tardaría hasta un minuto en bajar de 1/s
                    // y el aviso llegaría tarde. Una sola vez por
                    // lanzamiento; la pausa no reinicia la racha (el
                    // sondeo se para en onPause y la racha solo crece
                    // con ticks activos).
                    val snap = FrameProfileStats.snapshot()
                    noPresentStreakMs = updateNoPresentStreakMs(noPresentStreakMs, snap.presentedPerSecond.last, snap.emulatedFps.last, FRAME_PROFILE_POLL_TICK_MS)
                    if (shouldWarnNoPresent(noPresentStreakMs, noPresentWarned, NO_PRESENT_WARN_AFTER_MS)) {
                        noPresentWarned = true
                        appendLogAsync("frameprof", "El juego emula a ${"%.1f".format(snap.emulatedFps.last)} fps pero presenta ~0/s desde hace ${NO_PRESENT_WARN_AFTER_MS / 1000} s: nada llega al panel")
                        Toast.makeText(this@EmbeddedGameActivity, "El juego corre pero no presenta imagen", Toast.LENGTH_LONG).show()
                    }
                    ticks++
                    if (ticks % 4 == 0 && FrameProfileStats.shouldLogSummary(System.currentTimeMillis())) {
                        appendLogAsync("frameprof") { FrameProfileStats.summaryLine() }
                    }
                    delay(FRAME_PROFILE_POLL_TICK_MS)
                }
            }
            appendLogAsync("frameprof", "Perfil de frames activado (Fase L + gancho 1): cadencia de vsync + overlay Views + FPS emulado y presents/s a 4 Hz")
        }
    }

    /**
     * Ronda 0.2.66 (Fase L): para el perfil de frames (onPause y
     * onDestroy). Quita el callback de Choreographer y el oyente de
     * FrameMetrics: sin esto el callback retendría la Activity. Los
     * agregados ya medidos se conservan en FrameProfileStats hasta
     * el próximo JUGAR (o hasta que el reporte los lea).
     */
    private fun stopFrameProfiling() {
        if (!frameProfileActive && frameProfileJob == null) return
        frameProfileActive = false
        frameProfileJob?.cancel()
        frameProfileJob = null
        Choreographer.getInstance().removeFrameCallback(frameProfileChoreographerCallback)
        runCatching { window.removeOnFrameMetricsAvailableListener(frameProfileMetricsListener) }
    }

    /**
     * Ronda 0.2.64 (H-4, T2) + 0.2.65 (R-P1-1, S-P2-2): primer frame
     * entregado por el juego tras on_launch. Sondea getFps() hasta el
     * primer FPS > 0 y registra "Primer frame entregado por el juego
     * tras on_launch (T2 ≈ X ms desde JUGAR)" una sola vez. La
     * etiqueta es honesta a propósito: getFps() lee
     * EmuEnvState::fps, que cuenta frames que el juego ENTREGA al
     * emulador (sceDisplaySetFrameBuf), ANTES del render Vulkan; con
     * pantalla negra por superficie perdida, fps es mayor que 0
     * igualmente. T2 NO prueba que el juego se vea: la señal
     * correcta (frames presentados) necesita un contador nuevo en el
     * pin. La pantalla de título también entrega frames: T2 marca
     * el primer frame entregado, NO el gameplay interactivo. El
     * sondeo va cada 200 ms los primeros 10 s (antes 1 s siempre:
     * sobrestimaba T2 hasta 2 s) y luego 1 s. Corre en overlayScope
     * (se cancela en onDestroy); sin t0 no registra nada.
     */
    private fun maybeStartFirstFramePoll() {
        if (launchStartedAtMillis <= 0 || firstFrameLogged) return
        val pollStartedAtMillis = SystemClock.elapsedRealtime()
        overlayScope.launch {
            while (isActive && !firstFrameLogged) {
                val fps = runCatching { Vita3kCoreBridge.getFps() }.getOrDefault(-1f)
                if (fps > 0f) {
                    firstFrameLogged = true
                    val t2 = SystemClock.elapsedRealtime() - launchStartedAtMillis
                    appendLogAsync("launch", "Primer frame entregado por el juego tras on_launch (T2 ≈ ${t2}ms desde JUGAR; frames entregados, NO presentados en pantalla: no prueba que el juego se vea)")
                    return@launch
                }
                val elapsed = SystemClock.elapsedRealtime() - pollStartedAtMillis
                delay(if (elapsed < FIRST_FRAME_POLL_FAST_WINDOW_MS) FIRST_FRAME_POLL_FAST_DELAY_MS else 1_000L)
            }
        }
    }

    private fun stopBenchmarkSamplingAndSave() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        if (!benchmarkMode || benchmarkSaved || benchmarkSamples.isEmpty()) return
        // P1-3: una media PARCIAL (p. ej. 6 s por abrir el menú de
        // pausa) NO es la prueba completa de 30 s: no se guarda nada
        // en DataStore y el muestreo sigue al volver (las muestras se
        // conservan). Solo con 30 muestras se guarda, y al guardar se
        // limpian las muestras y se marca la prueba como guardada para
        // que una segunda pausa no re-guarde el mismo valor.
        if (benchmarkSamples.size < BENCHMARK_MAX_SAMPLES) return
        val mean = averageBenchmarkFps(benchmarkSamples) ?: return
        benchmarkSamples.clear()
        benchmarkSaved = true
        overlayScope.launch(Dispatchers.IO) {
            runCatching { SettingsRepository(this@EmbeddedGameActivity).saveAutoBenchmarkFps(mean) }
        }
    }

    /**
     * Modo rendimiento del launcher: mantener la pantalla encendida
     * mientras se juega. Antes solo se aplicaba en MainActivity: al
     * abrir el juego la bandera se perdía y la pantalla podía
     * apagarse en mitad de la partida. Se aplica aquí en cada
     * onResume (y se quita si el ajuste está OFF).
     *
     * Ronda 0.2.65 (S-P2-8): recibe los ajustes ya leídos; onResume
     * comparte la lectura con refreshOverlayState en vez de leer
     * settingsFlow dos veces.
     */
    private fun applyPerformanceScreenFlag(settings: LauncherSettings?) {
        if (settings?.performanceMode == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        val newTitleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        if (newTitleId.isEmpty() || newTitleId == currentTitleId) {
            // Mismo juego (o sin Title ID) y partida en curso: ignorar.
            return
        }
        // Otro Title ID: P2-A5. La variante anterior (finish() +
        // startActivity inmediato) dejaba convivir dos instancias SDL
        // en el mismo proceso sobre la misma sesión nativa. El
        // relanzamiento en proceso de upstream (requestAppRelaunch,
        // native_session.cpp:108) NO está expuesto en el puente
        // (ni en Vita3kCoreBridge ni en launcher_bridge.h), así que no
        // se puede usar sin añadir JNI. En la práctica es inalcanzable
        // (launcher de un solo juego: nada envía otro Title ID): se
        // ignora el intent nuevo y sigue la partida actual, con registro
        // en el log. No se cierra nada.
        LauncherLog.append(this, "launch", "onNewIntent con otro Title ID ($newTitleId) ignorado: sigue la partida actual ($currentTitleId); no hay relanzamiento en proceso expuesto en el puente.")
    }

    /**
     * Lo llama el núcleo al arrancar el juego (hook on_launch).
     * Firma exacta exigida por main_android.cpp: (Ljava/lang/String;)V.
     */
    fun setCurrentGameId(gameId: String?) {
        // Diagnóstico de pantalla negra (2026-11-08): si esta línea
        // no aparece en el log, el núcleo nunca llegó a arrancar el
        // juego (atascado en init/carga); si aparece y la pantalla
        // sigue negra, el juego arrancó pero el render no presenta.
        appendLogAsync("launch", "Núcleo confirmó juego en curso: ${gameId.orEmpty()} (on_launch)")
        // Ronda 0.2.64 (H-4, T1): JUGAR → título. El t0 viene del
        // extra del intent (elapsedRealtime, mismo reloj que play()
        // y el lanzador): si no hay t0, no se registra nada.
        if (launchStartedAtMillis > 0) {
            val t1 = SystemClock.elapsedRealtime() - launchStartedAtMillis
            appendLogAsync("launch", "T1 (JUGAR → título): ${t1}ms")
        }
        runOnUiThread {
            // Ronda 0.2.70 (Área 10, P1-8): el hilo SDL puede disparar
            // on_launch DESPUÉS de onDestroy (join de 1 s en
            // SDLActivity.onDestroy): sin esta guarda, el addContentView
            // de maybeShowBenchmarkPrompt tira BadTokenException.
            if (!isAliveForUiWork(isFinishing, isDestroyed)) return@runOnUiThread
            currentTitleId = gameId.orEmpty()
            // R2-P2-29: el núcleo confirma aquí que el juego está en
            // curso (hook on_launch): fijar el momento una sola vez y
            // mostrar el botón de la prueba de 30 s si toca (ronda
            // 0.2.65, R-P1-18: el muestreo solo arranca cuando el
            // usuario confirma que ya está jugando; antes de esto no
            // se muestrea nada, menús/cargas no cuentan).
            if (gameId?.isNotEmpty() == true && gameRunningSinceMillis == 0L) {
                gameRunningSinceMillis = SystemClock.uptimeMillis()
                maybeShowBenchmarkPrompt()
                // Ronda 0.2.64 (H-4, T2) + 0.2.65 (R-P1-1): primer
                // frame ENTREGADO por el juego. Se sondea getFps()
                // cada 200 ms los primeros 10 s (luego 1 s) hasta el
                // primer FPS > 0. getFps() lee frames emulados
                // (sceDisplaySetFrameBuf), NO presentados en pantalla:
                // T2 marca el primer frame entregado, NO el gameplay
                // interactivo ni la prueba de que el juego se vea.
                // Una sola vez por lanzamiento; overlayScope se
                // cancela en onDestroy.
                maybeStartFirstFramePoll()
                // Ronda 0.2.70 (9-P1-7): el plazo de la segunda etapa
                // se cuenta desde on_launch, no desde onResume.
                maybeStartSecondStageWatchdog()
            }
            // El juego arrancó: SDL está arriba y la sesión activa, así
            // que es el momento de crear el mando virtual del overlay
            // (antes, attach no crea nada). También re-carga el perfil
            // por si cambió desde el último lanzamiento. R4-P0-3: el
            // attach normal no basta si el mando ya se creó antes de
            // on_launch: se RE-CREA (detach + attach + verificación,
            // con reintentos). R4-P0-4: se quita la pausa de fondo con
            // verificación (el clear de onResume pudo descartarse
            // durante la carga).
            refreshOverlayRebindAndUnpauseOnLaunch()
        }
    }

    /**
     * Abre el menú de pausa propio (PauseMenuActivity, Compose).
     * Antes de abrirlo se quita el motivo de pausa de usuario: el
     * menú es la única autoridad de pausa mientras está abierto (el
     * juego se pausa por fondo al abrirse la Activity del menú y se
     * reanuda al volver con Continuar). Si no se quitara, al volver
     * el juego seguiría pausado por el botón guía sin que se viera
     * por qué.
     */
    private fun openPauseMenu() {
        // Tercera pasada (R-5): guarda propia, dentro de la función:
        // cubre a los tres llamadores a la vez (dispatchKeyEvent,
        // onBackPressed y openPauseMenuFromController, que conserva
        // su guarda en el hilo SDL como defensa en profundidad).
        // Sin ella, abrir el menú sobre una Activity en finish()
        // pierde el resultado en silencio.
        if (!isAliveForUiWork(isFinishing, isDestroyed)) return
        // R5-A3-5: el clear de USER también se verifica: si el núcleo
        // lo rechaza (fase distinta de Running) con el juego pausado,
        // queda en el log en vez de perderse en silencio.
        val userClearOk = runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_USER,
                false,
            )
        }.getOrDefault(false)
        if (!userClearOk && Vita3kCoreBridge.isAppPaused()) {
            LauncherLog.append(this, "pause", "openPauseMenu: el núcleo rechazó quitar la pausa de usuario (fase distinta de Running); el juego sigue pausado al abrir el menú")
        }
        runCatching {
            startActivityForResult(
                com.danielsnagustin.childoflight.launcher.ui.PauseMenuActivity.createIntent(this),
                REQUEST_PAUSE_MENU,
            )
        }
    }

    /**
     * Atrás en el juego abre el menú de pausa propio (no cierra el
     * juego ni lo deja en segundo plano). SDLActivity por defecto
     * cerraría la Activity; el juego se pausa por fondo mientras el
     * menú está abierto y se reanuda al elegir Continuar.
     *
     * NO basta con onBackPressed: la superficie SDL consume la tecla
     * BACK (SDLActivity.handleKeyEvent devuelve true y la manda al
     * núcleo como tecla) y onBackPressed nunca llega a correr; por
     * eso atrás "no funcionaba" y había que salir con Inicio
     * (2026-11-08). Se intercepta aquí, antes de que SDL la vea,
     * solo en ACTION_UP (un solo menú por pulsación).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            openPauseMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        openPauseMenu()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PAUSE_MENU) return
        if (resultCode != com.danielsnagustin.childoflight.launcher.ui.PauseMenuActivity.RESULT_EXIT_TO_LAUNCHER) return
        // Salir al inicio: el guardado de la sesión (tiempo +
        // snapshot) ocurre en MainActivity.onResume al volver al
        // launcher (noteReturnedFromGame -> notePlayEnded). El
        // notePlayEnded del menú de pausa es no-op: su SessionManager
        // es una instancia propia, distinta de la del ViewModel, y su
        // playStartMillis es siempre null (R2-P2-35). Cierre limpio
        // del motor (requestAppQuit -> SDL_EVENT_QUIT -> cleanup_launch ->
        // session.stop -> shutdown_app_runtime, verificado en el pin
        // 3e43bc80: sdl-frontend/session.cpp:665-669,414-444 y
        // app_init.cpp:890-992) y vuelta al inicio. finish() aquí
        // hace que onPause corra con isFinishing=true y limpie
        // GameActivityState.gameActivityAlive.
        runCatching {
            if (Vita3kCoreBridge.isAppRunning()) {
                Vita3kCoreBridge.requestAppQuit()
            }
        }
        finish()
    }

    /**
     * Botón guía del mando (hook on_guide_button). Firma exacta:
     * ()V. Abre el menú de pausa propio, igual que atrás; nunca
     * cierra el juego directamente.
     */
    fun openPauseMenuFromController() {
        // Ronda 0.2.70 (Área 10, 10-P2-2): misma familia que P1-8: el
        // botón guía llega desde el hilo SDL y puede llegar durante el
        // teardown; sin guarda, openPauseMenu() abre el menú sobre una
        // Activity destruida.
        runOnUiThread {
            if (!isAliveForUiWork(isFinishing, isDestroyed)) return@runOnUiThread
            openPauseMenu()
        }
    }

    /**
     * Rotación de la pantalla para el motion del núcleo (hook on_init).
     * Firma exacta exigida por main_android.cpp: ()I. Sin este método
     * SDL_main se cierra: get_display_rotation() no comprueba null.
     * R5-A3-3: devuelve constantes Surface.ROTATION_* (0-3, no
     * grados), igual que el oficial: display.rotation ya es esa
     * constante. No tocar el código: devuelve lo correcto.
     */
    @Suppress("DEPRECATION")
    fun getNativeDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    override fun onDestroy() {
        // Tercera pasada (R-6): la bandera de reanudada queda en
        // false al destruirse (onPause ya la limpia en el camino
        // normal; esto cubre finish() en onCreate, donde onPause no
        // llega a correr con ella en true).
        isActivityResumed = false
        // Ronda 0.2.66 (Fase L): quitar el callback de Choreographer
        // y el oyente de FrameMetrics antes de cancelar el ámbito
        // (el callback retendría la Activity).
        stopFrameProfiling()
        // Soltar el overlay y el mando virtual antes de pedir el cierre.
        runCatching { inputManager?.unregisterInputDeviceListener(gamepadListener) }
        overlayView?.releaseAll()
        detachOverlayController()
        overlayScope.cancel()
        // Si la partida sigue en curso al salir, pedir cierre limpio.
        runCatching {
            if (Vita3kCoreBridge.isAppRunning()) {
                Vita3kCoreBridge.requestAppQuit()
            }
        }
        super.onDestroy()
        // R2-P1-11 (refuerzo): si la Activity se cierra de verdad, la
        // bandera debe quedar en false aunque onPause no la limpiara.
        if (isFinishing) GameActivityState.gameActivityAlive = false
        // P1-1: apagar la sesión nativa DESPUÉS de super.onDestroy():
        // SDLActivity.onDestroy ya envió QUIT al hilo SDL y esperó
        // hasta 1 s a que terminara (join), así que la partida ya no
        // está en curso. El próximo init() re-leerá config.yml y los
        // ajustes cambiados entre partidas sí se aplicarán. Solo si la
        // Activity se cierra de verdad (isFinishing): si onDestroy
        // corre por un cambio de configuración (rotación), la Activity
        // se recrea enseguida y la sesión debe sobrevivir (su onCreate
        // la verá con isInitialized() y omitirá el init).
        if (isFinishing) {
            // Ronda 0.2.57: capturar los trofeos ANTES de destruir la
            // sesión (ver captureTrophiesBeforeSessionDestroy).
            // Ronda 0.2.65 (S-P2-4): la captura sigue SÍNCRONA a
            // propósito: MainActivity.onResume consume
            // TrophySyncPending.take() al volver, y si la captura
            // fuera a IO podría llegar después y los trofeos de esta
            // partida no se sincronizarían en esta vuelta. Lo que se
            // mueve a IO es la destrucción de la sesión (el teardown
            // de Vulkan, la parte lenta): destroyScope corre DESPUÉS
            // de super.onDestroy() (el hilo SDL ya terminó) y DESPUÉS
            // de la captura, en la misma secuencia. Riesgo residual:
            // un JUGAR inmediato podría ver la sesión vieja aún viva;
            // tryLaunchEmbedded ya destruye la sesión obsoleta antes
            // de init (destroyStaleSessionIfIdle) y tolera que siga
            // activa (SESSION_ACTIVE).
            captureTrophiesBeforeSessionDestroy()
            // Ronda 0.2.70 (Área 10, P1-11 / 10-P2-4): la destrucción
            // corre serializada con el destroy+init del próximo JUGAR
            // (Mutex de Vita3kCoreBridge) y con el token de generación
            // de la sesión que había al salir: si un JUGAR nuevo ya
            // creó otra sesión, esta destrucción obsoleta es no-op.
            // Con tope de 30 s (también para la espera del Mutex) y
            // cancelación del ámbito al terminar (antes destroyScope
            // no se cancelaba nunca).
            val sessionGeneration = Vita3kCoreBridge.currentSessionGeneration()
            destroyJob = destroyScope.launch {
                try {
                    withTimeout(DESTROY_SESSION_TIMEOUT_MS) {
                        Vita3kCoreBridge.withSessionLock {
                            destroyNativeSessionAfterSdlShutdown(sessionGeneration)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    LauncherLog.append(this@EmbeddedGameActivity, "launch", "destroySession sin volver en ${DESTROY_SESSION_TIMEOUT_MS / 1000} s al salir del juego; se abandona el teardown (la sesión puede seguir viva hasta matar el proceso)")
                } finally {
                    destroyScope.cancel()
                }
            }
        }
    }

    /**
     * Ronda 0.2.57 (puente JNI de trofeos): lee las colecciones de
     * trofeos del núcleo y las deja en TrophySyncPending para que el
     * ViewModel las sincronice al volver (syncTrophiesFromGame).
     *
     * Orden OBLIGADO (verificado en el pin): getTrophyCollections
     * devuelve vacío si no hay emuenv (native_trophies.cpp:224-226)
     * y destroySession destruye el emuenv (runtime_bridge.cpp:
     * session.emuenv.reset()). Leer DESPUÉS de destroySession daría
     * siempre vacío: por eso la captura va AQUÍ, después de
     * super.onDestroy() (el hilo SDL ya terminó y la partida ya no
     * está en curso: nunca se lee durante la partida) y ANTES de
     * destroySession. Síncrona en el hilo principal por necesidad
     * (ronda 0.2.65, S-P2-4): MainActivity.onResume consume
     * TrophySyncPending al volver, así que la captura debe aterrizar
     * antes de salir de onDestroy; y destroySession (ahora en IO,
     * justo después) la invalidaría. La lectura es un solo TRP
     * pequeño (20 trofeos).
     * Solo se guarda si hay colecciones; un fallo (sin núcleo, puente
     * roto, sesión ya destruida) solo deja el progreso local como
     * estaba y queda en el log: nunca tumba el cierre del juego.
     */
    private fun captureTrophiesBeforeSessionDestroy() {
        if (!Vita3kCoreBridge.available) return
        runCatching {
            val collections = Vita3kCoreBridge.getTrophyCollections()
            if (collections.isNotEmpty()) {
                TrophySyncPending.store(collections)
                LauncherLog.append(this, "trophies", "Trofeos capturados al salir del juego: ${collections.size} colecciones pendientes de sincronizar")
            } else {
                // 7-P2-1: el caso vacío también queda en el log: sin
                // él, "0 trofeos ganados", "puente roto" y "la captura
                // aún no corrió" son indistinguibles en el registro.
                LauncherLog.append(this, "trophies", "Trofeos al salir del juego: 0 colecciones (sin trofeos del núcleo, sesión ya destruida o puente sin datos)")
            }
        }.onFailure { e ->
            LauncherLog.append(this, "trophies", "No se pudieron leer los trofeos al salir del juego: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * P1-1 (lado Activity) + ronda 0.2.65 (S-P2-4): destroySession()
     * corre en destroyScope (Dispatchers.IO), DESPUÉS de
     * super.onDestroy() (el hilo SDL ya terminó tras su join de 1 s)
     * y DESPUÉS de la captura de trofeos, en la misma secuencia. El
     * "contrato solo Main" no lo explica el pin (R-P1-11): el
     * teardown de Vulkan no necesita el hilo UI, solo que el hilo
     * SDL ya no corra. Solo sin partida en curso. Si devuelve
     * SESSION_ACTIVE (-4), no se forzó nada: se registra y hace falta
     * matar el proceso para que los ajustes entren.
     */
    private fun destroyNativeSessionAfterSdlShutdown(sessionGeneration: Int) {
        if (!Vita3kCoreBridge.available) return
        // Ronda 0.2.70 (Área 10, P1-11): destroySessionIfGeneration
        // descarta la destrucción si la generación ya no es la de la
        // sesión que había al salir (un JUGAR nuevo creó otra): sin
        // esto, este job podía destruir la sesión NUEVA.
        val rc = runCatching { Vita3kCoreBridge.destroySessionIfGeneration(sessionGeneration) }.getOrDefault(Vita3kCoreBridge.NOT_IMPLEMENTED)
        when (rc) {
            Vita3kCoreBridge.OK ->
                LauncherLog.append(this, "launch", "Sesión del núcleo destruida al salir del juego; el próximo JUGAR re-lee config.yml")
            -2 ->
                LauncherLog.append(this, "launch", "Sin sesión que destruir al salir del juego")
            Vita3kCoreBridge.SESSION_STALE ->
                LauncherLog.append(this, "launch", "Destrucción de sesión obsoleta descartada al salir: la sesión ya es de una generación posterior (un JUGAR nuevo creó otra); no se destruyó nada")
            Vita3kCoreBridge.SESSION_ACTIVE -> {
                LauncherLog.append(this, "launch", "destroySession devolvió SESSION_ACTIVE (-4) al salir: la partida seguía activa; no se destruyó nada. Si los ajustes no se aplican en el próximo JUGAR, cierra la app por completo (matar el proceso).")
                // Ronda 0.2.66 (AUD-P2-2): aviso VISIBLE además del
                // log. destroyScope es IO: el Toast va al hilo principal.
                // Ronda 0.2.70 (Área 10, 10-P2-3): applicationContext,
                // no this: esta Activity ya está destruida.
                runOnUiThread {
                    Toast.makeText(applicationContext, "La sesión anterior del núcleo no se pudo destruir: si los ajustes no se aplican en el próximo JUGAR, cierra la app por completo y vuelve a intentarlo.", Toast.LENGTH_LONG).show()
                }
            }
            else ->
                LauncherLog.append(this, "launch", "destroySession al salir devolvió $rc")
        }
    }
}

/**
 * Ronda 0.2.65 (P0-3): decisión pura del watchdog de pantalla negra.
 * Dispara solo si NO hay ninguna señal de arranque: ni on_launch
 * (gameRunningSinceMillis en 0) ni primer frame entregado
 * (firstFrameLogged en false) ni FPS entregado mayor que 0 en este
 * instante. Si el juego confirmó on_launch, no dispara aunque el FPS
 * sea 0: el juego está en curso (por ejemplo, cargando) y matarlo
 * sería peor que esperar. Tampoco distingue "juego en curso con
 * imagen negra": eso necesita el contador de frames presentados del
 * pin (R-P1-1). Probada en JVM (Ronda065SurfaceWatchdogTest).
 */
internal fun shouldFireBlackScreenWatchdog(
    gameRunningSinceMillis: Long,
    firstFrameLogged: Boolean,
    fps: Float,
): Boolean {
    if (gameRunningSinceMillis != 0L) return false
    if (firstFrameLogged) return false
    return fps <= 0f
}

/**
 * Ronda 0.2.70 (Área 10, 10-P2-1): decisión pura del sondeo del
 * watchdog: una rebanada solo descuenta del plazo si la Activity
 * estaba reanudada durante ella; en segundo plano el plazo se
 * congela. Probada en JVM (Ronda070EstabilidadTest).
 */
internal fun nextWatchdogRemainingMs(remainingMs: Long, elapsedMs: Long, isResumed: Boolean): Long =
    if (isResumed) (remainingMs - elapsedMs).coerceAtLeast(0L) else remainingMs

/**
 * Ronda 0.2.70 (Área 10, P1-8 / 10-P2-2): decisión pura de guarda
 * de vida para los callbacks que llegan desde el hilo SDL
 * (setCurrentGameId, openPauseMenuFromController) y para
 * maybeShowBenchmarkPrompt: con la Activity terminando o
 * destruida, ningún addContentView ni startActivityForResult
 * puede correr (BadTokenException). Probada en JVM
 * (Ronda070EstabilidadTest).
 */
internal fun isAliveForUiWork(isFinishing: Boolean, isDestroyed: Boolean): Boolean =
    !isFinishing && !isDestroyed

/**
 * Ronda 0.2.70 (Área 10, P1-9): decisión pura del temporizador de
 * vigilancia del init en frío: dispara solo si el init no volvió
 * (initDone en false) y ya pasó el plazo. Probada en JVM
 * (Ronda070EstabilidadTest).
 */
internal fun shouldFireColdInitWatchdog(initDone: Boolean, elapsedMs: Long, timeoutMs: Long): Boolean =
    !initDone && elapsedMs >= timeoutMs

/**
 * Ronda 0.2.70 (Área 10, P1-10): decisión pura del relanzamiento
 * tras reentrada rápida: solo si el hilo SDL está muerto y NO hay
 * partida en curso (ni confirmada por on_launch ni corriendo según
 * el núcleo). Con partida en curso, un hilo muerto es un cierre
 * normal, no un negro que relanzar. Probada en JVM
 * (Ronda070EstabilidadTest).
 */
internal fun shouldRelaunchDeadSDLThread(
    isThreadAlive: Boolean,
    isAppRunning: Boolean,
    gameConfirmed: Boolean,
): Boolean = !isThreadAlive && !isAppRunning && !gameConfirmed

/**
 * Ronda 0.2.70 (9-P1-7): decisión pura del watchdog de SEGUNDA
 * ETAPA. Dispara SOLO si hubo on_launch (gameRunningSinceMillis
 * distinto de 0) y NO hay ningún present: contador a 0 y sin
 * timestamp de present. Un contador mayor que 0 o un timestamp
 * mayor que 0 significa que al menos un frame llegó al panel y no
 * dispara. Un contador -1 (sin núcleo o sin parche del gancho nº 1)
 * es "desconocido", no "sin presents": no dispara, para no matar un
 * juego que sí presenta por un contador ausente. Probada en JVM
 * (Ronda070RenderWatchdogTest).
 */
internal fun shouldFireSecondStageWatchdog(
    gameRunningSinceMillis: Long,
    presentedCount: Long,
    lastPresentTimeNs: Long,
): Boolean {
    if (gameRunningSinceMillis == 0L) return false
    if (presentedCount != 0L) return false
    if (lastPresentTimeNs > 0L) return false
    return true
}

/**
 * Ronda 0.2.70 (9-P1-7): racha de "emula pero no presenta" en el
 * sondeo a 4 Hz. Suma un tick (ms) solo si la última muestra de
 * presents/s es menor que 1.0 con FPS emulado mayor o igual que 1.0;
 * cualquier otro caso (presenta, no emula, o valores no finitos)
 * reinicia la racha a 0. Pura, probada en JVM
 * (Ronda070RenderWatchdogTest).
 */
internal fun updateNoPresentStreakMs(
    streakMs: Long,
    presentedPerSecondLast: Double,
    emulatedFpsLast: Double,
    tickMs: Long,
): Long {
    if (!presentedPerSecondLast.isFinite() || !emulatedFpsLast.isFinite()) return 0L
    return if (presentedPerSecondLast < 1.0 && emulatedFpsLast >= 1.0) streakMs + tickMs else 0L
}

/**
 * Ronda 0.2.70 (9-P1-7): aviso de "emula pero no presenta". Solo
 * si la racha alcanzó el umbral y aún no se avisó en este
 * lanzamiento. Pura, probada en JVM (Ronda070RenderWatchdogTest).
 */
internal fun shouldWarnNoPresent(
    streakMs: Long,
    alreadyWarned: Boolean,
    warnAfterMs: Long,
): Boolean {
    return !alreadyWarned && streakMs >= warnAfterMs
}

/**
 * Tercera pasada (R-2): decisión pura del detector de pantalla
 * negra EN MITAD DEL JUEGO. Un tick (ms) solo suma a la racha si
 * el contador de presents es conocido (>= 0), el juego NO está
 * pausado, ya hay línea base (previousCount >= 0), NI el contador
 * NI el timestamp de present avanzaron, y el juego emula (FPS
 * finito >= 1.0: "emula pero no presenta"). En cualquier otro
 * caso la racha se resetea a 0, SALVO pausa (congela: devuelve la
 * racha tal cual, sin sumar ni resetear) y contador desconocido
 * (-1: inerte, resetea a 0 para no arrastrar una racha vieja).
 * El primer tick válido solo establece la línea base (0). Pura,
 * probada en JVM (TerceraPasadaWatchdogTest).
 */
internal fun nextMidGameNoPresentStreakMs(
    streakMs: Long,
    previousCount: Long,
    currentCount: Long,
    previousTimeNs: Long,
    currentTimeNs: Long,
    emulatedFps: Float,
    isPaused: Boolean,
    tickMs: Long,
): Long {
    if (currentCount < 0L) return 0L
    if (isPaused) return streakMs
    if (previousCount < 0L) return 0L
    if (currentCount > previousCount || currentTimeNs > previousTimeNs) return 0L
    if (!emulatedFps.isFinite() || emulatedFps < 1f) return 0L
    return streakMs + tickMs
}

/**
 * Tercera pasada (R-2): decisión pura del diálogo de recuperación
 * de mitad de partida. Solo si la racha alcanzó el umbral de
 * diálogo y aún no se mostró en esta racha ("Seguir esperando"
 * resetea dialogShown y rearma). Pura, probada en JVM
 * (TerceraPasadaWatchdogTest).
 */
internal fun shouldFireMidGameNoPresentDialog(
    streakMs: Long,
    dialogAfterMs: Long,
    dialogShown: Boolean,
): Boolean = !dialogShown && streakMs >= dialogAfterMs
