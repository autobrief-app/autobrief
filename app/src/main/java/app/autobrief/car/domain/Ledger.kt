package app.autobrief.car.domain

import app.autobrief.car.model.CarUser
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * One append-only event of the logbook. `body` is the exact JSON text that was hashed, so any copy of
 * the entry (the car, the company server) can recompute
 * `hash = SHA-256(prevHash|seq|epochMs|kind|tripId|body)` without re-serialising anything.
 */
@Serializable
data class LedgerEntry(
    val seq: Long,
    val epochMs: Long,
    val kind: String,
    val tripId: String,
    val body: String,
    val prevHash: String,
    val hash: String,
)

object LedgerKind {
    const val TRIP = "TRIP"
    const val GAP = "GAP"
    const val CLASSIFY = "CLASSIFY"
    const val AMEND = "AMEND"
}

/** Result of re-checking the chain and comparing it with the logbook rows the screen shows. */
data class LedgerCheck(
    val ok: Boolean,
    val entryCount: Int,
    /** First 8 hex digits of the newest hash, "ABCD-1234"; "-" for an empty ledger. */
    val code: String,
    val problems: List<String>,
)

/**
 * Hash chain over every logbook change. A trip row is written once when the car parks (dashboard values),
 * and every purpose change is a new entry, so a later edit of the stored rows no longer matches the chain.
 * The chain alone cannot stop someone rewriting the whole file on the device; that is why each entry is
 * also sent to the company endpoint as it happens (an outside copy the device cannot rewrite).
 */
class Ledger(initial: List<LedgerEntry> = emptyList()) {

    private val entries = initial.sortedBy { it.seq }.toMutableList()

    val all: List<LedgerEntry> get() = entries.toList()
    val size: Int get() = entries.size
    val headHash: String get() = entries.lastOrNull()?.hash ?: GENESIS

    /** The newest entry's seq; 0 for an empty ledger. */
    val headSeq: Long get() = entries.lastOrNull()?.seq ?: 0

    fun append(kind: String, tripId: String, body: JsonObject, epochMs: Long): LedgerEntry {
        val seq = (entries.lastOrNull()?.seq ?: 0L) + 1
        val text = body.toString()
        val prev = headHash
        val entry = LedgerEntry(seq, epochMs, kind, tripId, text, prev, hashOf(prev, seq, epochMs, kind, tripId, text))
        entries += entry
        return entry
    }

    companion object {
        val GENESIS: String = "0".repeat(64)
        private val json = Json { ignoreUnknownKeys = true }

        fun hashOf(prevHash: String, seq: Long, epochMs: Long, kind: String, tripId: String, body: String): String =
            sha256("$prevHash|$seq|$epochMs|$kind|$tripId|$body")

        fun sha256(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

        private val HEX = "0123456789abcdef".toCharArray()

        /** Lower-case hex. Not String.format per byte: loading the demo sample hashes ~2,000 entries and
         *  64,000 format calls kept the main thread busy for most of a minute on a 2-core emulator. */
        fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                val v = b.toInt() and 0xff
                out[2 * i] = HEX[v ushr 4]
                out[2 * i + 1] = HEX[v and 0x0f]
            }
            return String(out)
        }

        fun code(hash: String?): String =
            if (hash == null || hash == GENESIS) "-" else hash.take(8).uppercase().let { "${it.take(4)}-${it.drop(4)}" }

        fun tripBody(r: TripRecord, vin: String?): JsonObject = buildJsonObject {
            put("id", r.id)
            put("startEpochMs", r.startEpochMs)
            put("endEpochMs", r.endEpochMs)
            put("startOdometerKm", r.startOdometerKm)
            put("endOdometerKm", r.endOdometerKm)
            put("vin", vin)
            put("source", r.source)
            put("toll", r.routeTollEstimateKrw)
            when {
                r.purpose == TripPurpose.UNLOGGED_GAP -> put("reason", r.memo)
                // a family trip read no location: it has no place fields at all, only the mark
                r.family -> put("family", true)
                else -> {
                    put("startPlace", r.startPlace)
                    put("endPlace", r.endPlace)
                }
            }
        }

        fun classifyBody(r: TripRecord): JsonObject = buildJsonObject {
            put("id", r.id)
            put("purpose", r.purpose.name)
            put("userName", r.user?.name)
            put("userDept", r.user?.department)
            put("memo", r.memo)
            put("by", r.classifiedBy)
            put("autoReason", r.autoReason)
            if (!r.family) {
                put("startPlace", r.startPlace)
                put("endPlace", r.endPlace)
            }
        }

        fun amendBody(r: TripRecord, previous: TripPurpose, reason: String): JsonObject = buildJsonObject {
            put("id", r.id)
            put("purpose", r.purpose.name)
            put("userName", r.user?.name)
            put("userDept", r.user?.department)
            put("memo", r.memo)
            put("by", r.classifiedBy)
            put("reason", reason)
            put("previousPurpose", previous.name)
        }

        /** The rows the chain describes, rebuilt from the entries alone. */
        fun replay(entries: List<LedgerEntry>): Map<String, TripRecord> = history(entries).mapValues { it.value.last() }

