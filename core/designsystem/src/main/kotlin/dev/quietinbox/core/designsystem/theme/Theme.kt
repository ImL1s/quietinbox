package dev.quietinbox.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class QuietThemeMode { SYSTEM, LIGHT, DARK }

/** True when the current theme is dark; used by avatar palettes. */
val LocalQuietDark = staticCompositionLocalOf { false }

val QuietShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()

val QuietTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun QuietInboxTheme(
    mode: QuietThemeMode = QuietThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        QuietThemeMode.SYSTEM -> isSystemInDarkTheme()
        QuietThemeMode.LIGHT -> false
        QuietThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> QuietDarkColors
        else -> QuietLightColors
    }
    CompositionLocalProvider(LocalQuietDark provides dark) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = if (reduceMotion) MotionScheme.standard() else MotionScheme.expressive(),
            shapes = QuietShapes,
            typography = QuietTypography,
            content = content,
        )
    }
}

/** Semantic colours for data-quality states, derived from the active scheme. */
object QualityColors {
    val verified: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.primary
    val inferred: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.secondary
    val uncertain: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.tertiary
    val failed: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.error
}
