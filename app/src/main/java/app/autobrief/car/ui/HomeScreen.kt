package app.autobrief.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.TaxBenefit
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.time.YearMonth

/** [vehicleStalled]: no gear or odometer from the car for [VEHICLE_WAIT_MS] (see [rememberVehicleStalled]). */
@Composable
fun HomeScreen(core: AutoBriefCore, wide: Boolean, vehicleStalled: Boolean, onOpen: (Tab) -> Unit) {
    val trips by core.trips.collectAsState()
    val open by core.openTrip.collectAsState()
    val lastId by core.lastClosedId.collectAsState()
    val notice by core.notice.collectAsState()
    val last = lastId?.let { id -> trips.firstOrNull { it.id == id } }
    val pending = trips.filter { it.purpose == TripPurpose.UNCLASSIFIED && it.id != last?.id }.sortedByDescending { it.startEpochMs }

    val settings by core.settings.collectAsState()
    val space by core.activeSpace.collectAsState()
    val today = core.today()
    val todays = trips.filter { TripLogbook.localDate(it.startEpochMs) == today }.sortedByDescending { it.startEpochMs }
    val main: @Composable () -> Unit = {
        if (space == AutoBriefCore.Space.LIVE && trips.isEmpty() && (settings.entityName.isBlank() || settings.users.isEmpty())) {
            SetupCard(core, onOpen)
        }
        when {
            open != null -> InProgressCard(core)
            last != null && last.purpose == TripPurpose.UNCLASSIFIED -> AskPurposeCard(core, last)
            last != null -> AutoConfirmCard(core, last)
            else -> ReadyCard(core, trips.lastOrNull { it.purpose != TripPurpose.UNLOGGED_GAP }, vehicleStalled)
        }
        if (pending.isNotEmpty()) PendingCard(core, pending, onOpen)
        if (todays.isNotEmpty()) TodayCard(todays)
    }
    val side: @Composable () -> Unit = {
        notice?.let { n ->
            Panel(border = LocalPalette.current.accent) {
                Txt(n.text, Type.bodyBold)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigButton("운행기록부 보기", Modifier.weight(1f), height = 76.dp) { core.dismissNotice(); onOpen(Tab.REPORT) }
                    BigButton("닫기", Modifier.weight(1f), outlined = true, height = 76.dp) { core.dismissNotice() }
                }
            }
        }
        MonthCard(core)
        TaxCard(core, onOpen)
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) { main() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) { side() }
        }
    } else {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            main()
            side()
        }
    }
}

/** First run on a real car: what to fill in, or a one-tap tour with the labelled fictional year. */
@Composable
private fun SetupCard(core: AutoBriefCore, onOpen: (Tab) -> Unit) {
    val p = LocalPalette.current
    Panel(border = p.accent) {
        Txt("처음 쓰시나요?", Type.title)
        Muted("설정에서 법인명, 차종, 자동차등록번호, 사용자(부서와 성명)를 넣으면 그다음 운행부터 국세청 서식 운행기록부가 채워집니다.", Type.body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton("설정 열기", Modifier.weight(1f)) { onOpen(Tab.SETTINGS) }
            BigButton("가상 예시로 둘러보기", Modifier.weight(1f), outlined = true, sub = "실제 기록과 따로 저장됩니다") {
                if (core.setDemoMode(true, null) == AutoBriefCore.SettingsResult.SAVED) core.loadSampleHistory()
            }
        }
    }
}

/** Today's rows, newest first: the logbook visibly growing during a drive day. */
@Composable
private fun TodayCard(todays: List<TripRecord>) {
    val p = LocalPalette.current
    Panel {
        Txt("오늘 운행 ${todays.size}건", Type.heading)
        todays.take(5).forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt("${hm(t.startEpochMs)}~${hm(t.endEpochMs)}", Type.body, p.muted)
                Txt("${km1(TripLogbook.printedKm(t))} km", Type.bodyBold)
                PurposeChip(t.purpose)
                Muted(
                    listOfNotNull(
                        listOfNotNull(t.startPlace, t.endPlace).joinToString("→").ifBlank { null },
                        when (t.classifiedBy) { ClassifiedBy.AUTO -> "자동"; ClassifiedBy.VOICE -> "음성"; ClassifiedBy.SCREEN -> "화면"; else -> null },
                        if (t.amended) "수정됨" else null,
                    ).joinToString(" · "),
                    Type.body,
                )
            }
        }
    }
}

@Composable
private fun InProgressCard(core: AutoBriefCore) {
    val p = LocalPalette.current
    val open by core.openTrip.collectAsState()
    val v by core.vehicle.collectAsState()
    val o = open ?: return
    val delta = (v.odometerKm ?: o.startOdometerKm) - o.startOdometerKm
    Panel(border = p.accent) {
        StatusPill("운행 기록 중", p.accent, filled = true)
        Txt("+${km1(delta)} km", Type.hero)
        Txt("출발 ${hm(o.startEpochMs)} · 계기판 ${km1(o.startOdometerKm)} km" + (o.startPlace?.let { " · $it" } ?: ""), Type.body, p.muted)
        Muted("도착해서 기어를 P로 바꾸면 운행기록부에 한 줄이 생깁니다. 집, 회사, 거래처로 등록한 곳이면 목적도 자동으로 적습니다.", Type.body)
    }
}

