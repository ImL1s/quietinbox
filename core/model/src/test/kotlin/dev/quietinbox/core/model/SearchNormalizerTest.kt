package dev.quietinbox.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class SearchNormalizerTest : FunSpec({
    test("normalises width, case and whitespace but keeps emoji") {
        SearchNormalizer.normalize("Ｈello   World 😀") shouldBe "hello world 😀"
    }

    test("CJK bigrams and Latin trigrams are produced") {
        val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize("明天開會 hello"))
        tokens shouldContainAll setOf("明天", "天開", "開會", "hello", "hel", "ell", "llo")
    }

    test("single CJK character is indexed on its own") {
        SearchNormalizer.tokens("好") shouldContain "好"
    }

    test("BoundedText truncates and flags") {
        val t = BoundedText.of("a".repeat(5000))!!
        t.value.length shouldBe Limits.MAX_TEXT_CHARS
        t.truncated shouldBe true
        BoundedText.of("") shouldBe null
    }
})
