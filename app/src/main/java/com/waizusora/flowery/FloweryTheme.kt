package com.waizusora.flowery

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle

private val FloweryNotoSans = FontFamily(
    Font(R.font.noto_sans, weight = FontWeight.Normal)
)

private val FloweryLightColors = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEEFF),
    onPrimaryContainer = Color(0xFF103B5C),
    secondary = Color(0xFF397DAE),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5F3FF),
    onSecondaryContainer = Color(0xFF173B56),
    background = Color(0xFFF4F9FE),
    onBackground = Color(0xFF152536),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF152536),
    surfaceVariant = Color(0xFFEAF2F9),
    onSurfaceVariant = Color(0xFF52697C),
    outline = Color(0xFFC5D7E6),
    outlineVariant = Color(0xFFDCE7F0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

private val FloweryDarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF064B59),
    onPrimaryContainer = Color(0xFFB8F4FC),
    secondary = Color(0xFF80DEEA),
    onSecondary = Color(0xFF12363D),
    secondaryContainer = Color(0xFF164854),
    onSecondaryContainer = Color(0xFFD5F7FA),
    background = Color(0xFF101B24),
    onBackground = Color(0xFFE6F4F7),
    surface = Color(0xFF172630),
    onSurface = Color(0xFFE6F4F7),
    surfaceVariant = Color(0xFF213743),
    onSurfaceVariant = Color(0xFFB0C8D0),
    outline = Color(0xFF49636D),
    outlineVariant = Color(0xFF344B55),
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
 * Applies the Flowery palette and the bundled Noto Sans typeface.
 * Android's native font fallback chain supplies additional Noto script
 * families for glyphs not covered by the bundled Latin/Greek/Cyrillic face.
 */
@Composable
internal fun FloweryTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FloweryDarkColors else FloweryLightColors,
        typography = Typography().withFloweryFont(FloweryNotoSans),
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FloweryNotoSans),
                content = content
            )
        }
    )
}
