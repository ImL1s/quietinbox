package dev.quietinbox.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * QuietInbox brand palette: "ink on paper" neutrals with a calm deep-teal primary, a muted
 * sea-green secondary and a warm amber tertiary reserved for "uncertain / attention" states.
 * Every data-quality state is also expressed with text and an icon, never colour alone.
 */
object QuietColors {
    val Teal10 = Color(0xFF00201E)
    val Teal20 = Color(0xFF003734)
    val Teal30 = Color(0xFF00504B)
    val Teal40 = Color(0xFF1F6F6B)
    val Teal80 = Color(0xFF8CD5CF)
    val Teal90 = Color(0xFFA8EFE9)
    val Teal95 = Color(0xFFD5FFFA)

    val Sea10 = Color(0xFF071F1D)
    val Sea20 = Color(0xFF1D3532)
    val Sea30 = Color(0xFF344C48)
    val Sea40 = Color(0xFF4A6360)
    val Sea80 = Color(0xFFB1CCC8)
    val Sea90 = Color(0xFFCCE8E4)

    val Amber10 = Color(0xFF2E1500)
    val Amber20 = Color(0xFF4C2700)
    val Amber30 = Color(0xFF6D3B04)
    val Amber40 = Color(0xFF8A5A2B)
    val Amber80 = Color(0xFFFFB783)
    val Amber90 = Color(0xFFFFDCC0)

    val Paper = Color(0xFFF6FAF9)
    val PaperDim = Color(0xFFD6DBDA)
    val PaperBright = Color(0xFFF6FAF9)
    val PaperLowest = Color(0xFFFFFFFF)
    val PaperLow = Color(0xFFF0F4F3)
    val PaperMid = Color(0xFFEAEFEE)
    val PaperHigh = Color(0xFFE4E9E8)
    val PaperHighest = Color(0xFFDEE3E2)
    val Ink = Color(0xFF191C1C)
    val InkVariant = Color(0xFF3F4947)
    val Outline = Color(0xFF6F7977)
    val OutlineVariant = Color(0xFFBEC9C6)

    val Night = Color(0xFF0F1414)
    val NightDim = Color(0xFF0F1414)
    val NightBright = Color(0xFF353A3A)
    val NightLowest = Color(0xFF0A0F0F)
    val NightLow = Color(0xFF171C1C)
    val NightMid = Color(0xFF1B2020)
    val NightHigh = Color(0xFF262B2B)
    val NightHighest = Color(0xFF303636)
    val InkOnNight = Color(0xFFE0E3E2)
    val InkVariantOnNight = Color(0xFFBEC9C6)
    val OutlineOnNight = Color(0xFF899391)
    val OutlineVariantOnNight = Color(0xFF3F4947)

    val Error40 = Color(0xFFBA1A1A)
    val Error80 = Color(0xFFFFB4AB)
    val Error90 = Color(0xFFFFDAD6)
    val Error10 = Color(0xFF410002)
    val Error20 = Color(0xFF690005)
    val Error30 = Color(0xFF93000A)
}

val QuietLightColors = lightColorScheme(
    primary = QuietColors.Teal40,
    onPrimary = Color.White,
    primaryContainer = QuietColors.Teal90,
    onPrimaryContainer = QuietColors.Teal10,
    inversePrimary = QuietColors.Teal80,
    secondary = QuietColors.Sea40,
    onSecondary = Color.White,
    secondaryContainer = QuietColors.Sea90,
    onSecondaryContainer = QuietColors.Sea10,
    tertiary = QuietColors.Amber40,
    onTertiary = Color.White,
    tertiaryContainer = QuietColors.Amber90,
    onTertiaryContainer = QuietColors.Amber10,
    error = QuietColors.Error40,
    onError = Color.White,
    errorContainer = QuietColors.Error90,
    onErrorContainer = QuietColors.Error10,
    background = QuietColors.Paper,
    onBackground = QuietColors.Ink,
    surface = QuietColors.Paper,
    onSurface = QuietColors.Ink,
    surfaceVariant = QuietColors.PaperHigh,
    onSurfaceVariant = QuietColors.InkVariant,
    surfaceDim = QuietColors.PaperDim,
    surfaceBright = QuietColors.PaperBright,
    surfaceContainerLowest = QuietColors.PaperLowest,
    surfaceContainerLow = QuietColors.PaperLow,
    surfaceContainer = QuietColors.PaperMid,
    surfaceContainerHigh = QuietColors.PaperHigh,
    surfaceContainerHighest = QuietColors.PaperHighest,
    outline = QuietColors.Outline,
    outlineVariant = QuietColors.OutlineVariant,
    inverseSurface = QuietColors.NightHigh,
    inverseOnSurface = QuietColors.PaperLow,
    scrim = Color.Black,
)

