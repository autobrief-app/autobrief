package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.autobrief.car.domain.KST
import app.autobrief.car.domain.LogbookSummary
import app.autobrief.car.model.TripPurpose
import java.time.Instant
import java.time.format.DateTimeFormatter

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MDHM: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

fun hm(epochMs: Long): String = HM.format(Instant.ofEpochMilli(epochMs).atZone(KST))
fun mdhm(epochMs: Long): String = MDHM.format(Instant.ofEpochMilli(epochMs).atZone(KST))
fun km1(v: Double): String = "%,.1f".format(v)

/** Korean 로/으로 after [word]: 으로 after a final consonant other than ㄹ ("출퇴근으로", "업무로", "비업무로"). */
fun withRo(word: String): String {
    val c = word.lastOrNull { it in '가'..'힣' } ?: return "${word}로"
    val jong = (c.code - 0xAC00) % 28
    return if (jong == 0 || jong == 8) "${word}로" else "${word}으로"
}
fun pct(r: Double?): String = r?.let { "%.1f%%".format(it * 100) } ?: "-"

/** Korean money in 만원 units for display ("117만원"); below 10,000 KRW shows in 원. */
fun manwon(krw: Long): String {
    val sign = if (krw < 0) "-" else ""
    val a = kotlin.math.abs(krw)
    return if (a >= 10_000) "$sign${"%,d".format((a + 5_000) / 10_000)}만원" else "$sign${"%,d".format(a)}원"
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.panel,
    border: Color? = null,
    padding: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .then(if (border != null) Modifier.border(2.dp, border, shape) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun Txt(text: String, style: TextStyle = Type.body, color: Color = LocalPalette.current.text, modifier: Modifier = Modifier) =
    Text(text, style = style, color = color, modifier = modifier)

@Composable
fun Muted(text: String, style: TextStyle = Type.small) = Txt(text, style, LocalPalette.current.muted)

/** A status pill: coloured dot + label. */
@Composable
fun StatusPill(label: String, color: Color, filled: Boolean = false) {
    val p = LocalPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.18f) else p.panelHigh)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Txt(label, Type.label, if (filled) color else p.text)
    }
}

@Composable
fun PurposeChip(purpose: TripPurpose) {
    val c = LocalPalette.current.purpose(purpose)
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Txt(purpose.label, Type.label, c) }
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
    enabled: Boolean = true,
    height: Dp = 96.dp,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    val bg = when {
        !enabled -> p.panelHigh
        outlined -> Color.Transparent
        else -> color
    }
    val fg = when {
        !enabled -> p.muted
        outlined -> color
        else -> onColor
    }
    Column(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(bg)
            .then(if (outlined) Modifier.border(3.dp, if (enabled) color else p.line, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt(label, Type.heading, fg)
        if (sub != null) Txt(sub, Type.small, fg.copy(alpha = 0.85f))
    }
}

/** Selectable chip row item (period, user, tax bracket). */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (selected) p.accent.copy(alpha = 0.2f) else p.panelHigh)
            .border(2.dp, if (selected) p.accent else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, Type.label, if (selected) p.accent else p.text) }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, numeric: Boolean = false, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.small) },
        singleLine = true,
        textStyle = Type.body.copy(color = p.text),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent, unfocusedBorderColor = p.line,
            focusedLabelColor = p.accent, unfocusedLabelColor = p.muted, cursorColor = p.accent,
        ),
        modifier = modifier,
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
@Composable
fun PurposeBar(s: LogbookSummary, height: Dp = 26.dp) {
    val p = LocalPalette.current
    val parts = listOf(
        TripPurpose.COMMUTE to s.commuteKm,
        TripPurpose.BUSINESS to s.businessKm,
        TripPurpose.PERSONAL to s.personalKm,
        TripPurpose.UNCLASSIFIED to s.unclassifiedKm,
        TripPurpose.UNLOGGED_GAP to s.gapKm,
    ).filter { it.second > 0.0 }
    val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(p.panelHigh)) {
        parts.forEach { (purpose, km) ->
            Box(Modifier.weight((km / total).toFloat().coerceAtLeast(0.004f)).fillMaxHeight().background(p.purpose(purpose)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        parts.forEach { (purpose, km) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(p.purpose(purpose)))
                Spacer(Modifier.width(8.dp))
                Txt("${purpose.label} ${km1(km)}", Type.small, p.muted)
            }
        }
    }
}

@Composable
fun RowScope.Spring() = Spacer(Modifier.weight(1f))

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))
