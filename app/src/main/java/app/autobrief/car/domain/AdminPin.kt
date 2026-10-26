package app.autobrief.car.domain

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * How the admin PIN is kept in the settings file: PBKDF2-HMAC-SHA256 over a random 16-byte salt, stored as
 * `pbkdf2-sha256:<iterations>:<hex>` so the work factor can rise later. Versions up to 2.0.0 stored
 * SHA-256(salt + PIN) with a clock-based salt; such a hash still verifies and is replaced on the next correct entry.
 */
object AdminPin {
    private const val SCHEME = "pbkdf2-sha256"

    /** About 0.1 s on a car head unit: the lockout, not the hash, is what stops guessing on the car itself. */
    const val ITERATIONS = 60_000

    private val random = SecureRandom()

    fun newSalt(): String = Ledger.hex(ByteArray(16).also(random::nextBytes))

    fun hash(pin: String, salt: String, iterations: Int = ITERATIONS): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return "$SCHEME:$iterations:${Ledger.hex(key)}"
    }

    fun matches(pin: String, salt: String, stored: String): Boolean {
        val expected = if (isLegacy(stored)) {
            Ledger.sha256(salt + pin)
        } else {
            val iterations = stored.split(':').getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return false
            hash(pin, salt, iterations)
        }
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), stored.toByteArray(Charsets.UTF_8))
    }

    /** A hash written before PBKDF2 (plain SHA-256 of salt + PIN). */
    fun isLegacy(stored: String): Boolean = !stored.startsWith("$SCHEME:")
}
