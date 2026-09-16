package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TaxDeadlineReminderTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun deadlineReminderIsAtNineOnTheUserEnteredDueDate() {
        val now = Instant.parse("2026-03-28T12:00:00Z")
        val trigger = taxDeadlineReminderAtMillis("2026-03-29", berlin, now)
        assertEquals(Instant.parse("2026-03-29T07:00:00Z").toEpochMilli(), trigger)
    }

    @Test fun remindersAreNotScheduledForPastDatesOrPastNineToday() {
        val now = Instant.parse("2026-06-10T08:30:00Z")
        assertNull(taxDeadlineReminderAtMillis("2026-06-09", berlin, now))
        assertNull(taxDeadlineReminderAtMillis("2026-06-10", berlin, now))
    }

    @Test fun invalidDatesDoNotScheduleAlarms() {
        assertNull(taxDeadlineReminderAtMillis("not-a-date", berlin, Instant.EPOCH))
    }
}
