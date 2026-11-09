package app.autobrief.car.domain

import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.Place
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Purpose rules from places and Pleos Navigation input: the navigation's HOME/OFFICE/DEFAULT bookmarks,
 * places registered in the app, and the destination entered in Pleos Navigation. Only the matched place
 * label is kept on the trip; coordinates and private/medical destination names are never stored.
 *
 * 집→회사 or 회사→집 (or navigation route to 집/회사) = 출퇴근; arriving at a client, 거래처→회사, or navigation
 * route to a client/business destination = 일반 업무; anything else (including personal/medical places) is left
 * for the driver — the classifier never guesses a purpose for a private or unknown place.
 */
object AutoClassifier {

    /** Parking spots sit around a building, not on its pin. */
    const val RADIUS_M = 300.0

    internal val PRIVATE_KEYWORDS = listOf(
        "병원", "의원", "약국", "한의원", "치과", "산부인과", "소아과", "내과", "외과", "피부과", "안과",
        "마트", "하이마트", "전자랜드", "백화점", "아울렛", "면세점", "슈퍼", "편의점", "다이소", "올리브영", "철물",
        "학교", "초등학교", "중학교", "고등학교", "대학교", "유치원", "어린이집", "학원",
        "공원", "캠핑", "호텔", "리조트", "펜션", "모텔", "골프", "컨트리클럽",
        "영화관", "극장", "성당", "교회", "사찰", "식당", "레스토랑", "카페", "베이커리", "맛집",
        "스타벅스", "투썸", "이디야", "메가커피", "빽다방", "폴바셋", "블루보틀", "할리스", "커피",
        "맥도날드", "버거킹", "롯데리아", "주유소", "세차장", "휴게소", "주차장",
        "세탁", "미용실", "헤어", "네일", "피트니스", "헬스", "요가", "필라테스",
        "노래방", "PC방", "당구장", "볼링장", "사우나", "목욕탕", "찜질방",
        "아파트", "빌라", "주택", "아파트단지", "주거단지", "빌라단지", "마을",
    )

    fun isPrivatePlaceName(rawName: String): Boolean {
        val cleaned = rawName.trim()
        if (cleaned.isBlank()) return true
        val withoutBizCompound = cleaned
            .replace("아파트형공장", "")
            .replace("아파트형 공장", "")
        return PRIVATE_KEYWORDS.any { withoutBizCompound.contains(it, ignoreCase = true) }
    }

    fun isBusinessPlaceName(rawName: String): Boolean {
        val cleaned = rawName.trim()
        if (cleaned.isBlank() || isPrivatePlaceName(cleaned)) return false
        return BUSINESS_KEYWORDS.any { cleaned.contains(it, ignoreCase = true) }
    }

    private val HOME_KEYWORDS = setOf("집", "우리집", "우리 집", "자택", "마이홈", "홈")
    private val OFFICE_KEYWORDS = setOf("회사", "본사", "당사", "사무실", "오피스", "직장")

    private val BUSINESS_KEYWORDS = listOf(
        "상사", "물산", "정밀", "물류", "전자", "산업", "테크", "시스템", "솔루션", "엔지니어링",
        "화학", "제약", "바이오", "건설", "중공업", "자동차", "모비스", "모터스", "제철", "전기",
        "통신", "네트웍스", "네트워크", "소프트", "랩스", "코퍼레이션", "홀딩스", "그룹", "파트너스",
        "컨설팅", "회계법인", "법무법인", "세무법인", "특허법인", "노무법인", "감정평가",
        "주식회사", "(주)", "㈜",
        "사옥", "아파트형공장", "아파트형 공장", "지식산업센터", "공장", "연구소", "R&D", "물류센터", "유통센터", "지사", "지점", "영업소", "사업소",
        "테크노밸리", "산업단지", "산단", "공단", "비즈니스센터", "트레이드센터", "컨벤션", "전시장",
        "시청", "구청", "도청", "군청", "세무서", "법원", "검찰청", "등기소", "특허청", "진흥원", "연구원",
        "협회", "조합", "은행", "증권", "보험", "캐피탈", "금고", "거래처", "고객사", "협력사",
    )

