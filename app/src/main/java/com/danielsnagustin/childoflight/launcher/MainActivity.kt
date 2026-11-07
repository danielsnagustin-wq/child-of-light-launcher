package com.danielsnagustin.childoflight.launcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.ui.AchievementsScreen
import com.danielsnagustin.childoflight.launcher.ui.CleanupScreen
import com.danielsnagustin.childoflight.launcher.ui.GpuDiagnosticsScreen
import com.danielsnagustin.childoflight.launcher.ui.HomeScreen
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.SavesScreen
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SettingsScreen
import com.danielsnagustin.childoflight.launcher.ui.TouchLayoutScreen

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
        setContent {
            val uiPrefs by vm.uiFlow.collectAsState(initial = null)
            val dark = when (uiPrefs?.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM, null -> isSystemInDarkTheme()
            }
            val accent = try {
                Color(android.graphics.Color.parseColor(uiPrefs?.accent?.hex ?: "#F2B705"))
            } catch (_: Exception) {
                Color(0xFFF2B705)
            }
            val scale = uiPrefs?.textSize?.scale ?: 1.0f
            val typography = Typography(
                headlineMedium = TextStyle(fontSize = (28 * scale).sp),
                titleLarge = TextStyle(fontSize = (22 * scale).sp),
                titleMedium = TextStyle(fontSize = (16 * scale).sp),
                bodyLarge = TextStyle(fontSize = (16 * scale).sp),
                bodyMedium = TextStyle(fontSize = (14 * scale).sp),
                bodySmall = TextStyle(fontSize = (12 * scale).sp),
            )
            val colorScheme = if (dark) {
                darkColorScheme(primary = accent, secondary = accent)
            } else {
                lightColorScheme(primary = accent, secondary = accent)
            }
            MaterialTheme(colorScheme = colorScheme, typography = typography) {
                LauncherApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del juego, refrescar estado y log.
        vm.refreshStatus()
        vm.refreshLog()
    }
}

@Composable
private fun LauncherApp(vm: LauncherViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val justUnlocked by vm.justUnlocked.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)

    // Modo rendimiento: pantalla encendida mientras el launcher está abierto.
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    LaunchedEffect(settings?.performanceMode) {
        if (settings?.performanceMode == true) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm) { screen = it }
                Screen.SETTINGS -> SettingsScreen(vm, { screen = Screen.HOME }, { screen = it })
                Screen.GPU -> GpuDiagnosticsScreen(vm) { screen = Screen.HOME }
                Screen.TOUCH -> TouchLayoutScreen(vm) { screen = Screen.HOME }
                Screen.SAVES -> SavesScreen(vm) { screen = Screen.HOME }
                Screen.ACHIEVEMENTS -> AchievementsScreen(vm) { screen = Screen.HOME }
                Screen.CLEANUP -> CleanupScreen(vm) { screen = Screen.SETTINGS }
            }
        }
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
