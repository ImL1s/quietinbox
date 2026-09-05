package dev.quietinbox.platform.crypto

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
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

    /**
     * Returns the secret, creating it when absent. Never overwrites an existing, unreadable file.
     * The secret is only handed out once its file is durable: a vault created with a key whose
     * file was lost on power failure could never be opened again.
     */
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

    /**
     * Durable write: data fsync'd before the rename, the directory (and its parent when the
     * directory was just created) fsync'd after; never overwrites in place. A directory fsync
     * failure is an [IOException]: an unproven rename must not be reported as success.
     */
    private fun writeAtomically(bytes: ByteArray) {
        val dir = file.parentFile ?: error("key file has no parent")
        val createdDir = !dir.exists() && dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed for ${file.name}")
        }
        fsyncDirectory(dir)
        if (createdDir) dir.parentFile?.let { fsyncDirectory(it) }
    }

    private fun fsyncDirectory(dir: File) {
        // java.io streams refuse to open a directory (EISDIR); the POSIX layer does not.
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            throw IOException("fsync ${dir.name}: ${e.message}", e)
        }
    }

    companion object {
        private const val VERSION: Byte = 1
    }
}
