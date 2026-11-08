package com.danielsnagustin.childoflight.launcher.core

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * TODO (Etapa 2, tras la primera build que arranque el juego):
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
 *   Title ID sin recrear la Activity.
 */
class EmbeddedGameActivity : SDLActivity() {

    companion object {
        const val EXTRA_TITLE_ID = "title_id"
        const val EXTRA_GAME_TITLE = "game_title"

        /** Request code del menú de pausa (startActivityForResult). */
        const val REQUEST_PAUSE_MENU = 2001

        fun createLaunchIntent(context: Context, titleId: String, gameTitle: String): Intent {
            return Intent(context, EmbeddedGameActivity::class.java).apply {
                putExtra(EXTRA_TITLE_ID, titleId)
                putExtra(EXTRA_GAME_TITLE, gameTitle)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    private var currentTitleId: String = ""

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
            Toast.makeText(this, "Sin Title ID: no se puede arrancar el juego.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentTitleId = titleId
        val storageRoot = EmbeddedCoreLayout.storageRoot(this)
        val rc = Vita3kCoreBridge.init(storageRoot.absolutePath)
        if (rc != Vita3kCoreBridge.OK) {
            Toast.makeText(
                this,
                "El motor integrado no se pudo iniciar (código $rc). Vuelve al inicio e inténtalo de nuevo.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
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
                    Vita3kCoreBridge.overlaySetButton(sdlButton, pressed)
                }

                override fun onAxis(sdlAxis: Int, value: Short) {
                    Vita3kCoreBridge.overlaySetAxis(sdlAxis, value)
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
        runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_BACKGROUND,
                false,
            )
        }
        refreshOverlay()
    }

    override fun onPause() {
        // Soltar todo lo pulsado al pausar: sin botones atascados al volver.
        overlayView?.releaseAll()
        super.onPause()
        runCatching {
            Vita3kCoreBridge.setPauseReasonEnabled(
                Vita3kCoreBridge.PAUSE_REASON_BACKGROUND,
                true,
            )
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
        // Otro Title ID: la forma segura sin relanzamiento en proceso
        // es cerrar y volver a abrir con el nuevo intent.
        finish()
        startActivity(createLaunchIntent(this, newTitleId, intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()))
    }

    /**
     * Lo llama el núcleo al arrancar el juego (hook on_launch).
     * Firma exacta exigida por main_android.cpp: (Ljava/lang/String;)V.
     */
    fun setCurrentGameId(gameId: String?) {
        runOnUiThread {
            currentTitleId = gameId.orEmpty()
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
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        openPauseMenu()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PAUSE_MENU) return
        if (resultCode != com.danielsnagustin.childoflight.launcher.ui.PauseMenuActivity.RESULT_EXIT_TO_LAUNCHER) return
        // Salir al inicio: la sesión ya se guardó en el menú
        // (notePlayEnded: tiempo + snapshot). Cierre limpio del motor
        // (requestAppQuit -> SDL_EVENT_QUIT -> cleanup_launch ->
        // session.stop -> shutdown_app_runtime, verificado en el pin
        // 3e43bc80: sdl-frontend/session.cpp:665-669,414-444 y
        // app_init.cpp:890-992) y vuelta al inicio.
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
    }
}
