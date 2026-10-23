package app.autobrief.car.ui

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.DisplayMasking
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.CustodyEndReason
import app.autobrief.car.model.Gear
import app.autobrief.car.model.SeatPos
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Panel = Color(0xFF1C2230)
private val Accent = Color(0xFF6FB7FF)
private val Muted = Color(0xFFA9B4C6)
private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun AutoBriefScreen(
    core: AutoBriefCore,
    startTab: MutableState<Int>,
    consentRequest: MutableState<ConsentRequest?>,
    onExported: () -> Unit,
    exportDir: File,
) {
    val restricted by core.restricted.collectAsState()
    val vehicle by core.vehicle.collectAsState()
    val demo = core.settings.collectAsState().value.demoMode
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color(0xFF0E121A), surface = Panel)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("오토브리프", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        if (vehicle.sdkConnected) "차량 연결됨" else "차량 연결 대기",
                        color = if (vehicle.sdkConnected) Accent else Muted, fontSize = 18.sp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("기어 ${gearLabel(vehicle.gear)}  계기판 ${vehicle.odometerKm?.let { "%,.1f km".format(it) } ?: "-"}", color = Muted, fontSize = 18.sp)
                }
                Spacer(Modifier.height(12.dp))
                if (demo) Text("시연 모드: 가상 신호 버튼이 켜져 있고, 가족 알림은 보내지 않습니다.", color = Color(0xFFFFC56B), fontSize = 17.sp)
                if (restricted) {
                    DrivingView(core)
                    return@Column
                }
                val titles = listOf("운행기록", "수면 케어", "대리운전", "설정")
                TabRow(selectedTabIndex = startTab.value) {
                    titles.forEachIndexed { i, t ->
                        Tab(selected = startTab.value == i, onClick = { startTab.value = i }, text = { Text(t, fontSize = 18.sp) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (startTab.value) {
                        0 -> LogbookTab(core, exportDir, onExported)
                        1 -> SleepTab(core)
                        2 -> CustodyTab(core, consentRequest)
                        else -> SettingsTab(core)
                    }
                }
            }
        }
    }
}

/** While driving (UX restrictions active): glanceable status only, no lists or text entry. */
@Composable
private fun DrivingView(core: AutoBriefCore) {
    val sleep by core.sleep.collectAsState()
    val custody by core.custody.collectAsState()
    core.custodyTick.collectAsState().value
    Card {
        Text("주행 중에는 화면 조작을 줄였습니다.", fontSize = 22.sp)
        sleep.driverHint?.let { Text(it, fontSize = 22.sp, color = Accent) }
        custody?.let {
            Text("대리운전 모드 기록 중  과속 ${it.overspeedEpisodes}회  급가속 ${it.harshAccelEvents}회  급감속 ${it.harshBrakeEvents}회", fontSize = 22.sp)
        }
        Text("주행 목적은 도착 후 화면에서 고르거나 음성으로 말씀해 주세요.", fontSize = 18.sp, color = Muted)
    }
}

// ------------------------------------------------------------------------------------------- Mode 1

