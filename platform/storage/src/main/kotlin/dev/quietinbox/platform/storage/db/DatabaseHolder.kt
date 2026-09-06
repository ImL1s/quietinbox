package dev.quietinbox.platform.storage.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VaultState {
    data object Opening : VaultState
    data class Ready(val db: QuietInboxDatabase) : VaultState
    data class Locked(val failure: KeyFailure) : VaultState
}

class VaultUnavailableException(val failure: KeyFailure) : IllegalStateException("vault unavailable: $failure")

/**
 * Owns the SQLCipher-backed Room instance. The vault opens lazily on first use with the
 * per-installation database key; if the key cannot be unwrapped the vault stays [VaultState.Locked]
 * and nothing is deleted — recovery is a user decision (plan section 9).
 */
@Singleton
class DatabaseHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyMaterial: KeyMaterial,
) {
    /** Any failure while opening must surface as [VaultState.Locked], never as a process crash. */
    private val guard = CoroutineExceptionHandler { _, t ->
        _state.value = VaultState.Locked(KeyFailure.Unavailable("open:${t::class.java.simpleName}"))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<VaultState>(VaultState.Opening)
    val state: StateFlow<VaultState> = _state

    init {
        scope.launch { open() }
    }

    /** Suspends until the vault is ready; throws [VaultUnavailableException] when locked. */
    suspend fun db(): QuietInboxDatabase {
        // Wait for any terminal state: a Locked outcome must throw, never hang the caller.
        return when (val s = _state.first { it !is VaultState.Opening }) {
            is VaultState.Ready -> s.db
            is VaultState.Locked -> throw VaultUnavailableException(s.failure)
            VaultState.Opening -> error("unreachable")
        }
    }

    /**
     * Convenience for repositories: mirrors [block] whenever the vault is ready and stays silent
     * (without completing) while it is opening or locked, so a later retry re-attaches the UI.
     */
    fun <T> flowWithDb(block: (QuietInboxDatabase) -> Flow<T>): Flow<T> =
        _state.flatMapLatest { s -> if (s is VaultState.Ready) block(s.db) else emptyFlow() }

    /** Try to open again (after the user fixed a Keystore problem, or after a restore). */
    suspend fun retry() = open()

    fun vaultExists(): Boolean = context.getDatabasePath(QuietInboxDatabase.FILE_NAME).exists()

    /**
     * Closes the vault and deletes every database file. Callers handle key destruction. Returns
     * false when any file is still there afterwards; a reset must not report success on hope.
     */
    suspend fun closeAndDeleteFiles(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            (_state.value as? VaultState.Ready)?.db?.close()
            _state.value = VaultState.Opening
            val base = context.getDatabasePath(QuietInboxDatabase.FILE_NAME)
            var allGone = true
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val f = File(base.path + suffix)
                f.delete()
                if (f.exists()) allGone = false
            }
            allGone
        }
    }

    private suspend fun open() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_state.value is VaultState.Ready) return@withLock
            when (val key = keyMaterial.database.getOrCreate()) {
                is KeyResult.Failed -> _state.value = VaultState.Locked(key.failure)
                is KeyResult.Ok -> {
                    try {
                        System.loadLibrary("sqlcipher")
                        val db = Room.databaseBuilder(context, QuietInboxDatabase::class.java, QuietInboxDatabase.FILE_NAME)
                            .openHelperFactory(SupportOpenHelperFactory(key.value))
                            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(*QuietInboxDatabase.MIGRATIONS)
                            .build()
                        // Force the connection open so a wrong key fails here, not on first query.
                        db.openHelper.writableDatabase
                        _state.value = VaultState.Ready(db)
                    } catch (t: Throwable) {
                        // UnsatisfiedLinkError (native lib), migration errors and I/O all land here.
                        _state.value = VaultState.Locked(KeyFailure.Unavailable("open:${t::class.java.simpleName}"))
                    }
                    // NOTE: the key array must stay intact — SupportOpenHelperFactory reuses it for
                    // every additional pooled connection (WAL readers). Zeroing it here breaks them.
                }
            }
        }
    }
}
