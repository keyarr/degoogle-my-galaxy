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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
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
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        Column(modifier = Modifier.statusBarsPadding()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "[DEGOOGLE://SAMSUNG-SECURITY-NULL]",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    "ROOT:${if (ui.facts.rootOk) "AVAILABLE" else "ABSENT"} // SELINUX:${ui.facts.selinux.uppercase()}",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262626)))
                        }
                    },
                    bottomBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .navigationBarsPadding(),
                        ) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262626)))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(68.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                listOf(
                                    Triple(Screen.HOME, R.drawable.ic_nav_home, R.string.nav_home),
                                    Triple(Screen.DIAGNOSTICS, R.drawable.ic_nav_diagnostics, R.string.nav_diagnostics),
                                    Triple(Screen.BACKUP, R.drawable.ic_nav_backup, R.string.nav_backup),
                                    Triple(Screen.SETTINGS, R.drawable.ic_nav_settings, R.string.nav_settings),
                                ).forEach { (target, icon, label) ->
                                    val selected = screen == target
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxSize()
                                            .clickable { screen = target },
                                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(icon),
                                            contentDescription = stringResource(label),
                                            modifier = Modifier.size(28.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Box(
                                            Modifier
                                                .width(18.dp)
                                                .height(2.dp)
                                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                                        )
                                    }
                                }
                            }
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
