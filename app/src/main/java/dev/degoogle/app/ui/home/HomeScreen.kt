package dev.degoogle.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.Screen
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusIcon
import dev.degoogle.app.ui.components.StatusRow

@Composable
fun HomeScreen(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
) {
    val context = LocalContext.current
    var showDeGoogleConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("DeGoogle", style = MaterialTheme.typography.headlineMedium)
        Text(
            "O estado abaixo é o estado REAL do aparelho, verificado a cada abertura.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (ui.refreshing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("  Verificando o estado do aparelho…")
            }
            return@Column
        }

        when (ui.state) {
            DeviceState.NO_ROOT -> NoRootCard()
            DeviceState.UNSUPPORTED -> UnsupportedCard(ui)
            DeviceState.STOCK -> StockContent(ui, vm, onNavigate, { showDeGoogleConfirm = true })
            DeviceState.PREPARED -> PreparedContent(ui, vm, onNavigate)
            DeviceState.MICROG_BOOTED -> BootedContent(ui, vm)
            DeviceState.MICROG_NEEDS_SETUP, DeviceState.MICROG_ACTIVE,
            DeviceState.MICROG_ACTIVE_BACKED_UP -> ActiveContent(
                ui, vm, onNavigate, { showRestoreConfirm = true },
            )
            DeviceState.RESTORE_PREPARED -> RestorePreparedContent(ui, vm)
            DeviceState.ERROR -> ErrorContent(ui, vm, onNavigate)
            DeviceState.PREPARING -> Unit
        }

        if (ui.error != null) {
            InfoCard("Erro") {
                Text(ui.error ?: "")
            }
        }

        if (ui.operationInProgress || ui.steps.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            InfoCard(if (ui.operationInProgress) "Operação em andamento" else "Resultado da última operação") {
                ui.steps.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(
                            when (s.ok) {
                                true -> Status.OK
                                false -> Status.FAIL
                                null -> Status.UNKNOWN
                            },
                        )
                        Text("  ${s.text}")
                    }
                }
            }
        }

    }

    // ------------------------------------------------------------ dialogs
    if (showDeGoogleConfirm) {
        AlertDialog(
            onDismissRequest = { showDeGoogleConfirm = false },
            title = { Text("DeGoogle?") },
            text = {
                Text(
                    "O aplicativo fará alterações temporárias no ambiente de sistema " +
                        "usando root e bind mounts.\n\n" +
                        "Nenhum arquivo da partição /product será alterado fisicamente.\n\n" +
                        "O processo requer um reboot (soft reboot).",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDeGoogleConfirm = false
                    vm.degoogle()
                }) { Text("Continuar") }
            },
            dismissButton = {
                TextButton(onClick = { showDeGoogleConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restaurar Google?") },
            text = {
                Text(
                    "O microG será removido do ambiente ativo e os componentes Google " +
                        "originais serão revelados novamente.\n\n" +
                        "Seu backup do microG será mantido.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirm = false
                    vm.restoreGoogle()
                }) { Text("Restaurar Google") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    if (ui.needsSetupPrompt) {
        AlertDialog(
            onDismissRequest = { vm.dismissSetupPrompt() },
            title = { Text("microG instalado com sucesso") },
            text = {
                Text(
                    "Agora abra as configurações do microG e faça a configuração inicial, " +
                        "incluindo sua conta, Cloud Messaging e demais opções desejadas.\n\n" +
                        "Quando terminar, volte para este aplicativo e crie um backup.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.dismissSetupPrompt()
                    openMicrogSettings(context)
                }) { Text("Abrir microG") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissSetupPrompt() }) { Text("Entendi") }
            },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun NoRootCard() {
    InfoCard("Root não disponível") {
        Text(
            "Este aplicativo requer acesso root e não fará nenhuma alteração sem ele.\n" +
                "Instale KernelSU, Magisk ou APatch e conceda root.",
        )
    }
}

@Composable
private fun UnsupportedCard(ui: UiState) {
    InfoCard("Dispositivo não suportado") {
        Text(
            "O aparelho possui root, mas sua configuração não corresponde a um perfil " +
                "suportado. Nenhuma modificação foi feita.\n",
        )
        val f = ui.facts
        StatusRow("Fabricante", f.manufacturer, Status.UNKNOWN)
        StatusRow("Modelo", f.model, Status.UNKNOWN)
        StatusRow("Dispositivo", f.device, Status.UNKNOWN)
        StatusRow("Android", "${f.androidRelease} (SDK ${f.androidSdk})", Status.UNKNOWN)
        StatusRow("Fingerprint", f.fingerprint.ifBlank { "—" }, Status.UNKNOWN)
        StatusRow("GMS real", f.gmsPath ?: "ausente", Status.UNKNOWN)
        StatusRow("GSF real", f.gsfPath ?: "ausente", Status.UNKNOWN)
        StatusRow("Play Store real", f.storePath ?: "ausente", Status.UNKNOWN)
    }
}

@Composable
private fun StockContent(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
    onDeGoogle: () -> Unit,
) {
    val f = ui.facts
    InfoCard("Sistema") {
        StatusRow("Root", if (f.rootOk) f.rootManager.ifBlank { "ok" } else "não", if (f.rootOk) Status.OK else Status.FAIL)
        StatusRow("Dispositivo", f.model.ifBlank { "—" }, Status.OK)
        StatusRow("Android", "${f.androidRelease.ifBlank { "?" }} (SDK ${f.androidSdk})", Status.OK)
        StatusRow("Perfil", if (f.profileMatch) "suportado" else "não suportado", if (f.profileMatch) Status.OK else Status.FAIL)
    }
    InfoCard("Google") {
        StatusRow("Play Services", f.gmsVersion ?: f.gmsPath ?: "—", if (f.gmsPath != null) Status.OK else Status.FAIL)
        StatusRow("Services Framework", "stock", if (!f.gsfPath.isNullOrEmpty()) Status.OK else Status.FAIL)
        StatusRow("Play Store", "stock", if (!f.storePath.isNullOrEmpty()) Status.OK else Status.FAIL)
    }
    InfoCard("microG") {
        StatusRow("microG", "—", Status.ABSENT)
        StatusRow("Backup", "—", Status.ABSENT)
    }

    Spacer(Modifier.height(16.dp))
    Button(onClick = onDeGoogle, modifier = Modifier.fillMaxWidth()) {
        Text("DeGoogle")
    }
    TextButton(onClick = { onNavigate(Screen.DIAGNOSTICS) }) { Text("Diagnóstico") }
}

@Composable
private fun PreparedContent(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
) {
    InfoCard("Preparação concluída") {
        Text(
            "As máscaras foram aplicadas no mount namespace correto.\n\n" +
                "O aparelho precisa de um soft reboot para o PackageManager registrar " +
                "os novos pacotes.",
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = { vm.softReboot() }, modifier = Modifier.fillMaxWidth()) {
        Text("Soft Reboot")
    }
    Text(
        "Este aparelho pode perder o root e o microG num reboot completo — " +
            "use apenas o soft reboot.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    OutlinedButton(
        onClick = { vm.restoreGoogle() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Cancelar e restaurar")
    }
    TextButton(onClick = { onNavigate(Screen.DIAGNOSTICS) }) { Text("Diagnóstico") }
}

@Composable
private fun BootedContent(ui: UiState, vm: AppViewModel) {
    InfoCard("Configuração pronta") {
        Text(
            "O microG foi registrado como priv-app e o GSF stock foi removido.\n\n" +
                "A configuração técnica (permissões, appops, Doze) será aplicada agora.",
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = { vm.finalizeIfNeeded() }, modifier = Modifier.fillMaxWidth()) {
        Text("Concluir configuração")
    }
}

@Composable
private fun ActiveContent(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
    onRestoreGoogle: () -> Unit,
) {
    val f = ui.facts
    InfoCard("microG ativo") {
        StatusRow("Priv-app", if (f.gmsPrivileged) "ok" else "não", if (f.gmsPrivileged) Status.OK else Status.FAIL)
        StatusRow("GSF stock", "removido", Status.OK)
        StatusRow("Companion", if (!f.storePath.isNullOrEmpty()) "ativo" else "ausente", if (!f.storePath.isNullOrEmpty()) Status.OK else Status.FAIL)
        StatusRow(
            "Backup",
            if (f.backupPresent) "disponível" else "não criado",
            if (f.backupPresent) Status.OK else Status.ABSENT,
        )
        if (f.gmsVersion != null) StatusRow("Versão microG", f.gmsVersion, Status.UNKNOWN)
    }

    Spacer(Modifier.height(16.dp))
    if (ui.state == DeviceState.MICROG_ACTIVE_BACKED_UP) {
        Button(onClick = { vm.createBackup() }, modifier = Modifier.fillMaxWidth()) {
            Text("Atualizar backup")
        }
    } else {
        Button(onClick = { vm.createBackup() }, modifier = Modifier.fillMaxWidth()) {
            Text("Criar backup")
        }
    }
    OutlinedButton(onClick = { onNavigate(Screen.BACKUP) }, modifier = Modifier.fillMaxWidth()) {
        Text("Backup")
    }
    OutlinedButton(onClick = { onNavigate(Screen.DIAGNOSTICS) }, modifier = Modifier.fillMaxWidth()) {
        Text("Ver diagnóstico")
    }
    TextButton(onClick = onRestoreGoogle, modifier = Modifier.fillMaxWidth()) {
        Text("Restaurar Google")
    }
}

@Composable
private fun RestorePreparedContent(ui: UiState, vm: AppViewModel) {
    InfoCard("Rollback preparado") {
        Text(
            "As máscaras foram removidas e o ambiente stock será revelado após o " +
                "soft reboot.",
        )
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = { vm.softReboot() }, modifier = Modifier.fillMaxWidth()) {
        Text("Soft Reboot")
    }
    Text(
        "Nunca use reboot completo neste aparelho — ele perde o root (exploit) e o microG.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ErrorContent(ui: UiState, vm: AppViewModel, onNavigate: (Screen) -> Unit) {
    InfoCard("Estado inconsistente") {
        Text(
            "O aparelho está em um estado que não corresponde a nenhum fluxo esperado. " +
                "Nenhuma operação foi executada automaticamente.\n\n" +
                "Veja o diagnóstico para entender o que foi detectado.",
        )
        val f = ui.facts
        StatusRow("GMS", f.gmsPath ?: "ausente", Status.UNKNOWN)
        StatusRow("GSF", f.gsfPath ?: "ausente", Status.UNKNOWN)
        StatusRow("Play Store", f.storePath ?: "ausente", Status.UNKNOWN)
        StatusRow("Mount GMS", if (f.mountGms) "ativo" else "—", Status.UNKNOWN)
        StatusRow("Mount GSF", if (f.mountGsf) "ativo" else "—", Status.UNKNOWN)
        StatusRow("Mount Store", if (f.mountStore) "ativo" else "—", Status.UNKNOWN)
    }
    val gmsUpdatable = ui.facts.gmsPath?.startsWith("/data/app/") == true ||
        ui.facts.storePath?.startsWith("/data/app/") == true
    if (gmsUpdatable) {
        Button(onClick = { vm.removeGmsUpdate() }, modifier = Modifier.fillMaxWidth()) {
            Text("Remover update e voltar ao stock")
        }
        Text(
            "A Play Store auto-atualizou o GMS para /data/app. Remover o update " +
                "volta ao base e desabilita a Play Store durante o fluxo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    TextButton(onClick = { onNavigate(Screen.DIAGNOSTICS) }) { Text("Ver diagnóstico") }
}

// ---------------------------------------------------------------------------

private fun openMicrogSettings(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_MAIN).setClassName(
        "com.google.android.gms",
        "com.google.android.gms.SettingsActivity",
    )
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:com.google.android.gms"),
                    ),
                )
            }
        }
}
