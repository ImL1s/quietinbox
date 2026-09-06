package dev.quietinbox.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.quietinbox.core.designsystem.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Locale-aware, time-zone-explicit formatting helpers (never fabricate precision). The locale is
 * required on purpose: the UI passes [currentLocale], and a default of `Locale.getDefault()` would
 * quietly bring back the process-default bug the moment a call site forgot it.
 */
object TimeFormat {
    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun localDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}

/**
 * The locale the UI is composed in, read through the composition so it follows a per-app language
 * (Android 13+) and every configuration change. `Locale.getDefault()` is the *process* default: when
 * the user changes the app language while the process is alive — it always is, the notification
 * listener keeps it up — resources switch but the default locale does not until the next
 * process-level configuration change, so every date and time went on in the device language
 * (round-21 finding). The fallback is only for an empty locale list, which Android never provides.
 */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales.let { if (it.isEmpty) Locale.ENGLISH else it[0] }

/** "just now", "5 min", "yesterday", or a date, for list rows. */
@Composable
fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val locale = currentLocale()
    val diff = nowMs - epochMs
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val today = TimeFormat.localDate(nowMs)
    val day = TimeFormat.localDate(epochMs)
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        minutes < 60 -> stringResource(R.string.time_minutes_ago, minutes)
        day == today -> TimeFormat.time(epochMs, locale = locale)
        day == today.minusDays(1) -> stringResource(R.string.time_yesterday)
        hours < 24 * 6 -> DateTimeFormatter.ofPattern("EEE", locale).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
        else -> DateTimeFormatter.ofPattern("M/d", locale).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}

@Composable
fun dayLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val today = TimeFormat.localDate(nowMs)
    val day = TimeFormat.localDate(epochMs)
    return when (day) {
        today -> stringResource(R.string.date_today)
        today.minusDays(1) -> stringResource(R.string.date_yesterday)
        else -> TimeFormat.date(epochMs, locale = currentLocale())
    }
}
