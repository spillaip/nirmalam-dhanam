package com.nirmalamgroup.nirmalamdhanam.domain.sms

import java.math.BigDecimal
import java.math.RoundingMode

sealed interface ParsedSmsAlert { val amountPaise: Long
    data class RetailDebit(override val amountPaise: Long, val merchant: String?, val vpa: String?) : ParsedSmsAlert
    data class MutualFund(override val amountPaise: Long, val allottedUnits: BigDecimal) : ParsedSmsAlert
    data class EquityTrade(val quantity: BigDecimal, val executionPricePaise: Long) : ParsedSmsAlert { override val amountPaise = quantity.multiply(BigDecimal(executionPricePaise)).setScale(0, RoundingMode.HALF_UP).longValueExact() }
    data class Retirement(override val amountPaise: Long, val runningBalancePaise: Long?) : ParsedSmsAlert
}

/** Parses only an in-memory String. It never logs, returns, or persists the original message body. */
class SmsParserEngine {
    private val money = "(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    private fun Regex.firstMoney(text: String): Long? = find(text)?.groupValues?.getOrNull(1)?.toPaise()
    fun parse(body: String): ParsedSmsAlert? {
        val normalized = body.replace(Regex("\\s+"), " ").trim()
        val debit = Regex("(?:debited|spent).*?$money(?:.*?(?:at|to|for)\\s+([^.;]+))?(?:.*?vpa\\s+([\\w.@-]+))?", RegexOption.IGNORE_CASE)
        debit.find(normalized)?.let { m -> return ParsedSmsAlert.RetailDebit(m.groupValues[1].toPaise(), m.groupValues.getOrNull(2)?.trim()?.ifBlank { null }, m.groupValues.getOrNull(3)?.ifBlank { null }) }
        val mf = Regex("(?:investment|invested|purchase).*?$money.*?(?:allotted|units?)\\s*[:=-]?\\s*([0-9,]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        mf.find(normalized)?.let { m -> return ParsedSmsAlert.MutualFund(m.groupValues[1].toPaise(), m.groupValues[2].replace(",", "").toBigDecimal()) }
        val equity = Regex("(?:NSE|BSE|executed|bought|sold).*?(?:qty|quantity)\\s*[:=-]?\\s*([0-9,]+(?:\\.[0-9]+)?).*?(?:price|rate|@)\\s*[:=-]?\\s*$money", RegexOption.IGNORE_CASE)
        equity.find(normalized)?.let { m -> return ParsedSmsAlert.EquityTrade(m.groupValues[1].replace(",", "").toBigDecimal(), m.groupValues[2].toPaise()) }
        val retirement = Regex("(?:NPS|EPF|PF).*?(?:contribution|credited).*?$money(?:.*?(?:balance|bal)\\s*[:=-]?\\s*$money)?", RegexOption.IGNORE_CASE)
        retirement.find(normalized)?.let { m -> return ParsedSmsAlert.Retirement(m.groupValues[1].toPaise(), m.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)?.toPaise()) }
        return null
    }
    private fun String.toPaise(): Long = BigDecimal(replace(",", "")).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}
