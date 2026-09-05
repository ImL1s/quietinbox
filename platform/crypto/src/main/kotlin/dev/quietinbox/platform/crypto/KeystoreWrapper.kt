package dev.quietinbox.platform.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Why key material could not be used. Never contains key bytes. */
sealed interface KeyFailure {
    /** The Keystore key was invalidated (e.g. lock-screen removed with auth-bound keys, or OS reset). */
    data object Invalidated : KeyFailure

    /** Stored ciphertext did not authenticate — corrupt file or a different Keystore. */
    data object Tampered : KeyFailure

    /** The Keystore is unavailable right now (e.g. before first unlock on some devices). */
    data class Unavailable(val cause: String) : KeyFailure
}

sealed interface KeyResult<out T> {
    data class Ok<T>(val value: T) : KeyResult<T>
    data class Failed(val failure: KeyFailure) : KeyResult<Nothing>
}

/**
 * Wraps secrets with an AES-256-GCM key that lives in the Android Keystore.
 *
 * The key deliberately does **not** require user authentication: the notification listener must
 * be able to write while the screen is locked (plan section 9, "v1 continuous capture mode").
 * UI locking is a separate, non-cryptographic gate. The key is only usable after the device's
 * first unlock because it is stored in credential-encrypted storage.
 */
class KeystoreWrapper(private val alias: String = DEFAULT_ALIAS) {

    fun wrap(plain: ByteArray, aad: ByteArray): KeyResult<ByteArray> = guard {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        check(iv.size == IV_BYTES) { "unexpected iv size" }
        iv + ct
    }

    fun unwrap(wrapped: ByteArray, aad: ByteArray): KeyResult<ByteArray> = guard {
        require(wrapped.size > IV_BYTES + TAG_BYTES / 8) { "wrapped blob too short" }
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val ct = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BYTES, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(ct)
    }

    /** Deletes the wrapping key. Only used by "delete all data"; everything wrapped becomes unreadable. */
    fun destroy() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    fun exists(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(alias)
    }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private inline fun <T> guard(block: () -> T): KeyResult<T> = try {
        KeyResult.Ok(block())
    } catch (e: KeyPermanentlyInvalidatedException) {
        KeyResult.Failed(KeyFailure.Invalidated)
    } catch (e: AEADBadTagException) {
        KeyResult.Failed(KeyFailure.Tampered)
    } catch (e: IllegalArgumentException) {
        KeyResult.Failed(KeyFailure.Tampered)
    } catch (e: Exception) {
        KeyResult.Failed(KeyFailure.Unavailable(e::class.java.simpleName))
    }

    companion object {
        const val DEFAULT_ALIAS = "dev.quietinbox.kek.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 128
    }
}
