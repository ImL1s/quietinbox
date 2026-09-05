package dev.quietinbox.platform.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Backup container crypto (plan section 11): a fixed plaintext header carrying a random salt,
 * followed by a Tink AES-256-GCM-HKDF streaming AEAD ciphertext keyed from the user's recovery
 * key. Tink handles per-segment nonces, ordering, truncation and EOF authentication; we only
 * derive the key and bind the header as associated data.
 */
object BackupCrypto {
    val MAGIC: ByteArray = "QIBK".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION: Byte = 1
    const val SALT_BYTES = 16
    const val HEADER_BYTES = 4 + 1 + SALT_BYTES
    private const val SEGMENT_BYTES = 1024 * 1024

    fun header(salt: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES)
        return MAGIC + byteArrayOf(FORMAT_VERSION) + salt
    }

    /** Parses and validates a header; returns the salt or null. */
    fun parseHeader(header: ByteArray): ByteArray? {
        if (header.size < HEADER_BYTES) return null
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        if (header[4] != FORMAT_VERSION) return null
        return header.copyOfRange(5, HEADER_BYTES)
    }

    fun streamingAead(recoveryKey: ByteArray, salt: ByteArray): StreamingAead {
        StreamingAeadConfig.register()
        val derived = Hkdf.deriveSha256(ikm = recoveryKey, salt = salt, info = "quietinbox-backup-v1".toByteArray(), length = 32)
        try {
            val params = AesGcmHkdfStreamingParameters.builder()
                .setKeySizeBytes(32)
                .setDerivedAesGcmKeySizeBytes(32)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .setCiphertextSegmentSizeBytes(SEGMENT_BYTES)
                .build()
            val key = AesGcmHkdfStreamingKey.create(params, SecretBytes.copyFrom(derived, InsecureSecretKeyAccess.get()))
            val handle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
                .build()
            return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        } finally {
            derived.fill(0)
        }
    }
}

/** RFC 5869 HKDF with HMAC-SHA256; covered by the RFC test vectors in unit tests. */
object Hkdf {
    fun deriveSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }
}
