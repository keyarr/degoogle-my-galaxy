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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                text = "Diagnóstico",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Telemetria e estado de baixo nível do sistema",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard(
            title = "Sistema & Ambiente",
            action = {
                StateBadge(
                    text = ui.state.displayName,
                    icon = Icons.Rounded.Verified,
                )
            },
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow("Acesso Root", if (f.rootOk) f.rootManager.ifBlank { "ok" } else "não", if (f.rootOk) Status.OK else Status.FAIL, leadingIcon = Icons.Rounded.Shield)
                StatusRow("Perfil", if (f.profileMatch) f.profileId else "não suportado", if (f.profileMatch) Status.OK else Status.FAIL, leadingIcon = Icons.Rounded.Verified)
                StatusRow("Dispositivo", "${f.manufacturer} ${f.model}", Status.OK, leadingIcon = Icons.Rounded.Fingerprint)
                StatusRow("Android", "${f.androidRelease.ifBlank { "?" }} (SDK ${f.androidSdk})", Status.OK, leadingIcon = Icons.Rounded.Android)
                StatusRow("SELinux", f.selinux, Status.UNKNOWN, leadingIcon = Icons.Rounded.Security)
                StatusRow("Arquitetura ABI", f.abi, Status.UNKNOWN)
                StatusRow("Build Fingerprint", f.fingerprint.ifBlank { "Não informado" }, Status.UNKNOWN, leadingIcon = Icons.Rounded.Tag)
            }
        }

        InfoCard("Google Play Services (GMS)") {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow("Caminho (Path)", f.gmsPath ?: "ausente", Status.UNKNOWN, leadingIcon = Icons.Rounded.Folder)
                StatusRow("Versão", f.gmsVersion ?: "Não informado", Status.UNKNOWN)
                StatusRow("UID", f.gmsUid ?: "Não informado", Status.UNKNOWN)
                StatusRow("Privileged (priv-app)", if (f.gmsPrivileged) "sim" else "não", if (f.gmsPrivileged) Status.OK else Status.FAIL)

                val flagsList = f.gmsFlags?.split(" ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (flagsList.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = "Flags de Pacote",
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
                    StatusRow("Flags de Pacote", "Não informado", Status.UNKNOWN)
                }
            }
        }

        InfoCard("Framework & Store") {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow("GSF Presente", if (f.gsfPath != null) "sim" else "não", if (f.gsfPath == null) Status.OK else Status.FAIL, leadingIcon = Icons.Rounded.Layers)
                if (f.gsfPath != null) StatusRow("GSF Path", f.gsfPath, Status.UNKNOWN)
                StatusRow("Play Store Path", f.storePath ?: "ausente", Status.UNKNOWN, leadingIcon = Icons.Rounded.Store)
                if (f.storeVersion != null) StatusRow("Play Store Versão", f.storeVersion, Status.UNKNOWN)
            }
        }

        InfoCard("Bind Mounts (/product)") {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow("GMS Mount", if (f.mountGms) "mascarado (microG)" else "visível (stock)", if (f.mountGms) Status.OK else Status.ABSENT, leadingIcon = if (f.mountGms) Icons.Rounded.Folder else Icons.Rounded.FolderOff)
                StatusRow("GSF Mount", if (f.mountGsf) "mascarado (vazio)" else "visível (stock)", if (f.mountGsf) Status.OK else Status.ABSENT)
                StatusRow("Store Mount", if (f.mountStore) "mascarado (companion)" else "visível (stock)", if (f.mountStore) Status.OK else Status.ABSENT)
                StatusRow("Fonte GMS", f.mountGmsSource ?: "Não informado", Status.UNKNOWN)
            }
        }

        InfoCard("Snapshot de Dados (Backup)") {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatusRow("Backup Presente", if (f.backupPresent) "sim" else "não", if (f.backupPresent) Status.OK else Status.ABSENT, leadingIcon = Icons.Rounded.Backup)
                StatusRow("Formato de Exportação", "MicroG Session (user0 + user_de)", Status.UNKNOWN)
                StatusRow("Diretório Local", "/data/local/tmp/microg-backup", Status.UNKNOWN)
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
                    if (copied) "Diagnóstico Copiado!" else "Copiar Relatório Completo",
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
                        text = "O relatório gerado é seguro e não contém tokens, senhas ou dados pessoais.",
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
