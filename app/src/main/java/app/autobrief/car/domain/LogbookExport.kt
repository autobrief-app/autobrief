package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.ClassifiedBy
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    )

    /**
     * ⑪ ⑫ ⑬ under the rows. ⑪ and ⑫ are the sums of the printed ⑦ and ⑧+⑨, so the printed cells always add up;
     * ⑬ = ⑫/⑪, null when there is no distance. [scope] names what the totals cover ("사업연도" on the form).
     */
    data class Totals(val scope: String, val totalKm: Double, val businessUseKm: Double, val ratio: Double?) {
        val totalLabel: String get() = "⑪$scope 총주행 거리(㎞)"
        val businessUseLabel: String get() = "⑫$scope 업무용 사용거리(㎞)"
        val ratioLabel: String get() = "⑬업무사용비율(⑫/⑪)"
        val ratioText: String get() = ratio?.let { "%.1f%%".format(it * 100) } ?: "-"
    }

    /** A plain table sheet (the ledger listing). */
    data class Table(val name: String, val columns: List<String>, val rows: List<List<Any?>>, val widths: List<Int>)

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
            append("작성: 오토브리프. ⑤⑥은 차량 계기판 누적거리(Pleos Vehicle SDK) 값이며 기록 뒤에는 바꿀 수 없고, 목적을 고친 내역은 '검증 기록' 시트에 남습니다. ")
            append("검증 코드 $verificationCode. ")
            append("근거 서식: 국세청 「업무용승용차 운행기록 방법에 관한 고시」 별지 서식.")
            if (summary.unclassifiedCount > 0) append(" 미분류 ${summary.unclassifiedCount}건은 업무용으로 계산하지 않았습니다.")
            if (summary.gapRowCount > 0) append(" 미기록 구간 ${summary.gapRowCount}건은 비업무로 계산했습니다.")
            if (!businessYear) append(" 이 파일은 사업연도 중 일부 기간의 발췌본입니다. 세무 신고에 쓰는 ⑬업무사용비율은 이 발췌 기간이 아니라 사업연도 전체의 ⑫/⑪입니다.")
        }
        return Sheet(if (businessYear) TITLE else "$TITLE (기간 발췌)", header, COLUMNS, rows, totals, note)
    }

    private fun remarks(r: TripRecord): String = buildList {
        when (r.purpose) {
            TripPurpose.PERSONAL -> add("비업무")
            TripPurpose.UNCLASSIFIED -> add("미분류")
            TripPurpose.UNLOGGED_GAP -> add("미기록 구간")
            else -> {}
        }
        when {
            r.classifiedBy == ClassifiedBy.AUTO && r.autoReason != null -> add("자동 분류(${r.autoReason})")
            r.classifiedBy == ClassifiedBy.VOICE -> add("음성 지정")
        }
        if (r.amended) add("수정됨")
        r.routeTollEstimateKrw?.takeIf { it > 0 }?.let { add("내비 예상 통행료 ${"%,d".format(it)}원") }
        if (r.memo.isNotBlank() && r.purpose != TripPurpose.UNLOGGED_GAP) add(r.memo)
    }.joinToString(", ")

    fun dateWithWeekday(d: LocalDate): String =
        "$d(${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

    /** The ledger entries that touch the period's trips, oldest first. */
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
        val user = s("userName")?.let { n -> " · $n" + (s("userDept")?.takeIf { it.isNotBlank() }?.let { "($it)" } ?: "") } ?: ""
        return when (e.kind) {
            LedgerKind.TRIP, LedgerKind.GAP -> "계기판 ${"%,.1f".format(d("startOdometerKm") ?: 0.0)} → ${"%,.1f".format(d("endOdometerKm") ?: 0.0)} km" +
                (s("source")?.takeIf { it != TripSource.SDK }?.let { " · $it" } ?: "")
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
        const val FOOT = 6
        const val FOOT_NUM = 7
        const val NOTE = 8
        const val FOOT_VALUE = 9
    }

    /** Workbook with the logbook form (borders, number format, landscape A4 fit-to-width) and optional tables. */
    fun toXlsx(sheet: Sheet, tables: List<Table> = emptyList()): ByteArray {
        val sheets = listOf(sheet.title.take(31) to formXml(sheet)) + tables.map { it.name.take(31) to tableXml(it) }
        val headerRows = listOf(sheet.headerLines.size + 2) + tables.map { 1 }
        val printTitles = sheets.indices.joinToString("") { i ->
            "<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"$i\">'${xml(sheets[i].first)}'!\$${headerRows[i]}:\$${headerRows[i]}</definedName>"
        }
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
        sheets.forEachIndexed { i, (_, body) -> files["xl/worksheets/sheet${i + 1}.xml"] = body }

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

    private class Grid(val lastCol: String) {
        val rows = StringBuilder()
        val merges = mutableListOf<String>()
        var r = 1
        fun row(cells: List<Pair<Any?, Int>>) {
            rows.append("<row r=\"$r\">")
            cells.forEachIndexed { i, (v, s) -> rows.append(cell("${colName(i)}$r", v, s)) }
            rows.append("</row>")
            r++
        }
        fun mergedLine(text: String, style: Int) {
            merges += "A$r:$lastCol$r"
            row(listOf(text to style))
        }
    }

    private fun formXml(sheet: Sheet): String {
        val g = Grid(colName(sheet.columns.size - 1))
        g.mergedLine(sheet.title, Style.TITLE)
        sheet.headerLines.forEach { g.mergedLine(it, Style.HEADER_LINE) }
        g.row(sheet.columns.map { it to Style.TH })
        val headerRow = g.r - 1
        sheet.rows.forEach { cells -> g.row(cells.map { it to if (it is Number) Style.TD_NUM else Style.TD }) }
        val t = sheet.totals
        listOf(t.totalLabel to t.totalKm, t.businessUseLabel to t.businessUseKm, t.ratioLabel to t.ratioText).forEach { (label, value) ->
            // label spans ③~⑥, value sits under ⑦
            g.merges += "A${g.r}:E${g.r}"
            g.row(listOf(label to Style.FOOT, null to Style.FOOT, null to Style.FOOT, null to Style.FOOT, null to Style.FOOT, value to if (value is Number) Style.FOOT_NUM else Style.FOOT_VALUE))
        }
        g.mergedLine(sheet.note, Style.NOTE)
        val widths = listOf(15, 11, 10, 14, 14, 13, 15, 15, 44)
        return worksheet(widths, g, frozenRows = headerRow, landscape = true)
    }

    private fun tableXml(t: Table): String {
        val g = Grid(colName(t.columns.size - 1))
        g.row(t.columns.map { it to Style.TH })
        t.rows.forEach { cells -> g.row(cells.map { it to if (it is Number) Style.TD_NUM else Style.TD }) }
        return worksheet(t.widths, g, frozenRows = 1, landscape = true)
    }

    private fun worksheet(widths: List<Int>, g: Grid, frozenRows: Int, landscape: Boolean): String {
        val cols = widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")
        val pane = "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"$frozenRows\" topLeftCell=\"A${frozenRows + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
        val merges = if (g.merges.isEmpty()) "" else "<mergeCells count=\"${g.merges.size}\">${g.merges.joinToString("") { "<mergeCell ref=\"$it\"/>" }}</mergeCells>"
        return HEAD + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>$pane<cols>$cols</cols><sheetData>${g.rows}</sheetData>$merges" +
            "<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>" +
            "<pageSetup paperSize=\"9\" orientation=\"${if (landscape) "landscape" else "portrait"}\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>"
    }

    private fun cell(ref: String, value: Any?, style: Int): String = when (value) {
        null -> "<c r=\"$ref\" s=\"$style\"/>"
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

    /** cellXfs order matches [Style]: 0 default, 1 title, 2 header line, 3 th, 4 td, 5 td number, 6 footer, 7 footer number, 8 note, 9 footer value. */
    private val STYLES = HEAD + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.0\"/></numFmts>" +
        "<fonts count=\"3\"><font><sz val=\"10\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"10\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"14\"/><name val=\"맑은 고딕\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEF1F6\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border><left style=\"thin\"><color rgb=\"FF8A94A6\"/></left><right style=\"thin\"><color rgb=\"FF8A94A6\"/></right><top style=\"thin\"><color rgb=\"FF8A94A6\"/></top><bottom style=\"thin\"><color rgb=\"FF8A94A6\"/></bottom><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"10\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>" +
        "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
}
