package dev.quietinbox.platform.storage.repo

import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of "delete everything": either the app is a fresh installation, or which step was not. */
sealed interface ResetResult {
    data object Done : ResetResult

    /** [step] is one of `database`, `media`, `keys`, `reopen`; nothing is reported as done that was not verified. */
    data class Failed(val step: String) : ResetResult
}

/** Whole-vault operations: state, retry after key problems, and the explicit "delete everything". */
@Singleton
class VaultRepository @Inject constructor(
    private val holder: DatabaseHolder,
    private val keyMaterial: KeyMaterial,
    private val mediaDir: MediaDirectory,
    private val settings: SettingsRepository,
    private val maintenance: VaultMaintenance,
) {
    val state: StateFlow<VaultState> get() = holder.state

    suspend fun retryOpen() = holder.retry()

    fun vaultExists(): Boolean = holder.vaultExists()

    fun keyLooksBroken(): Boolean = keyMaterial.databaseKeyLooksBroken()

    /**
     * Destroys the vault, media, secrets and preferences, then re-creates fresh keys so the app
     * behaves like a new installation. This is the only path that deletes key material.
     *
     * Runs as an exclusive maintenance step (QI-SEC-003): capture, media copies, journal replay,
     * retention and backups are stopped first and the pipeline lock is held throughout, so nothing
     * can be written with the old key while it is being destroyed. Every step is verified; a
     * failure names the step instead of pretending the reset completed.
     */
    suspend fun deleteEverything(): ResetResult = maintenance.exclusive {
        if (!holder.closeAndDeleteFiles()) return@exclusive ResetResult.Failed("database")
        if (!mediaDir.deleteAll()) return@exclusive ResetResult.Failed("media")
        // Bumps the key epoch: every cached cipher primitive (media blobs) is rebuilt from the new key.
        keyMaterial.destroyAll()
        if (keyMaterial.anySecretExists() || keyMaterial.keystoreKeyExists()) return@exclusive ResetResult.Failed("keys")
        settings.clearAll()
        holder.retry()
        if (holder.state.value is VaultState.Ready) ResetResult.Done else ResetResult.Failed("reopen")
    }

    /** Recreates the vault when the key is unusable and the user explicitly chose to start over. */
    suspend fun resetAfterKeyFailure(): ResetResult = deleteEverything()
}
