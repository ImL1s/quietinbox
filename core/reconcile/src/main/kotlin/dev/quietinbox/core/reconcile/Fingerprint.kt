package dev.quietinbox.core.reconcile

import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.TimestampQuality
import java.security.MessageDigest

/**
 * Exact-content fingerprint used for window alignment. It intentionally keeps whitespace,
 * width and emoji differences; only search uses normalisation.
 */
object Fingerprint {
    fun of(c: MessageCandidate): String {
        val sender = c.sender?.senderKey ?: c.sender?.displayName ?: ""
        val ts = c.sourceTimestampEpochMs?.takeIf { c.timestampQuality == TimestampQuality.SOURCE_MESSAGE }
        val material = buildString {
            append(sender).append(SEP)
            append(c.body).append(SEP)
            append(ts ?: "").append(SEP)
            append(c.kind.name).append(SEP)
            append(c.media?.uri ?: "")
        }
        return sha256Hex(material)
    }

    fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** Unit separator: cannot appear in notification text after bounding, so fields cannot collide. */
    private const val SEP: Char = '\u001F'
    private val HEX = "0123456789abcdef".toCharArray()
}
