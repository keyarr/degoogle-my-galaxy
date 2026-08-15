package dev.degoogle.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.Screen
import dev.degoogle.app.ui.backup.BackupScreen
import dev.degoogle.app.ui.diagnostics.DiagnosticsScreen
import dev.degoogle.app.ui.home.HomeScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notificação de operação pendente pós-boot (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        ui = ui,
                        vm = vm,
                        onNavigate = { screen = it },
                    )
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        ui = ui,
                        onBack = { screen = Screen.HOME },
                    )
                    Screen.BACKUP -> BackupScreen(
                        ui = ui,
                        vm = vm,
                        onBack = { screen = Screen.HOME },
                    )
                }
            }
        }
    }
}
