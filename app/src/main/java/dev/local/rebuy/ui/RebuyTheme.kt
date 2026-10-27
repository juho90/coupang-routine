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
    primary = Color(0xFF176853),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F1E7),
    onPrimaryContainer = Color(0xFF154F3C),
    inversePrimary = Color(0xFF91D6B6),
    secondary = Color(0xFF546B5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EEE6),
    onSecondaryContainer = Color(0xFF30483A),
    tertiary = Color(0xFF756344),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E8CE),
    onTertiaryContainer = Color(0xFF514022),
    background = Color(0xFFF5F7F3),
    onBackground = Color(0xFF202D26),
    surface = Color.White,
    onSurface = Color(0xFF202D26),
    surfaceVariant = Color(0xFFE7EEE6),
    onSurfaceVariant = Color(0xFF5E6D63),
    surfaceTint = Color(0xFF176853),
    surfaceDim = Color(0xFFDCE2DA),
    surfaceBright = Color(0xFFFBFCF9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9F5),
    surfaceContainer = Color(0xFFF0F4EE),
    surfaceContainerHigh = Color(0xFFEAF0E8),
    surfaceContainerHighest = Color(0xFFE3EBE1),
    inverseSurface = Color(0xFF2B3830),
    inverseOnSurface = Color(0xFFF0F5EE),
    outline = Color(0xFF78857B),
    outlineVariant = Color(0xFFDCE5DA),
    error = Color(0xFFBA3030),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E0),
    onErrorContainer = Color(0xFF762323),
    scrim = Color.Black,
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF91D6B6),
    onPrimary = Color(0xFF073B29),
    primaryContainer = Color(0xFF244D3C),
    onPrimaryContainer = Color(0xFFC3EED7),
    inversePrimary = Color(0xFF176853),
    secondary = Color(0xFFB4CCBC),
    onSecondary = Color(0xFF20372B),
    secondaryContainer = Color(0xFF344B3D),
    onSecondaryContainer = Color(0xFFD4E8D9),
    tertiary = Color(0xFFDDC69A),
    onTertiary = Color(0xFF3D3017),
    tertiaryContainer = Color(0xFF554429),
    onTertiaryContainer = Color(0xFFF4E8CE),
    background = Color(0xFF111A15),
    onBackground = Color(0xFFE3ECE1),
    surface = Color(0xFF19241D),
    onSurface = Color(0xFFE3ECE1),
    surfaceVariant = Color(0xFF344339),
    onSurfaceVariant = Color(0xFFB5C4B7),
    surfaceTint = Color(0xFF91D6B6),
    surfaceDim = Color(0xFF101A14),
    surfaceBright = Color(0xFF344138),
    surfaceContainerLowest = Color(0xFF0C150F),
    surfaceContainerLow = Color(0xFF152019),
    surfaceContainer = Color(0xFF1B281F),
    surfaceContainerHigh = Color(0xFF243329),
    surfaceContainerHighest = Color(0xFF2D3D31),
    inverseSurface = Color(0xFFE3ECE1),
    inverseOnSurface = Color(0xFF253129),
    outline = Color(0xFF84978A),
    outlineVariant = Color(0xFF3A4C40),
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
    titleMedium = rebuyTextStyle(16, 24, FontWeight.SemiBold, -0.1f),
    titleSmall = rebuyTextStyle(14, 20, FontWeight.Medium),
    bodyLarge = rebuyTextStyle(16, 25, tracking = -0.1f),
    bodyMedium = rebuyTextStyle(14, 22),
    bodySmall = rebuyTextStyle(12, 18, tracking = 0.05f),
    labelLarge = rebuyTextStyle(14, 20, FontWeight.SemiBold, 0.05f),
    labelMedium = rebuyTextStyle(12, 18, FontWeight.SemiBold, 0.1f),
    labelSmall = rebuyTextStyle(11, 16, FontWeight.Medium, 0.2f),
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
