package com.example.casinowallet.bonus.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class WelcomeBonusGrant(
    val amount: BigDecimal,
    val wageringTarget: BigDecimal,
    val grantedAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        val MINIMUM_QUALIFYING_DEPOSIT = BigDecimal("20.00")
        private val maximumGrant = BigDecimal("100.00")
        private val wageringMultiplier = BigDecimal("20")

        fun fromDeposit(depositAmount: BigDecimal, grantedAt: Instant): WelcomeBonusGrant? {
            if (depositAmount < MINIMUM_QUALIFYING_DEPOSIT) return null
            val amount = depositAmount.min(maximumGrant)
            return WelcomeBonusGrant(amount, amount * wageringMultiplier, grantedAt, grantedAt.plus(Duration.ofHours(168)))
        }
    }
}
