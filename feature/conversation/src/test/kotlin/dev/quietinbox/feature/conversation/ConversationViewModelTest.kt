package dev.quietinbox.feature.conversation

import android.content.Context
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.Message
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** QI-VAULT-010: an opening vault keeps loading, a locked one is shown as locked, a ready one shows the rows. */
class ConversationViewModelTest : FunSpec({
    // A real dispatcher: the ViewModel's debounce/delay must run on real time, not a test scheduler nobody advances.
    beforeSpec { Dispatchers.setMain(Dispatchers.Unconfined) }
    afterSpec { Dispatchers.resetMain() }

    suspend fun awaitUntil(timeoutMs: Long = 5_000, check: suspend () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try { check(); return } catch (e: Throwable) { if (e is CancellationException || System.currentTimeMillis() > deadline) throw e; delay(10) }
        }
    }

    class Harness {
        val context: Context = mockk(relaxed = true)
        val inbox: InboxRepository = mockk(relaxed = true)
        val media: MediaCopier = mockk(relaxed = true)
        val vault: VaultRepository = mockk()
        val vaultState = MutableStateFlow<VaultState>(VaultState.Opening)
        val conversation: Conversation = mockk(relaxed = true)
        val message: Message = mockk(relaxed = true)

        init {
            every { vault.state } returns vaultState
            // Mirrors flowWithDb: the vault reads deliver only while the vault is ready.
            every { inbox.observeConversation(any()) } returns vaultState.flatMapLatest { v -> if (v is VaultState.Ready) flowOf(conversation) else emptyFlow() }
            every { inbox.observeMessages(any(), any()) } returns vaultState.flatMapLatest { v -> if (v is VaultState.Ready) flowOf(listOf(message)) else emptyFlow() }
        }

        fun viewModel() = ConversationViewModel(7L, context, inbox, media, vault)
    }

    test("opening keeps loading, locked is shown as locked, ready shows the rows") {
        val h = Harness()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        delay(300)
        vm.state.value.loading shouldBe true
        vm.state.value.vaultLocked shouldBe false

        h.vaultState.value = VaultState.Locked(KeyFailure.Unavailable("test"))
        awaitUntil { vm.state.value.vaultLocked shouldBe true }
        vm.state.value.loading shouldBe false

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        awaitUntil { vm.state.value.messages.size shouldBe 1 }
        vm.state.value.loading shouldBe false
        vm.state.value.vaultLocked shouldBe false
        vm.state.value.conversation shouldBe h.conversation
        collector.cancel()
    }
})
