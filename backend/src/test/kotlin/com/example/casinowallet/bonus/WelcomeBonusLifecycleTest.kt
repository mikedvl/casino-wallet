package com.example.casinowallet.bonus

import com.example.casinowallet.bonus.domain.BonusStatus
import com.example.casinowallet.bonus.domain.WelcomeBonus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class WelcomeBonusLifecycleTest {
    @ParameterizedTest
    @CsvSource(
        "ACTIVE, 399.99, -1, ACTIVE", "ACTIVE, 399.99, 0, EXPIRED", "ACTIVE, 399.99, 1, EXPIRED",
        "ACTIVE, 400.00, -1, COMPLETED", "ACTIVE, 400.00, 0, COMPLETED", "ACTIVE, 405.00, 1, COMPLETED",
        "COMPLETED, 400.00, 1, COMPLETED", "EXPIRED, 399.99, -1, EXPIRED",
    )
    fun `completion takes priority and expiration starts at the exact instant with terminal states preserved`(
        status: BonusStatus, progress: String, nanosAfterExpiry: Long, expected: BonusStatus,
    ) {
        val expiresAt = Instant.parse("2026-04-01T12:00:00Z")
        val bonus = WelcomeBonus(UUID.randomUUID(), BigDecimal("20.00"), BigDecimal(progress),
            BigDecimal("400.00"), status, expiresAt)
        assertThat(bonus.resolvedStatus(expiresAt.plusNanos(nanosAfterExpiry))).isEqualTo(expected)
    }
}
