package app.autobrief.car.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
import androidx.compose.ui.unit.sp
import app.autobrief.car.R
import app.autobrief.car.model.TripPurpose

/** Bundled Pretendard (SIL OFL 1.1, unmodified; licence in assets/licenses). */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** Colour tokens; the purpose colours match the fleet console so the two screens read as one product. */
@Immutable
data class Palette(
    val bg: Color,
    val panel: Color,
    val panelHigh: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val commute: Color,
    val business: Color,
    val personal: Color,
    val pending: Color,
    val gap: Color,
    val ok: Color,
    val danger: Color,
    val onAccent: Color,
) {
    fun purpose(p: TripPurpose): Color = when (p) {
        TripPurpose.COMMUTE -> commute
        TripPurpose.BUSINESS -> business
        TripPurpose.PERSONAL -> personal
        TripPurpose.UNCLASSIFIED -> pending
        TripPurpose.UNLOGGED_GAP -> gap
    }
}

val NightPalette = Palette(
    bg = Color(0xFF0B0F17), panel = Color(0xFF151B26), panelHigh = Color(0xFF1D2533), line = Color(0xFF2A3445),
    text = Color(0xFFE8EDF5), muted = Color(0xFF9AA6B8), accent = Color(0xFF4FD1C5),
    commute = Color(0xFF6FB7FF), business = Color(0xFF4FD1C5), personal = Color(0xFFA0A8B8),
    pending = Color(0xFFFFB547), gap = Color(0xFF8E7FB0), ok = Color(0xFF5BD68A), danger = Color(0xFFFF6B6B),
    onAccent = Color(0xFF06231F),
)

val DayPalette = Palette(
    bg = Color(0xFFF3F5F9), panel = Color(0xFFFFFFFF), panelHigh = Color(0xFFEDF1F7), line = Color(0xFFD5DCE6),
    text = Color(0xFF111827), muted = Color(0xFF5B6576), accent = Color(0xFF0E9F94),
    commute = Color(0xFF2F7FD8), business = Color(0xFF0E9F94), personal = Color(0xFF6B7385),
    pending = Color(0xFFC77700), gap = Color(0xFF6E5C9A), ok = Color(0xFF1E9E55), danger = Color(0xFFD64545),
    onAccent = Color(0xFFFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { NightPalette }

/** Sizes for a car display read at arm's length (AAOS: at least 24sp body, 76dp touch targets). */
object Type {
    val hero = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 70.sp)
    val title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp)
    val heading = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp)
    val body = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp)
    val bodyBold = body.copy(fontWeight = FontWeight.Bold)
    val small = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 27.sp)
    val label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
}

@Composable
fun AutoBriefTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (night) NightPalette else DayPalette
    val scheme = if (night) {
        darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.panel, onSurface = p.text, outline = p.line)
    } else {
        lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.panel, onSurface = p.text, outline = p.line)
    }
    val typography = Typography(
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.small,
        labelLarge = Type.label, titleLarge = Type.heading, headlineMedium = Type.title,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
