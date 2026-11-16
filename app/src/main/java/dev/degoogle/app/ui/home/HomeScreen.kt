package dev.degoogle.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.degoogle.app.R
import dev.degoogle.app.domain.DeviceState
import dev.degoogle.app.domain.OperationMode
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.ui.AppViewModel
import dev.degoogle.app.ui.CheckStatus
import dev.degoogle.app.ui.DeviceCheck
import dev.degoogle.app.ui.Screen
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.OperationLog
import dev.degoogle.app.ui.components.StateBadge
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusRow
import dev.degoogle.app.ui.components.SlideToExecute
import dev.degoogle.app.ui.components.TechnicalBlock
import dev.degoogle.app.ui.components.TechnicalStatusRow

@Composable
fun HomeScreen(
    ui: UiState,
    vm: AppViewModel,
    onNavigate: (Screen) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cabeçalho One UI
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.home_screen_header),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (ui.hasResult && !ui.resultCurrent) {
            Text(
                text = stringResource(R.string.home_result_stale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            IsolationStages(ui)
        } else if (ui.resultCurrent) {
            IsolationStages(ui)
        }

        if (ui.refreshing || !ui.resultCurrent) {
            Surface(
                shape = RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (ui.refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    Text(
                        text = stringResource(
                            if (ui.refreshing) R.string.home_checking_state else R.string.home_check_incomplete,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            CheckProgressList(ui.checks)
            ui.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        if (ui.rescuePartyNotice) {
            InfoCard(
                title = stringResource(R.string.home_rescue_party_notice_title),
                action = {
                    Box(
                        modifier = Modifier
                            .offset(x = 8.dp, y = (-4).dp)
                            .size(28.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .clickable { vm.dismissRescuePartyNotice() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.home_rescue_party_notice_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            ) {
                Text(
                    text = stringResource(R.string.home_rescue_party_notice_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (ui.recoveryRequired) {
            InfoCard(stringResource(R.string.error_title)) {
                Text(
                    text = ui.recoveryMessage ?: stringResource(R.string.home_recovery_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!ui.recoveryDetail.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TechnicalBlock(text = ui.recoveryDetail)
                }
                SlideToExecute(
                    label = stringResource(R.string.slide_restore_stock),
                    enabled = !ui.operationInProgress,
                    onExecute = { vm.restoreGoogle() },
                )
            }
        }

        when (ui.state) {
            DeviceState.NO_ROOT -> NoRootCard()
            DeviceState.UNSUPPORTED -> UnsupportedCard(ui)
            DeviceState.STOCK -> StockContent(ui, vm, onNavigate, { vm.degoogle() })
            DeviceState.PREPARED -> PreparedContent(ui, vm, onNavigate)
            DeviceState.DEGOOGLE_PREPARED -> PurePreparedContent(ui, vm)
            DeviceState.DEGOOGLE_ACTIVE -> PureActiveContent(ui, vm)
            DeviceState.MICROG_BOOTED -> BootedContent(ui, vm)
            DeviceState.MICROG_NEEDS_SETUP, DeviceState.MICROG_ACTIVE,
            DeviceState.MICROG_ACTIVE_BACKED_UP -> ActiveContent(
                ui, { vm.restoreGoogle() },
            )
            DeviceState.RESTORE_PREPARED -> RestorePreparedContent(ui, vm)
            DeviceState.ERROR -> ErrorContent(ui, vm, onNavigate)
            DeviceState.PREPARING -> Unit
        }

        if (ui.error != null && (!ui.recoveryRequired || (ui.error != ui.recoveryMessage && ui.error != ui.recoveryDetail))) {
            val isLockError = ui.error?.contains("lock", ignoreCase = true) == true ||
                ui.error?.contains("lockdir", ignoreCase = true) == true ||
                ui.steps.any { it.text.contains("lockdir", ignoreCase = true) }

            InfoCard(stringResource(R.string.error_title)) {
                Text(
                    text = ui.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (isLockError) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.unlock() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                    ) {
                        Text(stringResource(R.string.home_btn_release_lock))
                    }
                }
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
    }

    // ------------------------------------------------------------ Dialogs
    if (ui.modeDialogVisible) {
        AlertDialog(
            shape = RoundedCornerShape(0.dp),
            onDismissRequest = { vm.dismissModeDialog() },
            title = { Text(stringResource(R.string.home_mode_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { vm.selectMode(OperationMode.PURE) }) {
                        Column {
                            Text(stringResource(R.string.home_mode_pure_title), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.home_mode_pure_desc))
                        }
                    }
                    TextButton(onClick = { vm.selectMode(OperationMode.MICROG) }) {
                        Column {
                            Text(stringResource(R.string.home_mode_microg_title), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.home_mode_microg_desc))
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (ui.needsSetupPrompt) {
        AlertDialog(
            shape = RoundedCornerShape(0.dp),
            onDismissRequest = { vm.dismissSetupPrompt() },
            title = { Text(stringResource(R.string.home_dialog_setup_title)) },
            text = {
                Text(stringResource(R.string.home_dialog_setup_text))
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(0.dp),
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

@Composable
private fun CheckProgressList(checks: List<DeviceCheck>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            checks.forEach { check ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(check.labelResource()), style = MaterialTheme.typography.bodySmall)
                    val (statusText, color) = when (check.status) {
                        CheckStatus.PENDING -> R.string.check_pending to MaterialTheme.colorScheme.onSurfaceVariant
                        CheckStatus.CONFIRMED -> R.string.check_confirmed to MaterialTheme.colorScheme.primary
                        CheckStatus.FAILED -> R.string.check_failed to MaterialTheme.colorScheme.error
                    }
                    Text(
                        stringResource(statusText),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                    )
                }
            }
        }
    }
}

private fun DeviceCheck.labelResource(): Int = when (id) {
    "root" -> R.string.check_root
    "device_properties" -> R.string.check_device_properties
    "transaction_snapshot" -> R.string.check_transaction_snapshot
    "package_locations" -> R.string.check_package_locations
    "mounts" -> R.string.check_mounts
    "gms_details" -> R.string.check_gms_details
    "transaction_masks" -> R.string.check_transaction_masks
    "mount_namespace" -> R.string.check_mount_namespace
    "bind_mount" -> R.string.check_bind_mount
    "selinux_mode" -> R.string.check_selinux_mode
    "selinux_context" -> R.string.check_selinux_context
    "signature_spoofing" -> R.string.check_signature_spoofing
    "post_boot_validation" -> R.string.check_post_boot_validation
    "transaction_reconciliation" -> R.string.check_transaction_reconciliation
    else -> R.string.check_compatibility
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
        StatusRow(stringResource(R.string.diag_manufacturer), f.manufacturer, Status.UNKNOWN, showStatus = false)
        StatusRow(stringResource(R.string.diag_model), f.model, Status.UNKNOWN, showStatus = false)
        StatusRow(stringResource(R.string.diag_device), f.device, Status.UNKNOWN, showStatus = false)
        StatusRow(stringResource(R.string.diag_android), "${f.androidRelease} (SDK ${f.androidSdk})", Status.UNKNOWN, showStatus = false)
        TechnicalStatusRow(stringResource(R.string.diag_fingerprint), f.fingerprint.ifBlank { stringResource(R.string.not_reported) }, Status.UNKNOWN, showStatus = false)
        TechnicalStatusRow(stringResource(R.string.diag_real_gms), f.gmsPath ?: stringResource(R.string.absent), Status.UNKNOWN, showStatus = false)
        TechnicalStatusRow(stringResource(R.string.diag_real_gsf), f.gsfPath ?: stringResource(R.string.absent), Status.UNKNOWN, showStatus = false)
        TechnicalStatusRow(stringResource(R.string.diag_real_store), f.storePath ?: stringResource(R.string.absent), Status.UNKNOWN, showStatus = false)
        StatusRow(
            stringResource(R.string.diag_compatibility),
            when (ui.compatibility.compatibility) {
                dev.degoogle.app.domain.DeviceCompatibility.SUPPORTED -> stringResource(R.string.compat_supported)
                dev.degoogle.app.domain.DeviceCompatibility.PROBABLY_SUPPORTED -> stringResource(R.string.compat_probably_supported)
                dev.degoogle.app.domain.DeviceCompatibility.REQUIRES_PROFILE -> stringResource(R.string.compat_requires_profile)
                dev.degoogle.app.domain.DeviceCompatibility.UNSAFE -> stringResource(R.string.compat_unsafe)
                dev.degoogle.app.domain.DeviceCompatibility.UNSUPPORTED -> stringResource(R.string.compat_unsupported)
            },
            if (ui.compatibility.compatibility.name == "PROBABLY_SUPPORTED") Status.UNKNOWN else Status.FAIL,
        )
        ui.compatibility.reasons.forEach { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
    val gmsHasUpdate = f.gmsPackage?.hasDataUpdate == true || f.gmsPath?.startsWith("/data/app/") == true
    val storeHasUpdate = f.storePackage?.hasDataUpdate == true || f.storePath?.startsWith("/data/app/") == true

    InfoCard(
        title = stringResource(R.string.home_system_environment),
        action = {
            StateBadge(
                text = stringResource(R.string.state_stock),
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
                showStatus = false,
            )
            StatusRow(
                stringResource(R.string.diag_play_services),
                f.gmsVersion ?: f.gmsPath ?: stringResource(R.string.not_reported),
                if (f.gmsPath != null) Status.OK else Status.FAIL,
                showStatus = false,
            )
            StatusRow(
                stringResource(R.string.diag_framework_gsf),
                if (!f.gsfPath.isNullOrEmpty()) stringResource(R.string.diag_stock_active) else stringResource(R.string.absent),
                if (!f.gsfPath.isNullOrEmpty()) Status.OK else Status.FAIL,
                showStatus = false,
            )
            StatusRow(
                stringResource(R.string.diag_backup_microg),
                if (f.backupPresent) stringResource(R.string.available) else stringResource(R.string.not_created),
                if (f.backupPresent) Status.OK else Status.ABSENT,
                showStatus = false,
            )
        }
    }

    if (gmsHasUpdate || storeHasUpdate) {
        InfoCard(stringResource(R.string.home_updates_detected_title)) {
            Text(
                text = stringResource(R.string.home_updates_detected_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            val updateWarning = listOf(
                dev.degoogle.app.domain.Capability.GMS_MASKABLE to ui.facts.gmsPackage?.hasDataUpdate,
                dev.degoogle.app.domain.Capability.STORE_MASKABLE to ui.facts.storePackage?.hasDataUpdate,
            ).any { (capability, update) ->
                update == true && ui.compatibility.matrix[capability].status == dev.degoogle.app.domain.CapabilityStatus.WARN
            }
            val onlyUpdateWarnings = ui.compatibility.matrix.results
                .filter { it.key in dev.degoogle.app.domain.CapabilityMatrix.CRITICAL_CAPABILITIES }
                .all { (capability, result) ->
                    result.status == dev.degoogle.app.domain.CapabilityStatus.PASS ||
                        (result.status == dev.degoogle.app.domain.CapabilityStatus.WARN &&
                            capability in setOf(
                                dev.degoogle.app.domain.Capability.GMS_MASKABLE,
                                dev.degoogle.app.domain.Capability.STORE_MASKABLE,
                            ))
                }
            if (updateWarning && onlyUpdateWarnings) {
                Button(
                    onClick = { vm.removeGmsUpdate() },
                    enabled = !ui.operationInProgress,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Text(stringResource(R.string.home_clean_updates_reprobe))
                }
            }
        }
    }

    if (!ui.fallbackRebootOptIn && ui.facts.rebootStrategy?.method in setOf("KSUD_SOFT_REBOOT", "ZYGOTE_RESTART")) {
        InfoCard(stringResource(R.string.home_fallback_reboot_title)) {
            Text(
                text = stringResource(R.string.home_fallback_reboot_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = vm::allowFallbackReboot,
                enabled = !ui.operationInProgress,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(0.dp),
            ) {
                Text(stringResource(R.string.home_fallback_reboot_allow))
            }
        }
    }

    if (ui.selectedMode == null) {
        Button(
            onClick = { vm.openModeDialog() },
            enabled = !ui.operationInProgress && !ui.recoveryRequired,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(0.dp),
        ) {
            Text(stringResource(R.string.home_choose_mode), fontWeight = FontWeight.Bold)
        }
    } else {
        InfoCard(stringResource(R.string.home_selected_mode)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (ui.selectedMode == OperationMode.PURE) R.string.home_mode_pure_title
                        else R.string.home_mode_microg_title,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = { vm.openModeDialog() },
                    enabled = !ui.operationInProgress && !ui.recoveryRequired,
                ) { Text(stringResource(R.string.home_change_mode)) }
            }
        }
        SlideToExecute(
            label = stringResource(
                if (ui.selectedMode == OperationMode.PURE) R.string.home_slide_degoogle
                else R.string.home_slide_microg,
            ),
            enabled = !ui.operationInProgress && !ui.recoveryRequired,
            onExecute = onDeGoogle,
        )
    }
}

@Composable
private fun PurePreparedContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = stringResource(R.string.home_pure_prepared_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_reboot_required),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.home_pure_prepared_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Button(
        onClick = { vm.softReboot() },
        enabled = !ui.operationInProgress,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(0.dp),
    ) { Text(stringResource(R.string.home_soft_reboot_btn), fontWeight = FontWeight.Bold) }
    SlideToExecute(
        label = stringResource(R.string.slide_restore_stock),
        enabled = !ui.operationInProgress,
        onExecute = { vm.restoreGoogle() },
    )
}

@Composable
private fun PureActiveContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = stringResource(R.string.home_pure_active_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_active),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StatusRow(stringResource(R.string.diag_play_services), stringResource(R.string.absent), Status.OK, showStatus = false)
            StatusRow(stringResource(R.string.diag_framework_gsf), stringResource(R.string.absent), Status.OK, showStatus = false)
            StatusRow(stringResource(R.string.diag_store_path), stringResource(R.string.absent), Status.OK, showStatus = false)
            StatusRow(stringResource(R.string.home_pure_masks), stringResource(R.string.ok), Status.OK, showStatus = false)
        }
    }
    SlideToExecute(
        label = stringResource(R.string.slide_restore_stock),
        enabled = !ui.operationInProgress && !ui.recoveryRequired,
        onExecute = { vm.restoreGoogle() },
    )
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
        shape = RoundedCornerShape(0.dp),
    ) {
        Text(stringResource(R.string.home_soft_reboot_btn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }

    SlideToExecute(
        label = stringResource(R.string.slide_restore_stock),
        onExecute = { vm.restoreGoogle() },
    )
}

@Composable
private fun IsolationStages(ui: UiState) {
    val pureMode = ui.facts.operationMode == OperationMode.PURE && !ui.facts.stockRestoreConfirmed
    val stockRemoved = if (pureMode) {
        ui.state == DeviceState.DEGOOGLE_ACTIVE
    } else {
        ui.state !in setOf(DeviceState.STOCK, DeviceState.UNSUPPORTED, DeviceState.NO_ROOT)
    }
    val namespaceActive = ui.state in setOf(
        DeviceState.PREPARED,
        DeviceState.DEGOOGLE_PREPARED,
        DeviceState.DEGOOGLE_ACTIVE,
        DeviceState.MICROG_BOOTED,
        DeviceState.MICROG_NEEDS_SETUP,
        DeviceState.MICROG_ACTIVE,
        DeviceState.MICROG_ACTIVE_BACKED_UP,
        DeviceState.RESTORE_PREPARED,
    )
    val identityVerified = ui.state in setOf(DeviceState.MICROG_ACTIVE, DeviceState.MICROG_ACTIVE_BACKED_UP)
    InfoCard(stringResource(R.string.home_namespace_integrity)) {
        StageLine(stringResource(R.string.home_stage_stock_google), stringResource(if (stockRemoved) R.string.home_stage_removed else R.string.home_stage_present))
        StageLine(stringResource(R.string.home_stage_namespace_masking), stringResource(if (namespaceActive) R.string.home_stage_active else R.string.home_stage_pending))
        if (pureMode) {
            StageLine(stringResource(R.string.home_stage_pure_validation), stringResource(if (ui.state == DeviceState.DEGOOGLE_ACTIVE) R.string.home_stage_verified else R.string.home_stage_pending))
        } else {
            StageLine(stringResource(R.string.home_stage_microg_identity), stringResource(if (identityVerified) R.string.home_stage_verified else R.string.home_stage_pending))
            StageLine(stringResource(R.string.home_stage_fcm_archive), stringResource(if (ui.facts.backupPresent) R.string.home_stage_sealed else R.string.home_stage_pending))
        }
    }
}

@Composable
private fun StageLine(label: String, state: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BootedContent(ui: UiState, vm: AppViewModel) {
    InfoCard(
        title = stringResource(R.string.home_booted_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_post_reboot),
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
        shape = RoundedCornerShape(0.dp),
    ) {
        Text(stringResource(R.string.home_complete_setup_btn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActiveContent(
    ui: UiState,
    onRestoreGoogle: () -> Unit,
) {
    val f = ui.facts

    InfoCard(
        title = stringResource(R.string.home_microg_active_title),
        action = {
            Text(
                text = stringResource(R.string.home_badge_active),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StatusRow(
                stringResource(R.string.diag_privileged),
                if (f.gmsPrivileged) stringResource(R.string.diag_registered_ok) else stringResource(R.string.no),
                if (f.gmsPrivileged) Status.OK else Status.FAIL,
                showStatus = false,
            )
            StatusRow(
                stringResource(R.string.diag_framework_gsf),
                stringResource(R.string.diag_stock_removed_masked),
                Status.OK,
                showStatus = false,
            )
            StatusRow(
                stringResource(R.string.diag_companion),
                if (!f.storePath.isNullOrEmpty()) stringResource(R.string.active) else stringResource(R.string.absent),
                if (!f.storePath.isNullOrEmpty()) Status.OK else Status.FAIL,
                showStatus = false,
            )
            StatusRow(
                stringResource(R.string.diag_backup_fcm),
                if (f.backupPresent) stringResource(R.string.available) else stringResource(R.string.not_created),
                if (f.backupPresent) Status.OK else Status.ABSENT,
                showStatus = false,
            )
            if (f.gmsVersion != null) {
                StatusRow(
                    stringResource(R.string.diag_version_microg),
                    f.gmsVersion,
                    Status.UNKNOWN,
                    showStatus = false,
                )
            }
            // libmapbox-gl.so entregue pela máscara do GMS; sem ela o Mapbox
            // do WhatsApp falha ao carregar. Ver commit 20755ae.
            StatusRow(
                stringResource(R.string.diag_mapbox_lib),
                if (f.gmsMapsLibPresent) stringResource(R.string.ok) else stringResource(R.string.absent),
                if (f.gmsMapsLibPresent) Status.OK else Status.ABSENT,
                showStatus = false,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.home_emergency_restore),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SlideToExecute(
            label = stringResource(R.string.slide_restore_stock),
            enabled = !ui.operationInProgress && !ui.recoveryRequired,
            onExecute = onRestoreGoogle,
        )
    }
}

internal enum class RestorePreparedAction {
    RESTORE_STOCK,
    SOFT_REBOOT,
    REINDEX_STOCK,
}

internal fun resolveRestorePreparedAction(facts: SystemFacts): RestorePreparedAction = when {
    facts.stockReindexPending && facts.stockReindexReady -> RestorePreparedAction.REINDEX_STOCK
    facts.stockReindexPending -> RestorePreparedAction.SOFT_REBOOT
    else -> RestorePreparedAction.RESTORE_STOCK
}

@Composable
private fun RestorePreparedContent(ui: UiState, vm: AppViewModel) {
    val action = resolveRestorePreparedAction(ui.facts)
    InfoCard(
        title = stringResource(
            when (action) {
                RestorePreparedAction.RESTORE_STOCK -> R.string.home_restore_needed_title
                RestorePreparedAction.SOFT_REBOOT,
                RestorePreparedAction.REINDEX_STOCK -> R.string.home_restore_prepared_title
            },
        ),
    ) {
        Text(
            text = stringResource(
                when (action) {
                    RestorePreparedAction.RESTORE_STOCK -> R.string.home_restore_needed_desc
                    RestorePreparedAction.SOFT_REBOOT -> R.string.home_restore_prepared_desc
                    RestorePreparedAction.REINDEX_STOCK -> R.string.home_reindex_stock_desc
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    when (action) {
        RestorePreparedAction.RESTORE_STOCK -> if (!ui.recoveryRequired) {
            SlideToExecute(
                label = stringResource(R.string.slide_restore_stock),
                enabled = !ui.operationInProgress,
                onExecute = { vm.restoreGoogle() },
            )
        }
        RestorePreparedAction.SOFT_REBOOT,
        RestorePreparedAction.REINDEX_STOCK -> Button(
            onClick = {
                if (action == RestorePreparedAction.REINDEX_STOCK) vm.reindexStock()
                else vm.softReboot()
            },
            enabled = !ui.operationInProgress && !ui.recoveryRequired,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(0.dp),
        ) {
            Text(
                stringResource(
                    if (action == RestorePreparedAction.REINDEX_STOCK) R.string.home_reindex_stock_btn
                    else R.string.home_soft_reboot_btn,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ErrorContent(ui: UiState, vm: AppViewModel, onNavigate: (Screen) -> Unit) {
    InfoCard(
        title = stringResource(R.string.home_error_state_title),
        action = {
            StateBadge(
                text = stringResource(R.string.home_badge_warning),
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
        TechnicalStatusRow(stringResource(R.string.diag_real_gms), f.gmsPath ?: stringResource(R.string.absent), Status.UNKNOWN)
        TechnicalStatusRow(stringResource(R.string.diag_real_gsf), f.gsfPath ?: stringResource(R.string.absent), Status.UNKNOWN)
        TechnicalStatusRow(stringResource(R.string.diag_real_store), f.storePath ?: stringResource(R.string.absent), Status.UNKNOWN)
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
            shape = RoundedCornerShape(0.dp),
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
