package app.autobrief.car.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pleos Gleo Interface contract (app-integration-guide): the intent action is the function name,
 * parameters arrive as the `ACTION_PARAM` JSON extra, and the app answers with the broadcast
 * `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` carrying `packageName` and `response`
 * ({functionName, status, message (English), data, mode}) within 7 seconds.
 */
object GleoContract {
    const val LOG_TRIP_PURPOSE = "LOG_TRIP_PURPOSE"
    const val SET_SLEEP_CARE = "SET_SLEEP_CARE"
    const val START_DESIGNATED_DRIVER_MODE = "START_DESIGNATED_DRIVER_MODE"
    val FUNCTIONS = setOf(LOG_TRIP_PURPOSE, SET_SLEEP_CARE, START_DESIGNATED_DRIVER_MODE)

    const val EXTRA_ACTION_PARAM = "ACTION_PARAM"
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_RESPONSE = "response"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The guide's sample passes `ACTION_PARAM` as a JSON object string, and its sender example wraps it in
     * one more pair of quotes; both forms are accepted. Without the extra, individual string extras are used.
     */
    fun parseParams(raw: String?, extras: Map<String, String?> = emptyMap()): JsonObject {
        if (raw != null) {
            val parsed = runCatching {
                var element = json.parseToJsonElement(raw.trim())
                if (element is JsonPrimitive && element.isString) element = json.parseToJsonElement(element.content)
                element as? JsonObject
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return buildJsonObject {
            extras.forEach { (k, v) -> if (k != EXTRA_ACTION_PARAM && v != null) put(k, v) }
        }
    }

    fun response(functionName: String, reply: AutoBriefCore.GleoReply, mode: String): String = buildJsonObject {
        put("functionName", functionName)
        put("status", reply.status)
        put("message", reply.message)
        reply.data?.let { put("data", it.toString()) }
        put("mode", mode)
    }.toString()
}
