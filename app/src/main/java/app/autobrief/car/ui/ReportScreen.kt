package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.time.LocalDate
import java.time.YearMonth

private data class PeriodOption(val label: String, val range: ClosedRange<LocalDate>, val businessYear: Boolean)

@Composable
fun ReportScreen(core: AutoBriefCore, onFlush: () -> Unit) {
    val p = AutoBriefTheme.colors
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
    // the workbook is made off the main thread: until it is, the screen says so and both buttons wait
    val workbook by core.workbook.collectAsState()
    val making = workbook is AutoBriefCore.WorkbookRequest.Making
    val opt = options[choice]
    val s = remember(trips, opt.range) { core.summary(opt.range) }
    val unit = if (opt.businessYear) "사업연도" else "기간"
    val rows = remember(trips, opt.range) {
        trips.filter { TripLogbook.localDate(it.startEpochMs) in opt.range }.sortedBy { it.startEpochMs }
    }

    val metrics = previewMetrics()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val density = LocalDensity.current
        var topHeight by remember { mutableStateOf(0.dp) }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
            Column(Modifier.onSizeChanged { topHeight = with(density) { it.height.toDp() } }, verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                ChipRow { options.forEachIndexed { i, o -> Choice(o.label, i == choice) { choice = i; core.dismissWorkbook() } } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Panel(Modifier.weight(1.2f)) {
                        Txt("업무용승용차 운행기록부", Type.headline)
                        Muted("$unit ${opt.range.start} ~ ${opt.range.endInclusive} · ${settings.entityName.ifBlank { "법인명 미입력" }} · ${settings.plateNumber.ifBlank { "등록번호 미입력" }}", Type.body)
                        Gap(4.dp)
                        FormRow("⑪ $unit 총주행 거리", "${km1(s.totalKm)} km")
                        FormRow("⑫ $unit 업무용 사용거리", "${km1(s.businessUseKm)} km")
                        FormRow("    ⑧ 출·퇴근용", "${km1(s.commuteKm)} km")
                        FormRow("    ⑨ 일반 업무용", "${km1(s.businessKm)} km")
                        FormRow("⑬ 업무사용비율 (⑫/⑪)", pct(s.businessUseRatio), strong = true)
                        if (s.rowCount > 0) PurposeBar(s)
                        Muted(
                            "운행 ${s.rowCount}건 · 미분류 ${s.unclassifiedCount}건(업무로 계산하지 않음) · 미기록 구간 ${s.gapRowCount}건(비업무) · 계기판 연속성 끊김 ${s.continuityBreaks}곳",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BigButton("엑셀 만들기", Modifier.weight(1f), enabled = !making && s.rowCount > 0, sub = "국세청 서식 + 검증 기록") {
                                core.requestWorkbook(opt.range, opt.businessYear, send = false)
                            }
                            BigButton(
                                "지금 보내기",
                                Modifier.weight(1f),
                                enabled = !making && s.rowCount > 0 && settings.archiveWebhookUrl.isNotBlank(),
                                outlined = true,
                                sub = if (settings.archiveWebhookUrl.isBlank()) "설정에서 주소를 넣어 주세요" else "회사 서버, 메일 연동",
                            ) {
                                if (core.requestWorkbook(opt.range, opt.businessYear, send = true)) onFlush()
                            }
                        }
                        workbook?.let { w ->
                            Txt(workbookMessage(w), Type.body, if (w is AutoBriefCore.WorkbookRequest.Failed) p.critical else p.accent)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Panel(border = if (check.ok) null else p.critical) {
                            Txt("기록 원장", Type.title)
                            Txt(if (check.ok) "정상" else "확인 필요", Type.display, if (check.ok) p.positive else p.critical)
                            KeyValue("검증 코드", check.code, strong = true)
                            KeyValue("원장 항목", "${check.entryCount}건")
                            if (check.ok) {
                                Muted("계기판 값은 기록 뒤에 바꿀 수 없고, 목적을 고친 내역은 지우지 않고 남습니다. 엑셀의 '검증 기록' 시트와 회사 서버의 사본으로 대조할 수 있습니다.")
                            } else {
                                check.problems.take(5).forEach { Txt("• $it", Type.body, p.critical) }
                            }
                        }
                        if (settings.carUse == CarUse.OWN) FuelCard(core, opt.range, "유류비 정산 ($unit ${opt.range.start} ~ ${opt.range.endInclusive})")
                        Panel {
                            Txt("보내기 상태", Type.title)
                            val pending = outbox.count { it.deliveredEpochMs == null }
                            val delivered = outbox.count { it.deliveredEpochMs != null }
                            KeyValue("보내기 주소", if (settings.archiveWebhookUrl.isBlank()) "미설정" else "설정됨")
                            KeyValue("대기 / 최근 완료", "${pending}건 / ${delivered}건")
                            outbox.lastOrNull { it.lastError != null && it.deliveredEpochMs == null }?.lastError?.let { Muted("마지막 오류: $it") }
                        }
                    }
                }
            }
            if (rows.isNotEmpty()) {
                // whole rows only: as many as fit under the panels on this display, at least 3 (then the page scrolls)
                val fit = ((viewport - topHeight - SECTION_GAP - metrics.chrome) / metrics.row).toInt().coerceIn(3, 8)
                PreviewPanel(rows, fit, metrics)
            }
            Gap(24.dp)
        }
    }
}