@Composable
private fun AskPurposeCard(core: AutoBriefCore, trip: TripRecord) {
    val p = LocalPalette.current
    val settings by core.settings.collectAsState()
    var memo by remember(trip.id) { mutableStateOf("") }
    var userIndex by remember(trip.id) { mutableStateOf(settings.defaultUserIndex.coerceIn(0, (settings.users.size - 1).coerceAtLeast(0))) }
    Panel(border = p.pending) {
        StatusPill("방금 끝난 운행", p.pending, filled = true)
        Txt("${km1(TripLogbook.printedKm(trip))} km 운행했습니다", Type.hero)
        Txt(
            "${hm(trip.startEpochMs)}~${hm(trip.endEpochMs)} · 계기판 ${km1(trip.startOdometerKm)} → ${km1(trip.endOdometerKm)} km" +
                (trip.endPlace?.let { " · 도착 $it" } ?: ""),
            Type.body, p.muted,
        )
        Gap(4.dp)
        Txt("이번 운행의 목적을 골라 주세요", Type.heading)
        PurposeButtons { purpose -> core.classifyTrip(trip.id, purpose, settings.users.getOrNull(userIndex), memo, ClassifiedBy.SCREEN) }
        if (settings.users.isNotEmpty()) {
            ChipRow(lead = "④ 사용자") {
                settings.users.forEachIndexed { i, u -> Choice("${u.name}(${u.department})", i == userIndex) { userIndex = i } }
            }
        }
        Field("비고 (거래처, 용무)", memo, Modifier.fillMaxWidth()) { memo = it }
        Muted("음성으로도 됩니다: “오토브리프, 방금 운행 업무로 기록해 줘”")
    }
}

@Composable
private fun AutoConfirmCard(core: AutoBriefCore, trip: TripRecord) {
    val p = LocalPalette.current
    var changing by remember(trip.id) { mutableStateOf(false) }
    var reason by remember(trip.id) { mutableStateOf<String?>(null) }
    val auto = trip.classifiedBy == ClassifiedBy.AUTO
    Panel(border = p.purpose(trip.purpose)) {
        StatusPill(if (auto) "자동 분류" else "기록 완료", p.purpose(trip.purpose), filled = true)
        Txt("${withRo(trip.purpose.label)} 기록했습니다", Type.title)
        Txt(
            listOfNotNull(
                trip.autoReason,
                "${km1(TripLogbook.printedKm(trip))} km",
                "${hm(trip.startEpochMs)}~${hm(trip.endEpochMs)}",
                trip.user?.let { "${it.name}(${it.department})" },
            ).joinToString(" · "),
            Type.body, p.muted,
        )
        if (!changing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("맞아요", Modifier.weight(1f)) { core.dismissLastClosed() }
                BigButton("바꾸기", Modifier.weight(1f), outlined = true) { changing = true }
            }
        } else {
            Txt("고치는 이유", Type.bodyBold)
            ReasonChips(reason) { reason = it }
            PurposeButtons(enabled = reason != null) { purpose ->
                reason?.let { why -> core.classifyTrip(trip.id, purpose, trip.user, trip.memo, ClassifiedBy.SCREEN, why) }
            }
            Muted(if (reason == null) "이유를 먼저 고르면 목적 버튼이 켜집니다." else "고친 내역은 지우지 않고 기록 원장에 '목적 수정'으로 남습니다.")
        }
    }
}

@Composable
private fun ReadyCard(core: AutoBriefCore, last: TripRecord?, vehicleStalled: Boolean) {
    val p = LocalPalette.current
    val v by core.vehicle.collectAsState()
    val ready = v.sdkConnected && v.gear != Gear.UNKNOWN && v.odometerKm != null
    Panel {
        StatusPill(if (ready) "기록 준비 완료" else "차량 연결을 기다리는 중", if (ready) p.ok else p.pending, filled = true)
        Txt(
            if (vehicleStalled) "차량 정보를 기다리고 있습니다. 1분이 넘으면 앱을 다시 열어 주세요" else "시동을 걸고 기어를 D로 바꾸면 기록이 시작됩니다",
            Type.title,
        )
        Muted("휴대폰도 GPS 기록도 없이 계기판 누적거리만 씁니다. 위치는 집, 회사, 거래처를 알아보는 데만 쓰고 저장하지 않습니다.", Type.body)
        if (last != null) {
            Gap(4.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Muted("마지막 운행", Type.body)
                PurposeChip(last.purpose)
                Txt("${mdhm(last.startEpochMs)} · ${km1(TripLogbook.printedKm(last))} km", Type.body)
            }
        }
    }
}

