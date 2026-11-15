package app.autobrief.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.model.Gear
import app.autobrief.car.model.VehicleSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.time.YearMonth

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("오늘", Icons.Filled.Home),
    TRIPS("운행 목록", Icons.Filled.List),
    REPORT("운행기록부", Icons.Filled.DateRange),
    SETTINGS("설정", Icons.Filled.Settings),
}

/**
 * The app's screen: 불러오는 중 while the stored logbook is read when the process starts (a moment; seconds on a loaded
 * head unit), then [AutoBriefScreen].
 */
@Composable
fun AutoBriefRoot(core: StateFlow<AutoBriefCore?>, tab: MutableState<Tab>, onFlush: () -> Unit, night: Boolean? = null) {
    val loaded by core.collectAsState()
    val c = loaded
    when {
        c != null -> AutoBriefScreen(c, tab, onFlush, night)
        night == null -> AutoBriefTheme { Loading() }
        else -> AutoBriefTheme(night) { Loading() }
    }
}

@Composable
private fun Loading() {
    val p = AutoBriefTheme.colors
    Column(
        Modifier.fillMaxSize().background(p.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppMark(96)
        Gap(28.dp)
        Txt("불러오는 중", Type.headline, p.textSecondary)
    }
}

@Composable
fun AutoBriefScreen(core: AutoBriefCore, tab: MutableState<Tab>, onFlush: () -> Unit, night: Boolean? = null) {
    val restricted by core.restricted.collectAsState()
    if (night == null) AutoBriefTheme { Root(core, tab, restricted, onFlush) } else AutoBriefTheme(night) { Root(core, tab, restricted, onFlush) }
}

/**
 * The glanceable driving layout shows whenever the gear is out of P, not only while CarUxRestrictions are active:
 * the restrictions follow vehicle speed, so in D or R at a standstill they are off, yet the driver is still driving.
 */
fun drivingLayout(restricted: Boolean, gear: Gear): Boolean = restricted || (gear != Gear.PARK && gear != Gear.UNKNOWN)

/** How long the screen waits for the car's gear and odometer before it asks the driver to reopen the app. */
const val VEHICLE_WAIT_MS = 60_000L

/** True once [VEHICLE_WAIT_MS] passed with no gear or no odometer value from the car (timed on screen from the core's state). */
@Composable
fun rememberVehicleStalled(v: VehicleSnapshot): Boolean {
    val missing = v.gear == Gear.UNKNOWN || v.odometerKm == null
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(missing) {
        waited = false
        if (missing) {
            delay(VEHICLE_WAIT_MS)
            waited = true
        }
    }
    return missing && waited
}

@Composable
private fun Root(core: AutoBriefCore, tab: MutableState<Tab>, restricted: Boolean, onFlush: () -> Unit) {
    val p = AutoBriefTheme.colors
    val v by core.vehicle.collectAsState()
    val vehicleStalled = rememberVehicleStalled(v)
    // The layout follows the window the app is given, not the display: beside the navigation app the platform hands this
    // app half of the display, while the Configuration can still report all of it. The width class starts from the
    // Configuration, so the first frame draws real content (no sub-composition), and the root's measured size corrects
    // it: one more composition, and only when the class differs.
    val density = LocalDensity.current
    var measured by remember { mutableStateOf<WindowWidth?>(null) }
    val width = measured ?: windowWidthOf(LocalConfiguration.current.screenWidthDp.toFloat())
    val wide = width == WindowWidth.WIDE
    val compact = width == WindowWidth.COMPACT
    var fieldWarmed by remember { mutableStateOf(false) }
    var warmingField by remember { mutableStateOf(false) }
    LaunchedEffect(v.sdkConnected) {
        if (v.sdkConnected && !fieldWarmed) {
            delay(1_500L)
            warmingField = true
            withFrameNanos { }
            warmingField = false
            fieldWarmed = true
        }
    }
    val screen: @Composable () -> Unit = {
        when (tab.value) {
            Tab.HOME -> HomeScreen(core, wide, vehicleStalled, onOpen = { tab.value = it })
            Tab.TRIPS -> TripsScreen(core)
            Tab.REPORT -> ReportScreen(core, onFlush)
            Tab.SETTINGS -> SettingsScreen(core, onFlush)
        }
    }
    CompositionLocalProvider(LocalCompact provides compact) {
    Box(Modifier.fillMaxSize().onSizeChanged { measured = windowWidthOf(it.width / density.density) }.background(p.background)) {
        if (drivingLayout(restricted, v.gear)) {
            DrivingView(core)
            return@Box
        }
        if (warmingField) {
            // alpha(0.01f) + requiredSize(320.dp) inside a 1.dp clipped box so every child has non-zero bounds
            // and OpenGLRenderer compiles the shaders/glyphs before the first unclassified trip parks.
            Box(Modifier.size(1.dp).clip(RoundedCornerShape(1.dp)).alpha(0.01f).clearAndSetSemantics {}) {
                Column(Modifier.requiredSize(320.dp, 320.dp)) {
                    PurposeButtons {}
                    Choice("예시", true) {}
                    Field("비고", "", Modifier.fillMaxWidth()) {}
                }
            }
        }
        if (compact) {
            // the tabs along the bottom: a side rail would take a fifth of this window's width
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                    StatusBar(core)
                    Gap(16.dp)
                    Box(Modifier.fillMaxSize()) { screen() }
                }
                Box(Modifier.fillMaxWidth().height(AutoBriefTheme.shape.strokeHairline).background(p.outline))
                NavBar(tab)
            }
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            NavRail(tab)
            Box(Modifier.fillMaxHeight().width(AutoBriefTheme.shape.strokeHairline).background(p.outline))
            Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 36.dp, top = 28.dp)) {
                StatusBar(core)
                Gap(20.dp)
                Box(Modifier.fillMaxSize()) { screen() }
            }
        }
    }
    }
}

