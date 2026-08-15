package dev.degoogle.app.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.degoogle.app.domain.SystemFacts
import dev.degoogle.app.ui.UiState
import dev.degoogle.app.ui.components.InfoCard
import dev.degoogle.app.ui.components.Status
import dev.degoogle.app.ui.components.StatusRow

@Composable
fun DiagnosticsScreen(ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val f = ui.facts

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Diagnóstico", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        InfoCard("Estado") {
            StatusRow("Estado derivado", ui.state.name, Status.UNKNOWN)
            StatusRow("Root", if (f.rootOk) f.rootManager.ifBlank { "ok" } else "não", if (f.rootOk) Status.OK else Status.FAIL)
            StatusRow("Perfil", if (f.profileMatch) f.profileId else "não suportado", if (f.profileMatch) Status.OK else Status.FAIL)
            StatusRow("Build", f.fingerprint.ifBlank { "—" }, Status.UNKNOWN)
            StatusRow("Android", "${f.androidRelease.ifBlank { "?" }} · SDK ${f.androidSdk}", Status.UNKNOWN)
            StatusRow("Dispositivo", "${f.manufacturer} ${f.model} (${f.device}/${f.product})", Status.UNKNOWN)
            StatusRow("SELinux", f.selinux, Status.UNKNOWN)
            StatusRow("ABI", f.abi, Status.UNKNOWN)
        }

        InfoCard("GMS (com.google.android.gms)") {
            StatusRow("Path", f.gmsPath ?: "ausente", Status.UNKNOWN)
            StatusRow("Versão", f.gmsVersion ?: "—", Status.UNKNOWN)
            StatusRow("UID", f.gmsUid ?: "—", Status.UNKNOWN)
            StatusRow("Flags", f.gmsFlags ?: "—", Status.UNKNOWN)
            StatusRow("Privileged", if (f.gmsPrivileged) "sim" else "não", if (f.gmsPrivileged) Status.OK else Status.FAIL)
        }

        InfoCard("GSF (com.google.android.gsf)") {
            StatusRow("Presente", if (f.gsfPath != null) "sim" else "não", if (f.gsfPath == null) Status.OK else Status.FAIL)
            if (f.gsfPath != null) StatusRow("Path", f.gsfPath, Status.UNKNOWN)
        }

        InfoCard("Play Store (com.android.vending)") {
            StatusRow("Path", f.storePath ?: "ausente", Status.UNKNOWN)
            StatusRow("Versão", f.storeVersion ?: "—", Status.UNKNOWN)
        }

        InfoCard("Mounts") {
            StatusRow("GMS", if (f.mountGms) "mascarado" else "visível", if (f.mountGms) Status.OK else Status.ABSENT)
            StatusRow("GSF", if (f.mountGsf) "mascarado" else "visível", if (f.mountGsf) Status.OK else Status.ABSENT)
            StatusRow("Store", if (f.mountStore) "mascarado" else "visível", if (f.mountStore) Status.OK else Status.ABSENT)
            StatusRow("Fonte GMS", f.mountGmsSource ?: "—", Status.UNKNOWN)
        }

        InfoCard("Backup") {
            StatusRow("Presente", if (f.backupPresent) "sim" else "não", if (f.backupPresent) Status.OK else Status.ABSENT)
            StatusRow("Formato", "MicroG Session", Status.UNKNOWN)
            StatusRow("Local", "/data/local/tmp/microg-backup", Status.UNKNOWN)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { copyDiagnostics(context, ui) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copiar diagnóstico") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Não inclui secrets, tokens ou dados de conta.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Voltar", style = MaterialTheme.typography.titleSmall)
        Text(
            "← toque para voltar",
            modifier = Modifier,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
    }
}

private fun copyDiagnostics(context: Context, ui: UiState) {
    val f = ui.facts
    val sb = StringBuilder()
    sb.appendLine("DeGoogle diagnóstico — estado ${ui.state.name}")
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