@Composable
private fun PendingCard(core: AutoBriefCore, pending: List<TripRecord>, onOpen: (Tab) -> Unit) {
    val settings by core.settings.collectAsState()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("목적을 정해야 할 운행 ${pending.size}건", Type.heading)
            Spring()
            Choice("전체 보기", false) { onOpen(Tab.TRIPS) }
        }
        Muted("미분류는 업무로 계산하지 않습니다.")
        val user: CarUser? = settings.users.getOrNull(settings.defaultUserIndex)
        pending.take(3).forEach { t ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Txt("${mdhm(t.startEpochMs)} · ${km1(TripLogbook.printedKm(t))} km", Type.bodyBold)
                    Muted("계기판 ${km1(t.startOdometerKm)} → ${km1(t.endOdometerKm)}")
                }
                PurposeButtons(outlined = true, height = 76.dp) { purpose -> core.classifyTrip(t.id, purpose, user, t.memo, ClassifiedBy.SCREEN) }
            }
        }
    }
}

@Composable
private fun MonthCard(core: AutoBriefCore) {
    val p = LocalPalette.current
    core.trips.collectAsState().value
    val month = YearMonth.from(core.today())
    val s = core.summary(TaxPeriods.month(month))
    Panel {
        Muted("${month.monthValue}월 업무사용비율", Type.body)
        if (s.rowCount > 0) {
            Txt(pct(s.businessUseRatio), Type.hero, p.accent)
            PurposeBar(s)
            Txt("총 ${km1(s.totalKm)} km · ${s.rowCount}건 · 업무용(출퇴근 포함) ${km1(s.businessUseKm)} km", Type.body)
            Muted("자동 분류 ${s.autoCount}건 · 음성 ${s.voiceCount}건 · 미분류 ${s.unclassifiedCount}건" + if (s.gapRowCount > 0) " · 미기록 구간 ${s.gapRowCount}건" else "")
        } else {
            Muted("이번 달 운행이 아직 없습니다.", Type.body)
        }
    }
}

@Composable
private fun TaxCard(core: AutoBriefCore, onOpen: (Tab) -> Unit) {
    val p = LocalPalette.current
    core.trips.collectAsState().value
    val settings by core.settings.collectAsState()
    val r = core.taxBenefit()
    val year = core.currentBusinessYear()
    Panel {
        Muted("올해 운행기록부 효과 (사업연도 ${year.start} ~ ${year.endInclusive})", Type.body)
        if (r == null) {
            Txt("연간 차량비용을 넣으면 계산합니다", Type.heading)
            Muted("설정에서 감가상각비(리스·렌트료 포함), 유류비, 보험료, 수리비, 자동차세, 통행료 합계를 넣어 주세요.")
            BigButton("설정 열기", outlined = true, height = 76.dp) { onOpen(Tab.SETTINGS) }
            return@Panel
        }
        if (r.extraDeductibleKrw > 0) {
            Txt("+${manwon(r.extraDeductibleKrw)}", Type.hero, p.ok)
            Txt("비용으로 더 인정 · 세금 약 ${manwon(r.taxEffectKrw)} 줄어듦", Type.heading)
            Muted("세율 ${"%.1f".format(r.ratePct)}%(지방소득세 포함) 가정", Type.small)
        } else {
            Txt("기록부 없이도 비용 인정이 같거나 큰 구간", Type.heading)
            val cap = manwon(TaxBenefit.NO_LOGBOOK_CAP_KRW)
            Muted(
                when {
                    settings.annualCarCostKrw <= TaxBenefit.NO_LOGBOOK_CAP_KRW ->
                        "연간 차량비용이 $cap 이하이면 운행기록부가 없어도 업무사용비율 100%로 봅니다. 기록은 세무서 요구에 대비한 증빙으로 남깁니다."
                    r.withLogbook.ratio <= r.withoutLogbook.ratio -> "업무사용비율이 $cap ÷ 차량비용보다 낮아 기록부 효과가 없습니다."
                    else -> "업무용 감가상각비 연 ${manwon(TaxBenefit.DEPRECIATION_CAP_KRW)} 한도에 걸려 기록부로 더 인정되는 금액이 없습니다."
                },
            )
        }
        KeyValue("운행기록부 없이 인정", "${manwon(r.withoutLogbook.deductibleKrw)} (비율 ${pct(r.withoutLogbook.ratio)})")
        KeyValue("운행기록부로 인정", "${manwon(r.withLogbook.deductibleKrw)} (비율 ${pct(r.withLogbook.ratio)})", p.accent)
        Muted(
            "연간 차량비용 ${manwon(settings.annualCarCostKrw)}, 감가상각비 ${manwon(settings.annualDepreciationKrw)} 기준. " +
                "업무용 감가상각비의 연 ${manwon(TaxBenefit.DEPRECIATION_CAP_KRW)} 초과분은 다음 해로 넘어갑니다. 최종 판단은 세무 대리인과 확인하세요.",
        )
        Muted("일반 법인 기준(임대업 중심 법인 500만/400만 규정, 연중 취득 차량 제외)")
    }
}
