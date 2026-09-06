package dev.quietinbox.platform.crypto

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of every per-installation secret:
 *
 * - **database key**: 32 random bytes handed to SQLCipher (never derived from a password);
 * - **media key**: 32 random bytes for AEAD encryption of copied media blobs;
 * - **recovery key**: 32 random bytes the user must write down; it is the *only* way to open a
 *   backup on another device, because Keystore keys never leave the device.
 *
 * All three are wrapped by the same Keystore KEK. Failures are surfaced as [KeyFailure] so the
 * UI can offer recovery; the app never silently deletes a vault it cannot open.
 */
@Singleton
class KeyMaterial @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val wrapper = KeystoreWrapper()
    private val dir = File(context.filesDir, "keys")

    val database = WrappedSecretFile(File(dir, "db.key"), "database", wrapper)
    val media = WrappedSecretFile(File(dir, "media.key"), "media", wrapper)
    val recovery = WrappedSecretFile(File(dir, "recovery.key"), "recovery", wrapper)

    private val _epoch = AtomicLong(0)

    /**
     * Incremented by [destroyAll]. A cached primitive built from a secret of an older epoch must be
     * rebuilt, never reused: after a reset the old media key is gone and anything encrypted with
     * its cached AEAD could never be decrypted again (QI-SEC-003).
     */
    val epoch: Long get() = _epoch.get()

    fun keystoreKeyExists(): Boolean = wrapper.exists()

    fun anySecretExists(): Boolean = database.exists() || media.exists() || recovery.exists()

    /** True when a vault exists on disk but its key file is missing or no longer opens. */
    fun databaseKeyLooksBroken(): Boolean = database.exists() && database.read() is KeyResult.Failed

    /** Irreversibly destroys every secret. Callers must delete the database files first. */
    fun destroyAll() {
        database.delete()
        media.delete()
        recovery.delete()
        wrapper.destroy()
        _epoch.incrementAndGet()
    }
}
