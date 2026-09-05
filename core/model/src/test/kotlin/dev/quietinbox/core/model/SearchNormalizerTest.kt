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
        SearchNormalizer.tokens("明天開會") shouldContain "開"
    }

    test("query tokens are always a subset of the index tokens of a matching body") {
        val index = SearchNormalizer.tokens(SearchNormalizer.normalize("Hello meeting 明天開會"))
        for (q in listOf("hell", "meet", "eeting", "開", "開會", "hello")) {
            val qt = SearchNormalizer.queryTokens(SearchNormalizer.normalize(q))
            (qt - index).isEmpty() shouldBe true
        }
    }

    test("BoundedText truncates and flags") {
        val t = BoundedText.of("a".repeat(5000))!!
        t.value.length shouldBe Limits.MAX_TEXT_CHARS
        t.truncated shouldBe true
        BoundedText.of("") shouldBe null
    }
})
