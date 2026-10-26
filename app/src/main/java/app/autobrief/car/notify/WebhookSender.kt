package app.autobrief.car.notify

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.model.OutboxItem
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delivers queued items (every ledger entry, period workbooks) to the endpoint the owner or company set:
 * their own server, which keeps an outside copy of the chain and forwards workbooks to storage or e-mail.
 * An item counts as delivered only after an HTTP 2xx; ledger entries go out in order and the first failure
 * stops the round so the receiver never sees a later entry before an earlier one.
 */
class WebhookSender(private val core: AutoBriefCore) {

    /** Sends undelivered items oldest first; call from a background thread. */
    @Synchronized
    fun flush() {
        val url = core.settings.value.archiveWebhookUrl
        if (url.isBlank() || !AutoBriefCore.isAllowedEndpoint(url)) return
        val pending = core.outbox.value.filter { it.deliveredEpochMs == null && it.attempts < MAX_ATTEMPTS }.sortedBy { it.createdEpochMs }
        val results = ArrayList<AutoBriefCore.Delivery>()
        for (item in pending.take(MAX_PER_FLUSH)) {
            val (ok, error) = post(url, item)
            results += AutoBriefCore.Delivery(item.key, ok, error)
            // results are written in batches: a crash re-sends at most one batch, which the receiver drops by key
            if (results.size >= MARK_EVERY) { core.markDeliveries(results.toList()); results.clear() }
            if (!ok) break
        }
        core.markDeliveries(results)
    }

    private fun post(url: String, item: OutboxItem): Pair<Boolean, String?> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Idempotency-Key", item.key)
            conn.outputStream.use { it.write(core.wireBody(item).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) true to null else false to "HTTP $code"
        } finally {
            conn.disconnect()
        }
    }.getOrElse { false to (it.message ?: it::class.java.simpleName) }

    companion object {
        const val MAX_ATTEMPTS = 50
        const val MAX_PER_FLUSH = 2_000
        const val MARK_EVERY = 50
    }
}
