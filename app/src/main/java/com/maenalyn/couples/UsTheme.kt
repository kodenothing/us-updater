package com.maenalyn.couples

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class UsPalette(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val gradStart: Color,
    val gradEnd: Color
)

val UsPalettes = listOf(
    UsPalette("Lavender & Blush", Color(0xFF8B6BD6), Color(0xFFE56BA6), Color(0xFFA98BE8), Color(0xFFF59CC8)),
    UsPalette("Lilac & Sky", Color(0xFF7C6FE0), Color(0xFF3FA9DE), Color(0xFFB3A8FF), Color(0xFF8FD3FF)),
    UsPalette("Mint & Rose", Color(0xFF2FA084), Color(0xFFE86A92), Color(0xFF8BE3C8), Color(0xFFF59CB8)),
    UsPalette("Peach & Coral", Color(0xFFE8605A), Color(0xFFE59A3F), Color(0xFFFFB199), Color(0xFFFFD59A)),
    UsPalette("Sky & Peach", Color(0xFF4F8FE0), Color(0xFFEE8A66), Color(0xFF8EC5FF), Color(0xFFFFB8A0))
)

val LocalUsGradient = staticCompositionLocalOf {
    Brush.linearGradient(listOf(UsPalettes[0].gradStart, UsPalettes[0].gradEnd))
}

private fun lightScheme(p: UsPalette): ColorScheme {
    val w = Color.White
    val k = Color.Black
    val bg = lerp(p.primary, w, 0.965f)
    val on = lerp(p.primary, k, 0.85f)
    return lightColorScheme(
        primary = p.primary, onPrimary = w,
        primaryContainer = lerp(p.primary, w, 0.85f), onPrimaryContainer = lerp(p.primary, k, 0.65f),
        secondary = p.secondary, onSecondary = w,
        secondaryContainer = lerp(p.secondary, w, 0.82f), onSecondaryContainer = lerp(p.secondary, k, 0.65f),
        background = bg, onBackground = on,
        surface = bg, onSurface = on,
        surfaceVariant = lerp(p.primary, w, 0.9f), onSurfaceVariant = lerp(p.primary, k, 0.45f),
        outline = lerp(p.primary, w, 0.6f),
        error = Color(0xFFD64B6A)
    )
}

private fun darkScheme(p: UsPalette): ColorScheme {
    val w = Color.White
    val k = Color.Black
    val bg = lerp(p.primary, k, 0.92f)
    val on = lerp(p.primary, w, 0.9f)
    return darkColorScheme(
        primary = lerp(p.primary, w, 0.4f), onPrimary = lerp(p.primary, k, 0.75f),
        primaryContainer = lerp(p.primary, k, 0.6f), onPrimaryContainer = lerp(p.primary, w, 0.85f),
        secondary = lerp(p.secondary, w, 0.4f), onSecondary = lerp(p.secondary, k, 0.75f),
        secondaryContainer = lerp(p.secondary, k, 0.62f), onSecondaryContainer = lerp(p.secondary, w, 0.85f),
        background = bg, onBackground = on,
        surface = bg, onSurface = on,
        surfaceVariant = lerp(p.primary, k, 0.82f), onSurfaceVariant = lerp(p.primary, w, 0.7f),
        outline = lerp(p.primary, k, 0.45f),
        error = Color(0xFFFF7A95)
    )
}

private val UsTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp, letterSpacing = (-1).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
    ),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

private val UsShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun UsTheme(dark: Boolean, paletteIndex: Int, content: @Composable () -> Unit) {
    val p = UsPalettes[paletteIndex.coerceIn(0, UsPalettes.lastIndex)]
    CompositionLocalProvider(
        LocalUsGradient provides Brush.linearGradient(listOf(p.gradStart, p.gradEnd))
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(p) else lightScheme(p),
            typography = UsTypography,
            shapes = UsShapes,
            content = content
        )
    }
}
