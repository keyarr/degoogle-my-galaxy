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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.Screen
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.StateBadge
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cabeçalho One UI
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                text = "DeGoogle",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (ui.facts.model.isNotBlank()) {
                    "${ui.facts.manufacturer} ${ui.facts.model} · One UI"
                } else {
                    "Verificação em tempo real do ambiente"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ui.refreshing) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = "Verificando o estado do aparelho…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
                Text(
                    text = ui.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (ui.operationInProgress || ui.steps.isNotEmpty()) {
            InfoCard(if (ui.operationInProgress) "Operação em andamento" else "Resultado da última operação") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    ui.steps.forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusIcon(
                                when (s.ok) {
                                    true -> Status.OK
                                    false -> Status.FAIL
                                    null -> Status.UNKNOWN
                                },
                            )
                            Text(
                                text = s.text,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // Cartão explicativo sobre Root Volátil com margem inferior protegendo a rolagem
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Apenas o Soft Reboot (userspace) é utilizado pelo app. Um reboot completo zera mounts dinâmicos e perde o root temporário do exploit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ------------------------------------------------------------ Dialogs
    if (showDeGoogleConfirm) {
        AlertDialog(
            onDismissRequest = { showDeGoogleConfirm = false },
            icon = { Icon(Icons.Rounded.DownloadForOffline, contentDescription = null) },
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
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        showDeGoogleConfirm = false
                        vm.degoogle()
                    },
                ) { Text("Continuar") }
            },
            dismissButton = {
                TextButton(onClick = { showDeGoogleConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Rounded.Undo, contentDescription = null) },
            title = { Text("Restaurar Google?") },
            text = {
                Text(
                    "O microG será removido do ambiente ativo e os componentes Google " +
                        "originais serão revelados novamente.\n\n" +
                        "Seu backup do microG será mantido.",
                )
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showRestoreConfirm = false
                        vm.restoreGoogle()
                    },
                ) { Text("Restaurar Google") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    if (ui.needsSetupPrompt) {
        AlertDialog(
            onDismissRequest = { vm.dismissSetupPrompt() },
            icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
            title = { Text("microG instalado com sucesso") },
            text = {
                Text(
                    "Abra as configurações do microG e faça a configuração inicial, " +
                        "incluindo sua conta, Cloud Messaging e demais opções desejadas.\n\n" +
                        "Quando terminar, volte para este aplicativo e crie um backup.",
                )
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        vm.dismissSetupPrompt()
                        openMicrogSettings(context)
                    },
                ) { Text("Abrir microG") }
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
            text = "Este aplicativo requer acesso root e não fará nenhuma alteração sem ele.\n" +
                "Instale KernelSU, Magisk ou APatch e conceda root.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun UnsupportedCard(ui: UiState) {
    InfoCard("Dispositivo não suportado") {
        Text(
            text = "O aparelho possui root, mas sua configuração não corresponde a um perfil " +
                "suportado. Nenhuma modificação foi feita.\n",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        val f = ui.facts
        StatusRow("Fabricante", f.manufacturer, Status.UNKNOWN, leadingIcon = Icons.Rounded.Android)
        StatusRow("Modelo", f.model, Status.UNKNOWN, leadingIcon = Icons.Rounded.Fingerprint)
        StatusRow("Dispositivo", f.device, Status.UNKNOWN, leadingIcon = Icons.Rounded.Security)
        StatusRow("Android", "${f.androidRelease} (SDK ${f.androidSdk})", Status.UNKNOWN, leadingIcon = Icons.Rounded.Android)
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

    InfoCard(
        title = "Ambiente do Sistema",
        action = {
            StateBadge(
                text = "Google Stock",
                icon = Icons.Rounded.Lock,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StatusRow(
                "Acesso Root",
                if (f.rootOk) f.rootManager.ifBlank { "ok" } else "não",
                if (f.rootOk) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Shield,
            )
            StatusRow(
                "Play Services",
                f.gmsVersion ?: f.gmsPath ?: "—",
                if (f.gmsPath != null) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Layers,
            )
            StatusRow(
                "Framework (GSF)",
                if (!f.gsfPath.isNullOrEmpty()) "stock ativo" else "ausente",
                if (!f.gsfPath.isNullOrEmpty()) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Store,
            )
            StatusRow(
                "Backup microG",
                if (f.backupPresent) "disponível" else "não criado",
                if (f.backupPresent) Status.OK else Status.ABSENT,
                leadingIcon = Icons.Rounded.Backup,
            )
        }
    }

    Button(
        onClick = onDeGoogle,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Icon(Icons.Rounded.DownloadForOffline, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Instalar microG (DeGoogle)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PreparedContent(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
) {
    InfoCard(
        title = "Preparação concluída",
        action = {
            StateBadge(
                text = "Requer Reboot",
                icon = Icons.Rounded.RestartAlt,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        },
    ) {
        Text(
            text = "As máscaras foram aplicadas no mount namespace global.\n\n" +
                "O aparelho precisa de um soft reboot para o PackageManager registrar os novos pacotes priv-app.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Button(
        onClick = { vm.softReboot() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Soft Reboot (Userspace)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }

    OutlinedButton(
        onClick = { vm.restoreGoogle() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(Icons.Rounded.Undo, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Cancelar e restaurar Google")
    }
}

@Composable
private fun BootedContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = "Configuração pronta",
        action = {
            StateBadge(
                text = "Pós-Reboot",
                icon = Icons.Rounded.Verified,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    ) {
        Text(
            text = "O microG foi registrado como priv-app e o GSF stock foi removido.\n\n" +
                "As permissões, AppOps e Doze bypass serão finalizados agora.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Button(
        onClick = { vm.finalizeIfNeeded() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Concluir configuração", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    val isBackedUp = ui.state == DeviceState.MICROG_ACTIVE_BACKED_UP

    InfoCard(
        title = "microG Ativo",
        action = {
            StateBadge(
                text = if (isBackedUp) "Ativo & Seguro" else "Ativo (Sem Backup)",
                icon = if (isBackedUp) Icons.Rounded.Shield else Icons.Rounded.WarningAmber,
                containerColor = if (isBackedUp) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
                contentColor = if (isBackedUp) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        },
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StatusRow(
                "Priv-app",
                if (f.gmsPrivileged) "registrado (ok)" else "não",
                if (f.gmsPrivileged) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.VerifiedUser,
            )
            StatusRow(
                "GSF Stock",
                "removido/mascarado",
                Status.OK,
                leadingIcon = Icons.Rounded.CloudOff,
            )
            StatusRow(
                "microG Companion",
                if (!f.storePath.isNullOrEmpty()) "ativo" else "ausente",
                if (!f.storePath.isNullOrEmpty()) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Store,
            )
            StatusRow(
                "Backup FCM",
                if (f.backupPresent) "disponível" else "não criado",
                if (f.backupPresent) Status.OK else Status.ABSENT,
                leadingIcon = Icons.Rounded.Backup,
            )
            if (f.gmsVersion != null) {
                StatusRow(
                    "Versão microG",
                    f.gmsVersion,
                    Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.Layers,
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { vm.createBackup() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Rounded.CloudDone, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (isBackedUp) "Atualizar Backup dos Tokens" else "Criar Backup dos Tokens",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        FilledTonalButton(
            onClick = onRestoreGoogle,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Undo, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Restaurar Google Stock")
        }
    }
}

@Composable
private fun RestorePreparedContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = "Rollback preparado",
        action = {
            StateBadge(
                text = "Requer Reboot",
                icon = Icons.Rounded.RestartAlt,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        },
    ) {
        Text(
            text = "As máscaras foram removidas e o ambiente Google original será revelado após o soft reboot.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Button(
        onClick = { vm.softReboot() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Soft Reboot (Userspace)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorContent(ui: UiState, vm: AppViewModel, onNavigate: (Screen) -> Unit) {
    InfoCard(
        title = "Estado inconsistente",
        action = {
            StateBadge(
                text = "Atenção",
                icon = Icons.Rounded.ErrorOutline,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        Text(
            text = "O aparelho está em um estado não esperado. Nenhuma operação foi executada automaticamente.\n\n" +
                "Consulte a aba de Diagnóstico para ver os detalhes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
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
        Button(
            onClick = { vm.removeGmsUpdate() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("Remover update e voltar ao stock")
        }
    }
}

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