@Composable
private fun LogbookTab(core: AutoBriefCore, exportDir: File, onExported: () -> Unit) {
    val trips by core.trips.collectAsState()
    val settings by core.settings.collectAsState()
    var periodChoice by remember { mutableStateOf(0) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now(app.autobrief.car.domain.KST)
    val fy = settings.fiscalYearStartMonth
    val year = if (today.monthValue >= fy) today.year else today.year - 1
    val periods = listOf(
        "이번 달" to TaxPeriods.month(today.year, today.monthValue),
        "상반기" to TaxPeriods.half(year, fy, 1),
        "하반기" to TaxPeriods.half(year, fy, 2),
        "사업연도" to TaxPeriods.businessYear(year, fy),
        "지난 사업연도" to TaxPeriods.businessYear(year - 1, fy),
    )
    val (periodName, period) = periods[periodChoice]
    val wholeYear = periodChoice >= 3

    val pending = trips.filter { it.purpose == TripPurpose.UNCLASSIFIED }.sortedByDescending { it.startEpochMs }
    Card {
        Text("목적을 정해야 할 주행 ${pending.size}건", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (pending.isEmpty()) Text("모든 주행의 목적이 정해져 있습니다.", color = Muted)
        pending.take(5).forEach { trip -> PendingTrip(core, trip, settings.users, settings.defaultUserIndex) }
    }
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEachIndexed { i, (name, _) ->
                if (i == periodChoice) Button(onClick = {}) { Text(name) } else OutlinedButton(onClick = { periodChoice = i }) { Text(name) }
            }
        }
        val s = core.summary(period)
        Text("$periodName (${period.start} ~ ${period.endInclusive})", color = Muted)
        if (s.rowCount == 0) {
            Text("이 기간의 운행 기록이 없습니다.", fontSize = 18.sp)
        } else {
            Text("⑪ 총주행 거리 ${"%,.1f".format(s.totalKm)} km", fontSize = 18.sp)
            Text("⑫ 업무용 사용거리 ${"%,.1f".format(s.businessUseKm)} km (출퇴근 ${"%,.1f".format(s.commuteKm)}, 일반 업무 ${"%,.1f".format(s.businessKm)})", fontSize = 18.sp)
            Text("⑬ 업무사용비율 ${s.businessUseRatio?.let { "%.1f%%".format(it * 100) } ?: "-"}", fontSize = 18.sp, color = Accent)
            Text("기록 ${s.rowCount}건, 미분류 ${s.unclassifiedCount}건, 미기록 구간 ${s.gapRowCount}건, 계기판 연속성 끊김 ${s.continuityBreaks}곳", color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = s.rowCount > 0, onClick = {
            val sheet = core.exportSheet(period, businessYear = wholeYear)
            val base = "운행기록부_${period.start}_${period.endInclusive}"
            exportDir.mkdirs()
            val xlsx = LogbookExport.toXlsx(sheet)
            File(exportDir, "$base.xlsx").writeBytes(xlsx)
            File(exportDir, "$base.csv").writeBytes(LogbookExport.toCsv(sheet))
            val queued = core.queueArchive(period, "$base.xlsx", Base64.encodeToString(xlsx, Base64.NO_WRAP))
            if (queued) onExported()
            exportResult = "차량에 저장: $base.xlsx" + if (queued) " (보관 주소로 전송 대기)" else " (보관 주소 미설정)"
        }) { Text("운행기록부 만들기 (국세청 서식 항목)") }
        exportResult?.let { Text(it, color = Muted) }
    }
}

@Composable
private fun PendingTrip(core: AutoBriefCore, trip: TripRecord, users: List<CarUser>, defaultIndex: Int) {
    var memo by remember(trip.id) { mutableStateOf("") }
    var userIndex by remember(trip.id) { mutableStateOf(defaultIndex.coerceIn(0, (users.size - 1).coerceAtLeast(0))) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "${TIME.format(java.time.Instant.ofEpochMilli(trip.startEpochMs).atZone(app.autobrief.car.domain.KST))}  " +
                "${"%,.1f".format(trip.startOdometerKm)} → ${"%,.1f".format(trip.endOdometerKm)} km (${"%.1f".format(trip.distanceKm)} km)" +
                (trip.destinationName?.let { "  목적지 ${DisplayMasking.mask(it)}" } ?: "") +
                (trip.routeTollEstimateKrw?.takeIf { it > 0 }?.let { "  내비 예상 통행료 ${"%,d".format(it)}원" } ?: ""),
            fontSize = 17.sp,
        )
        OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("비고(거래처, 목적)") }, singleLine = true)
        if (users.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("④사용자", color = Muted)
                users.forEachIndexed { i, u ->
                    val label = "${u.name}(${u.department})"
                    if (i == userIndex) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { userIndex = i }) { Text(label) }
                }
            }
        } else {
            Text("설정 탭에서 사용자(④)를 먼저 등록해 주세요.", color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL).forEach { p ->
                OutlinedButton(onClick = { core.classifyTrip(trip.id, p, users.getOrNull(userIndex), memo, "화면") }) { Text(p.label) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- Mode 2

@Composable
private fun SleepTab(core: AutoBriefCore) {
    val settings by core.settings.collectAsState()
    val vehicle by core.vehicle.collectAsState()
    val sleep by core.sleep.collectAsState()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("동승자 수면 케어", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Switch(checked = settings.sleepCareEnabled, onCheckedChange = { core.setSleepCare(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("잠들면 실내 조명 낮추기", fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Switch(checked = settings.dimAmbientOnSleep, onCheckedChange = { core.setDimAmbient(it) })
        }
        val refused by core.ambientRefused.collectAsState()
        if (vehicle.ambientBrightnessBounds == null || refused) Text("이 차량은 조명 조절을 앱에 열어 두지 않았습니다.", color = Muted)
        sleep.driverHint?.let { Text(it, fontSize = 19.sp, color = Accent) }
        Text("좌석별 스피커를 끄는 기능은 외부 앱에 열려 있지 않아, 순정 '후석 취침 모드' 안내와 조용한 알림, 오토브리프 기능의 음성비서 응답을 효과음으로 바꾸는 것으로 대신합니다.", color = Muted)
    }
    Card {
        SeatPos.values().forEach { pos ->
            val s = vehicle.seats[pos]
            val status = when {
                pos in sleep.sleeping -> "잠든 것 같음"
                pos in sleep.pending -> "등받이 젖힘 확인 중"
                s?.occupied == true -> "깨어 있음"
                else -> "빈 좌석"
            }
            Text(
                "${pos.label}: $status  (착석 ${yn(s?.occupied)}, 벨트 ${yn(s?.belted)}, 등받이 기울기 ${s?.reclineRatio?.let { "%.0f%%".format(it * 100) } ?: "-"}, 독서등 ${yn(s?.readingLightOn)})",
                fontSize = 17.sp,
            )
        }
    }
    if (settings.demoMode) DemoSeatSignals(core)
}

/** Virtual Console has no backrest or per-seat belt card: in demo mode these buttons inject labelled demo values. */
@Composable
private fun DemoSeatSignals(core: AutoBriefCore) {
    Card {
        Text("시연용 가상 좌석 신호", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("가상 콘솔에 등받이와 좌석별 벨트 항목이 없어, 시연할 때만 값을 넣습니다. 실제 차량에서는 SDK 값을 씁니다.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                core.onVehicle(VehicleEvent.BackrestBounds(SeatPos.values().associateWith { 0..100 }))
                core.onVehicle(VehicleEvent.SeatOccupancy(SeatPos.ROW2_RIGHT, true))
                core.onVehicle(VehicleEvent.SeatBelt(SeatPos.ROW2_RIGHT, true))
                core.onVehicle(VehicleEvent.ReadingLight(SeatPos.ROW2_RIGHT, false))
                core.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_RIGHT, 28))
            }) { Text("2열 오른쪽 등받이 젖힘(가상)") }
            OutlinedButton(onClick = { core.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_RIGHT, 95)) }) { Text("다시 세움(가상)") }
        }
    }
}

// ------------------------------------------------------------------------------------------- Mode 3

@Composable
private fun CustodyTab(core: AutoBriefCore, consentRequest: MutableState<ConsentRequest?>) {
    val settings by core.settings.collectAsState()
    val session by core.custody.collectAsState()
    val reports by core.reports.collectAsState()
    val outbox by core.outbox.collectAsState()
    val destination by core.destination.collectAsState()
    core.custodyTick.collectAsState().value
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val active = session
    Card {
        Text("대리운전, 발레 모드", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("순정 내비게이션은 그대로 쓰고, 이 앱은 주행 기록과 차주가 등록한 연락처로의 출발, 도착 알림을 맡습니다. 동, 호수는 이 앱 화면에서만 가립니다.", color = Muted)
        if (active == null) {
            if (settings.custodyPinHash.isEmpty()) Text("먼저 설정 탭에서 차주 PIN을 정해 주세요(기사님이 모드를 끄지 못하게 합니다).", color = Accent)
            Button(enabled = settings.custodyPinHash.isNotEmpty(), onClick = { consentRequest.value = ConsentRequest(notifyFamily = true) }) {
                Text("대리운전 모드 시작")
            }
        } else {
            Text("기록 중  목적지 ${active.destinationLabel ?: "내비 목적지 없음"}  남은 거리 ${destination?.remainDistanceM?.let { "%.1f km".format(it / 1000.0) } ?: "-"}", fontSize = 18.sp)
            Text("최고 속도 ${"%.0f".format(active.maxSpeedKph)} km/h  과속 ${active.overspeedEpisodes}회  급가속 ${active.harshAccelEvents}회  급감속 ${active.harshBrakeEvents}회  중간 정차 ${active.intermediateStops}회", fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8) }, label = { Text("차주 PIN") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    message = when (core.endCustodyEarly(pin)) {
                        AutoBriefCore.EndResult.ENDED -> "도착 전에 종료했습니다(가족에게 '도착 전 종료'로 알립니다)."
                        AutoBriefCore.EndResult.WRONG_PIN -> "PIN이 맞지 않습니다."
                        AutoBriefCore.EndResult.LOCKED -> "PIN을 여러 번 틀려 잠시 막았습니다. 몇 분 뒤 다시 시도해 주세요."
                        AutoBriefCore.EndResult.NOT_ACTIVE -> null
                    }
                    pin = ""
                }) { Text("차주 PIN으로 종료") }
            }
            if (settings.demoMode) VirtualDrivingSignals(core)
        }
        message?.let { Text(it, color = Accent) }
    }
    reports.lastOrNull()?.let { r ->
        Card {
            Text("최근 기록", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                (if (r.endReason == CustodyEndReason.ARRIVED) "도착 후 주차" else "도착 전 차주 종료") +
                    "  주행 ${r.distanceKm?.let { "%.0f km".format(it) } ?: "-"}  과속 ${r.overspeedEpisodes}회  급가속 ${r.harshAccelEvents}회  급감속 ${r.harshBrakeEvents}회  문 잠김 ${yn(r.doorsLocked)}",
            )
        }
    }
    val pending = outbox.filter { it.deliveredEpochMs == null }
    Card {
        Text("알림 보내기 상태", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (settings.familyWebhookUrl.isBlank()) Text("알림 받을 주소(웹훅)가 없어 알림을 보내지 않습니다.", color = Muted)
        Text("전송 완료 ${outbox.size - pending.size}건, 대기 ${pending.size}건" + (pending.lastOrNull()?.lastError?.let { " (마지막 오류: $it)" } ?: ""))
    }

    consentRequest.value?.let { req ->
        var notify by remember(req) { mutableStateOf(req.notifyFamily) }
        AlertDialog(
            onDismissRequest = { consentRequest.value = null },
            title = { Text("기사님 동의가 필요합니다") },
            text = {
                Column {
                    Text("이 차는 대리운전 동안 주행 거리, 속도, 과속, 급가속, 급감속 횟수와 도착 여부를 기록합니다. 아래를 켜면 출발과 도착을 차주가 등록한 연락처에도 알립니다. 동의하지 않으면 기록을 시작하지 않습니다.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = notify, onCheckedChange = { notify = it })
                        Text("출발, 도착 알림 보내기")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (core.startCustody(driverConsented = true, notifyFamily = notify) == null) {
                        message = "먼저 설정 탭에서 차주 PIN을 정해 주세요."
                    }
                    consentRequest.value = null
                }) { Text("동의하고 시작") }
            },
            dismissButton = {
                TextButton(onClick = {
                    core.startCustody(driverConsented = false, notifyFamily = false)
                    message = "기사님이 동의하지 않아 기록을 시작하지 않았습니다."
                    consentRequest.value = null
                }) { Text("동의하지 않음") }
            },
        )
    }
}

