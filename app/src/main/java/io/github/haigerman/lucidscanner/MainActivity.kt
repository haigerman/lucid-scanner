package io.github.haigerman.lucidscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.lucidscanner.data.ScanRepository
import io.github.haigerman.lucidscanner.data.Settings
import io.github.haigerman.lucidscanner.ui.CameraScreen
import io.github.haigerman.lucidscanner.ui.HomeScreen
import io.github.haigerman.lucidscanner.ui.LucidScannerTheme
import io.github.haigerman.lucidscanner.ui.ReviewScreen
import io.github.haigerman.lucidscanner.ui.SettingsScreen

private enum class Screen { HOME, CAMERA, REVIEW, SETTINGS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        ScanRepository.init(this)
        enableEdgeToEdge()
        setContent {
            LucidScannerTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                // wohin „Zurück“ aus den Einstellungen führt
                var settingsFrom by rememberSaveable { mutableStateOf(Screen.HOME) }
                var notice by rememberSaveable { mutableStateOf<String?>(null) }
                val pdf by ScanRepository.pdf.collectAsStateWithLifecycle()

                fun openSettings(from: Screen) {
                    settingsFrom = from
                    screen = Screen.SETTINGS
                }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        notice = notice,
                        onNewScan = {
                            notice = null
                            ScanRepository.start()
                            screen = Screen.CAMERA
                        },
                        onSettings = { openSettings(Screen.HOME) },
                    )
                    Screen.CAMERA -> CameraScreen(
                        onFinished = { screen = Screen.REVIEW },
                        onCancel = { screen = Screen.HOME },
                    )
                    Screen.REVIEW -> {
                        val result = pdf
                        if (result == null) {
                            // z. B. nachdem Android die App im Hintergrund beendet hat
                            LaunchedEffect(Unit) { screen = Screen.HOME }
                        } else {
                            ReviewScreen(
                                result = result,
                                onUploaded = { path ->
                                    notice = "Abgelegt: $path"
                                    screen = Screen.HOME
                                },
                                onDiscarded = { screen = Screen.HOME },
                                onSettings = { openSettings(Screen.REVIEW) },
                            )
                        }
                    }
                    Screen.SETTINGS -> SettingsScreen(onBack = { screen = settingsFrom })
                }
            }
        }
    }
}
