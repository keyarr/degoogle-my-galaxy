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
import dev.degoogle.app.domain.CapabilityStatus
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.StateBadge
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusRow
import dev.degoogle.app.ui.components.TechnicalStatusRow

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
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_fingerprint),
                    value = f.fingerprint.ifBlank { stringResource(R.string.not_reported) },
                    status = Status.UNKNOWN,
                )
            }
        }

        val isPortuguese = context.resources.configuration.locales[0].language.startsWith("pt")

        InfoCard(stringResource(R.string.diag_card_compatibility)) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow(
                    label = stringResource(R.string.diag_compatibility),
                    value = when (ui.compatibility.compatibility) {
                        dev.degoogle.app.domain.DeviceCompatibility.SUPPORTED -> stringResource(R.string.compat_supported)
                        dev.degoogle.app.domain.DeviceCompatibility.PROBABLY_SUPPORTED -> stringResource(R.string.compat_probably_supported)
                        dev.degoogle.app.domain.DeviceCompatibility.REQUIRES_PROFILE -> stringResource(R.string.compat_requires_profile)
                        dev.degoogle.app.domain.DeviceCompatibility.UNSAFE -> stringResource(R.string.compat_unsafe)
                        dev.degoogle.app.domain.DeviceCompatibility.UNSUPPORTED -> stringResource(R.string.compat_unsupported)
                    },
                    status = when (ui.compatibility.compatibility.name) {
                        "SUPPORTED" -> Status.OK
                        "PROBABLY_SUPPORTED" -> Status.UNKNOWN
                        else -> Status.FAIL
                    },
                    leadingIcon = Icons.Rounded.Shield,
                )
                StatusRow(
                    label = stringResource(R.string.diag_known_good_match),
                    value = when (ui.compatibility.knownGood.level) {
                        dev.degoogle.app.domain.KnownGoodMatch.EXACT_MATCH -> stringResource(R.string.match_exact)
                        dev.degoogle.app.domain.KnownGoodMatch.FIRMWARE_FAMILY_MATCH -> stringResource(R.string.match_family)
                        dev.degoogle.app.domain.KnownGoodMatch.MODEL_ONLY_MATCH -> stringResource(R.string.match_model_only)
                        dev.degoogle.app.domain.KnownGoodMatch.NO_MATCH -> stringResource(R.string.match_none)
                    },
                    status = if (ui.compatibility.knownGood.level.name == "EXACT_MATCH") Status.OK else Status.UNKNOWN,
                    leadingIcon = Icons.Rounded.Verified,
                )
                if (f.preparationInfo.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.diag_preparation_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.diag_capabilities),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                ui.compatibility.matrix.results.forEach { (capability, result) ->
                    val status = when (result.status) {
                        CapabilityStatus.PASS -> Status.OK
                        CapabilityStatus.WARN -> Status.UNKNOWN
                        CapabilityStatus.FAIL -> Status.FAIL
                        CapabilityStatus.UNKNOWN -> Status.UNKNOWN
                    }
                    val rawDetail = result.evidence.ifBlank { result.reason }.ifBlank {
                        when (result.status) {
                            CapabilityStatus.PASS -> stringResource(R.string.diag_capability_pass)
                            CapabilityStatus.WARN -> stringResource(R.string.diag_capability_warn)
                            CapabilityStatus.FAIL -> stringResource(R.string.diag_capability_fail)
                            CapabilityStatus.UNKNOWN -> stringResource(R.string.diag_capability_unknown)
                        }
                    }
                    val detail = localizeDetail(rawDetail, isPortuguese)
                    TechnicalStatusRow(
                        label = capability.name,
                        value = detail,
                        status = status,
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
                    value = if (f.mountGsf) {
                        stringResource(R.string.diag_mount_masked_empty)
                    } else if (f.gsfPath != null) {
                        stringResource(R.string.yes)
                    } else {
                        stringResource(R.string.no)
                    },
                    status = if (f.mountGsf || f.gsfPath != null) Status.OK else Status.FAIL,
                    leadingIcon = Icons.Rounded.Layers,
                )
                if (f.gsfPath != null) {
                    TechnicalStatusRow(
                        label = stringResource(R.string.diag_gsf_path),
                        value = f.gsfPath,
                        status = Status.UNKNOWN,
                    )
                }
                TechnicalStatusRow(
                    label = stringResource(R.string.diag_store_path),
                    value = f.storePath ?: stringResource(R.string.absent),
                    status = Status.UNKNOWN,
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
                TechnicalStatusRow(
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
                TechnicalStatusRow(
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
    sb.appendLine("Compatibilidade: ${ui.compatibility.compatibility} match=${ui.compatibility.knownGood.level}")
    sb.appendLine("\n===== COMPATIBILITY REPORT JSON =====")
    sb.appendLine(
        kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = true
        }.encodeToString(
            dev.degoogle.app.domain.CompatibilityReport.serializer(),
            ui.compatibility.report(f),
        ),
    )
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DeGoogle Diagnostics", sb.toString()))
}

private fun localizeDetail(detail: String, isPt: Boolean): String {
    if (detail.isBlank()) return detail
    if (isPt) return detail

    var text = detail
    text = text.replace("bind temporário global criado, lido e desmontado", "Global temporary bind created, read and unmounted")
    text = text.replace("GMS mascarado via microG", "GMS masked via microG")
    text = text.replace("GSF mascarado (vazio)", "GSF masked (empty)")
    text = text.replace("Store mascarada via Companion", "Play Store masked via Companion")
    text = text.replace("alvo=", "target=")
    text = text.replace("máscara microG ativa", "microG mask active")
    text = text.replace("máscara vazia ativa", "empty mask active")
    text = text.replace("máscara companion ativa", "companion mask active")
    text = text.replace("origem=", "source=")
    text = text.replace("alvo restaurado=", "restored target=")
    text = text.replace("microG ativo com assinatura oficial ou FakeGApps verificado", "microG active with official verified signature")
    text = text.replace("microG ativo com assinatura verificada", "microG active with verified signature")
    text = text.replace("cache legível e diretório pai gravável", "cache readable and parent directory writable")
    text = text.replace("MicroG ativo com flag PRIVILEGED", "microG active with PRIVILEGED flag")
    text = text.replace("microG ativo com flag PRIVILEGED", "microG active with PRIVILEGED flag")
    text = text.replace("estratégia existe, mas firmware não foi homologado", "reboot strategy exists, but firmware is unhomologated")
    text = text.replace("estratégia KSUD_SOFT_REBOOT homologada no Known-Good DB para este firmware", "KSUD_SOFT_REBOOT strategy validated in Known-Good DB")
    text = text.replace("homologada no Known-Good DB para este firmware", "validated in Known-Good DB for this firmware")
    text = text.replace("snapshot/journal persistentes podem ser criados em", "persistent snapshot/journal can be created in")
    text = text.replace("modelo, SDK, fingerprint, root e metadados do firmware conferem", "model, SDK, fingerprint, root and firmware metadata match")
    text = text.replace("somente modelo Samsung conhecido; isso não prova compatibilidade", "only Samsung model known; does not prove compatibility")
    text = text.replace("modelo, SDK e backend conferem; fingerprint não homologado", "model, SDK and backend match; fingerprint unhomologated")
    text = text.replace("dumpsys package reporta FAKE_PACKAGE_SIGNATURE concedida ao GMS", "dumpsys package reports FAKE_PACKAGE_SIGNATURE granted to GMS")
    text = text.replace("permissão especial mencionada, mas concessão não foi confirmada", "special permission mentioned, but grant not confirmed")
    text = text.replace("detectados; validação funcional pendente", "detected; functional validation pending")
    text = text.replace("PackageManager retornou a assinatura FakeGApps para GMS e Play Store; verificação funcional concluída", "PackageManager returned FakeGApps signature for GMS and Play Store; functional check passed")
    text = text.replace("PackageManager ainda não retorna a assinatura spoofada para GMS e Play Store", "PackageManager does not yet return spoofed signature for GMS and Play Store")
    text = text.replace("nenhuma evidência funcional de signature spoofing", "no functional evidence of signature spoofing")
    text = text.replace("mecanismo de root ausente ou recusado", "root mechanism absent or denied")
    text = text.replace("firmware Samsung reconhecido", "Samsung firmware recognized")
    text = text.replace("não foi possível reproduzir o contexto SELinux do alvo", "could not reproduce target SELinux context")
    text = text.replace("nsenter validado contra", "nsenter validated against")
    return text
}
