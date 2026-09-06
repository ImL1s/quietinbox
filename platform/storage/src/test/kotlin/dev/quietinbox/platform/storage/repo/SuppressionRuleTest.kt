package dev.quietinbox.platform.storage.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SuppressionRuleTest : FunSpec({
    test("proven ids decide when both sides have one") {
        SuppressionRule.applies("m1", 1_000L, "m1", 9_000L) shouldBe true
        SuppressionRule.applies("m1", 1_000L, "m2", 1_000L) shouldBe false
    }

    test("without ids, a replay of the same or an older post is suppressed and a later post is not") {
        SuppressionRule.applies(null, 1_000L, null, 1_000L) shouldBe true
        SuppressionRule.applies(null, 1_000L, null, 900L) shouldBe true
        SuppressionRule.applies(null, 1_000L, null, 1_001L) shouldBe false
        // A token from a message with an id still falls back to post times for an id-less candidate.
        SuppressionRule.applies("m1", 1_000L, null, 2_000L) shouldBe false
    }

    test("without a post time on either side the token applies (conservative)") {
        SuppressionRule.applies(null, null, null, 5_000L) shouldBe true
        SuppressionRule.applies(null, 5_000L, null, null) shouldBe true
    }
})
