package dev.quietinbox.platform.storage.repo

import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** QI-SEC-003 round-10: a reset that fails part-way names the step and still reopens the vault. */
class VaultRepositoryTest : FunSpec({

    class Harness {
        val holder: DatabaseHolder = mockk()
        val keys: KeyMaterial = mockk(relaxed = true)
        val mediaDir: MediaDirectory = mockk()
        val settings: SettingsRepository = mockk(relaxed = true)
        val maintenance = VaultMaintenance()
        val state = MutableStateFlow<VaultState>(VaultState.Ready(mockk(relaxed = true)))

        init {
            every { holder.state } returns state
            coEvery { holder.retry() } coAnswers { state.value = VaultState.Ready(mockk(relaxed = true)) }
            every { mediaDir.deleteAll() } returns true
            every { keys.anySecretExists() } returns false
            every { keys.keystoreKeyExists() } returns false
        }

        fun repo() = VaultRepository(holder, keys, mediaDir, settings, maintenance)
    }

    test("a database file that survives deletion is reported as the failed step and the vault is reopened") {
        val h = Harness()
        coEvery { h.holder.closeAndDeleteFiles() } coAnswers { h.state.value = VaultState.Opening; false }

        h.repo().deleteEverything() shouldBe ResetResult.Failed("database")

        coVerify(exactly = 1) { h.holder.retry() }
        h.state.value shouldBe h.state.value.also { (it is VaultState.Ready) shouldBe true }
        // Nothing after the failed step ran: keys were not destroyed for a vault that still exists.
        coVerify(exactly = 0) { h.keys.destroyAll() }
        h.maintenance.isActive shouldBe false
    }

    test("a surviving media file is the failed step; keys are kept") {
        val h = Harness()
        coEvery { h.holder.closeAndDeleteFiles() } coAnswers { h.state.value = VaultState.Opening; true }
        every { h.mediaDir.deleteAll() } returns false

        h.repo().deleteEverything() shouldBe ResetResult.Failed("media")
        coVerify(exactly = 0) { h.keys.destroyAll() }
        (h.state.value is VaultState.Ready) shouldBe true
    }

    test("the happy path destroys keys, clears settings and reports done") {
        val h = Harness()
        coEvery { h.holder.closeAndDeleteFiles() } coAnswers { h.state.value = VaultState.Opening; true }

        h.repo().deleteEverything() shouldBe ResetResult.Done
        coVerify(exactly = 1) { h.keys.destroyAll() }
        coVerify(exactly = 1) { h.settings.clearAll() }
        coVerify(exactly = 1) { h.holder.retry() }
    }
})