/** The window's width class, from its real width: [WIDE] draws the home screen in two columns, [COMPACT] is half of a split display. */
enum class WindowWidth { COMPACT, MEDIUM, WIDE }

const val WIDE_FROM_DP = 1400
const val COMPACT_UNDER_DP = 1000

fun windowWidthOf(widthDp: Float): WindowWidth = when {
    widthDp >= WIDE_FROM_DP -> WindowWidth.WIDE
    widthDp < COMPACT_UNDER_DP -> WindowWidth.COMPACT
    else -> WindowWidth.MEDIUM
}

@Composable
private fun NavRail(tab: MutableState<Tab>) {
    val p = AutoBriefTheme.colors
    Column(
        Modifier.fillMaxHeight().width(152.dp).background(p.surface).padding(vertical = 28.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppMark()
        Gap(18.dp)
        Tab.values().forEach { t -> NavTab(t, tab, Modifier.fillMaxWidth(), 18.dp) }
    }
}

/** The tabs of the compact window, in one row along the bottom. */
@Composable
private fun NavBar(tab: MutableState<Tab>) {
    val p = AutoBriefTheme.colors
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tab.values().forEach { t -> NavTab(t, tab, Modifier.weight(1f), 10.dp) }
    }
}

@Composable
private fun NavTab(t: Tab, tab: MutableState<Tab>, modifier: Modifier, padV: Dp) {
    val p = AutoBriefTheme.colors
    val s = AutoBriefTheme.shape
    // QUIET: no rim round a tab and line icons; the selected tab is told by its tint alone
    val quiet = AutoBriefTheme.quiet
    val selected = tab.value == t
    val shape = RoundedCornerShape(s.cornerMedium)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) p.primary.copy(alpha = p.tintAlpha) else if (quiet) Color.Transparent else p.surface)
            .then(if (quiet) Modifier else Modifier.border(s.strokeHairline, if (selected) p.primary.copy(alpha = p.emphasisBorderAlpha) else p.hairline, shape))
            .clickable(role = Role.Tab) { tab.value = t }
            .padding(vertical = padV),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (selected) p.primary else p.textSecondary
        if (quiet) LineIcon(t, tint, Modifier.size(38.dp)) else Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(38.dp))
        Gap(6.dp)
        Txt(t.label, Type.label, if (selected) p.primary else p.textSecondary)
    }
}

