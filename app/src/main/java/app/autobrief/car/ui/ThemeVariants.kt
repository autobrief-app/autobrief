package app.autobrief.car.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The token sets of the design variants A, B and C. The colour, type and shape values are copied from the design study's
// token files by a script; change them there, not here. Every text role keeps WCAG 4.5:1 on every surface of its set.

/** A text style from the token files: size in sp, weight, tracking in em, line height as a multiple of the size. */
private fun text(sizeSp: Int, weight: Int, trackingEm: Double, leading: Double, tabular: Boolean = false) = TextStyle(
    fontFamily = Pretendard, fontWeight = FontWeight(weight), fontSize = sizeSp.sp, lineHeight = (sizeSp * leading).sp,
    letterSpacing = trackingEm.em, fontFeatureSettings = if (tabular) "tnum, lnum" else null,
)

private val VariantANight = ColorRoles(
    background = Color(0xFF0B121C),
    surface = Color(0xFF111A26),
    surfaceVariant = Color(0xFF172231),
    surfaceRaised = Color(0xFF1D2939),
    outline = Color(0xFF5E6A7C),
    hairline = Color(0xFF273345),
    textPrimary = Color(0xFFF1ECE4),
    textSecondary = Color(0xFFBDB5A9),
    textTertiary = Color(0xFF999288),
    primary = Color(0xFFDCCDB7),
    onPrimary = Color(0xFF0B121C),
    accent = Color(0xFFC99A74),
    onAccent = Color(0xFF0B121C),
    positive = Color(0xFF7CC4A0),
    warning = Color(0xFFE2B565),
    critical = Color(0xFFF08A7A),
    chipBg = Color(0xFF1B2636),
    chipText = Color(0xFFE7DFD3),
    // trip purposes are steps of the primary colour; a trip still to classify warns, an unlogged gap is neutral
    purposeCommute = Color(0xFFDCCDB7), purposeBusiness = Color(0xFFDCCDB7).copy(alpha = 0.6f), purposePersonal = Color(0xFFDCCDB7).copy(alpha = 0.3f),
    purposeUnclassified = Color(0xFFE2B565), purposeGap = Color(0xFF999288),
    tintAlpha = CurrentNight.tintAlpha,
)

private val VariantADay = ColorRoles(
    background = Color(0xFFF3EFE9),
    surface = Color(0xFFFAF8F5),
    surfaceVariant = Color(0xFFEAE3D8),
    surfaceRaised = Color(0xFFFFFFFF),
    outline = Color(0xFF8E8576),
    hairline = Color(0xFFDCD3C6),
    textPrimary = Color(0xFF131A25),
    textSecondary = Color(0xFF454D5A),
    textTertiary = Color(0xFF5D636E),
    primary = Color(0xFF0F2B4F),
    onPrimary = Color(0xFFF6F3F2),
    accent = Color(0xFF86573C),
    onAccent = Color(0xFFFFFFFF),
    positive = Color(0xFF246B4C),
    warning = Color(0xFF7E5300),
    critical = Color(0xFFA93220),
    chipBg = Color(0xFFE6DED2),
    chipText = Color(0xFF1A2230),
    // trip purposes are steps of the primary colour; a trip still to classify warns, an unlogged gap is neutral
    purposeCommute = Color(0xFF0F2B4F), purposeBusiness = Color(0xFF0F2B4F).copy(alpha = 0.6f), purposePersonal = Color(0xFF0F2B4F).copy(alpha = 0.3f),
    purposeUnclassified = Color(0xFF7E5300), purposeGap = Color(0xFF5D636E),
    tintAlpha = CurrentDay.tintAlpha,
)

/** Midnight navy and sand: a cabin at dusk, warm sand light, hairlines, large light numerals. */
internal val VariantATokens = AutoBriefTokens(
    night = VariantANight,
    day = VariantADay,
    type = TypeScale(
        display = text(64, 300, -0.025, 1.08, tabular = true),
        headline = text(40, 500, -0.015, 1.2),
        title = text(30, 500, -0.01, 1.25),
        body = text(26, 400, 0.0, 1.45),
        label = text(20, 500, 0.005, 1.3),
        bodyStrong = text(26, 500, 0.0, 1.45),
        caption = text(22, 400, 0.0, 1.45),
        glance = text(96, 300, -0.03, 1.04, tabular = true),
    ),
    shape = ShapeTokens(
        cornerSmall = 4.dp, cornerMedium = 8.dp, cornerLarge = 12.dp, strokeHairline = 1.dp,
        cornerControl = 4.dp, cornerRow = 12.dp, cornerHero = 12.dp, strokeEmphasis = 1.5.dp,
    ),
    chipStyle = ChipStyle.PIXEL_PLATE,
    cardStyle = CardStyle.HAIRLINE,
    detail = DetailStyle.QUIET,
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
    tintAlpha = CurrentNight.tintAlpha,
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
    tintAlpha = CurrentDay.tintAlpha,
)

