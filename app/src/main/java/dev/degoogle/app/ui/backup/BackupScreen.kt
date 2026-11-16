package dev.degoogle.app.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.degoogle.app.R
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.OperationMode
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.OperationLog
import dev.degoogle.app.ui.components.Status
import java.text.DateFormat
import java.util.Date
import dev.degoogle.app.ui.components.StatusRow
import dev.degoogle.app.ui.components.TechnicalStatusRow

@Composable
fun BackupScreen(ui: UiState, vm: AppViewModel) {
    val showDeleteConfirmation = remember { mutableStateOf(false) }
    val f = ui.facts
    val pureMode = !f.stockRestoreConfirmed && (
        f.operationMode == OperationMode.PURE ||
            ui.state == DeviceState.STOCK && ui.selectedMode == OperationMode.PURE
        )
    val backupCreatedAt = f.backupCreatedAt?.let { epochSeconds ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(epochSeconds * 1000L))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                text = "03 FCM TOKEN VAULT",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        InfoCard(
            title = stringResource(R.string.backup_card_snapshot_state),
            titleFontWeight = FontWeight.Normal,
            action = {
                if (backupCreatedAt != null) {
                    Text(
                        text = stringResource(R.string.backup_created_at, backupCreatedAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.backup_structure_label),
                    value = stringResource(R.string.backup_structure_value),
                    status = Status.UNKNOWN,
                    showStatus = false,
                    valueFontWeight = FontWeight.Normal,
                )
                TechnicalStatusRow(
                    label = stringResource(R.string.backup_storage_path_label),
                    value = stringResource(R.string.backup_storage_path_value),
                    status = Status.UNKNOWN,
                    showStatus = false,
                    inlineValue = true,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(0.dp),
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

        if (pureMode) {
            InfoCard(stringResource(R.string.backup_pure_mode_title)) {
                Text(
                    text = stringResource(R.string.backup_pure_mode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

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

        // Ações Principais com espaçamento final para não colidir com a barra inferior
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Button(
                onClick = { vm.restoreBackup() },
                enabled = f.backupPresent && !ui.operationInProgress && !pureMode,
                modifier = Modifier.width(240.dp),
                shape = RoundedCornerShape(0.dp),
            ) { Text(stringResource(R.string.backup_btn_restore_data)) }

            Button(
                onClick = { vm.createBackup() },
                enabled = !ui.operationInProgress && !pureMode,
                modifier = Modifier.width(240.dp),
                shape = RoundedCornerShape(0.dp),
            ) { Text(stringResource(R.string.backup_btn_update_snapshot)) }

            Button(
                onClick = { showDeleteConfirmation.value = true },
                enabled = f.backupPresent && !ui.operationInProgress,
                modifier = Modifier.width(240.dp),
                shape = RoundedCornerShape(0.dp),
            ) { Text(stringResource(R.string.backup_btn_delete_files)) }
        }
    }

    if (showDeleteConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation.value = false },
            title = { Text(stringResource(R.string.backup_dialog_delete_title)) },
            text = { Text(stringResource(R.string.backup_dialog_delete_desc)) },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirmation.value = false
                    vm.deleteBackup()
                }, shape = RoundedCornerShape(0.dp)) {
                    Text(stringResource(R.string.backup_dialog_delete_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation.value = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
