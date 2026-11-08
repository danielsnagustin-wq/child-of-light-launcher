package com.danielsnagustin.childoflight.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.danielsnagustin.childoflight.launcher.data.AchievementRepository
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Menú de pausa propio del launcher (Compose, AMOLED #10131D + azul hielo).
 *
 * Es una Activity transparente encima del juego: al abrirse, la
 * Activity del juego pasa a onPause y el núcleo se pausa por fondo
 * (PAUSE_REASON_BACKGROUND, como al pulsar Inicio); al elegir
 * Continuar, la Activity se cierra y el juego se reanuda en onResume.
 * Por eso no es una vista dentro de la SDLActivity: SDLActivity es
 * una Activity plana (no ComponentActivity, sin LifecycleOwner) y
 * Compose necesita uno; una Activity transparente con su propio
 * ciclo de vida es la vía segura.
 *
 * El menú de pausa del propio núcleo (overlay del renderer, que se
 * dibuja cuando el juego está pausado) queda DETRÁS de este menú y
 * no se puede tocar mientras esta Activity está encima; al elegir
 * Continuar desaparece con la reanudación. No hay clave de config
 * que lo desactive (verificado en el pin 3e43bc80:
 * renderer.cpp update_overlays lo muestra siempre que
 * renderer->paused; TODO-VERIFY: buscar una vía JNI para
 * suprimirlo del todo).
 *
 * Acciones:
 * - Continuar: cierra el menú y el juego sigue.
 * - Guardar sesión: snapshot de la sesión activa (SessionManager).
 * - Ajustes rápidos: calidad (1x/2x) y Modo 60 FPS; se guardan y se
 *   aplican al reiniciar el juego (el núcleo lee config.yml al
 *   arrancar, no en caliente).
 * - Salir al inicio: devuelve RESULT_EXIT_TO_LAUNCHER; la Activity del juego
 *   cierra el motor de forma limpia (requestAppQuit) y vuelve al inicio.
 *   El guardado real de la sesión (tiempo + snapshot) ocurre en
 *   MainActivity.onResume al volver (R2-P2-35: el notePlayEnded() que
 *   había aquí era no-op en esta instancia propia del SessionManager).
 */
class PauseMenuActivity : ComponentActivity() {

    companion object {
        /** Resultado: salir al inicio (sesión guardada + cerrar el motor). */
        const val RESULT_EXIT_TO_LAUNCHER = 1001

        fun createIntent(context: Context): Intent =
            Intent(context, PauseMenuActivity::class.java)
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var settingsRepo: SettingsRepository
    // R2-P2-10: "Guardar sesión" no se puede pulsar hasta que refresh()
    // complete: antes se podía pulsar con las sesiones aún sin cargar y
    // el backup se hacía sobre el estado anterior (o vacío).
    private var sessionsReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = GameLibraryRepository(this)
        sessionManager = SessionManager(
            this,
            library,
            AchievementRepository(this),
            TouchLayoutRepository(this),
        )
        settingsRepo = SettingsRepository(this)
        // P1-2: este SessionManager es una instancia propia, distinta
        // de la del ViewModel de MainActivity. Sin refresh() sus
        // sesiones están vacías y "Guardar sesión" no guardaba nada
        // aunque dijera que sí. Se carga al abrir el menú (I/O, por
        // eso va en Dispatchers.IO); si falla, el botón dirá
        // honestamente que no se pudo guardar.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { sessionManager.refresh() }
                .onFailure {
                    LauncherLog.append(this@PauseMenuActivity, "session", "No se pudieron cargar las sesiones en el menú de pausa: ${it.message}")
                }
            sessionsReady = true
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ColAccent, secondary = ColAccent)) {
                val settings by settingsRepo.settingsFlow.collectAsState(initial = null)
                PauseMenuScreen(
                    settings = settings,
                    sessionManager = sessionManager,
                    settingsRepo = settingsRepo,
                    sessionsReady = sessionsReady,
                    onContinue = { finish() },
                    onExit = {
                        // R2-P2-35: antes se llamaba aquí a
                        // notePlayEnded(), que era no-op: este
                        // SessionManager es una instancia propia y su
                        // playStartMillis es siempre null. El guardado
                        // real (tiempo + snapshot) ocurre en
                        // MainActivity.onResume al volver al inicio.
                        setResult(RESULT_EXIT_TO_LAUNCHER)
                        finish()
                    },
                )
            }
        }
    }
}
