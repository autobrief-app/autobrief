package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.autobrief.car.domain.KST
import app.autobrief.car.domain.LogbookSummary
import app.autobrief.car.model.TripPurpose
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MDHM: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

fun hm(epochMs: Long): String = HM.format(Instant.ofEpochMilli(epochMs).atZone(KST))
fun mdhm(epochMs: Long): String = MDHM.format(Instant.ofEpochMilli(epochMs).atZone(KST))

/** Thousands-grouped integer ("1,650") without ICU/Locale Formatter allocation on the UI thread. */
fun comma(n: Long): String {
    if (n in -999L..999L) return n.toString()
    val sign = if (n < 0) "-" else ""
    val digits = if (n == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(n).toString()
    val len = digits.length
    val sb = StringBuilder(len + (len - 1) / 3 + sign.length)
    sb.append(sign)
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return sb.toString()
}

fun comma(n: Int): String = comma(n.toLong())

fun km1(v: Double): String {
    if (!v.isFinite()) return v.toString()
    val tenths = BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).movePointRight(1).toLong()
    val sign = if (tenths < 0) "-" else ""
    val a = kotlin.math.abs(tenths)
    return "$sign${comma(a / 10)}.${a % 10}"
}

/** Korean 로/으로 after [word]: 으로 after a final consonant other than ㄹ ("출퇴근으로", "업무로", "비업무로"). */
fun withRo(word: String): String {
    val c = word.lastOrNull { it in '가'..'힣' } ?: return "${word}로"
    val jong = (c.code - 0xAC00) % 28
    return if (jong == 0 || jong == 8) "${word}로" else "${word}으로"
}
fun pct(r: Double?): String = r?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "-"

/** Korean money in 만원 units for display ("117만원"); below 10,000 KRW shows in 원. */
fun manwon(krw: Long): String {
    val sign = if (krw < 0) "-" else ""
    val a = kotlin.math.abs(krw)
    return if (a >= 10_000) "$sign${comma((a + 5_000) / 10_000)}만원" else "$sign${comma(a)}원"
}

/**
 * True in the compact window: under [COMPACT_UNDER_DP] wide, which is what this app gets when the display shows two apps
 * side by side (about 853dp of the 2560dp display). Set by the screen root from the window's measured width.
 */
val LocalCompact = compositionLocalOf { false }

/** Two columns side by side, the first [startWeight] times as wide as the second; in the compact window the second follows under the first. */
@Composable
fun TwoColumns(startWeight: Float = 1f, gap: Dp = 24.dp, start: @Composable ColumnScope.() -> Unit, end: @Composable ColumnScope.() -> Unit) {
    if (LocalCompact.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            start()
            end()
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.weight(startWeight), verticalArrangement = Arrangement.spacedBy(gap), content = start)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap), content = end)
    }
}

/**
 * A row of short cells. In the compact window the cells that do not fit move to the next line instead of being squeezed
 * into the space that is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CellRow(
    modifier: Modifier = Modifier,
    gap: Dp = 16.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalCompact.current) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    } else {
        Row(modifier, verticalAlignment = verticalAlignment, horizontalArrangement = Arrangement.spacedBy(gap), content = content)
    }
}

/** A label at the start of a line and its value at the end; in the compact window the value moves under the label when both do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelValueRow(modifier: Modifier = Modifier, label: @Composable () -> Unit, value: @Composable () -> Unit) {
    if (LocalCompact.current) {
        FlowRow(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.align(Alignment.CenterVertically)) { label() }
            Box(Modifier.align(Alignment.CenterVertically)) { value() }
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            label()
            Spacer(Modifier.weight(1f))
            value()
        }
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color? = null,
    border: Color? = null,
    padding: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .cardFrame(RoundedCornerShape(AutoBriefTheme.shape.cornerLarge), emphasis = border, fill = color)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * A card's frame in the active [CardStyle]. OUTLINE: the surface inside a hairline outline, or a strong stroke of an
 * [emphasis] colour. ELEVATED: the raised surface on a shadow, stroked only in an [emphasis] colour. [fill] replaces the
 * style's surface.
 */
