package dev.quietinbox.platform.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AEAD for bounded blobs (media copies, thumbnails, diagnostic bundles) built on Tink's AES-256-GCM.
 * Each blob gets a fresh random nonce from Tink; the file name is bound as associated data so a
 * blob cannot be swapped for another one on disk.
 */
@Singleton
class BlobCipher @Inject constructor(
    private val keyMaterial: KeyMaterial,
) {
    private class Cached(val epoch: Long, val aead: Aead)

    /** Outcome of one build attempt: a usable result, or "the key epoch moved meanwhile, try again". */
    private sealed interface Build {
        class Done(val result: KeyResult<Aead>) : Build
        data object Stale : Build
    }

    @Volatile
    private var cached: Cached? = null

    /**
     * The primitive is tied to the key epoch: a reset invalidates it (QI-SEC-003). A primitive
     * built from a key that was destroyed while it was being built is never handed out either
     * (round-10 finding): the build is retried once under the new epoch, then fails closed.
     */
    private fun primitive(): KeyResult<Aead> {
        cached?.takeIf { it.epoch == keyMaterial.epoch }?.let { return KeyResult.Ok(it.aead) }
        repeat(2) {
            when (val b = build()) {
                is Build.Done -> return b.result
                Build.Stale -> Unit
            }
        }
        return KeyResult.Failed(KeyFailure.Unavailable("key epoch changed while building the cipher"))
    }

    private fun build(): Build = synchronized(this) {
        cached?.takeIf { it.epoch == keyMaterial.epoch }?.let { return Build.Done(KeyResult.Ok(it.aead)) }
        cached = null
        val epoch = keyMaterial.epoch
        val raw = when (val r = keyMaterial.media.getOrCreate()) {
            is KeyResult.Failed -> return Build.Done(r)
            is KeyResult.Ok -> r.value
        }
        try {
            AeadConfig.register()
            val params = AesGcmParameters.builder()
                .setKeySizeBytes(32)
                .setIvSizeBytes(12)
                .setTagSizeBytes(16)
                .setVariant(AesGcmParameters.Variant.NO_PREFIX)
                .build()
            val key = AesGcmKey.builder()
                .setParameters(params)
                .setKeyBytes(SecretBytes.copyFrom(raw, InsecureSecretKeyAccess.get()))
                .build()
            val handle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
                .build()
            val p = handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            // A reset that raced this build wins: a primitive of a dead epoch is neither cached nor returned.
            if (epoch != keyMaterial.epoch) return Build.Stale
            cached = Cached(epoch, p)
            Build.Done(KeyResult.Ok(p))
        } catch (e: Exception) {
            Build.Done(KeyResult.Failed(KeyFailure.Unavailable("tink:${e::class.java.simpleName}")))
        } finally {
            raw.fill(0)
        }
    }

    fun encryptToFile(plain: ByteArray, target: File): KeyResult<Unit> {
        val p = when (val r = primitive()) {
            is KeyResult.Failed -> return r
            is KeyResult.Ok -> r.value
        }
        return try {
            val ct = p.encrypt(plain, aadFor(target))
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(ct)
            if (!tmp.renameTo(target)) {
                target.writeBytes(ct)
                tmp.delete()
            }
            KeyResult.Ok(Unit)
        } catch (e: Exception) {
            KeyResult.Failed(KeyFailure.Unavailable("encrypt:${e::class.java.simpleName}"))
        }
    }

    fun decryptFile(source: File): KeyResult<ByteArray> {
        val p = when (val r = primitive()) {
            is KeyResult.Failed -> return r
            is KeyResult.Ok -> r.value
        }
        return try {
            KeyResult.Ok(p.decrypt(source.readBytes(), aadFor(source)))
        } catch (e: java.security.GeneralSecurityException) {
            KeyResult.Failed(KeyFailure.Tampered)
        } catch (e: Exception) {
            KeyResult.Failed(KeyFailure.Unavailable("decrypt:${e::class.java.simpleName}"))
        }
    }

    private fun aadFor(file: File): ByteArray = "quietinbox:blob:v1:${file.name}".toByteArray(Charsets.UTF_8)
}
