package com.danielsnagustin.childoflight.launcher

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.danielsnagustin.childoflight.launcher.data.GameActivityState
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.ui.AchievementsScreen
import com.danielsnagustin.childoflight.launcher.ui.AlertAction
import com.danielsnagustin.childoflight.launcher.ui.CleanupScreen
import com.danielsnagustin.childoflight.launcher.ui.GpuDiagnosticsScreen
import com.danielsnagustin.childoflight.launcher.ui.HomeScreen
import com.danielsnagustin.childoflight.launcher.ui.ImportScreen
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.LocalUiPreferences
import com.danielsnagustin.childoflight.launcher.ui.SavesScreen
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SettingsScreen
import com.danielsnagustin.childoflight.launcher.ui.TouchLayoutScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsAudioScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsControlsScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCpuScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsDiagnosticsScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsGeneralScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsGpuScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsPerformanceScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsPersonalizationScreen
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsStorageScreen

/**
 * Actividad única del launcher.
 * - Navegación por estado (sin librería de navegación).
 * - Tema del launcher: claro/oscuro/sistema + color de acento +
 *   tamaño de texto (todo de UiPreferences, en DataStore).
 * - Modo rendimiento: si está activado, mantiene la pantalla encendida.
 * - Popup + sonido de logro: el sonido lo reproduce el ViewModel al
 *   desbloquear; aquí se muestra el popup con el trofeo.
 * - Mensajes del ViewModel: Snackbar.
 */