    private val STREET_PREFIX = Regex("^(?:\\S+[시군구동읍면리가로길]\\s+)*\\d+(?:-\\d+)?(?:번지|번길)?\\s*")
    private val CORP_AFFIX = Regex("(?:주식회사|\\(주\\)|㈜)")

    private fun isHomeInput(rawTrimmed: String): Boolean =
        rawTrimmed in HOME_KEYWORDS || HOME_KEYWORDS.any { rawTrimmed.startsWith("$it:") || rawTrimmed.startsWith("$it：") }

    private fun isOfficeInput(cleaned: String, entityName: String): Boolean {
        val ownCorp = entityName.replace(CORP_AFFIX, "").trim()
        return cleaned in OFFICE_KEYWORDS || (ownCorp.length >= 2 && cleaned.contains(ownCorp) && !isPrivatePlaceName(cleaned))
    }

    data class Result(
        val purpose: TripPurpose?,
        val reason: String?,
        val startLabel: String?,
        val endLabel: String?,
        val memo: String = "",
    )

    fun classify(start: GeoPoint?, end: GeoPoint?, places: List<Place>): Result =
        classify(start, end, places, null, null, "")

    fun classify(
        start: GeoPoint?,
        end: GeoPoint?,
        places: List<Place>,
        naviDestinationName: String?,
        naviDestinationPoint: GeoPoint? = null,
        entityName: String = "",
    ): Result {
        val from = start?.let { nearest(it, places) }
        val rawNaviTrimmed = naviDestinationName?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim().orEmpty()
        val maskedNavi = DisplayMasking.mask(rawNaviTrimmed).trim()
        val naviIsPrivate = rawNaviTrimmed.isNotEmpty() &&
            !isHomeInput(rawNaviTrimmed) &&
            !isOfficeInput(maskedNavi, entityName) &&
            isPrivatePlaceName(maskedNavi)
        val rawToByPoint = (end?.let { nearest(it, places) }) ?: (naviDestinationPoint?.let { nearest(it, places) })
        val toByPoint = if (naviIsPrivate && rawToByPoint?.kind == PlaceKind.CLIENT) null else rawToByPoint
        val pointRule = evaluatePointRule(from, toByPoint)
        if (pointRule != null) return pointRule

        val toByNavi = matchNaviDestination(naviDestinationName, places, entityName, naviDestinationPoint)
        if (toByNavi != null) {
            when {
                from?.kind == PlaceKind.HOME && toByNavi.kind == PlaceKind.OFFICE ->
                    return Result(TripPurpose.COMMUTE, "${from.label}→${toByNavi.label}", from.label, toByNavi.label)
                from?.kind == PlaceKind.OFFICE && toByNavi.kind == PlaceKind.HOME ->
                    return Result(TripPurpose.COMMUTE, "${from.label}→${toByNavi.label}", from.label, toByNavi.label)
                toByNavi.kind == PlaceKind.CLIENT ->
                    return Result(TripPurpose.BUSINESS, "내비 목적지(${toByNavi.label})", from?.label, toByNavi.label, "${toByNavi.label} 방문")
                from?.kind == PlaceKind.CLIENT && toByNavi.kind == PlaceKind.OFFICE ->
                    return Result(TripPurpose.BUSINESS, "${from.label}→${toByNavi.label}", from.label, toByNavi.label, "${from.label} 방문 후 복귀")
                toByNavi.kind == PlaceKind.OFFICE && (from == null || from.kind == PlaceKind.HOME) ->
                    return Result(TripPurpose.COMMUTE, "내비 목적지(${toByNavi.label})", from?.label, toByNavi.label)
                toByNavi.kind == PlaceKind.HOME && (from == null || from.kind == PlaceKind.OFFICE) ->
                    return Result(TripPurpose.COMMUTE, "내비 목적지(${toByNavi.label})", from?.label, toByNavi.label)
            }
        }
        val to = toByPoint ?: toByNavi
        return Result(null, null, from?.label, to?.label)
    }

