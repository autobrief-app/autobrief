package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.time.YearMonth

@Composable
fun TripsScreen(core: AutoBriefCore) {
    val trips by core.trips.collectAsState()
    val latest = trips.lastOrNull()?.let { TripLogbook.yearMonth(it.startEpochMs) } ?: YearMonth.from(core.today())
    var month by remember { mutableStateOf(latest) }
    var selected by remember { mutableStateOf<String?>(null) }
    val rows = remember(trips, month) {
        trips.filter { TripLogbook.yearMonth(it.startEpochMs) == month }.sortedByDescending { it.startEpochMs }
    }
    val s = remember(rows) { TripLogbook.summarize(rows) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton("◀", outlined = true, height = 76.dp) { month = month.minusMonths(1) }
            Txt("${month.year}년 ${month.monthValue}월", Type.title, modifier = Modifier.widthIn(min = 260.dp).padding(horizontal = 8.dp))
            BigButton("▶", outlined = true, height = 76.dp) { month = month.plusMonths(1) }
            Spring()
            Column(horizontalAlignment = Alignment.End) {
                Txt("${s.rowCount}건 · ${km1(s.totalKm)} km · 업무 ${pct(s.businessUseRatio)}", Type.heading)
                Muted("미분류 ${s.unclassifiedCount}건 · 자동 분류 ${s.autoCount}건 · 음성 ${s.voiceCount}건")
            }
        }
        if (rows.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) { Muted("이 달의 운행이 없습니다.", Type.body) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.id }) { t -> TripRow(t) { selected = t.id } }
        }
    }
    selected?.let { id -> trips.firstOrNull { it.id == id } }?.let { t -> TripDialog(core, t) { selected = null } }
}

@Composable
private fun TripRow(t: TripRecord, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(p.panel)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(Modifier.width(210.dp)) {
            Txt(LogbookExport.dateWithWeekday(TripLogbook.localDate(t.startEpochMs)), Type.bodyBold)
            Muted("${hm(t.startEpochMs)}~${hm(t.endEpochMs)}")
        }
        Column(Modifier.width(320.dp)) {
            Txt("${km1(t.startOdometerKm)} → ${km1(t.endOdometerKm)}", Type.body)
            Muted(listOfNotNull(t.startPlace, t.endPlace).joinToString(" → ").ifBlank { if (t.family) "위치 기록 안 함" else "장소 미등록" })
        }
        Txt("${km1(TripLogbook.printedKm(t))} km", Type.heading, modifier = Modifier.width(170.dp))
        // a fixed box, so the user column starts at the same x whatever the chip says
        Box(Modifier.width(PURPOSE_COLUMN)) { PurposeChip(t.purpose) }
        Column(Modifier.weight(1f)) {
            if (t.purpose == TripPurpose.UNCLASSIFIED) Txt("목적 정하기 ›", Type.bodyBold, p.accent)
            else Txt(t.user?.let { "${it.name}(${it.department})" } ?: "-", Type.body)
            marks(t).ifBlank { t.memo }.takeIf { it.isNotBlank() }?.let { Muted(it) }
        }
    }
}

/** Wide enough for the longest purpose chip ("미기록 구간") at the label size. */
private val PURPOSE_COLUMN = 170.dp

private fun marks(t: TripRecord): String = buildList {
    when {
        t.classifiedBy == ClassifiedBy.AUTO && t.autoReason != null -> add("자동(${t.autoReason})")
        t.classifiedBy == ClassifiedBy.VOICE -> add("음성")
        t.classifiedBy == ClassifiedBy.SCREEN -> add("화면")
        t.classifiedBy == ClassifiedBy.FAMILY -> add("가족 운행")
    }
    if (t.family && t.classifiedBy != ClassifiedBy.FAMILY) add("위치 기록 안 함")
    if (t.amended) add("수정됨")
    if (t.memo.isNotBlank() && t.purpose != TripPurpose.UNLOGGED_GAP && !(t.family && t.memo == AutoBriefCore.FAMILY_MEMO)) add(t.memo)
}.joinToString(" · ")

