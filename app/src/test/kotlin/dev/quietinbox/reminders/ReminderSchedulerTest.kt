package dev.quietinbox.reminders

import dev.quietinbox.platform.storage.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderSchedulerTest {
    private val taipei = ZoneId.of("Asia/Taipei")
    private val settings = AppSettings(remindersEnabled = true, reminderHour = 20, reminderMinute = 30, reminderWeekdays = setOf(1, 3, 5))

    @Test
    fun sameDayLaterTimeIsToday() {
        val now = ZonedDateTime.of(2026, 9, 7, 9, 0, 0, 0, taipei) // Monday
        assertEquals(Duration.ofHours(11).plusMinutes(30), ReminderScheduler.delayUntilNext(settings, now))
    }

    @Test
    fun pastTimeSkipsToNextEnabledWeekday() {
        val now = ZonedDateTime.of(2026, 9, 7, 21, 0, 0, 0, taipei) // Monday after 20:30 → Wednesday
        assertEquals(Duration.ofDays(2).minusMinutes(30), ReminderScheduler.delayUntilNext(settings, now))
    }

    @Test
    fun remindsOnlyWhenEnabledAllowedAndSomethingIsUnviewed() {
        assertEquals(true, ReminderPolicy.shouldRemind(remindersEnabled = true, notificationsAllowed = true, unviewedConversations = 1))
        assertEquals(false, ReminderPolicy.shouldRemind(remindersEnabled = true, notificationsAllowed = true, unviewedConversations = 0))
        assertEquals(false, ReminderPolicy.shouldRemind(remindersEnabled = false, notificationsAllowed = true, unviewedConversations = 5))
        assertEquals(false, ReminderPolicy.shouldRemind(remindersEnabled = true, notificationsAllowed = false, unviewedConversations = 5))
    }

    @Test
    fun weekendRollsToMonday() {
        val now = ZonedDateTime.of(2026, 9, 5, 8, 0, 0, 0, taipei) // Saturday
        assertEquals(Duration.ofDays(2).plusHours(12).plusMinutes(30), ReminderScheduler.delayUntilNext(settings, now))
    }

    @Test
    fun dstTransitionKeepsWallClockTime() {
        val newYork = ZoneId.of("America/New_York")
        val s = settings.copy(reminderWeekdays = setOf(7))
        // Saturday 2026-11-01 before the DST end on Sunday 2026-11-01 02:00: Sunday 20:30 wall clock is 25 h + 12.5 h away.
        val now = ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, newYork)
        val next = now.plus(ReminderScheduler.delayUntilNext(s, now))
        assertEquals(20, next.hour)
        assertEquals(30, next.minute)
        assertEquals(7, next.dayOfWeek.value)
    }
}
