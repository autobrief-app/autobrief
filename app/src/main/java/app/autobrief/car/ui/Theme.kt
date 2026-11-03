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
    /** Alpha of a colour's tint behind its own text (chips, pills, the selected tab); lower by day so the text keeps 4.5:1. */
    val tint: Float,
) {
    fun purpose(p: TripPurpose): Color = when (p) {
        TripPurpose.COMMUTE -> commute
        TripPurpose.BUSINESS -> business
        TripPurpose.PERSONAL -> personal
        TripPurpose.UNCLASSIFIED -> pending
        TripPurpose.UNLOGGED_GAP -> gap
    }
}

// Every text colour below keeps WCAG 4.5:1 (AA) on bg, panel, panelHigh and on its own tint.
val NightPalette = Palette(
    bg = Color(0xFF0A0C10), panel = Color(0xFF11151C), panelHigh = Color(0xFF181E27), line = Color(0xFF252E3D),
    text = Color(0xFFF2F5F9), muted = Color(0xFF9AA6B8), accent = Color(0xFF66ADFF),
    commute = Color(0xFF70B5FF), business = Color(0xFF3CC9B0), personal = Color(0xFFDDA77B),
    pending = Color(0xFFF4A93B), gap = Color(0xFFA3B1C6), ok = Color(0xFF4FD685), danger = Color(0xFFF88078),
    onAccent = Color(0xFF0A0C10), tint = 0.16f,
)

val DayPalette = Palette(
    bg = Color(0xFFF3F5F8), panel = Color(0xFFFFFFFF), panelHigh = Color(0xFFE8ECF2), line = Color(0xFFCBD2DE),
    text = Color(0xFF0E131B), muted = Color(0xFF4A5568), accent = Color(0xFF0051B8),
    commute = Color(0xFF0B5394), business = Color(0xFF0B6B58), personal = Color(0xFF8A4F1D),
    pending = Color(0xFF965000), gap = Color(0xFF475569), ok = Color(0xFF166534), danger = Color(0xFFB91C1C),
    onAccent = Color(0xFFFFFFFF), tint = 0.12f,
)

val LocalPalette = staticCompositionLocalOf { NightPalette }

/** Sizes for a car display read at arm's length (AAOS: at least 24sp body, 76dp touch targets). */
object Type {
    val hero = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 70.sp)
    val title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp)
    val heading = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp)
    val body = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp)
    val bodyBold = body.copy(fontWeight = FontWeight.Bold)
    val small = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp)
    val label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
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
