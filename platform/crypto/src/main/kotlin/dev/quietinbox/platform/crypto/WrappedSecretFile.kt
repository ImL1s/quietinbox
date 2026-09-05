package dev.quietinbox.platform.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * A random secret generated once per installation and persisted only in Keystore-wrapped form.
 * Files live in the app's private `files/keys/` directory (credential-encrypted storage).
 *
 * File format: 1 byte version, 12 byte IV, ciphertext+tag. AAD binds the blob to its purpose so a
 * `db.key` cannot be presented as a `recovery.key`.
 */
class WrappedSecretFile(
    private val file: File,
    private val purpose: String,
    private val wrapper: KeystoreWrapper,
    private val sizeBytes: Int = 32,
) {
    private val aad: ByteArray get() = "quietinbox:$purpose:v1".toByteArray(Charsets.UTF_8)

    fun exists(): Boolean = file.exists()

    /** Returns the secret, creating it when absent. Never overwrites an existing, unreadable file. */
    @Synchronized
    fun getOrCreate(): KeyResult<ByteArray> {
        if (file.exists()) return read()
        val secret = ByteArray(sizeBytes).also { SecureRandom().nextBytes(it) }
        return when (val wrapped = wrapper.wrap(secret, aad)) {
            is KeyResult.Failed -> wrapped
            is KeyResult.Ok -> try {
                writeAtomically(byteArrayOf(VERSION) + wrapped.value)
                KeyResult.Ok(secret)
            } catch (e: IOException) {
                KeyResult.Failed(KeyFailure.Unavailable("write:${e::class.java.simpleName}"))
            }
        }
    }

    @Synchronized
    fun read(): KeyResult<ByteArray> {
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return KeyResult.Failed(KeyFailure.Unavailable("read:${it::class.java.simpleName}"))
        }
        if (bytes.isEmpty() || bytes[0] != VERSION) return KeyResult.Failed(KeyFailure.Tampered)
        return wrapper.unwrap(bytes.copyOfRange(1, bytes.size), aad)
    }

    /** Replaces the secret with a fresh random one (used by full reset). */
    @Synchronized
    fun reset(): KeyResult<ByteArray> {
        delete()
        return getOrCreate()
    }

    @Synchronized
    fun delete() {
        if (file.exists()) file.delete()
    }

    /** Durable write: data fsync'd before the rename, directory fsync'd after; never overwrites in place. */
    private fun writeAtomically(bytes: ByteArray) {
        val dir = file.parentFile ?: error("key file has no parent")
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed for ${file.name}")
        }
        runCatching { FileInputStream(dir).use { it.fd.sync() } }
    }

    companion object {
        private const val VERSION: Byte = 1
    }
}
