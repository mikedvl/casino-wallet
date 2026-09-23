package com.example.casinowallet.bonus.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class BonusStatus { ACTIVE, COMPLETED, EXPIRED }

data class WelcomeBonus(
    val id: UUID,
    val initialAmount: BigDecimal,
    val wageringProgress: BigDecimal,
    val wageringTarget: BigDecimal,
    val status: BonusStatus,
    val expiresAt: Instant,
) {
    fun resolvedStatus(now: Instant): BonusStatus = when {
        status != BonusStatus.ACTIVE -> status
        wageringProgress >= wageringTarget -> BonusStatus.COMPLETED
        now >= expiresAt -> BonusStatus.EXPIRED
        else -> BonusStatus.ACTIVE
    }
}
