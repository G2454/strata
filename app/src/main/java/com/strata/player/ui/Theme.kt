package com.strata.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.strata.player.R

@Immutable
data class Tokens(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val sheet: Color,
    val line: Color,
    val line2: Color,
    val text: Color,
    val sub: Color,
    val faint: Color,
    val accent: Color,      // accent used for text/icons
    val accentFill: Color,  // accent used for fills
    val onAccent: Color,
    val soft: Color,
    val scrim: Color,
    val accentRaw: Color,   // the light accent, used for glows
)

fun tokens(dark: Boolean, light: Color, deep: Color): Tokens = if (dark) Tokens(
    dark = true,
    bg = Color(0xFF0D0E11), surface = Color(0xFF16181D), surface2 = Color(0xFF22252D), sheet = Color(0xFF1A1C22),
    line = Color(0x12FFFFFF), line2 = Color(0x2EFFFFFF), text = Color(0xFFF2F1EE), sub = Color(0xFFA7AAB1), faint = Color(0xFF888C94),
    accent = light, accentFill = light, onAccent = Color(0xFF0D0E11), soft = light.copy(alpha = 0.14f), scrim = Color(0x8C000000), accentRaw = light,
) else Tokens(
    dark = false,
    bg = Color(0xFFF3F2EF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE7E5E0), sheet = Color(0xFFFFFFFF),
    line = Color(0x1414141A), line2 = Color(0x3314141A), text = Color(0xFF16171B), sub = Color(0xFF53565D), faint = Color(0xFF676A71),
    accent = deep, accentFill = deep, onAccent = Color.White, soft = deep.copy(alpha = 0.11f), scrim = Color(0x5914141A), accentRaw = light,
)

val LocalTokens = staticCompositionLocalOf { tokens(true, Color(0xFF7FD1FF), Color(0xFF0A6593)) }

object Fonts {
    val display = FontFamily(
        Font(R.font.bricolage_600, FontWeight.SemiBold),
        Font(R.font.bricolage_700, FontWeight.Bold),
    )
    val body = FontFamily(
        Font(R.font.geist_400, FontWeight.Normal),
        Font(R.font.geist_500, FontWeight.Medium),
        Font(R.font.geist_600, FontWeight.SemiBold),
        Font(R.font.geist_600, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(R.font.geist_mono_400, FontWeight.Normal),
        Font(R.font.geist_mono_500, FontWeight.Medium),
    )
}

object Type {
    fun display(size: Int, weight: FontWeight = FontWeight.Bold) =
        TextStyle(fontFamily = Fonts.display, fontWeight = weight, fontSize = size.sp, letterSpacing = (-0.015).em, lineHeight = (size * 1.12).sp)

    fun body(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = Fonts.body, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.35).sp)

    fun mono(size: Number, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 0.sp) =
        TextStyle(fontFamily = Fonts.mono, fontWeight = weight, fontSize = size.toFloat().sp, letterSpacing = spacing, lineHeight = (size.toFloat() * 1.4f).sp)

    fun label(size: Number = 11) = mono(size, FontWeight.Normal, 0.1.em)
}

@Composable
fun StrataTheme(t: Tokens, content: @Composable () -> Unit) {
    val scheme = if (t.dark) darkColorScheme(
        primary = t.accentFill, onPrimary = t.onAccent, background = t.bg, surface = t.surface, onSurface = t.text, onBackground = t.text,
    ) else lightColorScheme(
        primary = t.accentFill, onPrimary = t.onAccent, background = t.bg, surface = t.surface, onSurface = t.text, onBackground = t.text,
    )
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
