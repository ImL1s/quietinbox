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
    @Volatile
    private var aead: Aead? = null

    private fun primitive(): KeyResult<Aead> {
        aead?.let { return KeyResult.Ok(it) }
        synchronized(this) {
            aead?.let { return KeyResult.Ok(it) }
            val raw = when (val r = keyMaterial.media.getOrCreate()) {
                is KeyResult.Failed -> return r
                is KeyResult.Ok -> r.value
            }
            return try {
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
                aead = p
                KeyResult.Ok(p)
            } catch (e: Exception) {
                KeyResult.Failed(KeyFailure.Unavailable("tink:${e::class.java.simpleName}"))
            } finally {
                raw.fill(0)
            }
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
