package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BankTransactionsTest {
    private val statement = """<?xml version="1.0" encoding="UTF-8"?>
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
          <BkToCstmrStmt><Stmt>
            <Acct><Id><IBAN>DE02120300000000202051</IBAN></Id></Acct>
            <Ntry><NtryRef>ENTRY-1</NtryRef><Amt Ccy="EUR">119.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>
              <BookgDt><Dt>2026-09-14</Dt></BookgDt><NtryDtls><TxDtls>
                <Refs><EndToEndId>RE-2026-0042</EndToEndId></Refs>
                <RltdPties><Dbtr><Pty><Nm>Musterkunde GmbH</Nm></Pty></Dbtr></RltdPties>
                <RmtInf><Ustrd>Rechnung RE-2026-0042</Ustrd></RmtInf>
              </TxDtls></NtryDtls>
            </Ntry>
            <Ntry><NtryRef>ENTRY-2</NtryRef><Amt Ccy="EUR">19.95</Amt><CdtDbtInd>DBIT</CdtDbtInd>
              <BookgDt><DtTm>2026-09-15T09:30:00+02:00</DtTm></BookgDt>
              <NtryDtls><TxDtls><RltdPties><Cdtr><Nm>BueroPartner</Nm></Cdtr></RltdPties><RmtInf><Ustrd>Ordner</Ustrd></RmtInf></TxDtls></NtryDtls>
            </Ntry>
          </Stmt></BkToCstmrStmt>
        </Document>""".trimIndent()

    @Test fun parsesIso20022CreditAndDebitWithReferencesAndDates() {
        val parsed = parseCamt053(ByteArrayInputStream(statement.toByteArray()))
        assertEquals(listOf("DE02120300000000202051"), parsed.accountIbans)
        assertEquals(2, parsed.transactions.size)
        val incoming = parsed.transactions.first { it.amountCents > 0 }
        assertEquals(11900L, incoming.amountCents)
        assertEquals("Musterkunde GmbH", incoming.counterparty)
        assertEquals("Rechnung RE-2026-0042", incoming.description)
        assertEquals("2026-09-14", incoming.date)
        assertEquals("RE-2026-0042", incoming.reference.substringAfterLast(" · "))
        val outgoing = parsed.transactions.first { it.amountCents < 0 }
        assertEquals(-1995L, outgoing.amountCents)
        assertEquals("2026-09-15", outgoing.date)
    }

    @Test fun createsOnlyUnambiguousSuggestionsAndNeverMatchesDebits() {
        val invoice = Invoice(number = "RE-2026-0042", customer = "Musterkunde GmbH", description = "Leistung", amountCents = 11900, status = "Versendet")
        val transaction = parseCamt053(ByteArrayInputStream(statement.toByteArray())).transactions.first { it.amountCents > 0 }
        assertEquals(invoice, suggestInvoiceMatch(transaction, listOf(invoice)))
        assertNull(suggestInvoiceMatch(transaction.copy(matchedInvoiceId = "already-matched"), listOf(invoice)))
        assertNull(suggestInvoiceMatch(transaction.copy(amountCents = -11900), listOf(invoice)))
        val competing = invoice.copy(id = "another", customer = "Other customer")
        assertNull(suggestInvoiceMatch(transaction.copy(description = "", reference = "", counterparty = "Unknown"), listOf(invoice, competing)))
    }

    @Test fun debitCanBeMatchedToUniqueRecordedExpenseWithoutChangingItsData() {
        val debit = parseCamt053(ByteArrayInputStream(statement.toByteArray())).transactions.single { it.amountCents < 0 }
        val expense = Expense(id = "expense-1", merchant = "BueroPartner", category = "Büro", amountCents = 1995, date = "2026-09-15")
        val competing = expense.copy(id = "expense-2", merchant = "Other Merchant")

        assertEquals(expense, suggestExpenseMatch(debit, listOf(expense)))
        assertEquals(expense, suggestExpenseMatch(debit, listOf(expense, competing)))
        assertNull(suggestExpenseMatch(debit, listOf(expense), listOf(debit.copy(id = "other", matchedExpenseId = expense.id))))
        assertNull(suggestExpenseMatch(debit.copy(matchedExpenseId = expense.id), listOf(expense)))
        assertNull(suggestExpenseMatch(debit.copy(amountCents = -1994), listOf(expense)))
        assertNull(suggestExpenseMatch(debit.copy(counterparty = "Unknown", description = "", reference = ""), listOf(expense, competing)))
    }

    @Test fun rejectsDoctypeAndUnsupportedCurrencies() {
        val hostile = """<!DOCTYPE Document [<!ENTITY x SYSTEM "file:///etc/passwd">]><Document>&x;</Document>"""
        assertTrue(runCatching { parseCamt053(ByteArrayInputStream(hostile.toByteArray())) }.isFailure)
        val usd = statement.replace("Ccy=\"EUR\"", "Ccy=\"USD\"")
        assertTrue(runCatching { parseCamt053(ByteArrayInputStream(usd.toByteArray())) }.exceptionOrNull()?.message?.contains("nur EUR") == true)
    }
}