@Composable
fun Modifier.cardFrame(shape: Shape, emphasis: Color? = null, fill: Color? = null): Modifier {
    val t = AutoBriefTheme.tokens
    val p = AutoBriefTheme.colors
    val s = t.shape
    return when (t.cardStyle) {
        CardStyle.OUTLINE ->
            clip(shape).background(fill ?: p.surface).border(if (emphasis != null) s.strokeStrong else s.strokeHairline, emphasis ?: p.outline, shape)
        CardStyle.ELEVATED -> shadow(t.cardElevation, shape).background(fill ?: p.surfaceRaised).edge(s.strokeStrong, emphasis, shape)
        CardStyle.HAIRLINE ->
            clip(shape).background(fill ?: p.surface).border(if (emphasis != null) s.strokeStrong else s.strokeHairline, emphasis ?: p.hairline, shape)
        CardStyle.FLAT -> clip(shape).background(fill ?: p.surface).edge(s.strokeStrong, emphasis, shape)
        CardStyle.GLASS ->
            clip(shape)
                .background(fill ?: p.surface)
                .drawBehind { drawRect(p.glassHighlight, Offset(0f, s.strokeHairline.toPx()), Size(size.width, s.strokeHairline.toPx())) }
                .border(if (emphasis != null) s.strokeStrong else s.strokeHairline, emphasis ?: p.hairline, shape)
    }
}

/** A border of [color], or none when [color] is null. */
private fun Modifier.edge(width: Dp, color: Color?, shape: Shape): Modifier = if (color == null) this else border(width, color, shape)

/**
 * Korean wraps between words, never between the syllables of one ("다/음 해로"): an invisible U+2060 WORD JOINER,
 * which the Unicode line-break rules never split, glues every Hangul character to its non-space neighbours. (Phrase line
 * breaking, LineBreak.WordBreak.Phrase, does not change Korean wrapping on API 34.) A word longer than the line still breaks.
 */
fun keepWords(text: String): String {
    val n = text.length
    if (n < 2) return text
    val sb = StringBuilder(n * 2)
    var prev = text[0]
    sb.append(prev)
    for (i in 1 until n) {
        val c = text[i]
        if (!prev.isWhitespace() && !c.isWhitespace() && (prev in '가'..'힣' || c in '가'..'힣')) {
            sb.append('\u2060')
        }
        sb.append(c)
        prev = c
    }
    return if (sb.length == n) text else sb.toString()
}

@Composable
fun Txt(text: String, style: TextStyle = Type.body, color: Color = AutoBriefTheme.colors.textPrimary, modifier: Modifier = Modifier) {
    // the semantics carry the plain text, so TalkBack and tests read the words without the joiners
    Box(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        Text(remember(text) { keepWords(text) }, style = style, color = color)
    }
}

@Composable
fun Muted(
    text: String,
    style: TextStyle = Type.caption,
    // QUIET: explanations step back to the tertiary tone, so figures and titles lead
    color: Color = if (AutoBriefTheme.quiet) AutoBriefTheme.colors.textTertiary else AutoBriefTheme.colors.textSecondary,
) = Txt(text, style, color)

/** An instrument status readout cell: the label in a frame decorated per [ChipStyle] (LEADING_BAR: a vertical indicator bar). */
@Composable
fun StatusPill(label: String, color: Color, filled: Boolean = false, header: Boolean = false) {
    val p = AutoBriefTheme.colors
    if (AutoBriefTheme.quiet) {
        // plain text; the pip marks a status or an emphasised state; [header]: the quieter line beside the app title
        val status = color == p.positive || color == p.warning || color == p.critical
        val text = when {
            header -> if (filled) p.textSecondary else p.textTertiary
            filled -> p.textPrimary
            else -> p.textSecondary
        }
        PipLabel(label, color.takeIf { filled || status }, text, padV = 9.dp, gap = 10.dp)
        return
    }
    val text = if (filled) color else p.textPrimary
    val style = AutoBriefTheme.tokens.chipStyle
    val look = when (style) {
        ChipStyle.LEADING_BAR -> ChipLook(
            fill = if (filled) color.copy(alpha = p.tintAlpha) else p.surface,
            edge = if (filled) color.copy(alpha = p.emphasisBorderAlpha) else p.outline,
            label = text,
            bar = true,
        )
        ChipStyle.OUTLINE -> ChipLook(fill = Color.Transparent, edge = color, label = text, bar = false)
        ChipStyle.FILLED_SUBTLE -> ChipLook(fill = color.copy(alpha = p.tintAlpha), edge = null, label = text, bar = false)
        // the pip marks a status only; an emphasised pill is told by its border
        ChipStyle.PIXEL_PLATE, ChipStyle.PIXEL_TEXT, ChipStyle.PIXEL_GLASS ->
            pixelLook(style, p, edge = if (filled) color else null, pip = color == p.positive || color == p.warning || color == p.critical)
    }
    ChipFrame(label, color, look, RoundedCornerShape(AutoBriefTheme.shape.cornerControl), padH = 16.dp, padV = 9.dp, barHeight = 20.dp, barGap = 10.dp)
}