    private fun evaluatePointRule(from: Place?, to: Place?): Result? = when {
        from?.kind == PlaceKind.HOME && to?.kind == PlaceKind.OFFICE ->
            Result(TripPurpose.COMMUTE, "${from.label}→${to.label}", from.label, to.label)
        from?.kind == PlaceKind.OFFICE && to?.kind == PlaceKind.HOME ->
            Result(TripPurpose.COMMUTE, "${from.label}→${to.label}", from.label, to.label)
        to?.kind == PlaceKind.CLIENT ->
            Result(TripPurpose.BUSINESS, "거래처 도착(${to.label})", from?.label, to.label, "${to.label} 방문")
        from?.kind == PlaceKind.CLIENT && to?.kind == PlaceKind.OFFICE ->
            Result(TripPurpose.BUSINESS, "${from.label}→${to.label}", from.label, to.label, "${from.label} 방문 후 복귀")
        else -> null
    }

    /**
     * Resolves a Pleos Navigation destination input ([rawName] / [point]) to a logical [Place] when it names
     * home, the user's office, a registered/bookmarked place, or a corporate/business destination.
     * Personal, medical, retail, and residential destinations return null so their text is never stored.
     */
    fun matchNaviDestination(
        rawName: String?,
        places: List<Place>,
        entityName: String = "",
        point: GeoPoint? = null,
    ): Place? {
        val rawTrimmed = rawName?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim().orEmpty()
        if (rawTrimmed.isNotEmpty()) {
            if (isHomeInput(rawTrimmed)) {
                return places.firstOrNull { it.kind == PlaceKind.HOME }
                    ?: Place("집", PlaceKind.HOME, point ?: GeoPoint(0.0, 0.0), fromNavigation = true)
            }
            val cleaned = DisplayMasking.mask(rawTrimmed).trim()
            if (cleaned.isBlank()) return null
            if (isOfficeInput(cleaned, entityName)) {
                return places.firstOrNull { it.kind == PlaceKind.OFFICE }
                    ?: Place("회사", PlaceKind.OFFICE, point ?: GeoPoint(0.0, 0.0), fromNavigation = true)
            }
            // Never store or auto-classify private, medical, retail, or residential destinations,
            // even when they sit next to a registered client or contain a client's name (e.g. "일양상사 앞 스타벅스")
            if (isPrivatePlaceName(cleaned)) return null
        }

        if (point != null) {
            nearest(point, places)?.let { return it }
        }
        if (rawTrimmed.isEmpty()) return null
        val cleaned = DisplayMasking.mask(rawTrimmed).trim()

        // Match against registered or bookmarked client places by name
        places.firstOrNull {
            it.kind == PlaceKind.CLIENT && it.label.length >= 2 && (cleaned.contains(it.label) || it.label.contains(cleaned))
        }?.let { return it }

        // Recognize corporate/business/institutional destinations entered in Navigation without prior registration
        if (BUSINESS_KEYWORDS.any { cleaned.contains(it, ignoreCase = true) }) {
            val label = cleaned.replace(STREET_PREFIX, "").trim().take(20)
            if (label.length >= 2) {
                return Place(label, PlaceKind.CLIENT, point ?: GeoPoint(0.0, 0.0), fromNavigation = true)
            }
        }
        return null
    }

    /** The closest place within [RADIUS_M]; client places win a tie with home/office only by distance. */
    fun nearest(p: GeoPoint, places: List<Place>): Place? =
        places.map { it to distanceM(p, it.point) }
            .filter { it.second <= RADIUS_M }
            .minByOrNull { it.second }
            ?.first

    /** Haversine distance in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
