package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.FuelBasis
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import app.autobrief.car.model.TripSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.SortedMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

/**
 * Writes the NTS 「업무용승용차 운행기록부」 — the unnumbered annex form of the notice
 * 「업무용승용차 운행기록 방법에 관한 고시」 — column for column: ③ date (weekday), ④ user (department, name),
 * ⑤ odometer before, ⑥ odometer after, ⑦ distance, ⑧ commute, ⑨ general business, ⑩ remarks,
 * then ⑪ total, ⑫ business-use distance and ⑬ business-use ratio. A second sheet lists the ledger
 * entries behind the rows (who set each purpose, every correction, the hashes).
 */
object LogbookExport {

    data class Sheet(
        val title: String,
        val headerLines: List<String>,
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val totals: Totals,
        val note: String,
        /** Printed at the top of every page: the car on the left, the period on the right. */
        val pageHeader: Pair<String, String>,
    )

    /**
     * ⑪ ⑫ ⑬ under the rows. ⑪ and ⑫ are the sums of the printed ⑦ and ⑧+⑨, so the printed cells always add up;
     * ⑬ = ⑫/⑪, null when there is no distance. [scope] names what the totals cover ("사업연도" on the form).
     */
    data class Totals(val scope: String, val totalKm: Double, val businessUseKm: Double, val ratio: Double?) {
        val totalLabel: String get() = "⑪$scope 총주행 거리(㎞)"
        val businessUseLabel: String get() = "⑫$scope 업무용 사용거리(㎞)"
        val ratioLabel: String get() = "⑬업무사용비율(⑫/⑪)"
        val ratioText: String get() = ratio?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "-"
    }

    /** A plain table sheet (the ledger listing); [note] is printed under the rows across the whole width. */
    data class Table(val name: String, val columns: List<String>, val rows: List<List<Any?>>, val widths: List<Int>, val note: String? = null)

    /** A number printed with two decimals (the settlement's L/100 km, which the amount is computed from). */
    data class Fixed2(val value: Double)

    val COLUMNS = listOf(
        "③사용일자(요일)", "④사용자 부서", "④사용자 성명",
        "⑤주행 전 계기판의 거리(㎞)", "⑥주행 후 계기판의 거리(㎞)", "⑦주행거리(㎞)",
        "⑧출·퇴근용(㎞)", "⑨일반 업무용(㎞)", "⑩비고",
    )

    const val TITLE = "업무용승용차 운행기록부"

