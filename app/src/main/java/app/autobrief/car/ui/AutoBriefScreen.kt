package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.model.Gear
import java.time.YearMonth

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("오늘", Icons.Filled.Home),
    TRIPS("운행 목록", Icons.Filled.List),
    REPORT("운행기록부", Icons.Filled.DateRange),
    SETTINGS("설정", Icons.Filled.Settings),
}

@Composable
fun AutoBriefScreen(core: AutoBriefCore, tab: MutableState<Tab>, onFlush: () -> Unit, night: Boolean? = null) {
    val restricted by core.restricted.collectAsState()
    if (night == null) AutoBriefTheme { Root(core, tab, restricted, onFlush) } else AutoBriefTheme(night) { Root(core, tab, restricted, onFlush) }
}

@Composable
private fun Root(core: AutoBriefCore, tab: MutableState<Tab>, restricted: Boolean, onFlush: () -> Unit) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
        val wide = maxWidth >= 1400.dp
        if (restricted) {
            DrivingView(core)
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxSize()) {
            NavRail(tab)
            Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 36.dp, top = 28.dp)) {
                StatusBar(core)
                Gap(20.dp)
                Box(Modifier.fillMaxSize()) {
                    when (tab.value) {
                        Tab.HOME -> HomeScreen(core, wide, onOpen = { tab.value = it })
                        Tab.TRIPS -> TripsScreen(core)
                        Tab.REPORT -> ReportScreen(core, onFlush)
                        Tab.SETTINGS -> SettingsScreen(core, onFlush)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(tab: MutableState<Tab>) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxHeight().width(150.dp).padding(vertical = 28.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppMark()
        Gap(18.dp)
        Tab.values().forEach { t ->
            val selected = tab.value == t
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) p.accent.copy(alpha = 0.18f) else p.bg)
                    .clickable(role = Role.Tab) { tab.value = t }
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = null, tint = if (selected) p.accent else p.muted, modifier = Modifier.size(40.dp))
                Gap(6.dp)
                Txt(t.label, Type.label, if (selected) p.accent else p.muted)
            }
        }
    }
}

/** The app mark drawn with layout only (no bitmap): an odometer window with a tick. */
@Composable
fun AppMark(size: Int = 72) {
    val p = LocalPalette.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(p.accent), contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * 0.62).dp, (size * 0.3).dp).clip(RoundedCornerShape(6.dp)).background(p.onAccent), contentAlignment = Alignment.Center) {
            Txt("km", Type.label, p.accent)
        }
    }
}

@Composable
private fun StatusBar(core: AutoBriefCore) {
    val p = LocalPalette.current
    val v by core.vehicle.collectAsState()
    val navi by core.navi.collectAsState()
    val check by core.integrity.collectAsState()
    val space by core.activeSpace.collectAsState()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("오토브리프", Type.title)
        Spacer(Modifier.width(8.dp))
        if (space == AutoBriefCore.Space.DEMO) StatusPill("시연 모드 · 가상 예시", p.pending, filled = true)
        StatusPill(if (v.sdkConnected) "차량 연결됨" else "차량 연결 대기", if (v.sdkConnected) p.ok else p.pending)
        StatusPill("기어 ${gearLabel(v.gear)}", if (v.gear == Gear.PARK) p.muted else p.accent)
        StatusPill("계기판 ${v.odometerKm?.let { km1(it) + " km" } ?: "-"}", p.commute)
        StatusPill(if (navi.location != null) "내비 위치 받는 중" else "내비 위치 없음", if (navi.location != null) p.ok else p.muted)
        StatusPill(
            when {
                !check.ok -> "기록 확인 필요"
                check.entryCount == 0 -> "기록 원장 준비됨"
                else -> "기록 무결성 정상 · ${check.code}"
            },
            if (check.ok) p.ok else p.danger,
            filled = !check.ok,
        )
    }
}

fun gearLabel(g: Gear) = when (g) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.UNKNOWN -> "-"
}

/** While driving (UX restrictions active): glanceable status only, no lists or text entry. */
@Composable
fun DrivingView(core: AutoBriefCore) {
    val p = LocalPalette.current
    val open by core.openTrip.collectAsState()
    val v by core.vehicle.collectAsState()
    val trips by core.trips.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppMark(96)
        Gap(28.dp)
        val o = open
        if (o != null) {
            Txt("운행 기록 중", Type.title, p.accent)
            Gap(8.dp)
            val delta = (v.odometerKm ?: o.startOdometerKm) - o.startOdometerKm
            Txt("+${km1(delta)} km", Type.hero.copy(fontSize = Type.hero.fontSize * 1.6f, lineHeight = Type.hero.lineHeight * 1.6f))
            Txt("출발 ${km1(o.startOdometerKm)} km" + (o.startPlace?.let { " · $it" } ?: "") + " · ${hm(o.startEpochMs)}", Type.body, p.muted)
        } else {
            Txt("주행 중에는 화면 조작을 줄였습니다", Type.title)
            val month = core.summary(TaxPeriods.month(YearMonth.from(core.today())))
            if (month.rowCount > 0 && trips.isNotEmpty()) Txt("이번 달 업무사용비율 ${pct(month.businessUseRatio)}", Type.heading, p.muted)
        }
        Gap(36.dp)
        Box(Modifier.widthIn(max = 1100.dp).clip(RoundedCornerShape(20.dp)).background(p.panel).padding(28.dp)) {
            Txt("목적은 도착해서 P로 바꾼 뒤 화면에서 고르거나, 음성으로 “오토브리프, 방금 운행 업무로 기록해”라고 말씀해 주세요.", Type.body, p.muted)
        }
        Spacer(Modifier.height(1.dp).fillMaxWidth())
    }
}