val QuietDarkColors = darkColorScheme(
    primary = QuietColors.Teal80,
    onPrimary = QuietColors.Teal20,
    primaryContainer = QuietColors.Teal30,
    onPrimaryContainer = QuietColors.Teal90,
    inversePrimary = QuietColors.Teal40,
    secondary = QuietColors.Sea80,
    onSecondary = QuietColors.Sea20,
    secondaryContainer = QuietColors.Sea30,
    onSecondaryContainer = QuietColors.Sea90,
    tertiary = QuietColors.Amber80,
    onTertiary = QuietColors.Amber20,
    tertiaryContainer = QuietColors.Amber30,
    onTertiaryContainer = QuietColors.Amber90,
    error = QuietColors.Error80,
    onError = QuietColors.Error20,
    errorContainer = QuietColors.Error30,
    onErrorContainer = QuietColors.Error90,
    background = QuietColors.Night,
    onBackground = QuietColors.InkOnNight,
    surface = QuietColors.Night,
    onSurface = QuietColors.InkOnNight,
    surfaceVariant = QuietColors.NightHigh,
    onSurfaceVariant = QuietColors.InkVariantOnNight,
    surfaceDim = QuietColors.NightDim,
    surfaceBright = QuietColors.NightBright,
    surfaceContainerLowest = QuietColors.NightLowest,
    surfaceContainerLow = QuietColors.NightLow,
    surfaceContainer = QuietColors.NightMid,
    surfaceContainerHigh = QuietColors.NightHigh,
    surfaceContainerHighest = QuietColors.NightHighest,
    outline = QuietColors.OutlineOnNight,
    outlineVariant = QuietColors.OutlineVariantOnNight,
    inverseSurface = QuietColors.InkOnNight,
    inverseOnSurface = QuietColors.NightHigh,
    scrim = Color.Black,
)

/** Harmonious container colours for monogram avatars, indexed by a stable hash. */
val AvatarPalette: List<Pair<Color, Color>> = listOf(
    Color(0xFFA8EFE9) to Color(0xFF00201E),
    Color(0xFFCCE8E4) to Color(0xFF071F1D),
    Color(0xFFFFDCC0) to Color(0xFF2E1500),
    Color(0xFFD9E2FF) to Color(0xFF001A41),
    Color(0xFFE8DEF8) to Color(0xFF1D192B),
    Color(0xFFFFD8E4) to Color(0xFF31111D),
    Color(0xFFDDE9C6) to Color(0xFF131F00),
    Color(0xFFFFE08C) to Color(0xFF241A00),
)

val AvatarPaletteDark: List<Pair<Color, Color>> = listOf(
    Color(0xFF00504B) to Color(0xFFA8EFE9),
    Color(0xFF344C48) to Color(0xFFCCE8E4),
    Color(0xFF6D3B04) to Color(0xFFFFDCC0),
    Color(0xFF2F4578) to Color(0xFFD9E2FF),
    Color(0xFF4A4458) to Color(0xFFE8DEF8),
    Color(0xFF633B48) to Color(0xFFFFD8E4),
    Color(0xFF3B4D1F) to Color(0xFFDDE9C6),
    Color(0xFF5A4300) to Color(0xFFFFE08C),
)
