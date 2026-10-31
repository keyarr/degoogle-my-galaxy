package dev.degoogle.app.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.degoogle.app.R
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.OperationLog
import dev.degoogle.app.ui.components.StateBadge
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusRow
import dev.degoogle.app.ui.components.TechnicalStatusRow

@Composable
fun BackupScreen(ui: UiState, vm: AppViewModel) {
    var showUpdateConfirm by remember { mutableStateOf(false) }
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
                text = stringResource(R.string.backup_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.backup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard(
            title = stringResource(R.string.backup_card_snapshot_state),
            action = {
                StateBadge(
                    text = if (f.backupPresent) stringResource(R.string.backup_status_badge_saved) else stringResource(R.string.backup_status_badge_pending),
                    containerColor = if (f.backupPresent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (f.backupPresent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.backup_structure_label),
                    value = stringResource(R.string.backup_structure_value),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                TechnicalStatusRow(
                    label = stringResource(R.string.backup_storage_path_label),
                    value = stringResource(R.string.backup_storage_path_value),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_why_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.backup_why_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.error != null) {
            InfoCard(stringResource(R.string.error_title)) {
                Text(
                    text = ui.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (ui.operationInProgress || ui.steps.isNotEmpty()) {
            InfoCard(
                title = stringResource(
                    if (ui.operationInProgress) R.string.operation_log_live
                    else R.string.operation_log_last,
                ),
            ) {
                OperationLog(
                    steps = ui.steps,
                    inProgress = ui.operationInProgress,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Ações Principais com espaçamento final para não colidir com a barra inferior
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Button(
                onClick = {
                    if (f.backupPresent) showUpdateConfirm = true else vm.createBackup()
                },
                enabled = !ui.operationInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = if (f.backupPresent) stringResource(R.string.backup_btn_update_snapshot) else stringResource(R.string.backup_btn_create_snapshot),
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
                    Text(stringResource(R.string.backup_btn_restore_data))
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
                    Text(stringResource(R.string.backup_btn_delete_files))
                }
            }
        }
    }

    if (showUpdateConfirm) {
        AlertDialog(
            onDismissRequest = { showUpdateConfirm = false },
            title = { Text(stringResource(R.string.backup_dialog_update_title)) },
            text = {
                Text(stringResource(R.string.backup_dialog_update_desc))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        showUpdateConfirm = false
                        vm.createBackup()
                    },
                ) { Text(stringResource(R.string.backup_dialog_update_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.backup_dialog_restore_title)) },
            text = {
                Text(stringResource(R.string.backup_dialog_restore_desc))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        showRestoreConfirm = false
                        vm.restoreBackup()
                    },
                ) { Text(stringResource(R.string.backup_dialog_restore_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.backup_dialog_delete_title)) },
            text = {
                Text(stringResource(R.string.backup_dialog_delete_desc))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteBackup()
                    },
                ) { Text(stringResource(R.string.backup_dialog_delete_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