/** What the report screen says about its workbook: being made, where it went, or that it could not be written. */
private fun workbookMessage(w: AutoBriefCore.WorkbookRequest): String = when (w) {
    is AutoBriefCore.WorkbookRequest.Making -> "만드는 중…"
    is AutoBriefCore.WorkbookRequest.Made ->
        if (w.send) "저장하고 전송을 시작했습니다: ${w.result.fileName}" else "차량에 저장했습니다: ${w.result.fileName} (${w.result.rows}행)"
    is AutoBriefCore.WorkbookRequest.Failed -> "엑셀을 만들지 못했습니다(${w.error}). 차량 저장 공간을 확인한 뒤 다시 눌러 주세요."
}

private val SECTION_GAP = 22.dp

/** Heights of the preview's parts, from the text styles (so a larger font scale still yields whole rows). */
private data class PreviewMetrics(val header: Dp, val row: Dp, val chrome: Dp)

@Composable
private fun previewMetrics(): PreviewMetrics = with(LocalDensity.current) {
    val header = Type.label.lineHeight.toDp() + 20.dp
    val row = Type.caption.lineHeight.toDp() + 16.dp
    // panel padding 2 x 28, the title, the header, the closing note and the panel's two 14dp gaps
    PreviewMetrics(header, row, 56.dp + Type.title.lineHeight.toDp() + header + Type.caption.lineHeight.toDp() + 28.dp)
}

@Composable
private fun PreviewPanel(rows: List<TripRecord>, count: Int, m: PreviewMetrics) {
    val shown = rows.take(count)
    Panel {
        Txt(if (shown.size < rows.size) "미리보기 (처음 ${shown.size}행)" else "미리보기 (${shown.size}행)", Type.title)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // stretch the form columns to the panel; on a narrow display keep their widths and scroll sideways
            val natural = PREVIEW_WIDTHS.fold(0.dp) { sum, w -> sum + w }
            val stretch = ((maxWidth - 24.dp) / natural).coerceAtLeast(1f) // 24dp = the rows' side padding
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                PreviewHeader(stretch, m.header)
                shown.forEach { r -> PreviewRow(previewCells(r), stretch, m.row) }
            }
        }
        if (shown.size < rows.size) Muted("나머지 ${comma(rows.size - shown.size)}행은 엑셀 파일에서 볼 수 있습니다.")
    }
}

private fun previewCells(r: TripRecord): List<String> {
    val km = TripLogbook.printedKm(r)
    return listOf(
        LogbookExport.dateWithWeekday(TripLogbook.localDate(r.startEpochMs)),
        r.user?.let { "${it.department} ${it.name}" } ?: "",
        km1(r.startOdometerKm), km1(r.endOdometerKm), km1(km),
        if (r.purpose == TripPurpose.COMMUTE) km1(km) else "",
        if (r.purpose == TripPurpose.BUSINESS) km1(km) else "",
        r.purpose.label,
    )
}

@Composable
private fun FormRow(label: String, value: String, strong: Boolean = false) {
    val p = AutoBriefTheme.colors
    val s = AutoBriefTheme.shape
    val shape = RoundedCornerShape(s.cornerControl)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surfaceVariant)
            .border(s.strokeHairline, if (strong) p.accent.copy(alpha = p.emphasisBorderAlpha) else p.outline, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, Type.body)
        Spring()
        Txt(value, if (strong) Type.headline else Type.title, if (strong) p.accent else p.textPrimary)
    }
}

private val PREVIEW_COLS = listOf("③사용일자", "④사용자", "⑤주행 전", "⑥주행 후", "⑦주행거리", "⑧출퇴근", "⑨일반업무", "목적")
// sized for the 24sp label/small text: the longest cell of each column stays on one line
private val PREVIEW_WIDTHS: List<Dp> = listOf(210.dp, 180.dp, 150.dp, 150.dp, 140.dp, 120.dp, 140.dp, 150.dp)

@Composable
private fun PreviewHeader(stretch: Float, height: Dp) {
    val p = AutoBriefTheme.colors
    Row(Modifier.height(height).background(p.surfaceVariant).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PREVIEW_COLS.forEachIndexed { i, c -> Txt(c, Type.label, p.textSecondary, Modifier.width(PREVIEW_WIDTHS[i] * stretch)) }
    }
}

@Composable
private fun PreviewRow(cells: List<String>, stretch: Float, height: Dp) {
    Row(Modifier.height(height).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, c -> Txt(c, Type.caption, modifier = Modifier.width(PREVIEW_WIDTHS[i] * stretch)) }
    }
}
