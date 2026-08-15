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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.degoogle.app.R
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
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (ui.facts.model.isNotBlank()) {
                    stringResource(R.string.home_subtitle_device, ui.facts.manufacturer, ui.facts.model)
                } else {
                    stringResource(R.string.home_subtitle_realtime)
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
                        text = stringResource(R.string.home_checking_state),
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
            InfoCard(stringResource(R.string.error_title)) {
                Text(
                    text = ui.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (ui.operationInProgress || ui.steps.isNotEmpty()) {
            InfoCard(if (ui.operationInProgress) stringResource(R.string.operation_in_progress) else stringResource(R.string.last_operation_result)) {
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
                    text = stringResource(R.string.home_volatile_root_notice),
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
            title = { Text(stringResource(R.string.home_dialog_degoogle_title)) },
            text = {
                Text(stringResource(R.string.home_dialog_degoogle_text))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        showDeGoogleConfirm = false
                        vm.degoogle()
                    },
                ) { Text(stringResource(R.string.continue_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeGoogleConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            icon = { Icon(Icons.Rounded.Undo, contentDescription = null) },
            title = { Text(stringResource(R.string.home_dialog_restore_title)) },
            text = {
                Text(stringResource(R.string.home_dialog_restore_text))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showRestoreConfirm = false
                        vm.restoreGoogle()
                    },
                ) { Text(stringResource(R.string.home_btn_restore_google_stock)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (ui.needsSetupPrompt) {
        AlertDialog(
            onDismissRequest = { vm.dismissSetupPrompt() },
            icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
            title = { Text(stringResource(R.string.home_dialog_setup_title)) },
            text = {
                Text(stringResource(R.string.home_dialog_setup_text))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        vm.dismissSetupPrompt()
                        openMicrogSettings(context)
                    },
                ) { Text(stringResource(R.string.home_dialog_open_microg)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissSetupPrompt() }) { Text(stringResource(R.string.understand_btn)) }
            },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun NoRootCard() {
    InfoCard(stringResource(R.string.home_no_root_title)) {
        Text(
            text = stringResource(R.string.home_no_root_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun UnsupportedCard(ui: UiState) {
    InfoCard(stringResource(R.string.home_unsupported_title)) {
        Text(
            text = stringResource(R.string.home_unsupported_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        val f = ui.facts
        StatusRow(stringResource(R.string.diag_manufacturer), f.manufacturer, Status.UNKNOWN, leadingIcon = Icons.Rounded.Android)
        StatusRow(stringResource(R.string.diag_model), f.model, Status.UNKNOWN, leadingIcon = Icons.Rounded.Fingerprint)
        StatusRow(stringResource(R.string.diag_device), f.device, Status.UNKNOWN, leadingIcon = Icons.Rounded.Security)
        StatusRow(stringResource(R.string.diag_android), "${f.androidRelease} (SDK ${f.androidSdk})", Status.UNKNOWN, leadingIcon = Icons.Rounded.Android)
        StatusRow(stringResource(R.string.diag_fingerprint), f.fingerprint.ifBlank { stringResource(R.string.not_reported) }, Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_real_gms), f.gmsPath ?: stringResource(R.string.absent), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_real_gsf), f.gsfPath ?: stringResource(R.string.absent), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_real_store), f.storePath ?: stringResource(R.string.absent), Status.UNKNOWN)
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
        title = stringResource(R.string.home_system_environment),
        action = {
            StateBadge(
                text = stringResource(R.string.state_stock),
                icon = Icons.Rounded.Lock,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StatusRow(
                stringResource(R.string.diag_root_access),
                if (f.rootOk) f.rootManager.ifBlank { stringResource(R.string.ok) } else stringResource(R.string.no),
                if (f.rootOk) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Shield,
            )
            StatusRow(
                stringResource(R.string.diag_play_services),
                f.gmsVersion ?: f.gmsPath ?: stringResource(R.string.not_reported),
                if (f.gmsPath != null) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Layers,
            )
            StatusRow(
                stringResource(R.string.diag_framework_gsf),
                if (!f.gsfPath.isNullOrEmpty()) stringResource(R.string.diag_stock_active) else stringResource(R.string.absent),
                if (!f.gsfPath.isNullOrEmpty()) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Store,
            )
            StatusRow(
                stringResource(R.string.diag_backup_microg),
                if (f.backupPresent) stringResource(R.string.available) else stringResource(R.string.not_created),
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
        Text(stringResource(R.string.home_install_microg_btn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PreparedContent(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
) {
    InfoCard(
        title = stringResource(R.string.home_prepared_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_reboot_required),
                icon = Icons.Rounded.RestartAlt,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.home_prepared_desc),
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
        Text(stringResource(R.string.home_soft_reboot_btn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Text(stringResource(R.string.home_cancel_and_restore_btn))
    }
}

@Composable
private fun BootedContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = stringResource(R.string.home_booted_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_post_reboot),
                icon = Icons.Rounded.Verified,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.home_booted_desc),
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
        Text(stringResource(R.string.home_complete_setup_btn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        title = stringResource(R.string.home_microg_active_title),
        action = {
            StateBadge(
                text = if (isBackedUp) stringResource(R.string.home_badge_active_safe) else stringResource(R.string.home_badge_active_no_backup),
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
                stringResource(R.string.diag_privileged),
                if (f.gmsPrivileged) stringResource(R.string.diag_registered_ok) else stringResource(R.string.no),
                if (f.gmsPrivileged) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.VerifiedUser,
            )
            StatusRow(
                stringResource(R.string.diag_framework_gsf),
                stringResource(R.string.diag_stock_removed_masked),
                Status.OK,
                leadingIcon = Icons.Rounded.CloudOff,
            )
            StatusRow(
                stringResource(R.string.diag_companion),
                if (!f.storePath.isNullOrEmpty()) stringResource(R.string.active) else stringResource(R.string.absent),
                if (!f.storePath.isNullOrEmpty()) Status.OK else Status.FAIL,
                leadingIcon = Icons.Rounded.Store,
            )
            StatusRow(
                stringResource(R.string.diag_backup_fcm),
                if (f.backupPresent) stringResource(R.string.available) else stringResource(R.string.not_created),
                if (f.backupPresent) Status.OK else Status.ABSENT,
                leadingIcon = Icons.Rounded.Backup,
            )
            if (f.gmsVersion != null) {
                StatusRow(
                    stringResource(R.string.diag_version_microg),
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
                text = if (isBackedUp) stringResource(R.string.home_btn_update_token_backup) else stringResource(R.string.home_btn_create_token_backup),
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
            Text(stringResource(R.string.home_btn_restore_google_stock))
        }
    }
}

@Composable
private fun RestorePreparedContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = stringResource(R.string.home_restore_prepared_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_reboot_required),
                icon = Icons.Rounded.RestartAlt,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.home_restore_prepared_desc),
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
        Text(stringResource(R.string.home_soft_reboot_btn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorContent(ui: UiState, vm: AppViewModel, onNavigate: (Screen) -> Unit) {
    InfoCard(
        title = stringResource(R.string.home_error_state_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_warning),
                icon = Icons.Rounded.ErrorOutline,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.home_error_state_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        val f = ui.facts
        StatusRow(stringResource(R.string.diag_real_gms), f.gmsPath ?: stringResource(R.string.absent), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_real_gsf), f.gsfPath ?: stringResource(R.string.absent), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_real_store), f.storePath ?: stringResource(R.string.absent), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_mount_gms), if (f.mountGms) stringResource(R.string.active) else stringResource(R.string.inactive), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_mount_gsf), if (f.mountGsf) stringResource(R.string.active) else stringResource(R.string.inactive), Status.UNKNOWN)
        StatusRow(stringResource(R.string.diag_mount_store), if (f.mountStore) stringResource(R.string.active) else stringResource(R.string.inactive), Status.UNKNOWN)
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
            Text(stringResource(R.string.home_btn_remove_update_stock))
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
