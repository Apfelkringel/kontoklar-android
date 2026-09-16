package de.kontoklar.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TaxDeadlineTest {
    private val today = LocalDate.parse("2026-04-15")

    @Test fun statusUsesOnlyUserEnteredDateAndCompletion() {
        assertEquals("Überfällig", taxDeadlineStatus(TaxDeadline(title = "USt", dueDate = "2026-04-14"), today))
        assertEquals("Heute fällig", taxDeadlineStatus(TaxDeadline(title = "USt", dueDate = "2026-04-15"), today))
        assertEquals("Offen", taxDeadlineStatus(TaxDeadline(title = "USt", dueDate = "2026-04-16"), today))
        assertEquals("Erledigt", taxDeadlineStatus(TaxDeadline(title = "USt", dueDate = "2026-04-14", completed = true), today))
    }
}
