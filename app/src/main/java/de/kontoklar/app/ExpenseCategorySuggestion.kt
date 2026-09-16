package de.kontoklar.app

import java.text.Normalizer
import java.util.Locale

private val categoryHints = linkedMapOf(
    "Reisekosten" to listOf("deutsche bahn", "bahncard", "bahn", "db fernverkehr", "flixbus", "lufthansa", "ryanair", "taxi", "uber", "hotel", "airbnb", "mietwagen", "sixt", "hertz", "flugticket"),
    "Software" to listOf("adobe", "microsoft 365", "office 365", "google workspace", "github", "gitlab", "openai", "software", "saas", "cloud abo", "hosting", "server", "domain", "dropbox", "slack", "notion", "figma", "zoom"),
    "Telefon & Internet" to listOf("telekom", "vodafone", "o2", "telefon", "mobilfunk", "internetanschluss", "sim karte", "internetvertrag", "1und1", "1&1"),
    "Bewirtung" to listOf("restaurant", "cafe", "café", "gastronomie", "pizzeria", "lieferando", "ubereats", "wolt", "restaurantrechnung", "mittagstisch", "bistro", "bar "),
    "Arbeitsmittel" to listOf("laptop", "notebook", "computer", "monitor", "tastatur", "maus", "drucker", "scanner", "ssd", "usb stick", "mediamarkt", "saturn", "hardware", "headset"),
    "Büro" to listOf("bürobedarf", "buerobedarf", "papier", "briefumschlag", "ordner", "stift", "schreibwaren", "hefter", "locher", "toner", "druckerpapier", "schreibtisch")
)

private fun normalizeCategoryText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase(Locale.ROOT)

/** Heuristic bookkeeping-label suggestion only; it does not establish tax deductibility or treatment. */
fun suggestExpenseCategory(merchant: String?, receiptText: String): String? {
    val searchable = normalizeCategoryText(listOfNotNull(merchant, receiptText).joinToString(" "))
    if (searchable.isBlank()) return null
    val scores = categoryHints.mapValues { (_, hints) -> hints.count { hint ->
        val normalizedHint = normalizeCategoryText(hint).trim()
        val pattern = Regex("(?:^|[^a-z0-9])${Regex.escape(normalizedHint)}(?:$|[^a-z0-9])")
        pattern.containsMatchIn(searchable)
    } }.filterValues { it > 0 }
    val highest = scores.values.maxOrNull() ?: return null
    val winners = scores.filterValues { it == highest }.keys
    return winners.singleOrNull()
}