/** The app mark drawn with layout only (no bitmap): an OEM cockpit odometer emblem. */
@Composable
fun AppMark(size: Int = 72) {
    val p = AutoBriefTheme.colors
    val s = AutoBriefTheme.shape
    if (AutoBriefTheme.quiet) {
        PixelMark(size)
        return
    }
    // the outer corner keeps the logo's proportion (a sixth of the mark) in every variant
    val outerShape = RoundedCornerShape((size / 6).dp)
    val innerShape = RoundedCornerShape(s.cornerSmall)
    Box(
        Modifier.size(size.dp).clip(outerShape).background(p.surfaceVariant).border(s.strokeEmphasis, p.accent, outerShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size((size * 0.66).dp, (size * 0.34).dp).clip(innerShape).background(p.background).border(s.strokeHairline, p.outline, innerShape),
            contentAlignment = Alignment.Center,
        ) {
            // a logo glyph scaled with the mark, not running text
            Txt("km", Type.display.copy(fontSize = (size * 0.28).sp, lineHeight = (size * 0.28).sp), p.accent)
        }
    }
}

/** The mark of the QUIET style: four squares rising to full strength over a horizon line, the odometer counting up. */
@Composable
private fun PixelMark(size: Int) {
    val p = AutoBriefTheme.colors
    val cell = (size / 6).dp
    val gap = (size / 12).dp
    Column(Modifier.size(size.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(0.3f, 0.55f, 0.8f, 1f).forEach { strength -> Box(Modifier.size(cell).background(p.primary.copy(alpha = strength))) }
        }
        Spacer(Modifier.height(gap))
        Box(Modifier.width(cell * 4 + gap * 3).height(AutoBriefTheme.shape.strokeEmphasis).background(p.outline))
    }
}

/** The navigation icons of the QUIET style: thin lines and square pips on a 38-unit grid. */
@Composable
private fun LineIcon(tab: Tab, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 38f
        val stroke = Stroke(1.75.dp.toPx())
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width)
        fun pip(x: Float, y: Float, side: Float) = drawRect(color, Offset(x * u, y * u), Size(side * u, side * u))
        when (tab) {
            Tab.HOME -> {
                // a roof over the horizon
                val roof = Path().apply { moveTo(6f * u, 19f * u); lineTo(19f * u, 7f * u); lineTo(32f * u, 19f * u) }
                drawPath(roof, color, style = stroke)
                line(10f, 16f, 10f, 31f)
                line(28f, 16f, 28f, 31f)
                line(5f, 31f, 33f, 31f)
            }
            Tab.TRIPS -> listOf(10f, 19f, 28f).forEach { y ->
                pip(5f, y - 2.5f, 5f)
                line(14f, y, 33f, y)
            }
            Tab.REPORT -> {
                // a ruled sheet
                drawRect(color, Offset(8f * u, 5f * u), Size(22f * u, 28f * u), style = stroke)
                line(13f, 13f, 25f, 13f)
                line(13f, 19f, 25f, 19f)
                line(13f, 25f, 20f, 25f)
            }
            Tab.SETTINGS -> listOf(10f to 22f, 19f to 9f, 28f to 17f).forEach { (y, x) ->
                // three sliders
                line(5f, y, 33f, y)
                pip(x, y - 3f, 6f)
            }
        }
    }
}

