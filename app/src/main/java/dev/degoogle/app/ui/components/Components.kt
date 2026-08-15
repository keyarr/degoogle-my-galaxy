package dev.degoogle.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Ícone de status: ✓ / ✗ / — */
@Composable
fun StatusIcon(status: Status) {
    val (color, glyph) = when (status) {
        Status.OK -> MaterialTheme.colorScheme.primary to "✓"
        Status.FAIL -> MaterialTheme.colorScheme.error to "✗"
        Status.ABSENT -> MaterialTheme.colorScheme.onSurfaceVariant to "—"
        Status.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to "·"
    }
    Text(glyph, color = color, style = MaterialTheme.typography.titleMedium)
}

enum class Status { OK, FAIL, ABSENT, UNKNOWN }

/** Linha "label ... status valor" usada na home e no diagnóstico. */
@Composable
fun StatusRow(
    label: String,
    value: String,
    status: Status,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusIcon(status)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/** Bloco técnico expansível (logs/erros do backend). */
@Composable
fun TechnicalBlock(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(Modifier),
    ) {
        Text("Detalhes técnicos", style = MaterialTheme.typography.labelMedium)
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color.Gray,
        )
    }
}
