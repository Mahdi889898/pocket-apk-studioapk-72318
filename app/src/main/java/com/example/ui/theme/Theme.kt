package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldNeon,
    onPrimary = OnEmeraldDark,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = EmeraldNeon,
    secondary = IndigoVolt,
    onSecondary = TextPrimaryDark,
    secondaryContainer = IndigoContainerDark,
    onSecondaryContainer = IndigoVolt,
    tertiary = AmberSpark,
    onTertiary = OnEmeraldDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberSpark,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CarbonCard,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = CarbonCard,
    surfaceContainerHigh = CarbonElevated,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = ErrorCrimson,
    onError = TextPrimaryDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorCrimson
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldLight,
    onPrimary = SurfaceLight,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = EmeraldLight,
    secondary = IndigoLight,
    onSecondary = SurfaceLight,
    secondaryContainer = IndigoContainerLight,
    onSecondaryContainer = IndigoLight,
    tertiary = AmberSpark,
    onTertiary = TextPrimaryLight,
    background = PaperBgLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = CardLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = ErrorCrimson
)

val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StudioApkTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StudioTypography,
            shapes = StudioShapes,
            content = content
        )
    }
}
