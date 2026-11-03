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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.panel,
    border: Color? = null,
    padding: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .border(if (border != null) 2.dp else 1.dp, border ?: p.line, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

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
fun Txt(text: String, style: TextStyle = Type.body, color: Color = LocalPalette.current.text, modifier: Modifier = Modifier) {
    // the semantics carry the plain text, so TalkBack and tests read the words without the joiners
    Box(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        Text(remember(text) { keepWords(text) }, style = style, color = color)
    }
}

@Composable
fun Muted(text: String, style: TextStyle = Type.small) = Txt(text, style, LocalPalette.current.muted)

/** An instrument status readout cell: vertical indicator bar + label inside a crisp architectural frame. */
@Composable
fun StatusPill(label: String, color: Color, filled: Boolean = false) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (filled) color.copy(alpha = p.tint) else p.panel)
            .border(1.dp, if (filled) color.copy(alpha = 0.55f) else p.line, shape)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 20.dp).clip(RoundedCornerShape(1.5.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Txt(label, Type.label, if (filled) color else p.text)
    }
}

@Composable
fun PurposeChip(purpose: TripPurpose) {
    val p = LocalPalette.current
    val c = p.purpose(purpose)
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .clip(shape)
            .background(c.copy(alpha = p.tint))
            .border(1.dp, c.copy(alpha = 0.45f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 18.dp).clip(RoundedCornerShape(1.5.dp)).background(c))
        Spacer(Modifier.width(8.dp))
        Txt(purpose.label, Type.label, c)
    }
}

/** Large touch target (AAOS asks for at least 76dp). */
@Composable
fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.accent,
    onColor: Color = LocalPalette.current.onAccent,
    sub: String? = null,
    outlined: Boolean = false,
    tonal: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 96.dp,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        !enabled -> p.panelHigh
        tonal -> color.copy(alpha = p.tint)
        outlined -> p.panelHigh.copy(alpha = 0.5f)
        else -> color
    }
    val fg = when {
        !enabled -> p.muted
        tonal -> p.text
        outlined -> color
        else -> onColor
    }
    val subFg = when {
        !enabled -> p.muted
        tonal -> color
        outlined -> color
        else -> onColor
    }
    val borderMod = when {
        !enabled -> Modifier.border(1.dp, p.line, shape)
        tonal -> Modifier.border(1.5.dp, color.copy(alpha = 0.65f), shape)
        outlined -> Modifier.border(1.5.dp, color, shape)
        else -> Modifier
    }
    Column(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(bg)
            .then(borderMod)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt(label, Type.heading.copy(textAlign = TextAlign.Center), fg)
        if (sub != null) Txt(sub, Type.small.copy(textAlign = TextAlign.Center), subFg)
    }
}

/** Selectable chip row item (period, user, tax bracket). */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(if (selected) p.accent.copy(alpha = p.tint) else p.panelHigh)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) p.accent else p.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, Type.label, if (selected) p.accent else p.text) }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, numeric: Boolean = false, decimal: Boolean = false, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.small) },
        singleLine = true,
        textStyle = Type.body.copy(color = p.text),
        keyboardOptions = when {
            decimal -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            numeric -> KeyboardOptions(keyboardType = KeyboardType.Number)
            else -> KeyboardOptions.Default
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent, unfocusedBorderColor = p.line,
            focusedLabelColor = p.accent, unfocusedLabelColor = p.muted, cursorColor = p.accent,
        ),
        modifier = modifier.heightIn(min = 76.dp),
    )
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = LocalPalette.current.text, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Muted(key, Type.body)
        Spacer(Modifier.weight(1f))
        Txt(value, if (strong) Type.heading else Type.bodyBold, valueColor)
    }
}

/** Stacked bar of the period's distance by purpose, with a legend underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PurposeBar(s: LogbookSummary, height: Dp = 22.dp) {
    val p = LocalPalette.current
    val parts = listOf(
        TripPurpose.COMMUTE to s.commuteKm,
        TripPurpose.BUSINESS to s.businessKm,
        TripPurpose.PERSONAL to s.personalKm,
        TripPurpose.UNCLASSIFIED to s.unclassifiedKm,
        TripPurpose.UNLOGGED_GAP to s.gapKm,
    ).filter { it.second > 0.0 }
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    val barShape = RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth().height(height).clip(barShape).background(p.panelHigh).border(1.dp, p.line, barShape)) {
        parts.forEach { (purpose, km) ->
            Box(Modifier.weight((km / total).toFloat().coerceAtLeast(0.004f)).fillMaxHeight().background(p.purpose(purpose)))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { (purpose, km) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(p.purpose(purpose)))
                Spacer(Modifier.width(8.dp))
                Txt("${purpose.label} ${km1(km)} km", Type.small, p.muted)
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
fun ToggleRow(label: String, checked: Boolean, trackColor: Color = LocalPalette.current.accent, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(p.panelHigh)
            .border(1.dp, if (checked) trackColor.copy(alpha = 0.55f) else p.line, shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, Type.heading, modifier = Modifier.weight(1f))
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
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PICKABLE_PURPOSES.forEach { purpose ->
            BigButton(
                purpose.label, Modifier.weight(1f), color = p.purpose(purpose), onColor = p.bg, sub = purposeSub(purpose),
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