/** The pills wrap onto a second line on a narrow (portrait) display instead of scrolling out of sight. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBar(core: AutoBriefCore) {
    val p = AutoBriefTheme.colors
    val v by core.vehicle.collectAsState()
    val navi by core.navi.collectAsState()
    val position by core.positionSource.collectAsState()
    val paused by core.locationPaused.collectAsState()
    val check by core.integrity.collectAsState()
    val space by core.activeSpace.collectAsState()
    val s = AutoBriefTheme.shape
    val quiet = AutoBriefTheme.quiet
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // QUIET: one quiet line beside the title, every item after a hairline; else the pills
        @Composable
        fun item(label: String, color: Color, filled: Boolean = false) {
            if (!quiet) {
                StatusPill(label, color, filled)
                return
            }
            Box(Modifier.align(Alignment.CenterVertically).width(s.strokeHairline).height(18.dp).background(p.hairline))
            Box(Modifier.align(Alignment.CenterVertically)) { StatusPill(label, color, filled, header = true) }
        }
        Txt("오토브리프", Type.headline, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
        if (space == AutoBriefCore.Space.DEMO) item("시연 모드 · 가상 예시", p.warning, filled = true)
        item(if (v.sdkConnected) "차량 연결됨" else "차량 연결 대기", if (v.sdkConnected) p.positive else p.warning)
        // a value the car has not sent yet is "being checked", with a muted dot, never a bare dash
        if (v.gear == Gear.UNKNOWN) item("기어 확인 중", p.textTertiary)
        else item("기어 ${gearLabel(v.gear)}", if (v.gear == Gear.PARK) p.textTertiary else p.accent)
        item(v.odometerKm?.let { "계기판 ${km1(it)} km" } ?: "계기판 확인 중", if (v.odometerKm != null) p.purposeCommute else p.textTertiary)
        // navigation's position, else the car's own GPS (the fallback when navigation does not follow the car)
        if (paused) {
            item("위치 읽지 않음 (가족 운행)", p.purposePersonal, filled = true)
        } else {
            item(
                when (position) {
                    AutoBriefCore.PositionSource.NAVIGATION -> "내비 위치 받는 중"
                    AutoBriefCore.PositionSource.GPS -> "차량 GPS 위치 받는 중"
                    null -> "위치 정보 없음"
                },
                if (position != null) p.positive else p.textTertiary,
            )
            navi.destinationLabel?.let { dest ->
                item("내비 목적지 · $dest", p.accent)
            }
        }
        item(
            when {
                !check.ok -> "기록 확인 필요"
                check.entryCount == 0 -> "기록 원장 준비됨"
                else -> "원장 정상 · 검증 코드 ${check.code}"
            },
            if (check.ok) p.positive else p.critical,
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

/** While driving (out of P, or UX restrictions active): glanceable status only, no lists, no text entry, no voice tips. */
@Composable
fun DrivingView(core: AutoBriefCore) {
    val p = AutoBriefTheme.colors
    val open by core.openTrip.collectAsState()
    val v by core.vehicle.collectAsState()
    val navi by core.navi.collectAsState()
    val trips by core.trips.collectAsState()
    val s = AutoBriefTheme.shape
    val cardShape = RoundedCornerShape(s.cornerHero)
    val noteShape = RoundedCornerShape(s.cornerMedium)
    // the compact window keeps its width for the figures
    val compact = LocalCompact.current
    Column(
        Modifier.fillMaxSize().padding(if (compact) 28.dp else 64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(min = 960.dp, max = 1280.dp)
                .cardFrame(cardShape)
                .padding(horizontal = if (compact) 36.dp else 64.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppMark(88)
            Gap(24.dp)
            val o = open
            if (o != null) {
                if (o.family) {
                    StatusPill(FAMILY_BADGE, p.purposePersonal, filled = true)
                    Gap(16.dp)
                } else if (navi.destinationLabel != null) {
                    StatusPill("내비 목적지 연동 · ${navi.destinationLabel}", p.positive, filled = true)
                    Gap(16.dp)
                }
                Txt("운행 기록 중", Type.headline, p.accent)
                Gap(8.dp)
                val delta = (v.odometerKm ?: o.startOdometerKm) - o.startOdometerKm
                Txt("+${km1(delta)} km", Type.glance)
                Txt("출발 ${km1(o.startOdometerKm)} km" + (o.startPlace?.let { " · $it" } ?: "") + (navi.destinationLabel?.let { " → $it" } ?: "") + " · ${hm(o.startEpochMs)}", Type.body, p.textSecondary)
            } else {
                Txt("주행 중에는 화면 조작을 줄였습니다", Type.headline)
                val month = core.summary(TaxPeriods.month(YearMonth.from(core.today())))
                if (month.rowCount > 0 && trips.isNotEmpty()) Txt("이번 달 업무사용비율 ${pct(month.businessUseRatio)}", Type.title, p.textSecondary)
            }
            Gap(32.dp)
            Box(
                Modifier
                    // QUIET: plain text under the figure, no box inside the card
                    .then(if (AutoBriefTheme.quiet) Modifier else Modifier.clip(noteShape).background(p.surfaceVariant).border(s.strokeHairline, p.outline, noteShape))
                    .padding(horizontal = 36.dp, vertical = 20.dp),
            ) {
                Txt(
                    when {
                        o?.family == true -> "도착해서 P에 두면 거리만 비업무로 남깁니다"
                        navi.destinationLabel != null -> "내비 목적지(${navi.destinationLabel}) 연동 중 · 도착 후 P에 두면 목적을 자동 기록합니다"
                        else -> "도착해서 P에 두면 목적을 여쭙니다"
                    },
                    Type.title,
                    p.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(1.dp).fillMaxWidth())
    }
}
