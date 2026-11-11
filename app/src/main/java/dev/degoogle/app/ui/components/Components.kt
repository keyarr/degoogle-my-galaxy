package dev.degoogle.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import dev.degoogle.app.R
import dev.degoogle.app.ui.StepLog

enum class Status { OK, FAIL, ABSENT, UNKNOWN }

/** Rótulo textual curto para manter o resultado legível sem depender de cor. */
@Composable
private fun StatusText(status: Status) {
    Text(
        text = stringResource(
            when (status) {
                Status.OK -> R.string.status_ok
                Status.FAIL -> R.string.status_fail
                Status.ABSENT -> R.string.status_absent
                Status.UNKNOWN -> R.string.status_unknown
            },
        ),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = when (status) {
            Status.OK -> MaterialTheme.colorScheme.primary
            Status.FAIL -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * Log compacto das operações. Mostra as últimas linhas e acompanha o final
 * automaticamente enquanto o backend envia progresso pelo stderr.
 */
@Composable
fun OperationLog(
    steps: List<StepLog>,
    inProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val visibleSteps = steps.takeLast(12)

    LaunchedEffect(visibleSteps.lastOrNull()?.text, inProgress) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .heightIn(max = 184.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (visibleSteps.isEmpty()) {
                Text(
                    text = "AWAITING EXECUTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            visibleSteps.forEach { step ->
                val status = when (step.ok) {
                    true -> "[x]"
                    false -> "[!]"
                    null -> "[>]"
                }
                Text(
                    text = "$status ${step.text}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (step.ok == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Linha "label ... status valor" com visual refinado */
@Composable
fun StatusRow(
    label: String,
    value: String,
    status: Status,
    modifier: Modifier = Modifier,
    showStatus: Boolean = true,
    valueFontWeight: FontWeight = FontWeight.Medium,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = valueFontWeight,
            color = if (status == Status.OK) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
        if (showStatus) StatusText(status)
    }
}

/**
 * Linha para valores técnicos extensos (paths, fingerprints e mount sources).
 * O label/status ocupam uma linha própria; o valor recebe toda a largura
 * abaixo e pode quebrar sem espremer os demais elementos.
 */
@Composable
fun TechnicalStatusRow(
    label: String,
    value: String,
    status: Status,
    modifier: Modifier = Modifier,
    showStatus: Boolean = true,
    inlineValue: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (inlineValue) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.9f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (status == Status.OK) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1.1f),
                )
                if (showStatus) StatusText(status)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (showStatus) StatusText(status)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (status == Status.OK) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = true,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Seção da fita contínua, separada das demais por uma regra horizontal. */
@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    titleFontWeight: FontWeight = FontWeight.Bold,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262626)))
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = titleFontWeight,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (action != null) {
                    action()
                }
            }
            content()
        }
    }
}

/** Badge de estado arredondado */
@Composable
fun StateBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Bloco técnico formatado para logs e detalhes */
@Composable
fun TechnicalBlock(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(0.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.technical_details),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun SlideToExecute(
    label: String,
    enabled: Boolean = true,
    onExecute: () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var startedOnHandle by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val thumbWidth = 72.dp
        val thumbWidthPx = with(LocalDensity.current) { thumbWidth.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (enabled) Modifier.pointerInput(widthPx) {
                        detectDragGestures(
                            onDragStart = { startedOnHandle = it.x <= thumbWidthPx },
                            onDragEnd = {
                                if (startedOnHandle && progress >= 0.98f) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onExecute()
                                }
                                progress = 0f
                                startedOnHandle = false
                            },
                            onDragCancel = {
                                progress = 0f
                                startedOnHandle = false
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            if (startedOnHandle) {
                                progress = (progress + dragAmount.x / (widthPx - thumbWidthPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            }
                        }
                    } else Modifier,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(activeColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.BottomCenter)
                    .background(activeColor),
            )
            Text(
                text = label,
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset((progress * (widthPx - thumbWidthPx)).toInt(), 0) }
                    .size(width = thumbWidth, height = 48.dp)
                    .background(activeColor),
                contentAlignment = Alignment.Center,
            ) {
                Text("[ > ]", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