class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // P3 (crash ~15:06 sin stack trace): el handler deja el stack
        // trace en launcher.log antes de delegar; sin él, el próximo
        // crash tampoco dejaría rastro. Se instala antes de crear el
        // ViewModel (la primera vez que se toca `vm`).
        LauncherLog.installCrashHandler(applicationContext)
        // Crash al abrir (#80/#82): si la primera composición falla, la
        // app moría sin mostrar nada ("solo me vota y ya"). Ahora se
        // muestra el error en pantalla para fotografiarlo y mandarlo,
        // en vez de cerrar en silencio.
        // S10: este try/catch cubre SOLO la primera composición
        // (síncrona). Un fallo en una recomposición posterior no pasa
        // por aquí: va al handler de excepciones no capturadas
        // (installCrashHandler, que lo escribe en launcher.log) y el
        // proceso se cierra como un crash normal. No hay un
        // "catch global de Compose": el informe del crash queda en el
        // log y en el próximo reporte de bug.
        try {
            setContent { LauncherContent(vm) }
        } catch (t: Throwable) {
            // R5-A1-11: el append es I/O de disco y onCreate corre en
            // Main: va por IO, igual que el de onResume. Antes corría
            // en el hilo principal en el camino del fallo.
            lifecycleScope.launch(Dispatchers.IO) {
                LauncherLog.append(applicationContext, "launch", "Fallo en la primera composición: ${t.javaClass.simpleName}: ${t.message}")
            }
            setContent { LaunchErrorScreen(t) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del juego, refrescar estado y log, y cerrar la
        // medición de tiempo de la sesión activa (snapshot guardado).
        // P2-A7: en la vía externa el juego puede seguir en curso al
        // volver al launcher, y cerrar aquí cortaba el tiempo de lo
        // que se siguiera jugando fuera. Comprobar si
        // org.vita3k.emulator sigue en primer plano NO es viable:
        // ActivityManager.getRunningAppProcesses() solo devuelve el
        // propio proceso desde Android 5.0, y la única alternativa
        // (UsageStatsManager) exige el permiso especial de
        // estadísticas de uso. La distinción por vía vive en
        // SessionManager.notePlayStarted(external): la vía externa
        // hace checkpoint y la medición sigue; la embebida se cierra
        // aquí como siempre. El ViewModel aún no pasa la vía (oleada
        // 2: play() debe pasar external=true al lanzar fuera).
        vm.refreshStatus()
        vm.refreshLog()
        // R2-P1-11 (parte MainActivity): cerrar la medición de tiempo
        // SOLO cuando el juego ya no sigue vivo. Si el usuario pasó por
        // Inicio/Recientes a mitad de partida, EmbeddedGameActivity
        // sigue viva en la pila (GameActivityState.gameActivityAlive) y
        // cerrar aquí cortaba el tiempo de lo que se siguiera jugando.
        // En el primer arranque (sin partida) la bandera es false y el
        // comportamiento no cambia: notePlayEnded es no-op sin medición.
        if (!GameActivityState.gameActivityAlive) {
            vm.noteReturnedFromGame()
            // Ronda 0.2.57: sincronizar los trofeos ganados en la
            // partida (las colecciones ya se capturaron en
            // EmbeddedGameActivity.onDestroy antes de destroySession).
            vm.syncTrophiesFromGame()
        }
        // P2-A4a: si EmbeddedGameActivity falló su init DESPUÉS de que
        // launch() devolviera LaunchedEmbedded, el ViewModel ya puso
        // "Núcleo embebido: juego lanzado". La Activity anota el fallo
        // en Vita3KLauncher (bandera consumible) y aquí se corrige al
        // volver, con el motivo exacto. Sin tocar LauncherViewModel.
        // R4-P2-5: el append de log es I/O de disco y onResume corre en
        // Main: va por IO (el Toast sí queda en Main, es UI). Antes el
        // append (con su rotación bajo candado) corría en el hilo
        // principal en cada vuelta al launcher.
        Vita3KLauncher.takeEmbeddedLaunchFailure()?.let { reason ->
            lifecycleScope.launch(Dispatchers.IO) {
                LauncherLog.append(applicationContext, "launch", "El juego no llegó a arrancar: $reason")
            }
            Toast.makeText(this, "El juego no llegó a arrancar. $reason", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun LauncherContent(vm: LauncherViewModel) {
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val dark = when (uiPrefs?.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM, null -> isSystemInDarkTheme()
    }
    val accent = try {
        Color(android.graphics.Color.parseColor(uiPrefs?.accent?.hex ?: "#8CCBEB"))
    } catch (_: Exception) {
        Color(0xFF8CCBEB)
    }
    val scale = uiPrefs?.textSize?.scale ?: 1.0f
    // R4-P2-10: el tamaño de texto escala TODOS los estilos, no solo
    // 6: antes labelSmall/labelMedium y los display* quedaban a tamaño
    // fijo (Material3 por defecto) y el ajuste no los tocaba. Los
    // tamaños base son los defaults de Material3 (display 57/45/36,
    // headline 32/28/24, title 22/16/14, body 16/14/12, label 14/12/11).
    val typography = Typography(
        displayLarge = TextStyle(fontSize = (57 * scale).sp),
        displayMedium = TextStyle(fontSize = (45 * scale).sp),
        displaySmall = TextStyle(fontSize = (36 * scale).sp),
        headlineLarge = TextStyle(fontSize = (32 * scale).sp),
        headlineMedium = TextStyle(fontSize = (28 * scale).sp),
        headlineSmall = TextStyle(fontSize = (24 * scale).sp),
        titleLarge = TextStyle(fontSize = (22 * scale).sp),
        titleMedium = TextStyle(fontSize = (16 * scale).sp),
        titleSmall = TextStyle(fontSize = (14 * scale).sp),
        bodyLarge = TextStyle(fontSize = (16 * scale).sp),
        bodyMedium = TextStyle(fontSize = (14 * scale).sp),
        bodySmall = TextStyle(fontSize = (12 * scale).sp),
        labelLarge = TextStyle(fontSize = (14 * scale).sp),
        labelMedium = TextStyle(fontSize = (12 * scale).sp),
        labelSmall = TextStyle(fontSize = (11 * scale).sp),
    )
    val colorScheme = if (dark) {
        darkColorScheme(primary = accent, secondary = accent)
    } else {
        lightColorScheme(primary = accent, secondary = accent)
    }
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        // Ronda 0.2.55 P1-4: UN solo colector de uiFlow en toda la app:
        // este de LauncherContent. El valor se expone por
        // LocalUiPreferences y las pantallas lo leen directamente
        // (LocalUiPreferences.current, sin otro colector). Antes
        // LauncherApp lo recogía otra vez y cada pantalla creaba el
        // suyo con rememberUiPreferences().
        CompositionLocalProvider(LocalUiPreferences provides uiPrefs) {
            LauncherApp(vm)
        }
    }
}

@Composable
private fun LaunchErrorScreen(error: Throwable) {
    // Pantalla mínima de emergencia: si el launcher no puede arrancar,
    // esto es lo que ve el usuario en vez de un cierre en silencio.
    // El texto es el diagnóstico: con una captura se sabe la causa.
    MaterialTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            Column {
                Text("Child of Light no pudo arrancar.")
                Text("Error: ${error.javaClass.simpleName}: ${error.message ?: "sin mensaje"}")
                Text("Haz una captura de esta pantalla y mándala para arreglarlo.")
            }
        }
    }
}

/**
 * Ronda 0.2.55 P1-4: el modo rendimiento (pantalla encendida) vive
 * aquí, en un hijo que recoge settingsFlow dentro de sí. Antes
 * LauncherApp lo recogía arriba y cualquier cambio de ajuste
 * recomponía el Scaffold + AnimatedContent entero.
 */
@Composable
private fun KeepScreenOnEffect(vm: LauncherViewModel) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    LaunchedEffect(settings?.performanceMode) {
        if (settings?.performanceMode == true) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun LauncherApp(vm: LauncherViewModel) {
    // B2 (auditoría UI): antes la pantalla se guardaba con remember (se
    // perdía al rotar) y no había BackHandler: atrás cerraba la app desde
    // cualquier pantalla. Ahora la pantalla sobrevive a rotación y atrás
    // vuelve a Home (desde Limpiar, vuelve a Configuración).
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    // R2-P1-03: pila de vuelta real (sobrevive a rotación). Al navegar
    // se apila la pantalla de origen y "Atrás" desapila: GPU vuelve a
    // Home si se vino de Home y a Ajustes si se vino de Ajustes; igual
    // TOUCH/SAVES/ACHIEVEMENTS; categorías de Ajustes -> hub. Antes el
    // destino de atrás era fijo por pantalla e ignoraba el origen
    // (Home -> GPU -> atrás caía en el hub de Ajustes, donde el
    // usuario nunca estuvo).
    var backStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun navigateTo(target: Screen) {
        if (target.name != screenName) {
            backStack = backStack + screenName
            screenName = target.name
        }
    }
    fun goBack() {
        val previous = backStack.lastOrNull()
        if (previous != null) {
            backStack = backStack.dropLast(1)
            screenName = previous
        } else {
            screenName = Screen.HOME.name
        }
    }
    // R4-P2-6: la intro de Home se decide por la marca persistente
    // (UiPreferences.homeIntroSeen). Ronda 0.2.55 P1-4: se lee del
    // Local que provee LauncherContent, no con otro collectAsState.
    val uiPrefs = LocalUiPreferences.current
    // La animación de entrada de Home solo se reproduce una vez por
    // INSTALACIÓN (R4-P2-6): la marca vive en UiPreferences
    // (homeIntroSeen, persistente en DataStore), no en rememberSaveable.
    // Antes la bandera era por sesión/proceso: cada vez que el sistema
    // mataba el proceso, la portada (650 ms) y las tarjetas (450 ms +
    // retardos) volvían a sonar al abrir la app, y eso es lo que "se
    // siente lenta al navegar" describía. Con la marca persistente, a
    // partir de la segunda apertura Home aparece al instante. La
    // transición entre pantallas (150 ms) no se toca.
    val homeIntroDone = uiPrefs?.homeIntroSeen == true
    val screen = runCatching { Screen.valueOf(screenName) }.getOrDefault(Screen.HOME)
    // Atrás: desapilar (ver pila de arriba). Si la pila está vacía
    // (no debería pasar fuera de Home), volver a Home.
    BackHandler(enabled = screen != Screen.HOME) {
        goBack()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val alert by vm.alert.collectAsState()
    val justUnlocked by vm.justUnlocked.collectAsState()

    // "Reportar" del diálogo de fallo del motor reutiliza la exportación
    // de bug que ya existe (Diagnóstico → Reportar bug): mismo archivo
    // .txt con log + ajustes + dispositivo, que el usuario comparte a
    // mano. El launcher se registra siempre (no dentro del diálogo):
    // rememberLauncherForActivityResult no debe llamarse condicional.
    // Ronda 0.2.55 P1-4: LauncherApp ya NO recoge settingsFlow ni
    // gpuFlow (recomponían el Scaffold entero con cada cambio de
    // ajuste o de GPU): los lee el ViewModel al exportar, en IO.
    val exportBug = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportBugReportCurrent(uri)
    }

    // Modo rendimiento: pantalla encendida mientras el launcher está abierto.
    // Ronda 0.2.55 P1-4: vive en un composable hijo que recoge
    // settingsFlow dentro: un cambio de ajuste recompone solo ese
    // hijo, no LauncherApp entero.
    KeepScreenOnEffect(vm)

    // P2-39: los mensajes se ENCOLAN y se muestran uno a uno. Antes,
    // LaunchedEffect(message) se reiniciaba con cada mensaje nuevo y
    // el que se estaba mostrando se perdía (showSnackbar cancelado).
    // El productor recoge cada mensaje y libera el hueco enseguida
    // (clearMessage) para que el siguiente pueda llegar; el consumidor
    // los muestra en orden.
    // Ronda 0.2.55 P1-15: el consumidor era un bucle while(true) con
    // delay(100) permanente (10 despertares por segundo para siempre).
    // Ahora es un Channel con buffer ilimitado: el consumidor duerme
    // en el receive hasta que llega un mensaje, sin polling.
    val messageChannel = remember { Channel<String>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        snapshotFlow { message }.filterNotNull().collect { text ->
            messageChannel.send(text)
            vm.clearMessage()
        }
    }
    LaunchedEffect(Unit) {
        for (text in messageChannel) {
            snackbarHostState.showSnackbar(text)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Transición entre pantallas: solo fundido, sin
            // deslizamiento. Ronda 0.2.55 P0-3: el deslizamiento
            // componía dos pantallas a la vez 120-150 ms; con solo
            // fundido la entrante no arrastra el coste de la saliente.
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(120))
                },
                label = "screenTransition",
            ) { target ->
                when (target) {
                    // R5-A1-5: onIntroStarted persiste la marca al
                    // empezar la intro (primer frame), no solo al
                    // terminarla: si el usuario sale antes de los
                    // 900 ms o gira el teléfono, no se repite.
                    Screen.HOME -> HomeScreen(vm, introDone = homeIntroDone, onIntroDone = { vm.markHomeIntroSeen() }, onIntroStarted = { vm.markHomeIntroSeen() }) { navigateTo(it) }
                    Screen.IMPORT -> ImportScreen(vm) { goBack() }
                    Screen.SETTINGS -> SettingsScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_GENERAL -> SettingsGeneralScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_PERSONALIZATION -> SettingsPersonalizationScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_GPU -> SettingsGpuScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_CPU -> SettingsCpuScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_AUDIO -> SettingsAudioScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_PERFORMANCE -> SettingsPerformanceScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_CONTROLS -> SettingsControlsScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_STORAGE -> SettingsStorageScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.SETTINGS_DIAGNOSTICS -> SettingsDiagnosticsScreen(vm, { goBack() }, { navigateTo(it) })
                    Screen.GPU -> GpuDiagnosticsScreen(vm) { goBack() }
                    Screen.TOUCH -> TouchLayoutScreen(vm) { goBack() }
                    Screen.SAVES -> SavesScreen(vm) { goBack() }
                    Screen.ACHIEVEMENTS -> AchievementsScreen(vm) { goBack() }
                    Screen.CLEANUP -> CleanupScreen(vm) { goBack() }
                }
            }
        }
    }

    // Aviso importante (diálogo, no Snackbar): errores de importar y de
    // JUGAR, con la acción que los resuelve. El texto puede ser largo:
    // va con scroll para poder leerlo entero.
    alert?.let { info ->
        AlertDialog(
            onDismissRequest = { vm.clearAlert() },
            title = { Text("Child of Light") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(info.text)
                }
            },
            confirmButton = {
                when (info.action) {
                    AlertAction.INSTALL_VITA3K -> TextButton(onClick = { vm.clearAlert(); vm.openVita3kReleases() }) { Text("Instalar app externa") }
                    AlertAction.GO_IMPORT -> TextButton(onClick = { vm.clearAlert(); navigateTo(Screen.IMPORT) }) { Text("Ir a importar") }
                    AlertAction.DELIVER_PKG_TO_VITA3K -> TextButton(onClick = { vm.clearAlert(); vm.deliverPkgToVita3k() }) { Text("Copiar a la app externa") }
                    AlertAction.CONFIRM_DOWNLOADS_COPY -> TextButton(onClick = { vm.clearAlert(); vm.confirmDeliverPkgToDownloads() }) { Text("Copiar a Descargas") }
                    AlertAction.CONFIRM_PKG_INSTALLED -> TextButton(onClick = { vm.clearAlert(); vm.confirmPkgInstalledInVita3k() }) { Text("Ya lo instalé en la app externa") }
                    AlertAction.REPLACE_DUPLICATE -> TextButton(onClick = { vm.confirmReplaceDuplicate() }) { Text("Reemplazar") }
                    AlertAction.RETRY_LAUNCH -> TextButton(onClick = { vm.clearAlert(); vm.play() }) { Text("Reintentar") }
                    AlertAction.RESOLVE_LEGACY_CONFLICT -> TextButton(onClick = { vm.clearAlert(); vm.resolveLegacyConflictKeepEmbedded() }) { Text("Borrar la antigua y jugar") }
                    null -> TextButton(onClick = { vm.clearAlert() }) { Text("Entendido") }
                }
            },
            dismissButton = when (info.action) {
                // Fallo del motor integrado: el botón secundario es
                // "Reportar" (exporta el reporte de bug), no "Cerrar".
                AlertAction.RETRY_LAUNCH -> {
                    { TextButton(onClick = { vm.clearAlert(); exportBug.launch("col-bug-report.txt") }) { Text("Reportar") } }
                }
                null -> null
                else -> {
                    { TextButton(onClick = { vm.clearAlert() }) { Text("Cerrar") } }
                }
            },
        )
    }

    // Popup de logro desbloqueado (el sonido ya sonó en el ViewModel).
    justUnlocked?.let { progress ->
        AlertDialog(
            onDismissRequest = { vm.clearJustUnlocked() },
            title = { Text("🏆 ¡Logro desbloqueado!") },
            text = {
                Text("${progress.trophy.name}\n${progress.trophy.description}\n\nLogro local del launcher (no se sincroniza con PSN ni Steam).")
            },
            confirmButton = { TextButton(onClick = { vm.clearJustUnlocked() }) { Text("¡Bien!") } },
        )
    }
}
