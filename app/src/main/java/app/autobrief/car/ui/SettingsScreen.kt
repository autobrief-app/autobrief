package app.autobrief.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.FuelBasis
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TaxRate

@Composable
fun SettingsScreen(core: AutoBriefCore, onFlush: () -> Unit) {
    val p = LocalPalette.current
    val settings by core.settings.collectAsState()
    val navi by core.navi.collectAsState()
    val space by core.activeSpace.collectAsState()
    // the form edits a copy; switching space or loading the sample reloads it from storage
    val formKey = Triple(space, settings.entityName, settings.plateNumber)
    var entity by remember(formKey) { mutableStateOf(settings.entityName) }
    var bizNo by remember(formKey) { mutableStateOf(settings.businessNumber) }
    var model by remember(formKey) { mutableStateOf(settings.vehicleModel) }
    var plate by remember(formKey) { mutableStateOf(settings.plateNumber) }
    var fyStart by remember(formKey) { mutableStateOf(settings.fiscalYearStartMonth.toString()) }
    var users by remember(formKey, settings.users) { mutableStateOf(settings.users) }
    var defaultUser by remember(formKey) { mutableStateOf(settings.defaultUserIndex) }
    var userName by remember { mutableStateOf("") }
    var userDept by remember { mutableStateOf("") }
    var autoClassify by remember(formKey) { mutableStateOf(settings.autoClassify) }
    var cost by remember(formKey) { mutableStateOf(if (settings.annualCarCostKrw > 0) (settings.annualCarCostKrw / 10_000).toString() else "") }
    var dep by remember(formKey) { mutableStateOf(if (settings.annualDepreciationKrw > 0) (settings.annualDepreciationKrw / 10_000).toString() else "") }
    var taxRate by remember(formKey) { mutableStateOf(settings.taxRate) }
    var webhook by remember(formKey) { mutableStateOf(settings.archiveWebhookUrl) }
    var carUse by remember(formKey, settings.carUse) { mutableStateOf(settings.carUse) }
    var fuelPrice by remember(formKey, settings.fuelPriceKrwPerLiter) { mutableStateOf(settings.fuelPriceKrwPerLiter.takeIf { it > 0 }?.toString() ?: "") }
    var fuelBasis by remember(formKey, settings.fuelBasis) { mutableStateOf(settings.fuelBasis) }
    var manualKmPerLiter by remember(formKey, settings.manualKmPerLiter) {
        mutableStateOf(settings.manualKmPerLiter.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "")
    }
    var newPin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var placeName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    /** A space change (demo on or off, the sample loaded or wiped) is being made. */
    var changing by remember { mutableStateOf(false) }

    // the form scrolls; the save bar under it stays on screen
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Panel {
                        Txt("운행기록부 머리글", Type.heading)
                        Field("법인명(상호)", entity, Modifier.fillMaxWidth()) { entity = it }
                        Field("사업자등록번호", bizNo, Modifier.fillMaxWidth()) { bizNo = it }
                        Field("① 차종", model, Modifier.fillMaxWidth()) { model = it }
                        Field("② 자동차등록번호", plate, Modifier.fillMaxWidth()) { plate = it }
                        Field("사업연도 시작 월 (1~12)", fyStart, Modifier.fillMaxWidth(), numeric = true) { fyStart = it.filter(Char::isDigit).take(2) }
                    }
                    Panel {
                        Txt("④ 사용자", Type.heading)
                        Muted("고른 사람이 기본값입니다. 자동 분류된 운행에도 이 사람이 들어갑니다.")
                        ChipRow { users.forEachIndexed { i, u -> Choice("${u.name}(${u.department})", i == defaultUser) { defaultUser = i } } }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Field("성명", userName, Modifier.weight(1f)) { userName = it }
                            Field("부서", userDept, Modifier.weight(1f)) { userDept = it }
                            BigButton("추가", outlined = true, height = 76.dp) {
                                if (userName.isNotBlank()) users = users + CarUser(userName.trim(), userDept.trim())
                                userName = ""; userDept = ""
                            }
                        }
                    }
                    Panel {
                        Txt("연간 차량비용과 세율 (절세 효과 계산)", Type.heading)
                        Field("연간 차량 관련비용 (만원)", cost, Modifier.fillMaxWidth(), numeric = true) { cost = it.filter(Char::isDigit).take(6) }
                        Field("그중 감가상각비 또는 리스·렌트료의 감가상각비 상당액 (만원)", dep, Modifier.fillMaxWidth(), numeric = true) { dep = it.filter(Char::isDigit).take(6) }
                        Muted("관련비용 = 감가상각비(리스·렌트료), 유류비, 보험료, 수리비, 자동차세, 통행료 등. 세율은 지방소득세를 포함합니다.")
                        // the rate table follows the start of the business year (the one being edited above, once valid)
                        val yearStart = TaxPeriods.businessYearOf(core.today(), fyStart.toIntOrNull()?.takeIf { it in 1..12 } ?: settings.fiscalYearStartMonth).start
                        ChipRow { TaxRate.values().forEach { r -> Choice(TaxRate.pctText(r.ratePctFor(yearStart)), r == taxRate) { taxRate = r } } }
                        Muted("${taxRate.labelFor(yearStart)} · ${yearStart.year}년 ${yearStart.monthValue}월 시작 사업연도 세율")
                    }
                    Panel {
                        Txt("차량 구분", Type.heading)
                        ChipRow { CarUse.values().forEach { u -> Choice(u.label, u == carUse) { carUse = u } } }
                        if (carUse == CarUse.OWN) {
                            Muted("내 차를 업무에 쓰면 절세 계기판 대신 유류비 정산을 보여 줍니다. 업무 거리에 차량의 평균 연비와 유가를 곱해 계산합니다.")
                            Field("유가(원/L)", fuelPrice, Modifier.fillMaxWidth(), numeric = true) { fuelPrice = it.filter(Char::isDigit).take(5) }
                            Muted("오피넷 지역 평균가를 넣으세요. 예: 1,650")
                            ChipRow(lead = "정산 거리") { FuelBasis.values().forEach { b -> Choice(b.label, b == fuelBasis) { fuelBasis = b } } }
                            Field("연비 직접 입력(km/L)", manualKmPerLiter, Modifier.fillMaxWidth(), decimal = true) { v ->
                                manualKmPerLiter = v.filter { it.isDigit() || it == '.' }.take(5)
                            }
                            Muted("차량이 누적 연비를 알려 주지 않을 때만 씁니다. 차량 값이 오면 그 값을 씁니다.")
                        } else {
                            Muted("회사 차는 절세 계기판을 보여 줍니다(지금까지와 같음).")
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Panel {
                        ToggleRow("장소로 목적 자동 분류", autoClassify) { autoClassify = it }
                        Muted("집→회사, 회사→집은 출퇴근, 거래처 도착은 일반 업무로 적습니다. 위치는 장소를 알아보는 데만 쓰고 운행기록에는 장소 이름만 남깁니다.")
                        val home = navi.bookmarks.any { it.kind == PlaceKind.HOME }
                        val office = navi.bookmarks.any { it.kind == PlaceKind.OFFICE }
                        KeyValue("내비 즐겨찾기 집", if (home) "있음" else "없음", if (home) p.ok else p.muted)
                        KeyValue("내비 즐겨찾기 회사", if (office) "있음" else "없음", if (office) p.ok else p.muted)
                        if (navi.bookmarks.isEmpty() && settings.places.isEmpty()) Txt("등록한 장소가 없어 운행마다 목적을 여쭙니다", Type.body, p.pending)
                        settings.places.forEach { place ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val kindLabel = when (place.kind) { PlaceKind.HOME -> "집"; PlaceKind.OFFICE -> "회사"; PlaceKind.CLIENT -> "거래처" }
                                Txt(if (place.label == kindLabel) place.label else "${place.label} · $kindLabel", Type.body)
                                Muted("이 앱에 등록")
                                Spring()
                                Choice("삭제", false) { core.removePlace(place.label) }
                            }
                        }
                        Field("지금 위치에 붙일 이름 (예: 가상상사)", placeName, Modifier.fillMaxWidth()) { placeName = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(PlaceKind.HOME to "집으로", PlaceKind.OFFICE to "회사로", PlaceKind.CLIENT to "거래처로").forEach { (kind, label) ->
                                BigButton("지금 위치를 $label", Modifier.weight(1f), outlined = true, height = 76.dp) {
                                    val name = when (kind) { PlaceKind.HOME -> "집"; PlaceKind.OFFICE -> "회사"; PlaceKind.CLIENT -> placeName }
                                    message = when (core.registerPlaceHere(name, kind)) {
                                        AutoBriefCore.PlaceResult.SAVED -> "등록했습니다: $name"
                                        AutoBriefCore.PlaceResult.NO_LOCATION -> "내비에서 현재 위치를 받지 못했습니다. 잠시 뒤 다시 눌러 주세요."
                                        AutoBriefCore.PlaceResult.INVALID -> "거래처 이름을 먼저 적어 주세요."
                                    }
                                    placeName = ""
                                }
                            }
                        }
                    }
                    Panel {
                        Txt("보내기 주소 (회사 서버)", Type.heading)
                        Field("https:// 로 시작하는 회사 서버 주소", webhook, Modifier.fillMaxWidth()) { webhook = it.trim() }
                        Muted("기록이 생길 때마다 원장 한 줄과 해시가 이 주소로 갑니다. 차 안의 기록을 나중에 고쳐도 회사 쪽 사본과 맞지 않아 드러납니다.")
                    }
                    Panel {
                        Txt("관리자 PIN", Type.heading)
                        Field("새 PIN 4~8자리 (바꿀 때만)", newPin, Modifier.fillMaxWidth(), numeric = true) { newPin = it.filter(Char::isDigit).take(8) }
                        if (core.adminPinSet) Field("현재 PIN (보내기 주소, PIN, 시연 모드를 바꿀 때)", currentPin, Modifier.fillMaxWidth(), numeric = true) { currentPin = it.filter(Char::isDigit).take(8) }
                    }
                    Panel(border = if (space == AutoBriefCore.Space.DEMO) p.pending else null) {
                        // the other space is read off the main thread: the controls wait until the change is made
                        ToggleRow("시연 모드", space == AutoBriefCore.Space.DEMO, trackColor = p.pending, enabled = !changing) { on ->
                            val pin = currentPin.ifBlank { null }
                            changing = true
                            message = if (on) "시연 모드로 바꾸는 중…" else "실제 운행기록으로 돌아가는 중…"
                            core.launch {
                                try {
                                    message = when (setDemoMode(on, pin)) {
                                        AutoBriefCore.SettingsResult.SAVED -> if (on) "시연 모드: 실제 운행기록과 따로 저장됩니다." else "실제 운행기록으로 돌아왔습니다."
                                        AutoBriefCore.SettingsResult.PIN_REQUIRED -> "시연 모드를 켜려면 현재 관리자 PIN이 필요합니다."
                                        AutoBriefCore.SettingsResult.PIN_LOCKED -> "PIN을 여러 번 틀려 잠시 잠겼습니다. 잠시 뒤 다시 시도해 주세요."
                                        AutoBriefCore.SettingsResult.TRIP_IN_PROGRESS -> "운행 중에는 바꿀 수 없습니다. P에서 바꿔 주세요."
                                        AutoBriefCore.SettingsResult.INVALID_URL -> null
                                    }
                                } finally {
                                    changing = false
                                }
                            }
                        }
                        Muted("시연 기록은 실제 운행기록부와 다른 저장 공간에 따로 남습니다. 시연 중 차량이 움직인 거리는 실제 기록부에 '미기록 구간(비업무)'으로 남습니다.")
                        if (space == AutoBriefCore.Space.DEMO) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BigButton("가상 예시 이력 불러오기", Modifier.weight(1f), color = p.pending, onColor = p.bg, enabled = !changing, height = 76.dp) {
                                    val url = webhook.takeIf { AutoBriefCore.isAllowedEndpoint(it) } ?: ""
                                    changing = true
                                    message = "가상 예시 이력을 불러오는 중…"
                                    core.launch {
                                        try {
                                            val n = loadSampleHistory(url)
                                            onFlush()
                                            message = "가상 예시 운행 ${n}건을 불러왔습니다 (가상 회사, 가상 인물)."
                                        } finally {
                                            changing = false
                                        }
                                    }
                                }
                                BigButton("시연 기록 지우기", Modifier.weight(1f), outlined = true, color = p.danger, enabled = !changing, height = 76.dp) {
                                    changing = true
                                    core.launch {
                                        try {
                                            resetDemo()
                                            message = "시연 기록을 지웠습니다."
                                        } finally {
                                            changing = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Muted("오픈소스: AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization (Apache 2.0), Pretendard (SIL OFL 1.1). Pleos Connect SDK는 Pleos Playground 이용 조건을 따릅니다.")
            Gap(24.dp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.line))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("저장", Modifier.fillMaxWidth(0.3f)) {
                val next = settings.copy(
                    entityName = entity.trim(), businessNumber = bizNo.trim(), vehicleModel = model.trim(), plateNumber = plate.trim(),
                    fiscalYearStartMonth = fyStart.toIntOrNull()?.coerceIn(1, 12) ?: 1,
                    users = users, defaultUserIndex = defaultUser, autoClassify = autoClassify,
                    annualCarCostKrw = (cost.toLongOrNull() ?: 0) * 10_000, annualDepreciationKrw = (dep.toLongOrNull() ?: 0) * 10_000,
                    taxRate = taxRate, archiveWebhookUrl = webhook,
                    carUse = carUse, fuelPriceKrwPerLiter = fuelPrice.toIntOrNull() ?: 0, fuelBasis = fuelBasis,
                    manualKmPerLiter = manualKmPerLiter.toDoubleOrNull() ?: 0.0,
                )
                message = when (core.saveSettings(next, newPin.takeIf { it.length >= 4 }, currentPin.ifBlank { null })) {
                    AutoBriefCore.SettingsResult.SAVED -> "저장했습니다."
                    AutoBriefCore.SettingsResult.PIN_REQUIRED -> "보내기 주소나 PIN을 바꾸려면 현재 관리자 PIN이 필요합니다."
                    AutoBriefCore.SettingsResult.PIN_LOCKED -> "PIN을 여러 번 틀려 잠시 잠겼습니다. 잠시 뒤 다시 시도해 주세요."
                    AutoBriefCore.SettingsResult.INVALID_URL -> "보내기 주소는 https:// 로 시작하는 회사 서버 주소여야 합니다."
                    AutoBriefCore.SettingsResult.TRIP_IN_PROGRESS -> null
                }
                newPin = ""; currentPin = ""
                onFlush()
            }
            message?.let { Txt(it, Type.bodyBold, p.accent) }
        }
    }
}

