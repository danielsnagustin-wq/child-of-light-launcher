package com.danielsnagustin.childoflight.launcher.core

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import com.danielsnagustin.childoflight.launcher.data.GameActivityState
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.averageBenchmarkFps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * - getArguments() -> {"-r", titleId}: es EXACTAMENTE lo que
 *   SDL_main (vita3k/android/jni/main_android.cpp) espera para
 *   arrancar el juego con AppLaunchRequest{.app_path = titleId}.
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
 *   (0/90/180/270) para el motion del núcleo.
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
        /** true = prueba de 30 s: muestrear el FPS del núcleo y guardarlo al salir. */
        const val EXTRA_BENCHMARK = "benchmark"
        /** Muestras válidas de la prueba de 30 s: 1 por segundo de juego real. */
        const val BENCHMARK_MAX_SAMPLES = 30

        /** Request code del menú de pausa (startActivityForResult). */
        const val REQUEST_PAUSE_MENU = 2001

        /**
         * Margen tras confirmar el juego en curso antes de empezar a
         * muestrear FPS (R2-P2-29): la pantalla de título y la carga
         * inicial renderizan a FPS real pero no son juego; sin este
         * margen, 30 s en el título darían "60 fps medios" aunque el
         * juego vaya a 25.
         */
        const val BENCHMARK_START_MARGIN_MS = 5_000L

        fun createLaunchIntent(context: Context, titleId: String, gameTitle: String, benchmark: Boolean = false): Intent {
            return Intent(context, EmbeddedGameActivity::class.java).apply {
                putExtra(EXTRA_TITLE_ID, titleId)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                putExtra(EXTRA_BENCHMARK, benchmark)
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

    // --- Overlay táctil (GamepadOverlayView + mando virtual SDL) ---
    private var overlayView: GamepadOverlayView? = null
    private var overlayAttached: Boolean = false
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

    /** SDL carga solo libVita3K.so; SDL3 va estático dentro (libSDL3.a), sin DT_NEEDED. */
    override fun getLibraries(): Array<String> {
        return arrayOf("Vita3K")
    }

    /** argv de SDL_main: {"-r", titleId}. Sin Title ID, SDL_main devuelve -1. */
    override fun getArguments(): Array<String> {
        val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        if (titleId.isEmpty()) return emptyArray()
        return arrayOf("-r", titleId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // prepareFrontend (SDL_SetMainReady + metadatos + cámara) ANTES
        // de que SDLActivity toque nada nativo. Best-effort: si falla,
        // init() lo detectará y se cerrará con mensaje.
        runCatching { Vita3kCoreBridge.prepareFrontend() }
        super.onCreate(savedInstanceState)

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
        if (!Vita3kCoreBridge.isInitialized()) {
            val rc = Vita3kCoreBridge.init(storageRoot.absolutePath)
            if (rc != Vita3kCoreBridge.OK) {
                val failureMessage = Vita3KLauncher.initFailureMessage(rc, Vita3kCoreBridge.lastLoadError)
                Vita3KLauncher.noteEmbeddedLaunchFailed(failureMessage)
                Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else {
            LauncherLog.append(this, "launch", "Sesión del núcleo ya inicializada (init previo en JUGAR); se omite el init en onCreate")
        }
        // P3: deja constancia de que la Activity SDL se creó y el hilo
        // del juego está a punto de arrancar (el último paso antes de
        // SDL_main). Si el crash fuera aquí, esta sería la última línea.
        LauncherLog.append(this, "launch", "EmbeddedGameActivity creada: $titleId (init OK)")
        setupTouchOverlay()
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
        val view = overlayView ?: return
        val settings = runCatching { SettingsRepository(this).settingsFlow.first() }.getOrNull()
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
        )
    }

    private fun applyOverlayVisibility(
        view: GamepadOverlayView,
        show: Boolean,
        controls: List<com.danielsnagustin.childoflight.launcher.data.TouchControl>,
        settingsOpacity: Float,
        neon: Boolean,
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
        view.setControls(controls)
        view.visibility = View.VISIBLE
        maybeAttachOverlayController()
    }

    /**
     * Crea el mando virtual SDL del overlay, solo con la partida en
     * curso. attach es idempotente en upstream; si SDL aún no está
     * listo, no crea nada y overlayIsAttached() lo dice: se reintenta
     * en setCurrentGameId / onResume. El táctil se deja en frontal
     * (false): es lo que usa Child of Light para Igniculus.
     */
    private fun maybeAttachOverlayController() {
        if (!overlayShouldShow || overlayAttached) return
        if (!Vita3kCoreBridge.isAppRunning()) return
        if (!Vita3kCoreBridge.overlayAttach()) {
            LauncherLog.append(this, "overlay", "attach del mando virtual falló (sin núcleo o sin SDL)")
            return
        }
        if (!Vita3kCoreBridge.overlayIsAttached()) {
            LauncherLog.append(this, "overlay", "attach llamado pero el mando virtual no existe en SDL; se reintentará al arrancar el juego")
            return
        }
        overlayAttached = true
        Vita3kCoreBridge.overlaySetTouchState(false)
        LauncherLog.append(this, "overlay", "mando virtual del overlay activo")
    }

    private fun detachOverlayController() {
        if (!overlayAttached) return
        overlayAttached = false
        Vita3kCoreBridge.overlayDetach()
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

    private fun forwardOverlayButton(sdlButton: Int, pressed: Boolean) {
        if (Vita3kCoreBridge.overlaySetButton(sdlButton, pressed)) return
        if (!recoverOverlayController()) return
        if (!Vita3kCoreBridge.overlaySetButton(sdlButton, pressed)) {
            LauncherLog.append(this, "overlay", "botón $sdlButton perdido tras reintentar el attach")
        }
    }

    private fun forwardOverlayAxis(sdlAxis: Int, value: Short) {
        if (Vita3kCoreBridge.overlaySetAxis(sdlAxis, value)) return
        if (!recoverOverlayController()) return
        if (!Vita3kCoreBridge.overlaySetAxis(sdlAxis, value)) {
            LauncherLog.append(this, "overlay", "eje $sdlAxis perdido tras reintentar el attach")
        }
    }

    /** Marca el mando como perdido y reintenta el attach. Devuelve true si el mando vuelve a existir. */
    private fun recoverOverlayController(): Boolean {
        overlayAttached = false
        LauncherLog.append(this, "overlay", "mando virtual perdido a mitad de partida: reintentando el attach")
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
        // R2-P1-11: el juego sigue vivo al volver de Inicio/Recientes
        // o del menú de pausa; MainActivity lo comprueba para no
        // cerrar la medición de tiempo en esos casos.
        GameActivityState.gameActivityAlive = true
        runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_BACKGROUND,
                false,
            )
        }
        refreshOverlay()
        applyPerformanceScreenFlag()
        maybeStartBenchmarkSampling()
    }

    override fun onPause() {
        // R2-P1-11: SOLO se limpia la bandera al salir de verdad
        // (finish() ya llamado, p. ej. desde onActivityResult tras
        // "Salir al inicio"): ir a segundo plano (Inicio, Recientes,
        // llamada, menú de pausa) deja la Activity viva y la bandera
        // en true, para que MainActivity no cierre la medición.
        if (isFinishing) GameActivityState.gameActivityAlive = false
        // Soltar todo lo pulsado al pausar: sin botones atascados al volver.
        overlayView?.releaseAll()
        stopBenchmarkSamplingAndSave()
        super.onPause()
        runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_BACKGROUND,
                true,
            )
        }
    }

    /**
     * Prueba de 30 s (auditoría de funcionalidad 2026-11-08): si el
     * lanzamiento vino con benchmark=true, muestrea el FPS real del
     * núcleo (Vita3kCoreBridge.getFps(), que lee EmuEnvState::fps)
     * cada segundo mientras el juego está en primer plano, hasta 30
     * muestras válidas (~30 s de juego real). Al salir (onPause) guarda
     * la media en DataStore; el launcher la consume
     * una sola vez al volver y registra la prueba solo. Sin muestras
     * válidas no se guarda nada: la prueba queda para registro
     * manual. El muestreo no toca el juego: es una lectura JNI por
     * segundo, sin escribir nada en el núcleo.
     *
     * R2-P2-29: NO se muestrea hasta que el núcleo confirme el juego
     * en curso (setCurrentGameId, hook on_launch) + 5 s de margen
     * (título/carga inicial). Antes, el muestreo arrancaba en cada
     * onResume y la pantalla de título contaba como juego. Si el
     * núcleo nunca confirma el arranque, no se guarda nada (fallo
     * seguro: mejor sin prueba que con una media de menús).
     */
    private fun maybeStartBenchmarkSampling() {
        if (!benchmarkMode || benchmarkSaved || !Vita3kCoreBridge.available) return
        if (benchmarkJob?.isActive == true) return
        // Prueba completa: 30 muestras válidas. Sin el tope, el muestreo
        // medía toda la sesión en vez de 30 s.
        if (benchmarkSamples.size >= BENCHMARK_MAX_SAMPLES) return
        // Juego aún no confirmado por el núcleo: no muestrear; cuando
        // llegue setCurrentGameId se reintentará desde ahí.
        if (gameRunningSinceMillis == 0L) return
        benchmarkJob = overlayScope.launch {
            // Margen tras el arranque (título/carga): solo se descuenta
            // una vez; al reanudar tras una pausa ya pasó hace rato.
            val elapsed = SystemClock.uptimeMillis() - gameRunningSinceMillis
            val waitMs = BENCHMARK_START_MARGIN_MS - elapsed
            if (waitMs > 0) delay(waitMs)
            while (isActive && benchmarkSamples.size < BENCHMARK_MAX_SAMPLES) {
                val fps = runCatching { Vita3kCoreBridge.getFps() }.getOrDefault(-1f)
                if (fps > 0f) benchmarkSamples += fps
                delay(1000)
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
     */
    private fun applyPerformanceScreenFlag() {
        overlayScope.launch {
            val settings = runCatching { SettingsRepository(this@EmbeddedGameActivity).settingsFlow.first() }.getOrNull()
            if (settings?.performanceMode == true) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
        LauncherLog.append(this, "launch", "Núcleo confirmó juego en curso: ${gameId.orEmpty()} (on_launch)")
        runOnUiThread {
            currentTitleId = gameId.orEmpty()
            // R2-P2-29: el núcleo confirma aquí que el juego está en
            // curso (hook on_launch): fijar el momento una sola vez y
            // arrancar el muestreo del benchmark si toca (con su
            // margen de 5 s). Antes de esto, maybeStartBenchmarkSampling
            // no muestrea nada (menús/cargas no cuentan).
            if (gameId?.isNotEmpty() == true && gameRunningSinceMillis == 0L) {
                gameRunningSinceMillis = SystemClock.uptimeMillis()
                maybeStartBenchmarkSampling()
            }
            // El juego arrancó: SDL está arriba y la sesión activa, así
            // que es el momento de crear el mando virtual del overlay
            // (antes, attach no crea nada). También re-carga el perfil
            // por si cambió desde el último lanzamiento.
            refreshOverlay()
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
        runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_USER,
                false,
            )
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
        runOnUiThread { openPauseMenu() }
    }

    /**
     * Rotación de la pantalla para el motion del núcleo (hook on_init).
     * Firma exacta exigida por main_android.cpp: ()I. Sin este método
     * SDL_main se cierra: get_display_rotation() no comprueba null.
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
            destroyNativeSessionAfterSdlShutdown()
        }
    }

    /**
     * P1-1 (lado Activity): destroySession() en el hilo principal
     * (onDestroy corre en UI, por contrato JNI) y solo sin partida en
     * curso (el hilo SDL ya terminó tras super.onDestroy()). Si
     * devuelve SESSION_ACTIVE (-4), no se forzó nada: se registra y
     * hace falta matar el proceso para que los ajustes entren.
     */
    private fun destroyNativeSessionAfterSdlShutdown() {
        if (!Vita3kCoreBridge.available) return
        val rc = runCatching { Vita3kCoreBridge.destroySession() }.getOrDefault(Vita3kCoreBridge.NOT_IMPLEMENTED)
        when (rc) {
            Vita3kCoreBridge.OK ->
                LauncherLog.append(this, "launch", "Sesión del núcleo destruida al salir del juego; el próximo JUGAR re-lee config.yml")
            -2 ->
                LauncherLog.append(this, "launch", "Sin sesión que destruir al salir del juego")
            Vita3kCoreBridge.SESSION_ACTIVE ->
                LauncherLog.append(this, "launch", "destroySession devolvió SESSION_ACTIVE (-4) al salir: la partida seguía activa; no se destruyó nada. Si los ajustes no se aplican en el próximo JUGAR, cierra la app por completo (matar el proceso).")
            else ->
                LauncherLog.append(this, "launch", "destroySession al salir devolvió $rc")
        }
    }
}
