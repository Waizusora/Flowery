package com.waizusora.flowery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle

private val FloweryLightColors = lightColorScheme(
    primary = Color(0xFFB66D83),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3E1E7),
    onPrimaryContainer = Color(0xFF482532),
    secondary = Color(0xFF80616D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEDDE3),
    onSecondaryContainer = Color(0xFF382830),
    background = Color(0xFFF7F6F4),
    onBackground = Color(0xFF292629),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF292629),
    surfaceVariant = Color(0xFFF0EAEC),
    onSurfaceVariant = Color(0xFF746A6E),
    outline = Color(0xFFD8CDD1),
    outlineVariant = Color(0xFFE8E0E3),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

private val FloweryDarkColors = darkColorScheme(
    primary = Color(0xFFD58CA3),
    onPrimary = Color(0xFF321923),
    primaryContainer = Color(0xFF603B49),
    onPrimaryContainer = Color(0xFFF6DDE6),
    secondary = Color(0xFFD4AAB8),
    onSecondary = Color(0xFF3D2931),
    secondaryContainer = Color(0xFF59404A),
    onSecondaryContainer = Color(0xFFF2DCE4),
    background = Color(0xFF171719),
    onBackground = Color(0xFFF4F0F0),
    surface = Color(0xFF242326),
    onSurface = Color(0xFFF4F0F0),
    surfaceVariant = Color(0xFF302B2E),
    onSurfaceVariant = Color(0xFFC8B9BF),
    outline = Color(0xFF55474D),
    outlineVariant = Color(0xFF40363B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private fun Typography.withFloweryFont(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily)
)

/**
 * Applies the Flowery palette and a platform sans-serif family.
 * Android's script fallback chain supplies Noto families for scripts not
 * covered by the primary Latin sans-serif face.
 */
@Composable
internal fun FloweryTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val fontFamily = FontFamily.SansSerif
    MaterialTheme(
        colorScheme = if (darkTheme) FloweryDarkColors else FloweryLightColors,
        typography = Typography().withFloweryFont(fontFamily),
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = fontFamily),
                content = content
            )
        }
    )
}
