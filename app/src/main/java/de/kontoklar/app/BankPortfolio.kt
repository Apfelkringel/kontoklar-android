package de.kontoklar.app

data class BankAccountSummary(
    val id: String,
    val connectionId: String,
    val name: String,
    val type: String,
    val currency: String,
    val balanceMinor: Long?,
    val asOfDate: String
)

data class BankSecurityPosition(
    val id: String,
    val accountId: String,
    val connectionId: String,
    val name: String,
    val isin: String,
    val wkn: String,
    val quantityNominal: Double?,
    val quantityType: String,
    val quoteType: String,
    val quoteMinor: Long?,
    val quoteCurrency: String,
    val marketValueMinor: Long?,
    val marketValueCurrency: String,
    val profitOrLossMinor: Long?,
    val quoteDate: String
)

data class BankEuroTotals(val accountBalanceMinor: Long, val portfolioValueMinor: Long)

internal fun euroBankTotals(accounts: List<BankAccountSummary>, securities: List<BankSecurityPosition>): BankEuroTotals =
    BankEuroTotals(
        accountBalanceMinor = accounts.filter { it.currency.equals("EUR", ignoreCase = true) }.mapNotNull { it.balanceMinor }.sum(),
        portfolioValueMinor = securities.filter { it.marketValueCurrency.equals("EUR", ignoreCase = true) }.mapNotNull { it.marketValueMinor }.sum()
    )
