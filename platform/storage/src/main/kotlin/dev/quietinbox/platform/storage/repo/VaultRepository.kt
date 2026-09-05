package dev.quietinbox.platform.storage.repo

import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Whole-vault operations: state, retry after key problems, and the explicit "delete everything". */
@Singleton
class VaultRepository @Inject constructor(
    private val holder: DatabaseHolder,
    private val keyMaterial: KeyMaterial,
    private val mediaDir: MediaDirectory,
    private val settings: SettingsRepository,
) {
    val state: StateFlow<VaultState> get() = holder.state

    suspend fun retryOpen() = holder.retry()

    fun vaultExists(): Boolean = holder.vaultExists()

    fun keyLooksBroken(): Boolean = keyMaterial.databaseKeyLooksBroken()

    /**
     * Destroys the vault, media, secrets and preferences, then re-creates fresh keys so the app
     * behaves like a new installation. This is the only path that deletes key material.
     */
    suspend fun deleteEverything() {
        holder.closeAndDeleteFiles()
        mediaDir.deleteAll()
        keyMaterial.destroyAll()
        settings.clearAll()
        holder.retry()
    }

    /** Recreates the vault when the key is unusable and the user explicitly chose to start over. */
    suspend fun resetAfterKeyFailure() = deleteEverything()
}
