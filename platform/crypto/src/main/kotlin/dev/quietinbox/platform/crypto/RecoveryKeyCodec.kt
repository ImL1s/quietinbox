package dev.quietinbox.platform.crypto

import java.security.MessageDigest

/**
 * Human-transcribable encoding of the 256-bit recovery key.
 *
 * Format: Crockford Base32 (no I, L, O, U), 52 data characters plus 4 checksum characters
 * (first 20 bits of SHA-256), rendered in 14 groups of 4 separated by dashes. Decoding is
 * case-insensitive and tolerant of the common confusables (`O`→`0`, `I`/`L`→`1`).
 */
object RecoveryKeyCodec {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val KEY_BYTES = 32
    private const val DATA_CHARS = 52
    private const val CHECK_CHARS = 4

    fun encode(key: ByteArray): String {
        require(key.size == KEY_BYTES) { "recovery key must be 32 bytes" }
        val data = base32(key).take(DATA_CHARS)
        val check = base32(MessageDigest.getInstance("SHA-256").digest(key)).take(CHECK_CHARS)
        return (data + check).chunked(4).joinToString("-")
    }

    /**
     * Returns the key bytes or null when the text is malformed or the checksum fails.
     * The confusable folding (O→0, I/L→1) is applied to the whole string, checksum included; the
     * alphabet never contains those letters, so the fold is lossless.
     */
    fun decode(text: String): ByteArray? {
        val cleaned = text.uppercase()
            .replace("O", "0").replace("I", "1").replace("L", "1")
            .filter { it in ALPHABET }
        if (cleaned.length != DATA_CHARS + CHECK_CHARS) return null
        val data = cleaned.substring(0, DATA_CHARS)
        val check = cleaned.substring(DATA_CHARS)
        val bytes = unbase32(data, KEY_BYTES) ?: return null
        val expected = base32(MessageDigest.getInstance("SHA-256").digest(bytes)).take(CHECK_CHARS)
        return if (expected == check) bytes else null
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    private fun unbase32(text: String, outLen: Int): ByteArray? {
        val out = ByteArray(outLen)
        var buffer = 0
        var bits = 0
        var idx = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                if (idx < outLen) out[idx++] = ((buffer shr (bits - 8)) and 0xff).toByte()
                bits -= 8
            }
        }
        return if (idx == outLen) out else null
    }
}