        /** Every state the chain gave each row, oldest first (a row's last state is what [replay] returns). */
        fun history(entries: List<LedgerEntry>): Map<String, List<TripRecord>> {
            val states = linkedMapOf<String, MutableList<TripRecord>>()
            for (e in entries.sortedBy { it.seq }) {
                val b = runCatching { json.parseToJsonElement(e.body) as JsonObject }.getOrNull() ?: continue
                val next: TripRecord = when (e.kind) {
                    LedgerKind.TRIP, LedgerKind.GAP -> TripRecord(
                        id = e.tripId,
                        startEpochMs = b.long("startEpochMs") ?: 0,
                        endEpochMs = b.long("endEpochMs") ?: 0,
                        startOdometerKm = b.double("startOdometerKm") ?: 0.0,
                        endOdometerKm = b.double("endOdometerKm") ?: 0.0,
                        purpose = if (e.kind == LedgerKind.GAP) TripPurpose.UNLOGGED_GAP else TripPurpose.UNCLASSIFIED,
                        user = null,
                        memo = if (e.kind == LedgerKind.GAP) b.str("reason") ?: "" else "",
                        startPlace = b.str("startPlace"),
                        endPlace = b.str("endPlace"),
                        source = b.str("source") ?: "",
                        routeTollEstimateKrw = b.long("toll")?.toInt(),
                        family = b.bool("family") ?: false,
                    )
                    LedgerKind.CLASSIFY, LedgerKind.AMEND -> {
                        val t = states[e.tripId]?.last() ?: continue
                        val purpose = runCatching { TripPurpose.valueOf(b.str("purpose") ?: "") }.getOrNull() ?: continue
                        val name = b.str("userName")
                        t.copy(
                            purpose = purpose,
                            user = name?.let { CarUser(it, b.str("userDept") ?: "") },
                            memo = b.str("memo") ?: "",
                            classifiedBy = b.str("by"),
                            autoReason = if (e.kind == LedgerKind.CLASSIFY) b.str("autoReason") else t.autoReason,
                            amended = t.amended || e.kind == LedgerKind.AMEND,
                            startPlace = b.str("startPlace") ?: t.startPlace,
                            endPlace = b.str("endPlace") ?: t.endPlace,
                        )
                    }
                    else -> continue
                }
                states.getOrPut(e.tripId) { mutableListOf() } += next
            }
            return states
        }

        /**
         * Every field that reaches the form (⑤⑥⑦, ⑧⑨ by purpose, ④, ⑩ remarks) matches the chain's state [t]; place
         * labels are compared when the chain carries them (set after parking without a rule they are display-only).
         */
        private fun matches(t: TripRecord, r: TripRecord): Boolean =
            t.startOdometerKm == r.startOdometerKm && t.endOdometerKm == r.endOdometerKm &&
                t.startEpochMs == r.startEpochMs && t.endEpochMs == r.endEpochMs &&
                t.purpose == r.purpose && t.user == r.user && t.memo == r.memo &&
                t.classifiedBy == r.classifiedBy && t.amended == r.amended && t.autoReason == r.autoReason &&
                t.source == r.source && t.routeTollEstimateKrw == r.routeTollEstimateKrw && t.family == r.family &&
                (t.startPlace == null || t.startPlace == r.startPlace) && (t.endPlace == null || t.endPlace == r.endPlace)

        /**
         * The rows file is written after the ledger, so the app dying between the two leaves rows behind the chain: a
         * trip missing, or a purpose older than its last CLASSIFY/AMEND. Such rows are rebuilt from the chain, which is
         * the source of truth. A row equal to no state the chain ever gave it was edited, not left behind: it stays as
         * stored, and [check] reports it. Returns the rows and how many were rebuilt.
         */
        fun repair(rows: List<TripRecord>, entries: List<LedgerEntry>): Pair<List<TripRecord>, Int> {
            if (entries.isEmpty()) return rows to 0
            val history = history(entries)
            var rebuilt = 0
            val out = rows.mapTo(mutableListOf()) { r ->
                val states = history[r.id] ?: return@mapTo r
                val last = states.last()
                if (matches(last, r) || states.dropLast(1).none { matches(it, r) }) return@mapTo r
                rebuilt++
                last.copy(startPlace = last.startPlace ?: r.startPlace, endPlace = last.endPlace ?: r.endPlace)
            }
            val shown = rows.mapTo(HashSet()) { it.id }
            history.filterKeys { it !in shown }.values.forEach { out += it.last(); rebuilt++ }
            return (if (rebuilt == 0) rows else out.sortedBy { it.startEpochMs }) to rebuilt
        }

        /** Re-hashes the whole chain and compares its replay with the rows that are shown and exported. */
        fun check(entries: List<LedgerEntry>, rows: List<TripRecord>): LedgerCheck {
            val problems = mutableListOf<String>()
            var prev = GENESIS
            var expectedSeq = 1L
            for (e in entries.sortedBy { it.seq }) {
                if (e.seq != expectedSeq) problems += "${expectedSeq}번 기록이 빠졌거나 순서가 바뀌었습니다."
                if (e.prevHash != prev) problems += "${e.seq}번 기록이 앞 기록과 이어지지 않습니다."
                if (hashOf(e.prevHash, e.seq, e.epochMs, e.kind, e.tripId, e.body) != e.hash) problems += "${e.seq}번 기록의 해시가 내용과 맞지 않습니다."
                prev = e.hash
                expectedSeq = e.seq + 1
            }
            val replayed = replay(entries)
            for (r in rows) {
                val t = replayed[r.id]
                if (t == null) {
                    problems += "${dateLabel(r.startEpochMs)} 운행이 기록 원장에 없습니다."
                    continue
                }
                if (!matches(t, r)) problems += "${dateLabel(r.startEpochMs)} 운행이 원장과 다릅니다(계기판, 목적, 사용자, 비고 또는 분류 방식)."
            }
            val shown = rows.map { it.id }.toSet()
            replayed.keys.filter { it !in shown }.forEach { problems += "원장에 있는 운행($it)이 목록에서 사라졌습니다." }
            val head = entries.maxByOrNull { it.seq }?.hash
            return LedgerCheck(problems.isEmpty(), entries.size, code(head), problems.take(20))
        }

        private fun dateLabel(epochMs: Long) = LogbookExport.dateWithWeekday(TripLogbook.localDate(epochMs))

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.double(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}
