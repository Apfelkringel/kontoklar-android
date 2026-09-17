package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Mt940Test {
    @Test fun importsCreditDebitAndAccountLocally() {
        val mt940 = """:20:STATEMENT
:25:DE89370400440532013000
:60F:C260916EUR1000,00
:61:2609170917D12,34NTRFREF-1
:86:?00Stadtwerke GmbH\nVerwendungszweck Energie
:61:2609170917C250,00NTRFREF-2
:86:?00Kunde GmbH
:62F:C1237,66
""".trimIndent()

        val parsed = parseMt940(ByteArrayInputStream(mt940.toByteArray()))

        assertEquals(listOf("DE89370400440532013000"), parsed.accountIbans)
        assertEquals(2, parsed.transactions.size)
        assertEquals(-1234L, parsed.transactions[0].amountCents)
        assertEquals(25000L, parsed.transactions[1].amountCents)
        assertTrue(parsed.transactions[0].description.contains("Stadtwerke"))
    }

    @Test fun rejectsMalformedEntry() {
        assertTrue(runCatching { parseMt940(ByteArrayInputStream(":61:260917X1,00".toByteArray())) }.isFailure)
    }
}
