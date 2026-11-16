package dev.degoogle.app.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.degoogle.app.R
import dev.degoogle.app.domain.DeviceProfiles
import dev.degoogle.app.domain.OperationMode
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusRow
import dev.degoogle.app.ui.components.TechnicalStatusRow

@Composable
fun DiagnosticsScreen(ui: UiState) {
    val context = LocalContext.current
    val f = ui.facts
    val pureMode = f.operationMode == OperationMode.PURE && !f.stockRestoreConfirmed
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                text = "02 SYSFACTS DUMP",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        InfoCard(title = stringResource(R.string.diag_card_system)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_root_access),
                    value = if (f.rootOk) f.rootManager.ifBlank { stringResource(R.string.yes) } else stringResource(R.string.no),
                    status = if (f.rootOk) Status.OK else Status.FAIL,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_operation_mode),
                    value = f.operationMode?.name ?: stringResource(R.string.diag_mode_not_recorded),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_device),
                    value = "${f.manufacturer} ${f.model}",
                    status = Status.OK,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_android),
                    value = "${f.androidRelease.ifBlank { "?" }} (SDK ${f.androidSdk})",
                    status = Status.OK,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_selinux),
                    value = f.selinux,
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_abi),
                    value = f.abi,
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_fingerprint),
                    value = f.fingerprint.ifBlank { stringResource(R.string.not_reported) },
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
            }
        }

        if (pureMode) {
            InfoCard(stringResource(R.string.diag_card_pure_mode)) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    StatusRow(
                        label = stringResource(R.string.diag_empty_masks),
                        value = if (f.pureMasksValid) stringResource(R.string.diag_capability_pass) else stringResource(R.string.diag_capability_fail),
                        status = if (f.pureMasksValid) Status.OK else Status.FAIL,
                        showStatus = true,
                    )
                    StatusRow(
                        label = stringResource(R.string.diag_postboot_validation),
                        value = f.postBootStatus ?: stringResource(R.string.pending),
                        status = if (f.postBootValidated) Status.OK else Status.UNKNOWN,
                        showStatus = !f.postBootValidated,
                    )
                }
            }
        }

        InfoCard(stringResource(R.string.diag_card_gms)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_path),
                    value = f.gmsPath ?: stringResource(R.string.absent),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_version),
                    value = f.gmsVersion ?: stringResource(R.string.not_reported),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_uid),
                    value = f.gmsUid ?: stringResource(R.string.not_reported),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                if (pureMode) {
                    StatusRow(
                        label = stringResource(R.string.diag_microg_checks),
                        value = stringResource(R.string.not_applicable),
                        status = Status.UNKNOWN,
                        showStatus = false,
                    )
                } else {
                    StatusRow(
                        label = stringResource(R.string.diag_privileged),
                        value = if (f.gmsPrivileged) stringResource(R.string.yes) else stringResource(R.string.no),
                        status = if (f.gmsPrivileged) Status.OK else Status.FAIL,
                        showStatus = false,
                    )
                }

                val flagsList = f.gmsFlags?.split(" ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (!pureMode && flagsList.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = stringResource(R.string.diag_package_flags),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            flagsList.forEach { flag ->
                                Text(
                                    text = flag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (!pureMode) {
                    StatusRow(
                        label = stringResource(R.string.diag_package_flags),
                        value = stringResource(R.string.not_reported),
                        status = Status.UNKNOWN,
                        showStatus = false,
                    )
                }
            }
        }

        InfoCard(stringResource(R.string.diag_card_framework)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                val gsfStatus = if (f.mountGsf || f.gsfPath != null) Status.OK else Status.FAIL
                StatusRow(
                    label = stringResource(R.string.diag_gsf_present),
                    value = if (f.mountGsf) {
                        stringResource(R.string.diag_mount_masked_empty)
                    } else if (f.gsfPath != null) {
                        stringResource(R.string.yes)
                    } else {
                        stringResource(R.string.no)
                    },
                    status = gsfStatus,
                    showStatus = gsfStatus != Status.OK,
                )
                if (f.gsfPath != null) {
                    TechnicalStatusRow(
                        label = stringResource(R.string.diag_gsf_path),
                        value = f.gsfPath,
                        status = Status.UNKNOWN,
                        showStatus = false,
                    )
                }
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_store_path),
                    value = f.storePath ?: stringResource(R.string.absent),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                if (f.storeVersion != null) {
                    StatusRow(
                        stringResource(R.string.diag_store_version),
                        f.storeVersion,
                        Status.UNKNOWN,
                        showStatus = false,
                    )
                }
            }
        }

        InfoCard(stringResource(R.string.diag_card_mounts)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_mount_gms),
                    value = if (!f.mountGms) stringResource(R.string.diag_mount_visible_stock) else if (f.operationMode == OperationMode.PURE) stringResource(R.string.diag_mount_masked_empty) else stringResource(R.string.diag_mount_masked_microg),
                    status = if (f.mountGms) Status.OK else Status.ABSENT,
                    showStatus = !f.mountGms,
                )
                StatusRow(
                    label = stringResource(R.string.diag_mount_gsf),
                    value = if (f.mountGsf) stringResource(R.string.diag_mount_masked_empty) else stringResource(R.string.diag_mount_visible_stock),
                    status = if (f.mountGsf) Status.OK else Status.ABSENT,
                    showStatus = !f.mountGsf,
                )
                StatusRow(
                    label = stringResource(R.string.diag_mount_store),
                    value = if (!f.mountStore) stringResource(R.string.diag_mount_visible_stock) else if (f.operationMode == OperationMode.PURE) stringResource(R.string.diag_mount_masked_empty) else stringResource(R.string.diag_mount_masked_companion),
                    status = if (f.mountStore) Status.OK else Status.ABSENT,
                    showStatus = !f.mountStore,
                )
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_mount_gms_source),
                    value = f.mountGmsSource ?: stringResource(R.string.not_reported),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                if (f.operationMode == OperationMode.PURE) {
                    StatusRow(stringResource(R.string.diag_empty_masks), "GMS=${f.maskGmsEmpty} GSF=${f.maskGsfEmpty} Store=${f.maskStoreEmpty}; valid=${f.pureMasksValid}", Status.UNKNOWN, showStatus = false)
                    StatusRow(stringResource(R.string.diag_postboot_validation), f.postBootStatus ?: stringResource(R.string.pending), Status.UNKNOWN, showStatus = false)
                }
            }
        }

        InfoCard(stringResource(R.string.diag_card_backup)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                if (pureMode) {
                    Text(
                        text = stringResource(R.string.backup_pure_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusRow(
                    label = stringResource(R.string.diag_backup_present),
                    value = if (f.backupPresent) stringResource(R.string.yes) else stringResource(R.string.no),
                    status = if (f.backupPresent) Status.OK else Status.ABSENT,
                    showStatus = false,
                )
                StatusRow(
                    label = stringResource(R.string.diag_export_format),
                    value = stringResource(R.string.backup_structure_value),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_local_dir),
                    value = stringResource(R.string.backup_storage_path_value),
                    status = Status.UNKNOWN,
                    showStatus = false,
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Button(
                onClick = {
                    copyDiagnostics(context, ui)
                    copied = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(0.dp),
            ) {
                Text(
                    if (copied) stringResource(R.string.diag_report_copied) else stringResource(R.string.diag_copy_full_report),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Surface(
                shape = RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.diag_security_notice),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun copyDiagnostics(context: Context, ui: UiState) {
    val f = ui.facts
    val sb = StringBuilder()
    sb.appendLine("${context.getString(R.string.app_name)} ${context.getString(R.string.diag_title)}")
    sb.appendLine("${context.getString(R.string.diag_state)}: ${context.getString(ui.state.labelRes)}")
    sb.appendLine("${context.getString(R.string.diag_operation_mode)}: ${f.operationMode?.name ?: context.getString(R.string.diag_mode_not_recorded)}")
    sb.appendLine("${context.getString(R.string.diag_empty_masks)}: ${f.pureMasksValid}")
    sb.appendLine("${context.getString(R.string.diag_postboot_validation)}: ${f.postBootStatus ?: context.getString(R.string.pending)}")
    sb.appendLine("${context.getString(R.string.diag_root_access)}: ${if (f.rootOk) f.rootManager else context.getString(R.string.no)}")
    val localProfile = DeviceProfiles.matching(f.manufacturer, f.model, f.androidSdk)
    val profileId = f.profileId.takeIf { f.profileMatch && it.isNotBlank() } ?: localProfile?.id
    sb.appendLine("${context.getString(R.string.diag_profile)}: ${profileId ?: context.getString(R.string.not_supported)}")
    sb.appendLine("${context.getString(R.string.diag_device)}: ${f.manufacturer} ${f.model} (${f.device}/${f.product})")
    sb.appendLine("${context.getString(R.string.diag_android)}: ${f.androidRelease} (SDK ${f.androidSdk})")
    sb.appendLine("${context.getString(R.string.diag_fingerprint)}: ${f.fingerprint}")
    sb.appendLine("${context.getString(R.string.diag_selinux)}: ${f.selinux}")
    sb.appendLine("GMS: ${f.gmsPath ?: context.getString(R.string.absent)} v${f.gmsVersion ?: "?"} uid=${f.gmsUid ?: "?"} privileged=${f.gmsPrivileged}")
    sb.appendLine("GSF: ${f.gsfPath ?: context.getString(R.string.absent)}")
    sb.appendLine("${context.getString(R.string.diag_store_path)}: ${f.storePath ?: context.getString(R.string.absent)} v${f.storeVersion ?: "?"}")
    sb.appendLine("Mounts: GMS=${f.mountGms} GSF=${f.mountGsf} Store=${f.mountStore} ${context.getString(R.string.diag_mount_gms_source)}=${f.mountGmsSource ?: "—"}")
    sb.appendLine("${context.getString(R.string.diag_backup_present)}: ${f.backupPresent} ${context.getString(R.string.diag_export_format)}=MicroG Session ${context.getString(R.string.diag_local_dir)}=/data/local/tmp/microg-backup")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DeGoogle Diagnostics", sb.toString()))
}
