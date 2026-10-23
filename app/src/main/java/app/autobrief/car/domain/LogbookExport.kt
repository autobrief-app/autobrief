package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the NTS 「업무용승용차 운행기록부」 — the unnumbered annex form of the notice
 * 「업무용승용차 운행기록 방법에 관한 고시」 — column for column: ③ date (weekday), ④ user (department, name),
 * ⑤ odometer before, ⑥ odometer after, ⑦ distance, ⑧ commute, ⑨ general business, ⑩ remarks,
 * then ⑪ total, ⑫ business-use distance and ⑬ business-use ratio.
 */
object LogbookExport {

    data class Sheet(
        val title: String,
        val headerLines: List<String>,
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val footer: List<List<Any?>>,
        val note: String,
    )

    val COLUMNS = listOf(
        "③사용일자(요일)", "④사용자 부서", "④사용자 성명",
        "⑤주행 전 계기판의 거리(㎞)", "⑥주행 후 계기판의 거리(㎞)", "⑦주행거리(㎞)",
        "⑧출·퇴근용(㎞)", "⑨일반 업무용(㎞)", "⑩비고",
    )

    fun build(records: List<TripRecord>, settings: AppSettings, period: ClosedRange<LocalDate>, banner: String? = null): Sheet {
        val inPeriod = records.filter { TripLogbook.localDate(it.startEpochMs) in period }.sortedBy { it.startEpochMs }
        val summary = TripLogbook.summarize(inPeriod)
        val rows = inPeriod.map { r ->
            val km = TripLogbook.round1(r.distanceKm)
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
        val header = buildList {
            if (banner != null) add(banner)
            add("사업연도: ${period.start} ~ ${period.endInclusive}    법인명(상호): ${settings.entityName}    사업자등록번호: ${settings.businessNumber}")
            add("1. 기본정보    ①차종: ${settings.vehicleModel}    ②자동차등록번호: ${settings.plateNumber}")
            add("2. 업무용 사용비율 계산")
        }
        val footer = listOf(
            listOf("⑪사업연도 총주행 거리(㎞)", summary.totalKm),
            listOf("⑫사업연도 업무용 사용거리(㎞)", summary.businessUseKm),
            listOf("⑬업무사용비율(⑫/⑪)", summary.businessUseRatio?.let { "%.1f%%".format(it * 100) } ?: "-"),
        )
        val note = buildString {
            append("작성: 오토브리프. ⑤⑥은 차량 계기판 누적거리(Pleos Vehicle SDK) 값입니다. ")
            append("근거 서식: 국세청 「업무용승용차 운행기록 방법에 관한 고시」 별지 서식.")
            if (summary.unclassifiedCount > 0) append(" 미분류 ${summary.unclassifiedCount}건은 업무용으로 계산하지 않았습니다.")
        }
        return Sheet("업무용승용차 운행기록부", header, COLUMNS, rows, footer, note)
    }

    private fun remarks(r: TripRecord): String = buildList {
        when (r.purpose) {
            TripPurpose.PERSONAL -> add("비업무")
            TripPurpose.UNCLASSIFIED -> add("미분류")
            TripPurpose.UNLOGGED_GAP -> add("미기록 구간")
            else -> {}
        }
        if (r.designatedDriver) add("대리운전")
        if (r.classifiedBy == "음성") add("음성 지정")
        if (r.memo.isNotBlank()) add(r.memo)
    }.joinToString(", ")

    fun dateWithWeekday(d: LocalDate): String =
        "$d(${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

    fun toCsv(sheet: Sheet): ByteArray {
        val sb = StringBuilder("\uFEFF") // BOM so Excel opens the Korean text as UTF-8
        sheet.headerLines.forEach { sb.append(csv(listOf(it))).append("\r\n") }
        sb.append(csv(sheet.columns)).append("\r\n")
        sheet.rows.forEach { sb.append(csv(it)).append("\r\n") }
        sheet.footer.forEach { sb.append(csv(it)).append("\r\n") }
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

    /** Minimal OOXML workbook: inline strings, one bold style, merged title/header lines. */
    fun toXlsx(sheet: Sheet): ByteArray {
        val lastCol = colName(sheet.columns.size - 1)
        val xmlRows = StringBuilder()
        val merges = mutableListOf<String>()
        var r = 1
        fun row(cells: List<Any?>, bold: Boolean) {
            xmlRows.append("<row r=\"$r\">")
            cells.forEachIndexed { i, c -> xmlRows.append(cell("${colName(i)}$r", c, bold)) }
            xmlRows.append("</row>")
            r++
        }
        row(listOf(sheet.title), bold = true); merges += "A1:${lastCol}1"
        sheet.headerLines.forEach { line -> merges += "A$r:$lastCol$r"; row(listOf(line), bold = false) }
        row(sheet.columns, bold = true)
        sheet.rows.forEach { row(it, bold = false) }
        sheet.footer.forEach { row(it, bold = true) }
        merges += "A$r:$lastCol$r"; row(listOf(sheet.note), bold = false)

        val widths = listOf(16, 12, 10, 16, 16, 12, 12, 12, 40)
        val cols = widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>$cols</cols><sheetData>$xmlRows</sheetData><mergeCells count="${merges.size}">${merges.joinToString("") { "<mergeCell ref=\"$it\"/>" }}</mergeCells></worksheet>"""

        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="운행기록부" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="10"/><name val="맑은 고딕"/></font><font><b/><sz val="10"/><name val="맑은 고딕"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
            "xl/worksheets/sheet1.xml" to sheetXml,
        )
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

    private fun cell(ref: String, value: Any?, bold: Boolean): String {
        val style = if (bold) " s=\"1\"" else ""
        return when (value) {
            null -> ""
            is Number -> "<c r=\"$ref\"$style><v>$value</v></c>"
            else -> "<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>"
        }
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + i % 26)
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }
}
