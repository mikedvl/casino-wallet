package com.example.casinowallet.round.domain

import java.math.BigDecimal
import java.math.RoundingMode

class RoundAmounts private constructor(val stake: BigDecimal, val totalWin: BigDecimal) {
    companion object {
        private val decimal = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
        private val maximumAmount = BigDecimal("99999999999999999.99")

        fun parse(stake: String, totalWin: String): RoundAmounts {
            val parsedStake = parseDecimal(stake)
            require(parsedStake.signum() > 0) { "Stake must be positive" }
            return RoundAmounts(parsedStake, parseDecimal(totalWin))
        }

        private fun parseDecimal(value: String): BigDecimal {
            require(decimal.matches(value)) { "Expected a nonnegative decimal string with at most two fractional digits" }
            val amount = BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY)
            require(amount <= maximumAmount) { "Amount must fit NUMERIC(19,2)" }
            return amount
        }
    }
}
