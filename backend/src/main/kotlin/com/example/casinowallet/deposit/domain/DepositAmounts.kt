package com.example.casinowallet.deposit.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

object DepositAmounts {
    private val decimal = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    private val maximumCents = BigInteger("9999999999999999999")
    private val maximumAmount = BigDecimal(maximumCents, 2)

    fun parse(value: String): BigDecimal {
        require(decimal.matches(value)) { "Expected a decimal string with at most two fractional digits" }
        val amount = BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY)
        require(amount.signum() > 0 && amount <= maximumAmount) { "Amount must be positive and fit NUMERIC(19,2)" }
        return amount
    }

    fun fromCents(cents: BigInteger): BigDecimal {
        require(cents.signum() > 0 && cents <= maximumCents) { "Cents must be positive and fit NUMERIC(19,2)" }
        return BigDecimal(cents, 2)
    }
}