/** White minimal and deep navy: light-grey ground, white plates, one navy voice. */
internal val VariantBTokens = AutoBriefTokens(
    night = VariantBNight,
    day = VariantBDay,
    type = TypeScale(
        display = text(64, 300, -0.03, 1.08, tabular = true),
        headline = text(40, 500, -0.02, 1.2),
        title = text(30, 600, -0.01, 1.25),
        body = text(26, 400, -0.005, 1.45),
        label = text(20, 500, 0.01, 1.3),
        bodyStrong = text(26, 500, -0.005, 1.45),
        caption = text(22, 400, 0.0, 1.45),
        glance = text(96, 200, -0.035, 1.04, tabular = true),
    ),
    shape = ShapeTokens(
        cornerSmall = 2.dp, cornerMedium = 4.dp, cornerLarge = 8.dp, strokeHairline = 1.dp,
        cornerControl = 2.dp, cornerRow = 8.dp, cornerHero = 8.dp, strokeEmphasis = 1.5.dp,
    ),
    chipStyle = ChipStyle.PIXEL_TEXT,
    cardStyle = CardStyle.FLAT,
    detail = DetailStyle.QUIET,
)

private val VariantCNight = ColorRoles(
    background = Color(0xFF060A0F),
    surface = Color(0xFF0F1A26).copy(alpha = 0.82f),
    surfaceVariant = Color(0xFF142233).copy(alpha = 0.86f),
    surfaceRaised = Color(0xFF1A2B3E).copy(alpha = 0.9f),
    outline = Color(0xFF4F6A85),
    hairline = Color(0xFF3E7FA8),
    textPrimary = Color(0xFFEAF2F8),
    textSecondary = Color(0xFFAABACA),
    textTertiary = Color(0xFF8798AA),
    primary = Color(0xFF86C9EA),
    onPrimary = Color(0xFF04121C),
    accent = Color(0xFF3CB4DA),
    onAccent = Color(0xFF03121A),
    positive = Color(0xFF62CBA3),
    warning = Color(0xFFE9BA5E),
    critical = Color(0xFFFF8378),
    chipBg = Color(0xFF122130).copy(alpha = 0.88f),
    chipText = Color(0xFFD6E6F2),
    // trip purposes are steps of the primary colour; a trip still to classify warns, an unlogged gap is neutral
    purposeCommute = Color(0xFF86C9EA), purposeBusiness = Color(0xFF86C9EA).copy(alpha = 0.6f), purposePersonal = Color(0xFF86C9EA).copy(alpha = 0.3f),
    purposeUnclassified = Color(0xFFE9BA5E), purposeGap = Color(0xFF8798AA),
    tintAlpha = CurrentNight.tintAlpha,
)

private val VariantCDay = ColorRoles(
    background = Color(0xFFE8EDF2),
    surface = Color(0xFFF7F9FB).copy(alpha = 0.84f),
    surfaceVariant = Color(0xFFEDF1F5).copy(alpha = 0.88f),
    surfaceRaised = Color(0xFFFFFFFF).copy(alpha = 0.92f),
    outline = Color(0xFF748392),
    hairline = Color(0xFF9FBCD3),
    textPrimary = Color(0xFF0C1622),
    textSecondary = Color(0xFF3D4B59),
    textTertiary = Color(0xFF56636F),
    primary = Color(0xFF0A5A83),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF006E91),
    onAccent = Color(0xFFFFFFFF),
    positive = Color(0xFF1A6F4E),
    warning = Color(0xFF7C5300),
    critical = Color(0xFFA8241C),
    chipBg = Color(0xFFDAE4ED).copy(alpha = 0.9f),
    chipText = Color(0xFF0F1D2B),
    // trip purposes are steps of the primary colour; a trip still to classify warns, an unlogged gap is neutral
    purposeCommute = Color(0xFF0A5A83), purposeBusiness = Color(0xFF0A5A83).copy(alpha = 0.6f), purposePersonal = Color(0xFF0A5A83).copy(alpha = 0.3f),
    purposeUnclassified = Color(0xFF7C5300), purposeGap = Color(0xFF56636F),
    tintAlpha = CurrentDay.tintAlpha,
)

/** Glass HUD: dark translucent panels, cool light lines. */
internal val VariantCTokens = AutoBriefTokens(
    night = VariantCNight,
    day = VariantCDay,
    type = TypeScale(
        display = text(64, 400, -0.015, 1.08, tabular = true),
        headline = text(40, 500, -0.01, 1.2),
        title = text(30, 500, -0.005, 1.25),
        body = text(26, 400, 0.0, 1.45),
        label = text(20, 600, 0.06, 1.3),
        bodyStrong = text(26, 500, 0.0, 1.45),
        caption = text(22, 400, 0.0, 1.45),
        glance = text(96, 400, -0.02, 1.04, tabular = true),
    ),
    shape = ShapeTokens(
        cornerSmall = 6.dp, cornerMedium = 12.dp, cornerLarge = 16.dp, strokeHairline = 1.dp,
        cornerControl = 6.dp, cornerRow = 16.dp, cornerHero = 16.dp, strokeEmphasis = 1.5.dp,
    ),
    chipStyle = ChipStyle.PIXEL_GLASS,
    cardStyle = CardStyle.GLASS,
    detail = DetailStyle.QUIET,
)
