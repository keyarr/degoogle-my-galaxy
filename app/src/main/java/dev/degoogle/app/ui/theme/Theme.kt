package dev.degoogle.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val MonospaceTypography = Typography().let { t ->
    val mono = FontFamily.Monospace
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = mono),
        displayMedium = t.displayMedium.copy(fontFamily = mono),
        displaySmall = t.displaySmall.copy(fontFamily = mono),
        headlineLarge = t.headlineLarge.copy(fontFamily = mono),
        headlineMedium = t.headlineMedium.copy(fontFamily = mono),
        headlineSmall = t.headlineSmall.copy(fontFamily = mono),
        titleLarge = t.titleLarge.copy(fontFamily = mono),
        titleMedium = t.titleMedium.copy(fontFamily = mono),
        titleSmall = t.titleSmall.copy(fontFamily = mono),
        bodyLarge = t.bodyLarge.copy(fontFamily = mono),
        bodyMedium = t.bodyMedium.copy(fontFamily = mono),
        bodySmall = t.bodySmall.copy(fontFamily = mono),
        labelLarge = t.labelLarge.copy(fontFamily = mono),
        labelMedium = t.labelMedium.copy(fontFamily = mono),
        labelSmall = t.labelSmall.copy(fontFamily = mono),
    )
}

private val CypherpunkColors = darkColorScheme(
    primary = Color(0xFFFF9E00),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF663F00),
    onPrimaryContainer = Color(0xFFFFD18A),
    secondary = Color(0xFF8C8C8C),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFF9E00),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF663F00),
    onTertiaryContainer = Color(0xFFFFD18A),
    error = Color(0xFFFF3333),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4D1111),
    onErrorContainer = Color(0xFFFFB3AA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF0D0D0D),
    onSurfaceVariant = Color(0xFF8C8C8C),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF4D4D4D),
    outlineVariant = Color(0xFF262626),
)

@Composable
fun DeGoogleTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CypherpunkColors,
        typography = MonospaceTypography,
        content = content,
    )
}