    private val json = Json { ignoreUnknownKeys = true }
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * [businessYear] = the period is a whole 사업연도 (the form's own unit); otherwise it is labelled as a period extract.
     * A business year that has not ended by [exportDate] is labelled as the total to that date, never as the year's
     * total (작성방법 10: ⑪~⑬ are business-year totals). Without [exportDate] the end of the latest record in [records]
     * (the whole logbook) stands in for it, so a year with records after its end counts as finished.
     */
    fun build(
        records: List<TripRecord>,
        settings: AppSettings,
        period: ClosedRange<LocalDate>,
        verificationCode: String,
        businessYear: Boolean = true,
        exportDate: LocalDate? = null,
    ): Sheet {
        val asOf = exportDate ?: records.maxOfOrNull { it.endEpochMs }?.let(TripLogbook::localDate)
        val running = if (businessYear && asOf != null && period.endInclusive.isAfter(asOf)) "$asOf 현재 누계(사업연도 진행 중)" else null
        val unit = when {
            !businessYear -> "발췌 기간"
            running != null -> running
            else -> "사업연도"
        }
        val year = if (businessYear) period else TaxPeriods.businessYearOf(period.start, settings.fiscalYearStartMonth)
        val inPeriod = records.filter { TripLogbook.localDate(it.startEpochMs) in period }.sortedBy { it.startEpochMs }
        val summary = TripLogbook.summarize(inPeriod)
        val rows = inPeriod.map { r ->
            val km = TripLogbook.printedKm(r) // printed ⑥ − printed ⑤
            listOf(
                dateWithWeekday(TripLogbook.localDate(r.startEpochMs)),
                r.user?.department ?: "",
                r.user?.name ?: "",
                TripLogbook.round1(r.startOdometerKm),
                TripLogbook.round1(r.endOdometerKm),
                km,
                if (r.purpose == TripPurpose.COMMUTE) km else null,
                if (r.purpose == TripPurpose.BUSINESS) km else null,
                remarks(r),
            )
        }
        val sample = inPeriod.any { it.source == TripSource.SAMPLE }
        val header = buildList {
            if (sample) add("※ 가상 예시 데이터입니다. 실제 차량, 법인, 인물이 아닙니다.")
            add("사업연도: ${year.start} ~ ${year.endInclusive}${running?.let { ", $it" } ?: ""}    법인명(상호): ${settings.entityName}    사업자등록번호: ${settings.businessNumber}")
            if (!businessYear) add("발췌 기간: ${period.start} ~ ${period.endInclusive}")
            add("1. 기본정보    ①차종: ${settings.vehicleModel}    ②자동차등록번호: ${settings.plateNumber}")
            add("2. 업무용 사용비율 계산")
        }
        val totals = Totals(unit, summary.totalKm, summary.businessUseKm, summary.businessUseRatio)
        val note = buildString {
            append("작성: 오토브리프. ⑤⑥은 차량 계기판 누적거리(Pleos Vehicle SDK)를 0.1㎞ 단위로 반올림한 값이며 기록 뒤에는 바꿀 수 없고, 목적을 고친 내역은 '검증 기록' 시트에 남습니다. ")
            append("검증 코드 $verificationCode. ")
            append("근거 서식: 국세청 「업무용승용차 운행기록 방법에 관한 고시」 별지 서식.")
            if (summary.unclassifiedCount > 0) append(" 미분류 ${summary.unclassifiedCount}건은 업무용으로 계산하지 않았습니다.")
            if (summary.gapRowCount > 0) append(" 미기록 구간 ${summary.gapRowCount}건은 비업무로 계산했습니다.")
            append(" 계기판 연속성 끊김 ${summary.continuityBreaks}곳(앞 행의 ⑥과 다음 행의 ⑤가 0.05㎞ 넘게 다른 곳).")
            if (!businessYear) append(" 이 파일은 사업연도 중 일부 기간의 발췌본입니다. 세무 신고에 쓰는 ⑬업무사용비율은 이 발췌 기간이 아니라 사업연도 전체의 ⑫/⑪입니다.")
        }
        val pagePeriod = if (businessYear) "사업연도 ${period.start} ~ ${period.endInclusive}${running?.let { ", $it" } ?: ""}"
        else "발췌 기간 ${period.start} ~ ${period.endInclusive}"
        val title = if (businessYear) TITLE else "$TITLE (기간 발췌)"
        return Sheet(title, header, COLUMNS, rows, totals, note, "자동차등록번호 ${settings.plateNumber}" to pagePeriod)
    }

    private fun remarks(r: TripRecord): String = if (r.family) familyRemarks(r) else buildList {
        when (r.purpose) {
            TripPurpose.PERSONAL -> add("비업무")
            TripPurpose.UNCLASSIFIED -> add("미분류")
            TripPurpose.UNLOGGED_GAP -> add(gapLabel(r))
            else -> {}
        }
        when {
            r.classifiedBy == ClassifiedBy.AUTO && r.autoReason != null -> add("자동 분류(${r.autoReason})")
            r.classifiedBy == ClassifiedBy.VOICE -> add("음성 지정")
        }
        if (r.amended) add("수정됨")
        if (r.memo.isNotBlank() && r.purpose != TripPurpose.UNLOGGED_GAP) add(r.memo)
    }.joinToString(", ")

    /**
     * ⑩ of a family trip (가족 운행): no location was read for it. As recorded it is 개인 운행; corrected to a business purpose
     * (⑧ or ⑨ then holds the distance) it still says that no location was recorded. The default memo is not repeated.
     */
    private fun familyRemarks(r: TripRecord): String = buildList {
        add(if (r.purpose == TripPurpose.PERSONAL) FAMILY_REMARK else NO_LOCATION_REMARK)
        if (r.amended) add("수정됨")
        if (r.memo.isNotBlank() && r.memo != AutoBriefCore.FAMILY_MEMO) add(r.memo)
    }.joinToString(", ")

    const val FAMILY_REMARK = "개인 운행(위치 기록 안 함)"
    const val NO_LOCATION_REMARK = "위치 기록 안 함"

    /** A gap row is dated by its start in ③; when the unlogged stretch runs over days, ⑩ gives the whole range. */
    private fun gapLabel(r: TripRecord): String {
        val from = TripLogbook.localDate(r.startEpochMs)
        val to = TripLogbook.localDate(r.endEpochMs)
        return if (to == from) "미기록 구간" else "미기록 구간 ${dateWithWeekday(from)} ~ ${dateWithWeekday(to)}"
    }

    fun dateWithWeekday(d: LocalDate): String =
        "$d(${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

    /** The ledger entries that touch the period's trips, oldest first; a trip's entry also shows the navigation's toll estimate. */
    fun ledgerTable(entries: List<LedgerEntry>, records: List<TripRecord>, period: ClosedRange<LocalDate>, headCode: String): Table {
        val ids = records.filter { TripLogbook.localDate(it.startEpochMs) in period }.map { it.id }.toSet()
        val rows = entries.filter { it.tripId in ids }.sortedBy { it.seq }.map { e ->
            listOf(
                e.seq,
                STAMP.format(Instant.ofEpochMilli(e.epochMs).atZone(KST)),
                kindLabel(e.kind),
                dateWithWeekday(TripLogbook.localDate(records.first { it.id == e.tripId }.startEpochMs)),
                describe(e),
                e.hash.take(16),
                e.prevHash.take(16),
            )
        } + listOf(
            listOf(null, null, null, null, "전체 원장의 검증 코드 $headCode, 해시 = SHA-256(이전 해시|순번|시각|종류|운행 ID|내용)", null, null),
        )
        return Table(
            "검증 기록",
            listOf("순번", "기록 시각(KST)", "종류", "운행일", "내용", "해시(앞 16자리)", "이전 해시(앞 16자리)"),
            rows,
            listOf(8, 17, 12, 15, 60, 20, 20),
        )
    }

    const val FUEL_SHEET = "유류비 정산"

    /**
     * 유류비 정산 (내 차 mode): one row per calendar month, its business distance, the economy in L/100km (two decimals, the
     * figure the amount is computed from) and km/L, where the economy came from, the fuel price and the amount; a total
     * row under them. A month without an economy or a price shows "-" and the note says why: no figure is assumed.
     */
    fun fuelTable(
        months: List<FuelReimbursement.Month>,
        basis: FuelBasis,
        economy: FuelReimbursement.Economy?,
        economyAtMs: Long?,
        exportDate: LocalDate,
    ): Table {
        fun dash(v: Any?): Any = v ?: "-"
        val rows: List<List<Any?>> = months.map { m ->
            val r = m.result
            listOf(
                m.month.toString(),
                r.businessKm,
                dash(r.litersPer100Km?.let(::Fixed2)),
                dash(r.litersPer100Km?.let { TripLogbook.round1(FuelReimbursement.kmPerLiter(it)) }),
                m.economySource?.label ?: "없음",
                dash(r.priceKrwPerLiter),
                dash(r.amountKrw),
            )
        }
        val complete = months.isNotEmpty() && months.all { it.result.amountKrw != null }
        val total = listOf(
            "합계", TripLogbook.round1(months.sumOf { it.result.businessKm }), null, null, null, null,
            if (complete) months.sumOf { it.result.amountKrw!! } else "-",
        )
        val note = buildString {
            append(if (basis == FuelBasis.WITH_COMMUTE) "정산 거리: 출퇴근(⑧)과 일반 업무(⑨). " else "정산 거리: 일반 업무(⑨)만. ")
            append("유류비 = 업무 거리 × 연비(L/100km) ÷ 100 × 유가, 연비는 소수 둘째 자리, 금액은 10원 단위로 반올림합니다. ")
            append("연비와 유가는 이 파일을 만든 $exportDate 앱에 있던 값입니다")
            when (economy?.source) {
                FuelReimbursement.Source.VEHICLE -> append(
                    economyAtMs?.let { "(차량 누적 연비, ${STAMP.format(Instant.ofEpochMilli(it).atZone(KST))} 차량이 알려 준 값)." } ?: "(차량 누적 연비).",
                )
                FuelReimbursement.Source.MANUAL -> append("(연비는 직접 입력한 값).")
                null -> append(". 차량 연비 값을 받지 못했고 직접 입력한 연비도 없어 금액을 비워 두었습니다.")
            }
            if (economy != null && months.any { it.result.missing == FuelReimbursement.Missing.PRICE }) append(" 유가를 넣지 않아 금액을 비워 두었습니다.")
        }
        return Table(
            FUEL_SHEET,
            listOf("월", "업무 거리(km)", "연비(L/100km)", "연비(km/L)", "연비 출처", "유가(원/L)", "정산 금액(원)"),
            rows + listOf(total),
            listOf(10, 14, 14, 12, 16, 12, 16),
            note,
        )
    }

    private fun kindLabel(kind: String) = when (kind) {
        LedgerKind.TRIP -> "운행 기록"
        LedgerKind.GAP -> "미기록 구간"
        LedgerKind.CLASSIFY -> "목적 지정"
        LedgerKind.AMEND -> "목적 수정"
        else -> kind
    }

    private fun describe(e: LedgerEntry): String {
        val b = runCatching { json.parseToJsonElement(e.body) as JsonObject }.getOrNull() ?: return e.body
        fun s(k: String) = (b[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun d(k: String) = (b[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        fun purpose(k: String) = s(k)?.let { runCatching { TripPurpose.valueOf(it).label }.getOrNull() } ?: "-"
        val family = (b["family"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        val user = s("userName")?.let { n -> " · $n" + (s("userDept")?.takeIf { it.isNotBlank() }?.let { "($it)" } ?: "") } ?: ""
        return when (e.kind) {
            LedgerKind.TRIP, LedgerKind.GAP -> "계기판 ${app.autobrief.car.ui.km1(d("startOdometerKm") ?: 0.0)} → ${app.autobrief.car.ui.km1(d("endOdometerKm") ?: 0.0)} km" +
                (s("source")?.takeIf { it != TripSource.SDK }?.let { " · $it" } ?: "") +
                (d("toll")?.takeIf { it > 0 }?.let { " · 내비 예상 통행료 ${app.autobrief.car.ui.comma(it.toLong())}원" } ?: "") +
                (if (family) ", family=true(가족 운행, 위치 기록 안 함)" else "")
            LedgerKind.CLASSIFY -> purpose("purpose") + user + " · ${s("by") ?: "-"}" + (s("autoReason")?.let { "($it)" } ?: "")
            LedgerKind.AMEND -> "${purpose("previousPurpose")} → ${purpose("purpose")}$user · ${s("by") ?: "-"} · 사유: ${s("reason") ?: "-"}"
            else -> e.body
        }
    }

    fun toCsv(sheet: Sheet): ByteArray {
        val sb = StringBuilder("\uFEFF") // BOM so Excel opens the Korean text as UTF-8
        sheet.headerLines.forEach { sb.append(csv(listOf(it))).append("\r\n") }
        sb.append(csv(sheet.columns)).append("\r\n")
        sheet.rows.forEach { sb.append(csv(it)).append("\r\n") }
        val t = sheet.totals
        listOf(listOf(t.totalLabel, t.totalKm), listOf(t.businessUseLabel, t.businessUseKm), listOf(t.ratioLabel, t.ratioText))
            .forEach { sb.append(csv(it)).append("\r\n") }
        sb.append(csv(listOf(sheet.note))).append("\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun csv(cells: List<Any?>): String = cells.joinToString(",") { c ->
        val s = when (c) {
            null -> ""
            is Double -> if (c % 1.0 == 0.0) c.toLong().toString() else c.toString()
            else -> c.toString()
        }
        // a cell starting with = + - @ would run as a formula when the CSV is opened in a spreadsheet
        val safe = if (c is String && s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    // ------------------------------------------------------------------------------------ OOXML writer

    private object Style {
        const val TITLE = 1
        const val HEADER_LINE = 2
        const val TH = 3
        const val TD = 4
        const val TD_NUM = 5
        const val SHADE = 6
        const val TOTAL_NUM = 7
        const val NOTE = 8
        const val TOTAL_PCT = 9
        const val TOTAL_TEXT = 10
        const val TD_INT = 11
        const val TD_NUM2 = 12
    }

    /** Column widths of the form sheet, ③ to ⑩. */
    private val FORM_WIDTHS = listOf(15, 11, 10, 14, 14, 13, 15, 15, 44)
    private const val LINE_PT = 15.0

    /** A formula cell and the value it evaluates to, cached so viewers that do not recalculate still show it. */
    private class Formula(val expr: String, val cached: Double)

    /** A worksheet part and the rows its printed pages repeat at the top. */
    private class Part(val xml: String, val titleRows: IntRange)

    /**
     * Workbook with the logbook form (the form's three-row column header and totals block, A4 landscape one page wide,
     * the car and the period on every page) and optional tables.
     */
    fun toXlsx(sheet: Sheet, tables: List<Table> = emptyList()): ByteArray {
        val page = headerFooter(sheet.pageHeader)
        val sheets = listOf(sheet.title.take(31) to formXml(sheet, page)) + tables.map { it.name.take(31) to tableXml(it, page) }
        val printTitles = sheets.mapIndexed { i, (name, part) ->
            "<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"$i\">'${xml(name)}'!\$${part.titleRows.first}:\$${part.titleRows.last}</definedName>"
        }.joinToString("")
        val files = linkedMapOf<String, String>()
        files["[Content_Types].xml"] = HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"
        files["_rels/.rels"] = HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        files["xl/workbook.xml"] = HEAD + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            sheets.mapIndexed { i, (name, _) -> "<sheet name=\"${xml(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
            "</sheets><definedNames>$printTitles</definedNames></workbook>"
        files["xl/_rels/workbook.xml.rels"] = HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        files["xl/styles.xml"] = STYLES
        sheets.forEachIndexed { i, (_, part) -> files["xl/worksheets/sheet${i + 1}.xml"] = part.xml }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Cells placed by row and column; a block is merged, with its value in the top-left cell and its style on every cell. */
    private class Grid(private val columns: Int) {
        private val cells = sortedMapOf<Int, SortedMap<Int, Pair<Any?, Int>>>()
        private val heights = mutableMapOf<Int, Double>()
        val merges = mutableListOf<String>()

        /** The first row below everything placed so far. */
        var next = 1
            private set

        fun put(row: Int, col: Int, value: Any?, style: Int) {
            cells.getOrPut(row) { sortedMapOf() }[col] = value to style
            next = maxOf(next, row + 1)
        }

        fun block(row: Int, col: Int, lastRow: Int, lastCol: Int, value: Any?, style: Int) {
            for (r in row..lastRow) for (c in col..lastCol) put(r, c, if (r == row && c == col) value else null, style)
            if (lastRow > row || lastCol > col) merges += "${colName(col)}$row:${colName(lastCol)}$lastRow"
        }

        /** A new row of single cells. */
        fun row(values: List<Any?>, style: (Any?) -> Int) {
            val r = next
            values.forEachIndexed { c, v -> put(r, c, v, style(v)) }
        }

        /** A new merged line across the sheet, as tall as its wrapped text needs. */
        fun line(text: String, style: Int, widths: List<Int>, minPt: Double = 0.0) {
            val r = next
            block(r, 0, r, columns - 1, text, style)
            height(r, maxOf(minPt, heightFor(listOf(text to widths.sum()))))
        }

        fun height(row: Int, pt: Double) {
            heights[row] = pt
        }

        fun xml(): String = buildString {
            cells.forEach { (r, row) ->
                append("<row r=\"$r\"")
                heights[r]?.let { append(" ht=\"$it\" customHeight=\"1\"") }
                append(">")
                row.forEach { (c, cell) -> append(cellXml("${colName(c)}$r", cell.first, cell.second)) }
                append("</row>")
            }
        }
    }

    private fun bodyStyle(value: Any?): Int = when (value) {
        is Fixed2 -> Style.TD_NUM2
        is Int, is Long -> Style.TD_INT
        is Number -> Style.TD_NUM
        else -> Style.TD
    }

    private fun formXml(sheet: Sheet, page: String): Part {
        val g = Grid(COLUMNS.size)
        g.line(sheet.title, Style.TITLE, FORM_WIDTHS, minPt = 26.0)
        sheet.headerLines.forEach { g.line(it, Style.HEADER_LINE, FORM_WIDTHS) }
        // the column header over three rows, as on the form: 운 행 내 역 over ⑤~⑩, 업무용 사용거리 over ⑧⑨
        val h = g.next
        listOf(0, 1, 2).forEach { c -> g.block(h, c, h + 2, c, COLUMNS[c], Style.TH) }
        g.block(h, 3, h, 8, "운 행 내 역", Style.TH)
        listOf(3, 4, 5, 8).forEach { c -> g.block(h + 1, c, h + 2, c, COLUMNS[c], Style.TH) }
        g.block(h + 1, 6, h + 1, 7, "업무용 사용거리(㎞)", Style.TH)
        g.put(h + 2, 6, COLUMNS[6], Style.TH)
        g.put(h + 2, 7, COLUMNS[7], Style.TH)
        g.height(h, 20.0)
        g.height(h + 1, 20.0)
        g.height(h + 2, 30.0)
        val first = g.next
        sheet.rows.forEach { g.row(it, ::bodyStyle) }
        totals(g, sheet.totals, first, g.next - 1)
        g.line(sheet.note, Style.NOTE, FORM_WIDTHS)
        return Part(worksheet(FORM_WIDTHS, g, frozenRows = h + 2, page), h..h + 2)
    }

    /**
     * The form's totals block: labels, then values, ⑪ under ⑤~⑦, ⑫ under ⑧⑨, ⑬ under ⑩, with ③ and ④ shaded.
     * ⑪ and ⑫ are SUMs over the printed ⑦ and ⑧⑨ cells and ⑬ divides them, so the printed figures always reconcile.
     */
    private fun totals(g: Grid, t: Totals, first: Int, last: Int) {
        val label = g.next
        val value = label + 1
        g.block(label, 0, value, 0, null, Style.SHADE)
        g.block(label, 1, value, 2, null, Style.SHADE)
        g.block(label, 3, label, 5, t.totalLabel, Style.TH)
        g.block(label, 6, label, 7, t.businessUseLabel, Style.TH)
        g.put(label, 8, t.ratioLabel, Style.TH)
        val hasRows = last >= first
        g.block(value, 3, value, 5, if (hasRows) Formula("SUM(F$first:F$last)", t.totalKm) else t.totalKm, Style.TOTAL_NUM)
        g.block(value, 6, value, 7, if (hasRows) Formula("SUM(G$first:H$last)", t.businessUseKm) else t.businessUseKm, Style.TOTAL_NUM)
        if (t.ratio != null) g.put(value, 8, Formula("G$value/D$value", t.ratio), Style.TOTAL_PCT) else g.put(value, 8, "-", Style.TOTAL_TEXT)
        g.height(label, heightFor(listOf(t.totalLabel to FORM_WIDTHS.subList(3, 6).sum(), t.businessUseLabel to FORM_WIDTHS.subList(6, 8).sum(), t.ratioLabel to FORM_WIDTHS[8])))
        g.height(value, 20.0)
    }

    private fun tableXml(t: Table, page: String): Part {
        val g = Grid(t.columns.size)
        g.row(t.columns) { Style.TH }
        t.rows.forEach { g.row(it, ::bodyStyle) }
        t.note?.let { g.line(it, Style.NOTE, t.widths) }
        return Part(worksheet(t.widths, g, frozenRows = 1, page), 1..1)
    }

    /** Rows Excel will not grow to fit (merged cells) get a height from the text's estimated width in column units. */
    private fun heightFor(cells: List<Pair<String, Int>>): Double =
        cells.maxOf { (text, width) -> ceil(units(text) / (width * 0.9)).coerceAtLeast(1.0) } * LINE_PT + 4.0

    /** Width in column units (one '0' of the 10 pt font): Hangul, circled numbers and other wide characters count about two. */
    private fun units(text: String): Double = text.sumOf { ch ->
        when {
            ch == ' ' -> 0.6
            ch.code < 0x1100 -> 1.0
            else -> 1.9
        }
    }

    /** Every printed page: the car on the left and the period on the right at the top, the page number at the bottom. */
    private fun headerFooter(pageHeader: Pair<String, String>): String {
        fun literal(s: String) = s.replace("&", "&&") // a single & starts a header/footer code
        val top = "&L" + literal(pageHeader.first) + "&R" + literal(pageHeader.second)
        return "<headerFooter><oddHeader>${xml(top)}</oddHeader><oddFooter>${xml("&C페이지 &P / &N")}</oddFooter></headerFooter>"
    }

    private fun worksheet(widths: List<Int>, g: Grid, frozenRows: Int, page: String): String {
        val cols = widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")
        val pane = "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"$frozenRows\" topLeftCell=\"A${frozenRows + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
        val merges = if (g.merges.isEmpty()) "" else "<mergeCells count=\"${g.merges.size}\">${g.merges.joinToString("") { "<mergeCell ref=\"$it\"/>" }}</mergeCells>"
        return HEAD + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>$pane<cols>$cols</cols><sheetData>${g.xml()}</sheetData>$merges" +
            "<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>" +
            "<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>$page</worksheet>"
    }

    private fun cellXml(ref: String, value: Any?, style: Int): String = when (value) {
        null -> "<c r=\"$ref\" s=\"$style\"/>"
        is Formula -> "<c r=\"$ref\" s=\"$style\"><f>${xml(value.expr)}</f><v>${value.cached}</v></c>"
        is Fixed2 -> "<c r=\"$ref\" s=\"$style\"><v>${value.value}</v></c>"
        is Number -> "<c r=\"$ref\" s=\"$style\"><v>$value</v></c>"
        else -> "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>"
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + i % 26)
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    /** cellXfs order matches [Style]: 0 default, then title … integer cell. */
    private val STYLES = HEAD + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"3\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.0\"/><numFmt numFmtId=\"165\" formatCode=\"0.0%\"/><numFmt numFmtId=\"166\" formatCode=\"#,##0.00\"/></numFmts>" +
        "<fonts count=\"3\"><font><sz val=\"10\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"10\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"14\"/><name val=\"맑은 고딕\"/></font></fonts>" +
        "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEF1F6\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFBFBFBF\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border><left style=\"thin\"><color rgb=\"FF8A94A6\"/></left><right style=\"thin\"><color rgb=\"FF8A94A6\"/></right><top style=\"thin\"><color rgb=\"FF8A94A6\"/></top><bottom style=\"thin\"><color rgb=\"FF8A94A6\"/></bottom><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"13\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"165\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
}
