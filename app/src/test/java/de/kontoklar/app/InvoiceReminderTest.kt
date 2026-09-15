package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class InvoiceReminderTest {
    @Test fun reminderIsScheduledAtNineTheDayAfterDueDate() {
        val zone = ZoneId.of("Europe/Berlin")
        val expected = LocalDateTime.parse("2026-10-02T09:00:00").atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, invoiceReminderAtMillis("2026-10-01", zone))
    }

    @Test fun alreadyOverdueInvoiceUsesItsOriginalReminderTimeSoOpeningAppDoesNotKeepPostponingIt() {
        val zone = ZoneId.of("Europe/Berlin")
        val expected = LocalDateTime.parse("2026-09-02T09:00:00").atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, invoiceReminderAtMillis("2026-09-01", zone))
    }

    @Test fun malformedDueDateDoesNotScheduleAnAlarm() {
        assertNull(invoiceReminderAtMillis("31/12/2026", ZoneId.of("Europe/Berlin")))
    }
}
