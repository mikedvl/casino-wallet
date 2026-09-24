package com.example.casinowallet.bonus

import com.example.casinowallet.bonus.domain.WelcomeBonusGrant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class WelcomeBonusGrantTest {
    @Test
    fun `a deposit below twenty euros does not qualify`() {
        assertThat(WelcomeBonusGrant.fromDeposit(BigDecimal("19.99"), GRANTED_AT)).isNull()
    }

    @ParameterizedTest
    @CsvSource("20.00, 20.00, 400.00", "75.50, 75.50, 1510.00", "100.00, 100.00, 2000.00",
        "100.01, 100.00, 2000.00", "500.00, 100.00, 2000.00", "99999999999999999.99, 100.00, 2000.00")
    fun `grant is capped with exact wagering target and a 168 hour lifetime`(deposit: String, grant: String, target: String) {
        val result = checkNotNull(WelcomeBonusGrant.fromDeposit(BigDecimal(deposit), GRANTED_AT))
        assertThat(result.amount).isEqualTo(BigDecimal(grant))
        assertThat(result.wageringTarget).isEqualTo(BigDecimal(target))
        assertThat(result.grantedAt).isEqualTo(GRANTED_AT)
        assertThat(result.expiresAt).isEqualTo(GRANTED_AT.plus(Duration.ofHours(168)))
    }

    companion object {
        private val GRANTED_AT = Instant.parse("2026-03-28T12:34:56Z")
    }
}