/** Virtual Console has no vehicle-speed card: one labelled demo hard brake for the emulator demo. */
@Composable
private fun VirtualDrivingSignals(core: AutoBriefCore) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            core.onVehicle(VehicleEvent.Speed(52.0))
            delay(1_000)
            core.onVehicle(VehicleEvent.Speed(34.0))
        }
    }) { Text("시연용 가상 급감속 1회") }
    // the emulator's navigation may never report arrival on its own
    OutlinedButton(onClick = { core.onNavi(NaviEvent.Arrived(null)) }) { Text("시연용 가상 도착 신호") }
}

// ------------------------------------------------------------------------------------------- settings

@Composable
private fun SettingsTab(core: AutoBriefCore) {
    val settings by core.settings.collectAsState()
    val session by core.custody.collectAsState()
    if (session != null) {
        Card { Text("대리운전 모드 중에는 설정을 바꿀 수 없습니다. 도착하거나 차주 PIN으로 종료한 뒤 바꿔 주세요.", fontSize = 18.sp) }
        return
    }
    var entity by remember { mutableStateOf(settings.entityName) }
    var bizNo by remember { mutableStateOf(settings.businessNumber) }
    var model by remember { mutableStateOf(settings.vehicleModel) }
    var plate by remember { mutableStateOf(settings.plateNumber) }
    var fyStart by remember { mutableStateOf(settings.fiscalYearStartMonth.toString()) }
    var users by remember { mutableStateOf(settings.users) }
    var defaultUser by remember { mutableStateOf(settings.defaultUserIndex) }
    var userName by remember { mutableStateOf("") }
    var userDept by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf(settings.archiveWebhookUrl) }
    var family by remember { mutableStateOf(settings.familyWebhookUrl) }
    var demoMode by remember { mutableStateOf(settings.demoMode) }
    var newPin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    Card {
        Text("운행기록부 머리글", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Field("법인명(상호)", entity) { entity = it }
        Field("사업자등록번호", bizNo) { bizNo = it }
        Field("차종", model) { model = it }
        Field("자동차등록번호", plate) { plate = it }
        Field("사업연도 시작 월(1~12)", fyStart) { fyStart = it.filter(Char::isDigit).take(2) }
        Text("사용자(④), 고른 사람이 기본값입니다", fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            users.forEachIndexed { i, u ->
                val label = "${u.name}(${u.department})"
                if (i == defaultUser) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { defaultUser = i }) { Text(label) }
            }
        }
        Row {
            Field("사용자 성명", userName) { userName = it }
            Spacer(Modifier.width(8.dp))
            Field("부서", userDept) { userDept = it }
        }
        OutlinedButton(onClick = {
            if (userName.isNotBlank()) users = users + CarUser(userName.trim(), userDept.trim())
            userName = ""; userDept = ""
        }) { Text("사용자 추가") }
    }
    Card {
        Text("보내기 주소(차주가 만든 HTTPS 웹훅)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Field("운행기록부 보관 주소", archive) { archive = it.trim() }
        Field("가족 알림 주소", family) { family = it.trim() }
        Text("웹훅이 받은 내용을 클라우드 저장소, 이메일, 메신저로 넘깁니다. 차량은 문자를 직접 보내지 않습니다.", color = Muted)
    }
    Card {
        Text("차주 PIN과 시연 모드", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Field("새 PIN 4~8자리 (바꿀 때만)", newPin) { newPin = it.filter(Char::isDigit).take(8) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시연 모드(가상 신호 버튼, 가족 알림 끔)", fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Switch(checked = demoMode, onCheckedChange = { demoMode = it })
        }
        if (core.custodyPinSet) {
            Field("현재 PIN (PIN, 보내기 주소, 시연 모드를 바꿀 때)", currentPin) { currentPin = it.filter(Char::isDigit).take(8) }
        }
    }
    Button(onClick = {
        val month = fyStart.toIntOrNull()?.coerceIn(1, 12) ?: 1
        val next = settings.copy(
            entityName = entity.trim(), businessNumber = bizNo.trim(), vehicleModel = model.trim(), plateNumber = plate.trim(),
            fiscalYearStartMonth = month, users = users, defaultUserIndex = defaultUser,
            archiveWebhookUrl = archive, familyWebhookUrl = family, demoMode = demoMode,
        )
        saved = when (core.saveOwnerSettings(next, newPin.takeIf { it.length >= 4 }, currentPin)) {
            AutoBriefCore.SettingsResult.SAVED -> "저장했습니다."
            AutoBriefCore.SettingsResult.PIN_REQUIRED -> "PIN, 보내기 주소, 시연 모드를 바꾸려면 현재 PIN이 필요합니다."
            AutoBriefCore.SettingsResult.LOCKED_DURING_CUSTODY -> "대리운전 모드 중에는 저장할 수 없습니다."
        }
        newPin = ""; currentPin = ""
    }) { Text("저장") }
    saved?.let { Text(it, color = Accent) }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).background(Panel, RoundedCornerShape(12.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private fun yn(v: Boolean?) = when (v) { true -> "예"; false -> "아니오"; null -> "-" }

private fun gearLabel(g: Gear) = when (g) {
    Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "-"
}
