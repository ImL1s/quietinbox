package dev.quietinbox.platform.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * QI-CRYPTO-005: the first use of a fresh KEK alias by several secrets at once must produce one
 * key. Without the creation lock, a losing `generateKey()` replaced the winner's key and the
 * secret wrapped under the first key could never be unwrapped again.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreWrapperTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun parallelFirstUseOfASharedAliasCreatesOneKekAndEverySecretStaysReadable() {
        repeat(5) { round ->
            val alias = "test.kek.$round.${System.nanoTime()}"
            val dir = File(context.filesDir, "keys-race-" + System.nanoTime())
            val wrapper = KeystoreWrapper(alias)
            try {
                val purposes = listOf("database", "media", "recovery")
                val files = purposes.map { WrappedSecretFile(File(dir, "$it.key"), it, wrapper) }
                val pool = Executors.newFixedThreadPool(purposes.size)
                val go = CountDownLatch(1)
                val results = files.map { f -> pool.submit<KeyResult<ByteArray>> { go.await(); f.getOrCreate() } }
                go.countDown()
                val secrets = results.map { it.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<KeyResult.Ok<ByteArray>>().value }
                pool.shutdown()

                // A new process: new wrapper instance, same alias, same files.
                val again = KeystoreWrapper(alias)
                purposes.forEachIndexed { i, purpose ->
                    val read = WrappedSecretFile(File(dir, "$purpose.key"), purpose, again).read()
                    read.shouldBeInstanceOf<KeyResult.Ok<ByteArray>>().value.contentEquals(secrets[i]) shouldBe true
                }
            } finally {
                wrapper.destroy()
                dir.deleteRecursively()
            }
        }
    }
}
