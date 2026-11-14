package app.autobrief.car.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The token set of the shipped look. Colours, weights, tracking and corners come from the design study's token file;
// text sizes keep the car-display floor (every style at least 24sp). Every text role keeps WCAG 4.5:1 on every surface.

/** A text style: size and line height in sp, weight, tracking in em; [tabular] sets lining tabular figures. */
private fun text(sizeSp: Int, lineSp: Int, weight: Int, trackingEm: Double, tabular: Boolean = false) = TextStyle(
    fontFamily = Pretendard, fontWeight = FontWeight(weight), fontSize = sizeSp.sp, lineHeight = lineSp.sp,
    letterSpacing = trackingEm.em, fontFeatureSettings = if (tabular) "tnum, lnum" else null,
)

private val VariantBNight = ColorRoles(
    background = Color(0xFF0E1116),
    surface = Color(0xFF151920),
    surfaceVariant = Color(0xFF1C2129),
    surfaceRaised = Color(0xFF232933),
    outline = Color(0xFF636B78),
    hairline = Color(0xFF272D37),
    textPrimary = Color(0xFFF2F3F5),
    textSecondary = Color(0xFFB5BBC4),
    textTertiary = Color(0xFF8F96A0),
    primary = Color(0xFFB3C8E8),
    onPrimary = Color(0xFF0B1B33),
    accent = Color(0xFF8EADDB),
    onAccent = Color(0xFF0B1B33),
    positive = Color(0xFF6CC496),
    warning = Color(0xFFE3B256),
    critical = Color(0xFFF3807A),
    chipBg = Color(0xFF1E242E),
    chipText = Color(0xFFE3E7EC),
    // trip purposes are steps of the primary colour; a trip still to classify warns, an unlogged gap is neutral
    purposeCommute = Color(0xFFB3C8E8), purposeBusiness = Color(0xFFB3C8E8).copy(alpha = 0.6f), purposePersonal = Color(0xFFB3C8E8).copy(alpha = 0.3f),
    purposeUnclassified = Color(0xFFE3B256), purposeGap = Color(0xFF8F96A0),
    tintAlpha = 0.16f,
)

private val VariantBDay = ColorRoles(
    background = Color(0xFFF5F5F3),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEDEA),
    surfaceRaised = Color(0xFFFFFFFF),
    outline = Color(0xFF848991),
    hairline = Color(0xFFE1E2E4),
    textPrimary = Color(0xFF11161D),
    textSecondary = Color(0xFF474E58),
    textTertiary = Color(0xFF656B74),
    primary = Color(0xFF0B2A52),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF1D4A85),
    onAccent = Color(0xFFFFFFFF),
    positive = Color(0xFF1D6B4A),
    warning = Color(0xFF7F5400),
    critical = Color(0xFFAE2318),
    chipBg = Color(0xFFECEEF1),
    chipText = Color(0xFF16202E),
    // trip purposes are steps of the primary colour; a trip still to classify warns, an unlogged gap is neutral
    purposeCommute = Color(0xFF0B2A52), purposeBusiness = Color(0xFF0B2A52).copy(alpha = 0.6f), purposePersonal = Color(0xFF0B2A52).copy(alpha = 0.3f),
    purposeUnclassified = Color(0xFF7F5400), purposeGap = Color(0xFF656B74),
    tintAlpha = 0.12f,
)

/** White minimal and deep navy: light-grey ground, white plates, one navy voice. */
internal val VariantBTokens = AutoBriefTokens(
    night = VariantBNight,
    day = VariantBDay,
    type = TypeScale(
        display = text(64, 70, 300, -0.03, tabular = true),
        headline = text(34, 42, 500, -0.02),
        title = text(28, 36, 600, -0.01),
        body = text(24, 32, 400, -0.005),
        label = text(24, 30, 500, 0.01),
        bodyStrong = text(24, 32, 500, -0.005),
        caption = text(24, 32, 400, 0.0),
    ),
    shape = ShapeTokens(
        cornerSmall = 2.dp, cornerMedium = 4.dp, cornerLarge = 8.dp, strokeHairline = 1.dp,
        cornerControl = 2.dp, cornerRow = 8.dp, cornerHero = 8.dp, strokeEmphasis = 1.5.dp,
    ),
    chipStyle = ChipStyle.PIXEL_TEXT,
    cardStyle = CardStyle.FLAT,
    detail = DetailStyle.QUIET,
)
