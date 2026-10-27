package app.bulwark.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.bulwark.android.R

/** Bulwark brand palette (all text pairs meet WCAG AA; most meet AAA). */
object BulwarkColors {
    val Blue = Color(0xFF1F4E8C)
    val Sky = Color(0xFF8FB4E8)
    val Steel = Color(0xFF5B6675)
    val Silver = Color(0xFFA7B0BC)
    val Mist = Color(0xFFF2F4F7)
    val Ink = Color(0xFF1A1F26)
    val Navy = Color(0xFF0F1B2D)
    val Slate = Color(0xFF1C2430)
    val Paper = Color(0xFFE6EAF0)
}

private fun archivo(weight: Int) = Font(
    R.font.archivo, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun sourceSans(weight: Int) = Font(
    R.font.source_sans_3, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Archivo = FontFamily(archivo(700), archivo(800))
val SourceSans = FontFamily(sourceSans(400), sourceSans(600))
val SourceCodePro = FontFamily(
    Font(R.font.source_code_pro, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = SourceSans, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = SourceSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontFamily = SourceSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
)

private val light = lightColorScheme(
    primary = BulwarkColors.Blue, onPrimary = Color.White,
    background = BulwarkColors.Mist, onBackground = BulwarkColors.Ink,
    surface = Color.White, onSurface = BulwarkColors.Ink, onSurfaceVariant = BulwarkColors.Steel,
)

private val dark = darkColorScheme(
    primary = BulwarkColors.Sky, onPrimary = BulwarkColors.Navy,
    background = BulwarkColors.Navy, onBackground = BulwarkColors.Paper,
    surface = BulwarkColors.Slate, onSurface = BulwarkColors.Paper, onSurfaceVariant = BulwarkColors.Silver,
)

@Composable
fun BulwarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = typography, content = content)
}
