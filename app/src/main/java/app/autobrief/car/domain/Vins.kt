package app.autobrief.car.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The VIN as the Pleos Vehicle SDK hands it over: the CarInfo value as a JSON string, quotes included ("\"KMH…\"").
 * The Pleos Connect emulator image has no VIN; its VHAL holds the placeholder `invalid`, which arrived as "\"invalid\""
 * and was sent to the fleet console as the car's identity.
 */
object Vins {
    /** ISO 3779: 17 characters, digits and capital letters without I, O and Q. */
    private val FORMAT = Regex("[A-HJ-NPR-Z0-9]{17}")

    /** The VIN in [raw], or null when it is not one (a placeholder, empty, the wrong length or alphabet). */
    fun normalize(raw: String?): String? {
        var text = raw?.trim() ?: return null
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            text = runCatching { (Json.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: return null
        }
        return text.trim().uppercase().takeIf { FORMAT.matches(it) }
    }
}
