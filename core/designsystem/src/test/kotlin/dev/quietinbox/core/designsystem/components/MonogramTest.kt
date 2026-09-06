package dev.quietinbox.core.designsystem.components

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Avatar monograms: one glyph for a Han, kana or hangul name, two Latin initials otherwise. */
class MonogramTest {

    @Test
    fun hanNameGivesItsFirstCharacter() {
        monogram("林小美 Mia Lin") shouldBe "林"
    }

    @Test
    fun kanaAndHangulNamesGiveOneGlyphLikeHan() {
        monogram("さくら") shouldBe "さ"
        monogram("プロダクトチーム Product Team") shouldBe "プ"
        monogram("김민수") shouldBe "김"
        monogram("가족 단톡방 Family") shouldBe "가"
    }

    @Test
    fun latinNamesGiveTwoInitials() {
        monogram("Diego Ramos") shouldBe "DR"
        monogram("Mom") shouldBe "MO"
    }

    @Test
    fun blankOrMissingLabelIsAQuestionMark() {
        monogram(null) shouldBe "?"
        monogram("   ") shouldBe "?"
    }
}
