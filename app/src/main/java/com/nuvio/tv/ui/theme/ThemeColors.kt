package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors

data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color = NuvioPrimitives.white,
    val onSecondaryVariant: Color = NuvioPrimitives.white,
    val accentGradient: List<Color> = listOf(secondary),
    val focusRing: Color,
    val focusRingGradient: List<Color> = listOf(focusRing),
    val focusBackground: Color,
    val background: Color = NuvioPrimitives.neutral950,
    val backgroundElevated: Color = NuvioPrimitives.neutral900,
    val backgroundCard: Color = NuvioPrimitives.neutral825,
    val surface: Color = NuvioPrimitives.neutral875,
    val surfaceVariant: Color = NuvioPrimitives.neutral800,
    val panel: Color = NuvioPrimitives.neutral900,
    val overlay: Color = Color(0xD9000000),
    val field: Color = NuvioPrimitives.neutral850,
    val menu: Color = NuvioPrimitives.neutral875,
    val modal: Color = NuvioPrimitives.neutral900,
    val playerOverlay: Color = Color(0xCC000000)
)

object ThemeColors {
    // ---- HoneyStream family palettes (same values as HoneyLive) ----
    private fun honey(accent: Long, deep: Long, glow: Long, bg: Long, surface: Long, surfaceHi: Long) = ThemeColorPalette(
        secondary = Color(accent), secondaryVariant = Color(deep),
        onSecondary = Color(0xFF120D06), onSecondaryVariant = Color(0xFF120D06),
        focusRing = Color(glow), focusBackground = Color(surfaceHi),
        background = Color(bg), backgroundElevated = Color(surface), backgroundCard = Color(surface),
        surface = Color(surface), surfaceVariant = Color(surfaceHi), panel = Color(surface),
        field = Color(surfaceHi), menu = Color(surface), modal = Color(surface),
    )
    val Honey = honey(0xFFF5A623, 0xFFD9820B, 0xFFFFC94A, 0xFF120D06, 0xFF1E160A, 0xFF2E2210)
    val Halloween = honey(0xFFFF7A1A, 0xFFD9530B, 0xFFFFB347, 0xFF0E0812, 0xFF1C1024, 0xFF2C1A38)
    val Autumn = honey(0xFFE07B39, 0xFFB5541C, 0xFFF4A261, 0xFF140C07, 0xFF22150D, 0xFF332014)
    val Holidays = honey(0xFFE5484D, 0xFFB8282E, 0xFFFF7B7F, 0xFF07120D, 0xFF0F2119, 0xFF173225)
    val Frost = honey(0xFF7FD3FF, 0xFF3FA7E0, 0xFFB5E6FF, 0xFF070E16, 0xFF0F1C2A, 0xFF17293D)
    val Spring = honey(0xFF8CD867, 0xFF5BAA3A, 0xFFB7EE96, 0xFF0B130C, 0xFF152317, 0xFF203421)
    val Summer = honey(0xFFFFD23F, 0xFFE0A800, 0xFFFFE680, 0xFF0F0D04, 0xFF1D1A0B, 0xFF2D2812)

    val Crimson = ThemeColorPalette(
        secondary = NuvioPrimitives.red500,
        secondaryVariant = NuvioPrimitives.red600,
        focusRing = NuvioPrimitives.red300,
        focusBackground = Color(0xFF3D1A1A),
        backgroundCard = Color(0xFF241A1A)
    )

    val Ocean = ThemeColorPalette(
        secondary = NuvioPrimitives.blue500,
        secondaryVariant = NuvioPrimitives.blue700,
        focusRing = NuvioPrimitives.blue300,
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24)
    )

    val Violet = ThemeColorPalette(
        secondary = NuvioPrimitives.violet500,
        secondaryVariant = NuvioPrimitives.violet700,
        focusRing = NuvioPrimitives.violet300,
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24)
    )

    val Emerald = ThemeColorPalette(
        secondary = NuvioPrimitives.green500,
        secondaryVariant = NuvioPrimitives.green700,
        focusRing = NuvioPrimitives.green300,
        focusBackground = Color(0xFF1A3D1E),
        backgroundCard = Color(0xFF1A241A)
    )

    val Amber = ThemeColorPalette(
        secondary = NuvioPrimitives.amber500,
        secondaryVariant = NuvioPrimitives.amber700,
        focusRing = NuvioPrimitives.amber300,
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A)
    )

    val Rose = ThemeColorPalette(
        secondary = NuvioPrimitives.rose500,
        secondaryVariant = NuvioPrimitives.rose700,
        focusRing = NuvioPrimitives.rose300,
        focusBackground = Color(0xFF3D1A2D),
        backgroundCard = Color(0xFF241A1F)
    )

    val White = ThemeColorPalette(
        secondary = NuvioPrimitives.neutral100,
        secondaryVariant = NuvioPrimitives.neutral200,
        onSecondary = NuvioPrimitives.neutral925,
        onSecondaryVariant = NuvioPrimitives.neutral925,
        focusRing = NuvioPrimitives.white,
        focusBackground = Color(0xFF303030),
        backgroundCard = NuvioPrimitives.neutral850
    )

    fun getColorPalette(
        theme: AppTheme,
        customColors: CustomThemeColors = CustomThemeColors.Default
    ): ThemeColorPalette {
        return when (theme) {
            AppTheme.HONEY -> Honey
            AppTheme.HALLOWEEN -> Halloween
            AppTheme.AUTUMN -> Autumn
            AppTheme.HOLIDAYS -> Holidays
            AppTheme.FROST -> Frost
            AppTheme.SPRING -> Spring
            AppTheme.SUMMER -> Summer
            AppTheme.CUSTOM -> customColors.toColorPalette()
            AppTheme.GOLD -> SupporterThemeColors.Gold
            AppTheme.JADE -> SupporterThemeColors.Jade
            AppTheme.ROSE_GOLD -> SupporterThemeColors.RoseGold
            AppTheme.ARCTIC_BLUE -> SupporterThemeColors.ArcticBlue
            AppTheme.GRAPHITE -> SupporterThemeColors.Graphite
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
        }
    }
}
