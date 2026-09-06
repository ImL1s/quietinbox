package dev.quietinbox.core.designsystem.components

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The formatters render in the locale they are handed, never in the process default: the UI passes
 * the composition's locale so a per-app language (Android 13+) is honoured while the process lives on.
 */
class TimeFormatTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val septemberThirdMorning = ZonedDateTime.of(2026, 9, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun japaneseAndKoreanDatesCarryNoEnglishMonth() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val ja = TimeFormat.date(septemberThirdMorning, zone, Locale.JAPAN)
            val ko = TimeFormat.date(septemberThirdMorning, zone, Locale.KOREA)
            ja shouldNotContain "Sep"
            ko shouldNotContain "Sep"
            ja shouldContain "2026"
            TimeFormat.date(septemberThirdMorning, zone, Locale.US) shouldContain "Sep"
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun timesFollowTheGivenLocaleNotTheProcessDefault() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            TimeFormat.time(septemberThirdMorning, zone, Locale.KOREA) shouldContain "오"
            TimeFormat.time(septemberThirdMorning, zone, Locale.JAPAN) shouldNotContain "M"
            TimeFormat.time(septemberThirdMorning, zone, Locale.US) shouldContain "M"
        } finally {
            Locale.setDefault(previous)
        }
    }
}
