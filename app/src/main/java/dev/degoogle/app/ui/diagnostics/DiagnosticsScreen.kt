package dev.degoogle.app.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.StateBadge
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusRow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(ui: UiState) {
    val context = LocalContext.current
    val f = ui.facts
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.diag_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.diag_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard(
            title = stringResource(R.string.diag_card_system),
            action = {
                StateBadge(
                    text = stringResource(ui.state.labelRes),
                    icon = Icons.Rounded.Verified,
                )
            },
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_root_access),
                    value = if (f.rootOk) f.rootManager.ifBlank { stringResource(R.string.ok) } else stringResource(R.string.no),
                    status = if (f.rootOk) Status.OK else Status.FAIL,
                    leadingIcon = Icons.Rounded.Shield,
                )
                StatusRow(
                    label = stringResource(R.string.diag_profile),
                    value = if (f.profileMatch) f.profileId else stringResource(R.string.not_supported),
                    status = if (f.profileMatch) Status.OK else Status.FAIL,
                    leadingIcon = Icons.Rounded.Verified,
                )
                StatusRow(
                    label = stringResource(R.string.diag_device),
                    value = "${f.manufacturer} ${f.model}",
                    status = Status.OK,
                    leadingIcon = Icons.Rounded.Fingerprint,
                )
                StatusRow(
                    label = stringResource(R.string.diag_android),
                    value = "${f.androidRelease.ifBlank { "?" }} (SDK ${f.androidSdk})",
                    status = Status.OK,
                    leadingIcon = Icons.Rounded.Android,
                )
                StatusRow(
                    label = stringResource(R.string.diag_selinux),
                    value = f.selinux,
                    status = Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.Security,
                )
                StatusRow(
                    label = stringResource(R.string.diag_abi),
                    value = f.abi,
                    status = Status.UNKNOWN,
                )
                StatusRow(
                    label = stringResource(R.string.diag_fingerprint),
                    value = f.fingerprint.ifBlank { stringResource(R.string.not_reported) },
                    status = Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.Tag,
                )
            }
        }

        InfoCard(stringResource(R.string.diag_card_gms)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_path),
                    value = f.gmsPath ?: stringResource(R.string.absent),
                    status = Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.Folder,
                )
                StatusRow(
                    label = stringResource(R.string.diag_version),
                    value = f.gmsVersion ?: stringResource(R.string.not_reported),
                    status = Status.UNKNOWN,
                )
                StatusRow(
                    label = stringResource(R.string.diag_uid),
                    value = f.gmsUid ?: stringResource(R.string.not_reported),
                    status = Status.UNKNOWN,
                )
                StatusRow(
                    label = stringResource(R.string.diag_privileged),
                    value = if (f.gmsPrivileged) stringResource(R.string.yes) else stringResource(R.string.no),
                    status = if (f.gmsPrivileged) Status.OK else Status.FAIL,
                )

                val flagsList = f.gmsFlags?.split(" ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (flagsList.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = stringResource(R.string.diag_package_flags),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            flagsList.forEach { flag ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = flag,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    border = null,
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }
                    }
                } else {
                    StatusRow(
                        label = stringResource(R.string.diag_package_flags),
                        value = stringResource(R.string.not_reported),
                        status = Status.UNKNOWN,
                    )
                }
            }
        }

        InfoCard(stringResource(R.string.diag_card_framework)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_gsf_present),
                    value = if (f.gsfPath != null) stringResource(R.string.yes) else stringResource(R.string.no),
                    status = if (f.gsfPath == null) Status.OK else Status.FAIL,
                    leadingIcon = Icons.Rounded.Layers,
                )
                if (f.gsfPath != null) StatusRow(stringResource(R.string.diag_gsf_path), f.gsfPath, Status.UNKNOWN)
                StatusRow(
                    label = stringResource(R.string.diag_store_path),
                    value = f.storePath ?: stringResource(R.string.absent),
                    status = Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.Store,
                )
                if (f.storeVersion != null) StatusRow(stringResource(R.string.diag_store_version), f.storeVersion, Status.UNKNOWN)
            }
        }

        InfoCard(stringResource(R.string.diag_card_mounts)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_mount_gms),
                    value = if (f.mountGms) stringResource(R.string.diag_mount_masked_microg) else stringResource(R.string.diag_mount_visible_stock),
                    status = if (f.mountGms) Status.OK else Status.ABSENT,
                    leadingIcon = if (f.mountGms) Icons.Rounded.Folder else Icons.Rounded.FolderOff,
                )
                StatusRow(
                    label = stringResource(R.string.diag_mount_gsf),
                    value = if (f.mountGsf) stringResource(R.string.diag_mount_masked_empty) else stringResource(R.string.diag_mount_visible_stock),
                    status = if (f.mountGsf) Status.OK else Status.ABSENT,
                )
                StatusRow(
                    label = stringResource(R.string.diag_mount_store),
                    value = if (f.mountStore) stringResource(R.string.diag_mount_masked_companion) else stringResource(R.string.diag_mount_visible_stock),
                    status = if (f.mountStore) Status.OK else Status.ABSENT,
                )
                StatusRow(
                    label = stringResource(R.string.diag_mount_gms_source),
                    value = f.mountGmsSource ?: stringResource(R.string.not_reported),
                    status = Status.UNKNOWN,
                )
            }
        }

        InfoCard(stringResource(R.string.diag_card_backup)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_backup_present),
                    value = if (f.backupPresent) stringResource(R.string.yes) else stringResource(R.string.no),
                    status = if (f.backupPresent) Status.OK else Status.ABSENT,
                    leadingIcon = Icons.Rounded.Backup,
                )
                StatusRow(
                    label = stringResource(R.string.diag_export_format),
                    value = stringResource(R.string.backup_structure_value),
                    status = Status.UNKNOWN,
                )
                StatusRow(
                    label = stringResource(R.string.diag_local_dir),
                    value = stringResource(R.string.backup_storage_path_value),
                    status = Status.UNKNOWN,
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
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(
                    if (copied) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (copied) stringResource(R.string.diag_report_copied) else stringResource(R.string.diag_copy_full_report),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.diag_security_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun copyDiagnostics(context: Context, ui: UiState) {
    val f = ui.facts
    val sb = StringBuilder()
    sb.appendLine("DeGoogle Diagnóstico")
    sb.appendLine("Estado derivado: ${ui.state.name}")
    sb.appendLine("Root: ${if (f.rootOk) f.rootManager else "não"}")
    sb.appendLine("Perfil: ${if (f.profileMatch) f.profileId else "não suportado"}")
    sb.appendLine("Dispositivo: ${f.manufacturer} ${f.model} (${f.device}/${f.product})")
    sb.appendLine("Android: ${f.androidRelease} (SDK ${f.androidSdk})")
    sb.appendLine("Fingerprint: ${f.fingerprint}")
    sb.appendLine("SELinux: ${f.selinux}")
    sb.appendLine("GMS: ${f.gmsPath ?: "ausente"} v${f.gmsVersion ?: "?"} uid=${f.gmsUid ?: "?"} privileged=${f.gmsPrivileged}")
    sb.appendLine("GSF: ${f.gsfPath ?: "ausente"}")
    sb.appendLine("Store: ${f.storePath ?: "ausente"} v${f.storeVersion ?: "?"}")
    sb.appendLine("Mounts: GMS=${f.mountGms} GSF=${f.mountGsf} Store=${f.mountStore} fonte=${f.mountGmsSource ?: "—"}")
    sb.appendLine("Backup: presente=${f.backupPresent} formato=MicroG Session local=/data/local/tmp/microg-backup")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DeGoogle diagnóstico", sb.toString()))
}
