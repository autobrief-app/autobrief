package app.autobrief.car.notify

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.model.OutboxItem
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delivers queued items to the owner's own HTTPS endpoints (one for the logbook archive, one for family
 * notices); that endpoint forwards to cloud storage, e-mail or a messenger. An item counts as delivered
 * only after an HTTP 2xx. The car cannot send SMS itself (Pleos does not support SMS).
 */
class WebhookSender(private val core: AutoBriefCore) {

    /** Sends every undelivered item once; call from a background thread. */
    fun flush() {
        val settings = core.settings.value
        core.outbox.value.filter { it.deliveredEpochMs == null && it.attempts < MAX_ATTEMPTS }.forEach { item ->
            val url = when (item.kind) {
                "archive" -> settings.archiveWebhookUrl
                else -> settings.familyWebhookUrl
            }
            // left pending (not counted as an attempt) until the owner sets an HTTPS endpoint
            if (!url.startsWith("https://")) return@forEach
            val (ok, error) = post(url, item)
            core.markDelivery(item.key, ok, error)
        }
    }

    private fun post(url: String, item: OutboxItem): Pair<Boolean, String?> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Idempotency-Key", item.key)
            conn.outputStream.use { it.write(item.payloadJson.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) true to null else false to "HTTP $code"
        } finally {
            conn.disconnect()
        }
    }.getOrElse { false to (it.message ?: it::class.java.simpleName) }

    companion object {
        const val MAX_ATTEMPTS = 20
    }
}
