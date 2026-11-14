package app.autobrief.car.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.autobrief.car.model.TripPurpose

/**
 * The colour roles of one mode (night or day). Screens read roles, never hex values, so a design variant is a new set of
 * these. The purpose colours match the fleet console so the two screens read as one product.
 */
@Immutable
data class ColorRoles(
    /** Behind everything (the screen). */
    val background: Color,
    /** Cards (Panel, trip rows, the driving card) and the navigation rail. */
    val surface: Color,
    /** Insets on a card: form rows, toggle rows, the distance bar track, disabled buttons, the driving note. */
    val surfaceVariant: Color,
    /** An ELEVATED card's fill ([CardStyle.ELEVATED]); OUTLINE cards use [surface]. */
    val surfaceRaised: Color,
    /** Default border and divider colour. */
    val outline: Color,
    /** A fainter border (the unselected navigation tab's rim). */
    val hairline: Color,
    val textPrimary: Color,
    /** Captions and secondary labels. */
    val textSecondary: Color,
    /** Disabled text, inactive status indicators and values that read "off". */
    val textTertiary: Color,
    /** What can be pressed or is selected: filled buttons, selected chips and tabs, focus, switches, call-to-action text. */
    val primary: Color,
    val onPrimary: Color,
    /** Emphasis in content: hero figures, live-state pills, the app mark, emphasised card borders. */
    val accent: Color,
    val onAccent: Color,
    val positive: Color,
    val warning: Color,
    val critical: Color,
    /** An unselected choice chip's fill. */
    val chipBg: Color,
    /** An unselected choice chip's label. */
    val chipText: Color,
    val purposeCommute: Color,
    val purposeBusiness: Color,
    val purposePersonal: Color,
    val purposeUnclassified: Color,
    val purposeGap: Color,
    /** Alpha of a colour's tint behind its own text (chips, pills, the selected tab); lower by day so the text keeps 4.5:1. */
    val tintAlpha: Float,
    /** Alpha of a coloured border that marks a state (filled status pill, switched-on row, selected tab, the ratio row). */
    val emphasisBorderAlpha: Float = 0.55f,
    /** Alpha of a purpose chip's border. */
    val chipBorderAlpha: Float = 0.45f,
    /** Alpha of a tonal button's border. */
    val tonalBorderAlpha: Float = 0.65f,
    /** Alpha of [surfaceVariant] behind an outlined button. */
    val outlinedFillAlpha: Float = 0.5f,
    /** The faint line under the top edge of a [CardStyle.GLASS] card. */
    val glassHighlight: Color = Color.White.copy(alpha = 0.10f),
) {
    fun purpose(p: TripPurpose): Color = when (p) {
        TripPurpose.COMMUTE -> purposeCommute
        TripPurpose.BUSINESS -> purposeBusiness
        TripPurpose.PERSONAL -> purposePersonal
        TripPurpose.UNCLASSIFIED -> purposeUnclassified
        TripPurpose.UNLOGGED_GAP -> purposeGap
    }
}

/** The type scale. Sizes are for a car display read at arm's length (AAOS: at least 24sp body, 76dp touch targets). */
@Immutable
data class TypeScale(
    /** The one big figure of a card (distance, ratio, amount). */
    val display: TextStyle,
    /** Card and screen titles. */
    val headline: TextStyle,
    /** Section titles and button labels. */
    val title: TextStyle,
    val body: TextStyle,
    /** Chips, pills, tabs and table headers. */
    val label: TextStyle,
    val bodyStrong: TextStyle = body.copy(fontWeight = FontWeight.Bold),
    /** Secondary lines (button sub-labels, field labels, notes); AAOS keeps it at the body size. */
    val caption: TextStyle = body,
    /** How much larger than [display] the driving view's distance is drawn when [glance] is not set. */
    val glanceScale: Float = 1.6f,
    /** The driving view's distance, read at a glance while driving. */
    val glance: TextStyle = display.copy(fontSize = display.fontSize * glanceScale, lineHeight = display.lineHeight * glanceScale),
)

/**
 * Corner radii and stroke widths. A variant sets the four base values; the others follow them unless set too
 * (the defaults reproduce today's in-between radii and heavier strokes from the base values).
 */
