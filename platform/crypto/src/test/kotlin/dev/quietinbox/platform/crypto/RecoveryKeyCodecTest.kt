package dev.quietinbox.platform.crypto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.security.SecureRandom

class RecoveryKeyCodecTest {
    @Test
    fun roundTripsRandomKeys() {
        repeat(200) {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val text = RecoveryKeyCodec.encode(key)
            text.length shouldBe 56 + 13
            RecoveryKeyCodec.decode(text)?.toList() shouldBe key.toList()
        }
    }

    @Test
    fun toleratesCaseAndConfusables() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val text = RecoveryKeyCodec.encode(key).lowercase().replace("0", "o").replace("1", "l")
        RecoveryKeyCodec.decode(text)?.toList() shouldBe key.toList()
    }

    @Test
    fun rejectsTypos() {
        val key = ByteArray(32) { it.toByte() }
        val text = RecoveryKeyCodec.encode(key)
        val broken = text.replaceRange(6, 7, if (text[6] == 'A') "B" else "A")
        RecoveryKeyCodec.decode(broken) shouldBe null
        RecoveryKeyCodec.decode("garbage") shouldBe null
        RecoveryKeyCodec.decode(text) shouldNotBe null
    }
}
