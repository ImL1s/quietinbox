package dev.quietinbox.platform.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class HkdfTest : FunSpec({
    test("RFC 5869 test case 1") {
        val okm = Hkdf.deriveSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        okm.hex() shouldBe "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    }

    test("RFC 5869 test case 3 with empty salt and info") {
        val okm = Hkdf.deriveSha256(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = ByteArray(0),
            info = ByteArray(0),
            length = 42,
        )
        okm.hex() shouldBe "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
    }

    test("header round trip") {
        val salt = ByteArray(16) { it.toByte() }
        BackupCrypto.parseHeader(BackupCrypto.header(salt))?.toList() shouldBe salt.toList()
        BackupCrypto.parseHeader("nope".toByteArray()) shouldBe null
    }
})