@Immutable
data class ShapeTokens(
    /** Purpose chips, the distance bar, the app mark's inner plate. */
    val cornerSmall: Dp,
    /** Buttons, toggle rows, navigation tabs, the driving note. */
    val cornerMedium: Dp,
    /** Cards (Panel). */
    val cornerLarge: Dp,
    /** Default border and divider width. */
    val strokeHairline: Dp,
    /** Status pills, choice chips, form rows. */
    val cornerControl: Dp = (cornerSmall + cornerMedium) / 2,
    /** Trip list rows. */
    val cornerRow: Dp = (cornerMedium + cornerLarge) / 2,
    /** The driving view's card. */
    val cornerHero: Dp = cornerLarge + (cornerLarge - cornerMedium) / 2,
    /** Selected chips, outlined and tonal buttons, the app mark's rim. */
    val strokeEmphasis: Dp = strokeHairline * 1.5f,
    /** A card's coloured border and the settings save bar's divider. */
    val strokeStrong: Dp = strokeHairline * 2,
    /** Width of a chip's leading bar ([ChipStyle.LEADING_BAR]); its ends are fully rounded. */
    val indicatorWidth: Dp = 3.dp,
    /** Width of a distance-bar legend swatch; its ends are fully rounded. */
    val swatchWidth: Dp = 4.dp,
    /** Side of the square status pip of the PIXEL chip styles. */
    val pipSize: Dp = 8.dp,
)

/** How status pills, purpose chips and choice chips are decorated. */
enum class ChipStyle {
    /** A tinted fill, a thin coloured border and a short coloured bar before the label (status and purpose chips). */
    LEADING_BAR,

    /** No fill and no bar: a coloured border only. */
    OUTLINE,

    /** A tinted fill only: no border, no bar. */
    FILLED_SUBTLE,

    /**
     * A plain plate: [ColorRoles.chipBg] inside an [ColorRoles.outline] hairline, the label in [ColorRoles.chipText]. The only
     * ornament is a square pip in the chip's colour, shown for a status (positive, warning, critical) and for a trip purpose.
     * A selected choice chip fills with [ColorRoles.primary].
     */
    PIXEL_PLATE,

    /** As [PIXEL_PLATE] without the fill: text inside an outline. */
    PIXEL_TEXT,

    /**
     * As [PIXEL_PLATE] inside a [ColorRoles.hairline] light line; a selected choice chip keeps its fill and takes an
     * [ColorRoles.accent] border and label.
     */
    PIXEL_GLASS;

    /** The PIXEL styles: a square pip instead of colour fills and bars. */
    val pixel: Boolean get() = this == PIXEL_PLATE || this == PIXEL_TEXT || this == PIXEL_GLASS
}

/** How cards (Panel, trip rows, the driving card) are set off from the background. */
enum class CardStyle {
    /** [ColorRoles.surface] inside a hairline [ColorRoles.outline] border. */
    OUTLINE,

    /** [ColorRoles.surfaceRaised] on a shadow of [AutoBriefTokens.cardElevation]; a border only for an emphasis colour. */
    ELEVATED,

    /** [ColorRoles.surface] inside a [ColorRoles.hairline] border: depth comes from the tone steps, not from a line. */
    HAIRLINE,

    /** [ColorRoles.surface] with no border; a border only for an emphasis colour. */
    FLAT,

    /** A glass panel: a translucent [ColorRoles.surface] inside a [ColorRoles.hairline] light line, with a faint top highlight. */
    GLASS,
}

/** How much framing the details carry. */
enum class DetailStyle {
    /** Bordered status pills, inset rows in their own boxes, purpose-coloured buttons, the "km" tile mark, filled icons. */
    BOXED,

    /**
     * One hairline per group and no box inside a box: statuses and purposes as plain text after a square pip, the status
     * header as one quiet line, rows on a rule, neutral purpose buttons, the pixel mark and line icons.
     */
    QUIET,
}

/** Everything the UI's look is made of, for one design variant. */
@Immutable
data class AutoBriefTokens(
    val night: ColorRoles,
    val day: ColorRoles,
    val type: TypeScale,
    val shape: ShapeTokens,
    val chipStyle: ChipStyle,
    val cardStyle: CardStyle,
    /** Shadow depth of an ELEVATED card; OUTLINE cards ignore it. */
    val cardElevation: Dp = 4.dp,
    val detail: DetailStyle = DetailStyle.BOXED,
) {
    fun colors(night: Boolean): ColorRoles = if (night) this.night else day
}