@Composable
fun PurposeChip(purpose: TripPurpose) {
    val p = AutoBriefTheme.colors
    val c = p.purpose(purpose)
    if (AutoBriefTheme.quiet) {
        PipLabel(purpose.label, c, p.textPrimary, padV = 5.dp, gap = 8.dp)
        return
    }
    val style = AutoBriefTheme.tokens.chipStyle
    val look = when (style) {
        ChipStyle.LEADING_BAR -> ChipLook(fill = c.copy(alpha = p.tintAlpha), edge = c.copy(alpha = p.chipBorderAlpha), label = c, bar = true)
        ChipStyle.OUTLINE -> ChipLook(fill = Color.Transparent, edge = c, label = c, bar = false)
        ChipStyle.FILLED_SUBTLE -> ChipLook(fill = c.copy(alpha = p.tintAlpha), edge = null, label = c, bar = false)
        ChipStyle.PIXEL_PLATE, ChipStyle.PIXEL_TEXT, ChipStyle.PIXEL_GLASS -> pixelLook(style, p, edge = null, pip = true)
    }
    ChipFrame(purpose.label, c, look, RoundedCornerShape(AutoBriefTheme.shape.cornerSmall), padH = 12.dp, padV = 5.dp, barHeight = 18.dp, barGap = 8.dp)
}

/** How a coloured chip draws: its fill, its border (null: none), its label colour and whether the leading bar or the square pip shows. */
private data class ChipLook(val fill: Color, val edge: Color?, val label: Color, val bar: Boolean, val pip: Boolean = false)

/** A PIXEL-style chip: a neutral plate whose only colour is its square [pip] and, when [edge] is set, its border. */
private fun pixelLook(style: ChipStyle, p: ColorRoles, edge: Color?, pip: Boolean) = ChipLook(
    fill = if (style == ChipStyle.PIXEL_TEXT) Color.Transparent else p.chipBg,
    edge = edge ?: if (style == ChipStyle.PIXEL_GLASS) p.hairline else p.outline,
    label = p.chipText,
    bar = false,
    pip = pip,
)

/** A status or purpose without a plate: a square pip in [pip] colour (none when null), then the label. */
@Composable
private fun PipLabel(label: String, pip: Color?, text: Color, padV: Dp, gap: Dp) {
    Row(Modifier.padding(vertical = padV), verticalAlignment = Alignment.CenterVertically) {
        if (pip != null) {
            Box(Modifier.size(AutoBriefTheme.shape.pipSize).background(pip))
            Spacer(Modifier.width(gap))
        }
        Txt(label, Type.label, text)
    }
}

@Composable
private fun ChipFrame(label: String, color: Color, look: ChipLook, shape: Shape, padH: Dp, padV: Dp, barHeight: Dp, barGap: Dp) {
    val s = AutoBriefTheme.shape
    Row(
        Modifier
            .clip(shape)
            .background(look.fill)
            .edge(s.strokeHairline, look.edge, shape)
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (look.bar) {
            Box(Modifier.size(width = s.indicatorWidth, height = barHeight).clip(RoundedCornerShape(s.indicatorWidth / 2)).background(color))
            Spacer(Modifier.width(barGap))
        }
        if (look.pip) {
            Box(Modifier.size(s.pipSize).background(color))
            Spacer(Modifier.width(barGap))
        }
        Txt(label, Type.label, look.label)
    }
}

