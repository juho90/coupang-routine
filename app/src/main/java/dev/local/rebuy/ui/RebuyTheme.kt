package dev.local.rebuy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val lightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF0FF),
    onPrimaryContainer = Color(0xFF153E91),
    inversePrimary = Color(0xFFA9C7FF),
    secondary = Color(0xFF526176),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEFF3),
    onSecondaryContainer = Color(0xFF344155),
    tertiary = Color(0xFF8A5B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBC2),
    onTertiaryContainer = Color(0xFF603E00),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF182338),
    surface = Color.White,
    onSurface = Color(0xFF182338),
    surfaceVariant = Color(0xFFECEFF3),
    onSurfaceVariant = Color(0xFF566176),
    surfaceTint = Color(0xFF2563EB),
    surfaceDim = Color(0xFFDDE1E7),
    surfaceBright = Color(0xFFFCFCFD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFEBEEF2),
    surfaceContainerHighest = Color(0xFFE5E9EE),
    inverseSurface = Color(0xFF2A3241),
    inverseOnSurface = Color(0xFFF3F5F8),
    outline = Color(0xFF798394),
    outlineVariant = Color(0xFFDCE1E8),
    error = Color(0xFFBA3030),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E0),
    onErrorContainer = Color(0xFF762323),
    scrim = Color.Black,
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF102E63),
    primaryContainer = Color(0xFF193D77),
    onPrimaryContainer = Color(0xFFD9E5FF),
    inversePrimary = Color(0xFF2563EB),
    secondary = Color(0xFFBEC8D8),
    onSecondary = Color(0xFF273345),
    secondaryContainer = Color(0xFF354153),
    onSecondaryContainer = Color(0xFFDDE5F0),
    tertiary = Color(0xFFF3C46B),
    onTertiary = Color(0xFF442D00),
    tertiaryContainer = Color(0xFF614200),
    onTertiaryContainer = Color(0xFFFFEBC2),
    background = Color(0xFF11151B),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF191E26),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF323A47),
    onSurfaceVariant = Color(0xFFBCC5D3),
    surfaceTint = Color(0xFFA9C7FF),
    surfaceDim = Color(0xFF10141A),
    surfaceBright = Color(0xFF353C48),
    surfaceContainerLowest = Color(0xFF0C1016),
    surfaceContainerLow = Color(0xFF171C24),
    surfaceContainer = Color(0xFF1D232D),
    surfaceContainerHigh = Color(0xFF272E39),
    surfaceContainerHighest = Color(0xFF303846),
    inverseSurface = Color(0xFFE6EAF2),
    inverseOnSurface = Color(0xFF293140),
    outline = Color(0xFF8995A7),
    outlineVariant = Color(0xFF3C4655),
    error = Color(0xFFFFB4AC),
    onError = Color(0xFF690006),
    errorContainer = Color(0xFF64262A),
    onErrorContainer = Color(0xFFFFDAD5),
    scrim = Color.Black,
)

private fun rebuyTextStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private val rebuyTypography = Typography(
    displayLarge = rebuyTextStyle(52, 60, FontWeight.Bold, -1.2f),
    displayMedium = rebuyTextStyle(44, 52, FontWeight.Bold, -1f),
    displaySmall = rebuyTextStyle(36, 44, FontWeight.Bold, -0.8f),
    headlineLarge = rebuyTextStyle(32, 40, FontWeight.Bold, -0.7f),
    headlineMedium = rebuyTextStyle(28, 36, FontWeight.SemiBold, -0.6f),
    headlineSmall = rebuyTextStyle(24, 32, FontWeight.SemiBold, -0.4f),
    titleLarge = rebuyTextStyle(20, 28, FontWeight.SemiBold, -0.25f),
    titleMedium = rebuyTextStyle(17, 25, FontWeight.SemiBold, -0.1f),
    titleSmall = rebuyTextStyle(14, 20, FontWeight.Medium),
    bodyLarge = rebuyTextStyle(16, 25, tracking = -0.1f),
    bodyMedium = rebuyTextStyle(15, 23),
    bodySmall = rebuyTextStyle(13, 20, tracking = 0.05f),
    labelLarge = rebuyTextStyle(14, 20, FontWeight.SemiBold, 0.05f),
    labelMedium = rebuyTextStyle(13, 20, FontWeight.SemiBold, 0.1f),
    labelSmall = rebuyTextStyle(12, 18, FontWeight.Medium, 0.2f),
)

private val rebuyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
internal fun RebuyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors,
        typography = rebuyTypography,
        shapes = rebuyShapes,
        content = content,
    )
}
