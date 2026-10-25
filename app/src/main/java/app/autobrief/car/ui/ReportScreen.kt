package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import java.time.LocalDate
import java.time.YearMonth

private data class PeriodOption(val label: String, val range: ClosedRange<LocalDate>, val businessYear: Boolean)

@Composable
fun ReportScreen(core: AutoBriefCore, onFlush: () -> Unit) {
    val p = LocalPalette.current
    val trips by core.trips.collectAsState()
    val settings by core.settings.collectAsState()
    val check by core.integrity.collectAsState()
    val outbox by core.outbox.collectAsState()
    val today = core.today()
    val fy = settings.fiscalYearStartMonth
    val by = TaxPeriods.businessYearOf(today, fy)
    val byYear = by.start.year
    val options = listOf(
        PeriodOption("이번 달", TaxPeriods.month(YearMonth.from(today)), false),
        PeriodOption("지난달", TaxPeriods.month(YearMonth.from(today).minusMonths(1)), false),
        PeriodOption("상반기", TaxPeriods.half(byYear, fy, 1), false),
        PeriodOption("하반기", TaxPeriods.half(byYear, fy, 2), false),
        PeriodOption("사업연도", by, true),
        PeriodOption("지난 사업연도", TaxPeriods.businessYear(byYear - 1, fy), true),
    )
    var choice by remember { mutableStateOf(4) }
    var result by remember { mutableStateOf<String?>(null) }
    val opt = options[choice]
    val s = core.summary(opt.range)
    val unit = if (opt.businessYear) "사업연도" else "기간"
    val rows = trips.filter { TripLogbook.localDate(it.startEpochMs) in opt.range }.sortedBy { it.startEpochMs }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEachIndexed { i, o -> Choice(o.label, i == choice) { choice = i; result = null } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel(Modifier.weight(1.2f)) {
                Txt("업무용승용차 운행기록부", Type.title)
                Muted("$unit ${opt.range.start} ~ ${opt.range.endInclusive} · ${settings.entityName.ifBlank { "법인명 미입력" }} · ${settings.plateNumber.ifBlank { "등록번호 미입력" }}", Type.body)
                Gap(4.dp)
                FormRow("⑪ $unit 총주행 거리", "${km1(s.totalKm)} km")
                FormRow("⑫ $unit 업무용 사용거리", "${km1(s.businessUseKm)} km")
                FormRow("    ⑧ 출·퇴근용", "${km1(s.commuteKm)} km")
                FormRow("    ⑨ 일반 업무용", "${km1(s.businessKm)} km")
                FormRow("⑬ 업무사용비율 (⑫/⑪)", pct(s.businessUseRatio), strong = true)
                if (s.rowCount > 0) PurposeBar(s)
                Muted(
                    "기록 ${s.rowCount}건 · 미분류 ${s.unclassifiedCount}건(업무로 계산하지 않음) · 미기록 구간 ${s.gapRowCount}건(비업무) · 계기판 연속성 끊김 ${s.continuityBreaks}곳",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton("엑셀 만들기", Modifier.weight(1f), enabled = s.rowCount > 0, sub = "국세청 서식 + 검증 기록") {
                        val r = core.exportWorkbook(opt.range, opt.businessYear, send = false)
                        result = "차량에 저장했습니다: ${r.fileName} (${r.rows}행)"
                    }
                    BigButton(
                        "보내기 주소로 보내기",
                        Modifier.weight(1f),
                        enabled = s.rowCount > 0 && settings.archiveWebhookUrl.isNotBlank(),
                        outlined = true,
                        sub = if (settings.archiveWebhookUrl.isBlank()) "설정에서 주소를 넣어 주세요" else "회사 서버, 메일 연동",
                    ) {
                        val r = core.exportWorkbook(opt.range, opt.businessYear, send = true)
                        onFlush()
                        result = "저장하고 전송을 시작했습니다: ${r.fileName}"
                    }
                }
                result?.let { Txt(it, Type.body, p.accent) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Panel(border = if (check.ok) null else p.danger) {
                    Txt("기록 원장", Type.heading)
                    Txt(if (check.ok) "정상" else "확인 필요", Type.hero, if (check.ok) p.ok else p.danger)
                    KeyValue("검증 코드", check.code, strong = true)
                    KeyValue("원장 기록 수", "${check.entryCount}건")
                    if (check.ok) {
                        Muted("계기판 값은 기록 뒤에 바꿀 수 없고, 목적을 고친 내역은 지우지 않고 남습니다. 엑셀의 '검증 기록' 시트와 회사 서버의 사본으로 대조할 수 있습니다.")
                    } else {
                        check.problems.take(5).forEach { Txt("• $it", Type.body, p.danger) }
                    }
                }
                Panel {
                    Txt("보내기 상태", Type.heading)
                    val pending = outbox.count { it.deliveredEpochMs == null }
                    val delivered = outbox.count { it.deliveredEpochMs != null }
                    KeyValue("보내기 주소", if (settings.archiveWebhookUrl.isBlank()) "미설정" else "설정됨")
                    KeyValue("대기 / 최근 완료", "${pending}건 / ${delivered}건")
                    outbox.lastOrNull { it.lastError != null && it.deliveredEpochMs == null }?.lastError?.let { Muted("마지막 오류: $it") }
                }
            }
        }
        if (rows.isNotEmpty()) {
            Panel {
                Txt("미리보기 (처음 8행)", Type.heading)
                PreviewHeader()
                rows.take(8).forEach { r ->
                    val km = TripLogbook.round1(r.distanceKm)
                    PreviewRow(
                        listOf(
                            app.autobrief.car.domain.LogbookExport.dateWithWeekday(TripLogbook.localDate(r.startEpochMs)),
                            r.user?.let { "${it.department} ${it.name}" } ?: "",
                            km1(r.startOdometerKm), km1(r.endOdometerKm), km1(km),
                            if (r.purpose == app.autobrief.car.model.TripPurpose.COMMUTE) km1(km) else "",
                            if (r.purpose == app.autobrief.car.model.TripPurpose.BUSINESS) km1(km) else "",
                            r.purpose.label,
                        ),
                    )
                }
            }
        }
        Gap(24.dp)
    }
}

@Composable
private fun FormRow(label: String, value: String, strong: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.panelHigh).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Type.body)
        Spring()
        Txt(value, if (strong) Type.title else Type.heading, if (strong) p.accent else p.text)
    }
}

private val PREVIEW_COLS = listOf("③사용일자", "④사용자", "⑤주행 전", "⑥주행 후", "⑦주행거리", "⑧출퇴근", "⑨일반업무", "목적")
private val PREVIEW_WIDTHS: List<Dp> = listOf(190.dp, 210.dp, 150.dp, 150.dp, 130.dp, 120.dp, 130.dp, 130.dp)

@Composable
private fun PreviewHeader() {
    val p = LocalPalette.current
    Row(Modifier.background(p.panelHigh).padding(vertical = 10.dp, horizontal = 12.dp)) {
        PREVIEW_COLS.forEachIndexed { i, c -> Txt(c, Type.label, p.muted, Modifier.width(PREVIEW_WIDTHS[i])) }
    }
}

@Composable
private fun PreviewRow(cells: List<String>) {
    Row(Modifier.padding(vertical = 8.dp, horizontal = 12.dp)) {
        cells.forEachIndexed { i, c -> Txt(c, Type.small, modifier = Modifier.width(PREVIEW_WIDTHS[i])) }
    }
}
