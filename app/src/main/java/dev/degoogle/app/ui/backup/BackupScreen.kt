package dev.degoogle.app.ui.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusIcon
import dev.degoogle.app.ui.components.StatusRow

@Composable
fun BackupScreen(ui: UiState, vm: AppViewModel, onBack: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val f = ui.facts

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Backup do microG", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        InfoCard("Backup") {
            StatusRow(
                "Backup",
                if (f.backupPresent) "encontrado" else "não criado",
                if (f.backupPresent) Status.OK else Status.ABSENT,
            )
            StatusRow("Formato", "MicroG Session", Status.UNKNOWN)
            StatusRow("Local", "/data/local/tmp/microg-backup", Status.UNKNOWN)
        }

        if (f.backupPresent) {
            InfoCard("Restauração automática") {
                Text(
                    "Os dados de user0 e user_de serão restaurados após a " +
                        "reinstalação do microG. Também é possível restaurá-los manualmente.",
                )
            }
        }

        if (ui.error != null) {
            InfoCard("Erro") {
                Text(ui.error)
            }
        }

        if (ui.operationInProgress || ui.steps.isNotEmpty()) {
            InfoCard(
                if (ui.operationInProgress) "Operação em andamento" else "Resultado da última operação",
            ) {
                ui.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(
                            when (step.ok) {
                                true -> Status.OK
                                false -> Status.FAIL
                                null -> Status.UNKNOWN
                            },
                        )
                        Text("  ${step.text}")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (f.backupPresent) {
            Button(onClick = { vm.createBackup() }, modifier = Modifier.fillMaxWidth()) {
                Text("Atualizar backup")
            }
        } else {
            Button(onClick = { vm.createBackup() }, modifier = Modifier.fillMaxWidth()) {
                Text("Criar backup")
            }
        }
        if (f.backupPresent) {
            OutlinedButton(
                onClick = { showRestoreConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restaurar backup local")
            }
        }
        if (f.backupPresent) {
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Excluir backup")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restaurar backup local?") },
            text = {
                Text(
                    "Os dados atuais do microG serão substituídos pelos dados salvos " +
                        "em /data/local/tmp/microg-backup. O microG será parado durante " +
                        "a restauração.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirm = false
                    vm.restoreBackup()
                }) { Text("Restaurar") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir backup?") },
            text = {
                Text(
                    "O backup do microG será excluído permanentemente. " +
                        "Esta ação não pode ser desfeita.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    vm.deleteBackup()
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            },
        )
    }
}