@Composable
private fun TripDialog(core: AutoBriefCore, t: TripRecord, onClose: () -> Unit) {
    val p = LocalPalette.current
    val settings by core.settings.collectAsState()
    var purpose by remember(t.id) { mutableStateOf(t.purpose.takeIf { it.isUserPurpose }) }
    var memo by remember(t.id) { mutableStateOf(t.memo) }
    // a correction needs one of the reasons the home card offers; nothing is preselected, the memo is optional
    var reason by remember(t.id) { mutableStateOf<String?>(null) }
    var reasonMemo by remember(t.id) { mutableStateOf("") }
    var userIndex by remember(t.id) { mutableStateOf(settings.users.indexOf(t.user).takeIf { it >= 0 } ?: settings.defaultUserIndex) }
    var message by remember(t.id) { mutableStateOf<String?>(null) }
    val correcting = t.purpose.isUserPurpose
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(Modifier.widthIn(max = 1500.dp).fillMaxWidth(0.9f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt("${LogbookExport.dateWithWeekday(TripLogbook.localDate(t.startEpochMs))} ${hm(t.startEpochMs)}~${hm(t.endEpochMs)}", Type.title)
                PurposeChip(t.purpose)
                Spring()
                BigButton("닫기", outlined = true, height = 76.dp, onClick = onClose)
            }
            KeyValue("⑤ 주행 전 계기판", "${km1(t.startOdometerKm)} km")
            KeyValue("⑥ 주행 후 계기판", "${km1(t.endOdometerKm)} km")
            KeyValue("⑦ 주행거리", "${km1(TripLogbook.printedKm(t))} km", strong = true)
            KeyValue(
                "출발 → 도착 장소",
                listOfNotNull(t.startPlace, t.endPlace).joinToString(" → ").ifBlank { if (t.family) "위치 기록 안 함 (가족 운행)" else "등록된 장소 아님" },
            )
            t.routeTollEstimateKrw?.takeIf { it > 0 }?.let { KeyValue("내비 예상 통행료", "${comma(it)}원") }
            if (t.purpose == TripPurpose.UNLOGGED_GAP) {
                Muted("앱이 꺼져 있던 동안 계기판이 움직인 구간입니다. 업무로 바꿀 수 없고 비업무로 계산합니다.", Type.body)
            } else {
                Gap(6.dp)
                Txt(if (correcting) "목적 고치기" else "목적 정하기", Type.heading)
                ChipRow { PICKABLE_PURPOSES.forEach { c -> Choice(c.label, purpose == c) { purpose = c } } }
                if (settings.users.isNotEmpty()) {
                    ChipRow(lead = "④ 사용자") {
                        settings.users.forEachIndexed { i, u -> Choice("${u.name}(${u.department})", i == userIndex) { userIndex = i } }
                    }
                }
                Field("비고", memo, Modifier.fillMaxWidth()) { memo = it }
                if (correcting) {
                    Txt("고치는 이유 (기록 원장에 남습니다)", Type.bodyBold)
                    ReasonChips(reason) { reason = it }
                    Field("정정 메모 (선택)", reasonMemo, Modifier.fillMaxWidth()) { reasonMemo = it }
                }
                BigButton("저장", Modifier.fillMaxWidth(0.3f), enabled = purpose != null && (!correcting || reason != null)) {
                    val why = if (correcting) listOfNotNull(reason, reasonMemo.trim().ifBlank { null }).joinToString(" · ") else null
                    val result = core.classifyTrip(t.id, purpose!!, settings.users.getOrNull(userIndex), memo, ClassifiedBy.SCREEN, why)
                    message = when (result) {
                        AutoBriefCore.ClassifyResult.OK -> "저장했습니다."
                        AutoBriefCore.ClassifyResult.REASON_REQUIRED -> "고치는 이유를 골라 주세요."
                        AutoBriefCore.ClassifyResult.NOT_FOUND -> "이 운행을 찾지 못했습니다."
                    }
                    if (result == AutoBriefCore.ClassifyResult.OK) onClose()
                }
                message?.let { Txt(it, Type.body, p.pending) }
            }
            Gap(6.dp)
            Txt("기록 원장", Type.heading)
            core.ledgerFor(t.id).forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Txt("#${e.seq}", Type.bodyBold, modifier = Modifier.width(110.dp))
                    Txt(mdhm(e.epochMs), Type.body, modifier = Modifier.width(240.dp))
                    Txt(kindLabel(e.kind), Type.body, modifier = Modifier.width(170.dp))
                    Muted(e.hash.take(16), Type.body)
                }
            }
        }
    }
}

fun kindLabel(kind: String) = when (kind) {
    LedgerKind.TRIP -> "운행 기록"
    LedgerKind.GAP -> "미기록 구간"
    LedgerKind.CLASSIFY -> "목적 지정"
    LedgerKind.AMEND -> "목적 수정"
    else -> kind
}
