package app.autobrief.car.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.FuelReimbursement
import app.autobrief.car.model.FuelBasis
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The settlement in one sentence, every number from the calculation:
 * "이번 달 업무 312.4km, 평균 연비 12.1km/L(100km당 8.26L, 차량 누적 연비), 유가 1,650원/L, 정산 42,580원". Only for a
 * month with an amount. The amount is computed from L/100km to two decimals, so the line prints that figure too: from
 * the km/L figure alone the sum would not come out (312.4 ÷ 12.1 × 1,650 rounds to 42,600).
 */
fun fuelLine(m: FuelReimbursement.Month, withCommute: Boolean, label: String = "이번 달"): String {
    val r = m.result
    val economy = r.litersPer100Km?.let { String.format(Locale.ROOT, "%.1f", FuelReimbursement.kmPerLiter(it)) } ?: "-"
    val per100 = r.litersPer100Km?.let { "100km당 ${String.format(Locale.ROOT, "%.2f", it)}L, " } ?: ""
    return "$label 업무${if (withCommute) "(출퇴근 포함)" else ""} ${km1(r.businessKm)}km, " +
        "평균 연비 ${economy}km/L($per100${m.economySource?.label ?: "-"}), " +
        "유가 ${r.priceKrwPerLiter?.let(::comma) ?: "-"}원/L, 정산 ${r.amountKrw?.let(::comma) ?: "-"}원"
}

/**
 * 유류비 정산 for an employee's own car (내 차 mode), in place of the tax meter: this month's amount on the home screen, the
 * chosen period on the report screen. Without a usable economy or price it says which is missing and never shows a figure
 * made up in its place. [onOpen] (home only) opens the settings where the missing value is entered.
 */
@Composable
fun FuelCard(core: AutoBriefCore, period: ClosedRange<LocalDate>, title: String, onOpen: ((Tab) -> Unit)? = null) {
    val p = AutoBriefTheme.colors
    val trips by core.trips.collectAsState()
    val energy by core.energy.collectAsState()
    val settings by core.settings.collectAsState()
    val thisMonth = YearMonth.from(core.today())
    val months = remember(trips, energy, settings, period, thisMonth) { core.fuelSettlements(period) }
    val withCommute = settings.fuelBasis == FuelBasis.WITH_COMMUTE
    Panel {
        Muted(title, Type.body)
        val missing = months.firstOrNull { it.result.missing != null }?.result?.missing
        when {
            months.isEmpty() -> Txt("이 기간에는 정산할 달이 없습니다", Type.title)
            missing == FuelReimbursement.Missing.ECONOMY && core.electricOnly -> {
                Txt("전기차 정산은 다음 버전", Type.title)
                Muted("차량이 전비(km/kWh)만 알려 줍니다. 충전 요금으로 정산하는 기능은 다음 버전에서 제공합니다.")
            }
            missing == FuelReimbursement.Missing.ECONOMY -> {
                Txt("차량 연비 값을 받지 못했습니다", Type.title)
                Muted("차량이 누적 연비를 알려 주면 그 값으로 계산합니다. 그 전에는 직접 넣은 연비로 계산할 수 있습니다.")
                onOpen?.let { open -> BigButton("연비 직접 입력(km/L)", outlined = true, height = 76.dp) { open(Tab.SETTINGS) } }
            }
            missing == FuelReimbursement.Missing.PRICE -> {
                Txt("유가를 넣으면 계산합니다", Type.title)
                Muted("설정의 유가(원/L)에 오피넷 지역 평균가를 넣으세요. 예: 1,650")
                onOpen?.let { open -> BigButton("설정 열기", outlined = true, height = 76.dp) { open(Tab.SETTINGS) } }
            }
            else -> {
                val total = months.sumOf { it.result.amountKrw ?: 0L }
                Txt("${comma(total)}원", Type.display, p.accent)
                months.takeLast(3).forEach { m ->
                    Txt(fuelLine(m, withCommute, if (m.month == thisMonth) "이번 달" else "${m.month.year}년 ${m.month.monthValue}월"), Type.body)
                }
                if (months.size > 3) Muted("나머지 달은 엑셀의 '유류비 정산' 시트에 있습니다.")
                val reading = core.fuelReading()
                Muted(
                    "정산 거리: ${settings.fuelBasis.label}. " + when {
                        months.last().economySource == FuelReimbursement.Source.VEHICLE && reading != null ->
                            "연비는 차량이 알려 준 누적 연비입니다(${mdhm(reading.atMs)} 기준)."
                        else -> "연비는 직접 입력한 값입니다."
                    },
                )
            }
        }
    }
}
