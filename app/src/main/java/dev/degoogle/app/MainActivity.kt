package dev.degoogle.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.degoogle.app.data.Prefs
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.Screen
import dev.degoogle.app.ui.backup.BackupScreen
import dev.degoogle.app.ui.diagnostics.DiagnosticsScreen
import dev.degoogle.app.ui.home.HomeScreen
import dev.degoogle.app.ui.settings.SettingsScreen
import dev.degoogle.app.ui.theme.DeGoogleTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Notificação de operação pendente pós-boot (Android 13+). Só pedimos
        // quando o usuário mantém as notificações habilitadas nas Configurações.
        val notificationsOn = runBlocking { Prefs(this@MainActivity).notificationsEnabled.first() }
        if (Build.VERSION.SDK_INT >= 33 &&
            notificationsOn &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DeGoogleTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                val ui by vm.ui.collectAsStateWithLifecycle()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = screen == Screen.HOME,
                                onClick = { screen = Screen.HOME },
                                icon = {
                                    Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.nav_home))
                                },
                                label = { Text(stringResource(R.string.nav_home)) },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.DIAGNOSTICS,
                                onClick = { screen = Screen.DIAGNOSTICS },
                                icon = {
                                    Icon(Icons.Rounded.Troubleshoot, contentDescription = stringResource(R.string.nav_diagnostics))
                                },
                                label = { Text(stringResource(R.string.nav_diagnostics)) },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.BACKUP,
                                onClick = { screen = Screen.BACKUP },
                                icon = {
                                    Icon(Icons.Rounded.Backup, contentDescription = stringResource(R.string.nav_backup))
                                },
                                label = { Text(stringResource(R.string.nav_backup)) },
                            )
                            NavigationBarItem(
                                selected = screen == Screen.SETTINGS,
                                onClick = { screen = Screen.SETTINGS },
                                icon = {
                                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.nav_settings))
                                },
                                label = { Text(stringResource(R.string.nav_settings)) },
                            )
                        }
                    },
                ) { innerPadding ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                ui = ui,
                                vm = vm,
                                onNavigate = { screen = it },
                            )
                            Screen.DIAGNOSTICS -> DiagnosticsScreen(
                                ui = ui,
                            )
                            Screen.BACKUP -> BackupScreen(
                                ui = ui,
                                vm = vm,
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }
    }
}