/** Large touch target (AAOS asks for at least 76dp). */
@Composable
fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = AutoBriefTheme.colors.primary,
    onColor: Color = AutoBriefTheme.colors.onPrimary,
    sub: String? = null,
    outlined: Boolean = false,
    tonal: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 96.dp,
    onClick: () -> Unit,
) {
    val p = AutoBriefTheme.colors
    val s = AutoBriefTheme.shape
    val shape = RoundedCornerShape(s.cornerMedium)
    val bg = when {
        !enabled -> p.surfaceVariant
        tonal -> color.copy(alpha = p.tintAlpha)
        outlined -> p.surfaceVariant.copy(alpha = p.outlinedFillAlpha)
        else -> color
    }
    val fg = when {
        !enabled -> p.textTertiary
        tonal -> p.textPrimary
        outlined -> color
        else -> onColor
    }
    val subFg = when {
        !enabled -> p.textTertiary
        tonal -> color
        outlined -> color
        else -> onColor
    }
    val borderMod = when {
        !enabled -> Modifier.border(s.strokeHairline, p.outline, shape)
        tonal -> Modifier.border(s.strokeEmphasis, color.copy(alpha = p.tonalBorderAlpha), shape)
        outlined -> Modifier.border(s.strokeEmphasis, color, shape)
        else -> Modifier
    }
    Column(
        modifier
            // never narrower than the 76dp touch floor (the month arrows' glyph alone is narrower)
            .sizeIn(minWidth = 76.dp, minHeight = height)
            .clip(shape)
            .background(bg)
            .then(borderMod)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt(label, Type.title.copy(textAlign = TextAlign.Center), fg)
        if (sub != null) Txt(sub, Type.caption.copy(textAlign = TextAlign.Center), subFg)
    }
}

/** Selectable chip row item (period, user, tax bracket), decorated per [ChipStyle] (it never shows a leading bar). */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = AutoBriefTheme.colors
    val s = AutoBriefTheme.shape
    val shape = RoundedCornerShape(s.cornerControl)
    val style = AutoBriefTheme.tokens.chipStyle
    if (style.pixel) {
        // selected: a solid primary plate, or on glass the same plate inside an accent line
        val solid = selected && style != ChipStyle.PIXEL_GLASS
        val plate = when {
            solid -> p.primary
            style == ChipStyle.PIXEL_TEXT -> Color.Transparent
            else -> p.chipBg
        }
        val line = when {
            solid -> p.primary
            selected -> p.accent
            style == ChipStyle.PIXEL_GLASS -> p.hairline
            else -> p.outline
        }
        val text = when {
            solid -> p.onPrimary
            selected -> p.accent
            else -> p.chipText
        }
        // the plate is 56dp tall inside the same 76dp touch target
        Box(
            Modifier.heightIn(min = 76.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .heightIn(min = 56.dp)
                    .clip(shape)
                    .background(plate)
                    .border(if (selected) s.strokeEmphasis else s.strokeHairline, line, shape)
                    .padding(horizontal = 22.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(label, Type.label, text) }
        }
        return
    }
    val fill = when {
        style == ChipStyle.OUTLINE -> Color.Transparent
        selected -> p.primary.copy(alpha = p.tintAlpha)
        else -> p.chipBg
    }
    val edge = when {
        style == ChipStyle.FILLED_SUBTLE -> null
        selected -> p.primary
        else -> p.outline
    }
    Box(
        Modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(fill)
            .edge(if (selected) s.strokeEmphasis else s.strokeHairline, edge, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, Type.label, if (selected) p.primary else p.chipText) }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, numeric: Boolean = false, decimal: Boolean = false, onChange: (String) -> Unit) {
    val p = AutoBriefTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.caption) },
        singleLine = true,
        textStyle = Type.body.copy(color = p.textPrimary),
        keyboardOptions = when {
            decimal -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            numeric -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.primary, unfocusedBorderColor = p.outline,
            focusedLabelColor = p.primary, unfocusedLabelColor = p.textSecondary, cursorColor = p.primary,
        ),
        modifier = modifier.heightIn(min = 76.dp),
    )
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = AutoBriefTheme.colors.textPrimary, strong: Boolean = false) {
    LabelValueRow(Modifier.fillMaxWidth(), label = { Muted(key, Type.body) }) {
        Txt(value, if (strong) Type.title else Type.bodyStrong, valueColor)
    }
}

