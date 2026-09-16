package de.kontoklar.app

import java.time.LocalDate
import java.util.UUID

data class TaxDeadline(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dueDate: String,
    val note: String = "",
    val completed: Boolean = false
)

fun taxDeadlineStatus(deadline: TaxDeadline, today: LocalDate = LocalDate.now()): String {
    if (deadline.completed) return "Erledigt"
    val date = runCatching { LocalDate.parse(deadline.dueDate) }.getOrNull() ?: return "Datum prüfen"
    return if (date.isBefore(today)) "Überfällig" else if (date == today) "Heute fällig" else "Offen"
}
