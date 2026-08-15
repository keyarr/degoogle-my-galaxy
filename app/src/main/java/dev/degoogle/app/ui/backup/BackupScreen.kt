package dev.degoogle.app.ui.backup

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
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.StateBadge
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusIcon
import dev.degoogle.app.ui.components.StatusRow

@Composable
fun BackupScreen(ui: UiState, vm: AppViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val f = ui.facts

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                text = "Backup & Tokens",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Preservação de credenciais e notificações push (FCM)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard(
            title = "Estado do Snapshot",
            action = {
                StateBadge(
                    text = if (f.backupPresent) "Disponível" else "Não Criado",
                    icon = if (f.backupPresent) Icons.Rounded.CloudDone else Icons.Rounded.WarningAmber,
                    containerColor = if (f.backupPresent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (f.backupPresent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    "Status do Backup",
                    if (f.backupPresent) "salvo em disco" else "pendente",
                    if (f.backupPresent) Status.OK else Status.ABSENT,
                    leadingIcon = Icons.Rounded.CloudDone,
                )
                StatusRow(
                    "Estrutura",
                    "user0 + user_de (MicroG Session)",
                    Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.FolderZip,
                )
                StatusRow(
                    "Local no Armazenamento",
                    "/data/local/tmp/microg-backup",
                    Status.UNKNOWN,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Por que fazer o backup?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Apps como WhatsApp e Telegram registram um token FCM único na primeira abertura. Ao salvar o backup, você restaura essas notificações instantaneamente após qualquer re-instalação.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (ui.error != null) {
            InfoCard("Erro") {
                Text(
                    text = ui.error,
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
                    ui.steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusIcon(
                                when (step.ok) {
                                    true -> Status.OK
                                    false -> Status.FAIL
                                    null -> Status.UNKNOWN
                                },
                            )
                            Text(text = step.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Ações Principais com espaçamento final para não colidir com a barra inferior
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Button(
                onClick = { vm.createBackup() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (f.backupPresent) "Atualizar Snapshot do microG" else "Criar Snapshot do microG",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (f.backupPresent) {
                FilledTonalButton(
                    onClick = { showRestoreConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Restaurar Dados do Backup")
                }

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Excluir Arquivos do Backup")
                }
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null) },
            title = { Text("Restaurar backup?") },
            text = {
                Text(
                    "Os dados atuais do microG serão substituídos pelos dados salvos " +
                        "em /data/local/tmp/microg-backup. O processo do microG será finalizado durante a cópia.",
                )
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        showRestoreConfirm = false
                        vm.restoreBackup()
                    },
                ) { Text("Restaurar") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Excluir backup permanentemente?") },
            text = {
                Text(
                    "Os arquivos em /data/local/tmp/microg-backup serão apagados. " +
                        "Esta ação não pode ser desfeita.",
                )
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteBackup()
                    },
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            },
        )
    }
}
