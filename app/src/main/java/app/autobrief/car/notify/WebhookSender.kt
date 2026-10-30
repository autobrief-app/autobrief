package app.autobrief.car.notify

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.model.OutboxItem
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delivers queued items (every ledger entry, period workbooks) to the endpoint the owner or company set: their own
 * server, which keeps an outside copy of the chain and forwards workbooks to storage or e-mail. An item counts as
 * delivered only after an HTTP 2xx.
 *
 * - Ledger entries go out in seq order (not by the queueing clock, which a head unit can move), and the first entry
 *   not sent (it failed, or it still waits out its pause) holds back the rest, so the receiver never sees a later
 *   entry before an earlier one. A failing entry is tried again after a pause that grows from 5 s to 10 min, for as
 *   long as it takes: an entry is never given up.
 * - Workbooks go out after them, each on its own pause, and are given up after [MAX_ARCHIVE_ATTEMPTS] failures.
 * - A round works from one snapshot of the space, its address and its queue; it ends when the space or the address
 *   changes, and starts no send after [ROUND_MS] (one send can take 20 s with the timeouts below).
 */
class WebhookSender(
    private val core: AutoBriefCore,
    /** The queue's clock (epoch ms), for the pauses between attempts. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Milliseconds that only move forward, for the round's time cap. */
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    private val post: (url: String, body: String, key: String) -> Pair<Boolean, String?> = ::httpPost,
) {
    /** Sends what is due; call from a background thread. True when the round stopped at its cap with items still due. */
    @Synchronized
    fun flush(maxMs: Long = ROUND_MS): Boolean {
        val snapshot = core.outboxSnapshot()
        if (snapshot.url.isBlank() || !AutoBriefCore.isAllowedEndpoint(snapshot.url)) return false
        val round = Round(snapshot, maxMs)
        try {
            val pending = snapshot.items.filter { it.deliveredEpochMs == null }
            for (item in pending.filter { it.kind == LEDGER }.sortedBy(::seqOf)) {
                if (!round.due(item) || !round.send(item)) break
            }
            if (!round.ended) {
                for (item in pending.filter { it.kind != LEDGER }) {
                    if (round.due(item) && !round.send(item)) break
                }
            }
        } finally {
            round.markResults()
        }
        return round.capped
    }

    private inner class Round(private val snapshot: AutoBriefCore.OutboxSnapshot, private val maxMs: Long) {
        private val started = monotonic()
        private val at = now()
        private val results = ArrayList<AutoBriefCore.Delivery>()
        private var sent = 0
        var capped = false
            private set
        var ended = false
            private set

        /** Past its pause; a clock set back does not hold an item for hours. */
        fun due(item: OutboxItem): Boolean {
            val last = item.lastAttemptEpochMs ?: return true
            return at < last || at - last >= backoffMs(item.attempts)
        }

        /** False when [item] failed or the round has to end (cap reached, space or address changed). */
        fun send(item: OutboxItem): Boolean {
            if (monotonic() - started >= maxMs || sent >= MAX_PER_FLUSH) {
                capped = true
                ended = true
                return false
            }
            if (!core.isCurrent(snapshot)) {
                ended = true
                return false
            }
            val (ok, error) = post(snapshot.url, snapshot.body(item), item.key)
            sent++
            results += AutoBriefCore.Delivery(item.key, ok, error)
            // results are written in batches: a crash re-sends at most one batch, which the receiver drops by key
            if (results.size >= MARK_EVERY) markResults()
            return ok
        }

        fun markResults() {
            if (results.isEmpty()) return
            core.markDeliveries(snapshot.space, results.toList())
            results.clear()
        }
    }

    companion object {
        private const val LEDGER = "ledger"
        const val MAX_PER_FLUSH = 2_000
        const val MARK_EVERY = 200
        const val ROUND_MS = 20_000L
        const val BACKOFF_FIRST_MS = 5_000L
        const val BACKOFF_MAX_MS = 10 * 60_000L
        const val MAX_ARCHIVE_ATTEMPTS = AutoBriefCore.ARCHIVE_MAX_ATTEMPTS

        /** The pause after [attempts] attempts: 5 s, doubling, at most 10 min. */
        fun backoffMs(attempts: Int): Long =
            if (attempts <= 0) 0 else minOf(BACKOFF_FIRST_MS shl (attempts - 1).coerceAtMost(16), BACKOFF_MAX_MS)

        /** An entry's seq (queued by 2.0.0 without the field: the key ends in it). */
        private fun seqOf(item: OutboxItem): Long = item.seq ?: item.key.substringAfterLast('-').toLongOrNull() ?: Long.MAX_VALUE
    }
}

private fun httpPost(url: String, body: String, key: String): Pair<Boolean, String?> = runCatching {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 5_000
    conn.readTimeout = 15_000
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(bytes.size)
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    conn.setRequestProperty("Idempotency-Key", key)
    conn.outputStream.use { it.write(bytes) }
    val code = conn.responseCode
    // drain and close the response stream (instead of conn.disconnect()) so Android's HttpURLConnection keep-alive pool reuses the TCP socket
    runCatching { (if (code in 200..399) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
    if (code in 200..299) true to null else false to "HTTP $code"
}.getOrElse { false to (it.message ?: it::class.java.simpleName) }