/** Stacked bar of the period's distance by purpose, with a legend underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PurposeBar(s: LogbookSummary, height: Dp = 22.dp) {
    val p = AutoBriefTheme.colors
    val parts = listOf(
        TripPurpose.COMMUTE to s.commuteKm,
        TripPurpose.BUSINESS to s.businessKm,
        TripPurpose.PERSONAL to s.personalKm,
        TripPurpose.UNCLASSIFIED to s.unclassifiedKm,
        TripPurpose.UNLOGGED_GAP to s.gapKm,
    ).filter { it.second > 0.0 }
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    val shapes = AutoBriefTheme.shape
    val barShape = RoundedCornerShape(shapes.cornerSmall)
    val rim = if (AutoBriefTheme.quiet) null else p.outline
    Row(Modifier.fillMaxWidth().height(height).clip(barShape).background(p.surfaceVariant).edge(shapes.strokeHairline, rim, barShape)) {
        parts.forEach { (purpose, km) ->
            Box(Modifier.weight((km / total).toFloat().coerceAtLeast(0.004f)).fillMaxHeight().background(p.purpose(purpose)))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { (purpose, km) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (AutoBriefTheme.tokens.chipStyle.pixel) {
                    // the legend key is the same square pip as the purpose chip's
                    Box(Modifier.size(shapes.pipSize).background(p.purpose(purpose)))
                } else {
                    Box(Modifier.size(width = shapes.swatchWidth, height = 18.dp).clip(RoundedCornerShape(shapes.swatchWidth / 2)).background(p.purpose(purpose)))
                }
                Spacer(Modifier.width(8.dp))
                Txt("${purpose.label} ${km1(km)} km", Type.caption, p.textSecondary)
            }
        }
    }
}

/** Choice chips that wrap onto the next line instead of running off a narrow screen; [lead] is a muted caption. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(lead: String? = null, content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lead != null) Box(Modifier.heightIn(min = 76.dp), contentAlignment = Alignment.CenterStart) { Muted(lead, Type.body) }
        content()
    }
}

/** A switch whose whole 76dp row is the touch target; the Switch itself only shows the state. */
@Composable
fun ToggleRow(label: String, checked: Boolean, trackColor: Color = AutoBriefTheme.colors.primary, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = AutoBriefTheme.colors
    val s = AutoBriefTheme.shape
    if (AutoBriefTheme.quiet) {
        // a plain row in its card; the switch speaks in the primary colour whatever the row is about
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(label, Type.title, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = p.primary))
        }
        return
    }
    val shape = RoundedCornerShape(s.cornerMedium)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(p.surfaceVariant)
            .border(s.strokeHairline, if (checked) trackColor.copy(alpha = p.emphasisBorderAlpha) else p.outline, shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, Type.title, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = trackColor))
    }
}

/** The purposes a person can pick, in the order of the NTS form. */
val PICKABLE_PURPOSES = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)

/** The line under a purpose on its button: the NTS form column it fills (personal use has none). */
fun purposeSub(purpose: TripPurpose): String? = when (purpose) {
    TripPurpose.COMMUTE -> "⑧ 출·퇴근용"
    TripPurpose.BUSINESS -> "⑨ 일반 업무용"
    TripPurpose.PERSONAL -> "개인 용무"
    TripPurpose.UNCLASSIFIED, TripPurpose.UNLOGGED_GAP -> null
}

/** The three purposes as large buttons: the same words, sub-labels and colours wherever a purpose is picked. */
@Composable
fun PurposeButtons(
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 96.dp,
    onPick: (TripPurpose) -> Unit,
) {
    val p = AutoBriefTheme.colors
    // QUIET: the three buttons are told apart by their words, not by a colour each
    val quiet = AutoBriefTheme.quiet
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PICKABLE_PURPOSES.forEach { purpose ->
            BigButton(
                purpose.label, Modifier.weight(1f), color = if (quiet) p.textSecondary else p.purpose(purpose), onColor = p.background, sub = purposeSub(purpose),
                outlined = outlined, tonal = !outlined, enabled = enabled, height = height,
            ) { onPick(purpose) }
        }
    }
}

/** Why a recorded purpose changes: the same reasons on the home card and in the trip detail; none is preselected. */
val CORRECTION_REASONS = listOf("경유지가 있었어요", "개인 용무를 겸했어요", "사용자가 달라요", "기타 정정")

@Composable
fun ReasonChips(selected: String?, onSelect: (String) -> Unit) {
    ChipRow { CORRECTION_REASONS.forEach { r -> Choice(r, r == selected) { onSelect(r) } } }
}

@Composable
fun RowScope.Spring() = Spacer(Modifier.weight(1f))

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))
