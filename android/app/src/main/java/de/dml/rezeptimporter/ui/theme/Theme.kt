package de.dml.rezeptimporter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ParchmentScheme = lightColorScheme(
    primary = ManaViolet,
    onPrimary = Color.White,
    secondary = RupeeGreen,
    onSecondary = Color.White,
    tertiary = LegendaryGold,
    onTertiary = ArcaneAbyss,
    background = ParchmentBackground,
    onBackground = ArcaneAbyss,
    surface = ParchmentSurface,
    onSurface = ArcaneAbyss,
    surfaceVariant = ParchmentBackground,
    onSurfaceVariant = MutedPurpleGray,
    outline = CardEdgeKhaki,
    outlineVariant = CardEdgeKhaki,
)

private val DungeonScheme = darkColorScheme(
    primary = GlowingMana,
    onPrimary = Color.White,
    secondary = GlowingRupee,
    onSecondary = DeepVoidPurple,
    tertiary = LegendaryGold,
    onTertiary = DeepVoidPurple,
    background = DeepVoidPurple,
    onBackground = FadedLavender,
    surface = ShadowedCrypt,
    onSurface = FadedLavender,
    surfaceVariant = ShadowedCrypt,
    onSurfaceVariant = NeutralGray,
    outline = DarkArcane,
    outlineVariant = DarkArcane,
)

@Composable
fun ArcaneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DungeonScheme else ParchmentScheme,
        typography = ArcaneTypography,
        content = content,
    )
}
