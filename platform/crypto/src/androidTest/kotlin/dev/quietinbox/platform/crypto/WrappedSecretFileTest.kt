package dev.quietinbox.platform.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The durable write path (data fsync, rename, directory fsync via `Os.fsync`) only runs on a
 * device: java.io cannot open a directory, so this cannot be proven on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class WrappedSecretFileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun secretIsCreatedOnceInAFreshDirectoryAndReadBackUnchanged() {
        val dir = File(context.filesDir, "keys-test-" + System.nanoTime())
        try {
            val file = WrappedSecretFile(File(dir, "db.key"), "test", KeystoreWrapper())
            val first = file.getOrCreate().shouldBeInstanceOf<KeyResult.Ok<ByteArray>>().value
            first.size shouldBe 32
            File(dir, "db.key").exists() shouldBe true
            File(dir, "db.key.tmp").exists() shouldBe false
            val second = file.getOrCreate().shouldBeInstanceOf<KeyResult.Ok<ByteArray>>().value
            second.contentEquals(first) shouldBe true
            // Missing directory (created by mkdirs, parent fsync'd) and an existing one both work.
            val nested = WrappedSecretFile(File(File(dir, "deeper"), "media.key"), "test2", KeystoreWrapper())
            nested.getOrCreate().shouldBeInstanceOf<KeyResult.Ok<ByteArray>>()
        } finally {
            dir.deleteRecursively()
        }
    }
}
