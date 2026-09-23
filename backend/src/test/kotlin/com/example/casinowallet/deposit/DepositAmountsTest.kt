package com.example.casinowallet.deposit

import com.example.casinowallet.deposit.domain.DepositAmounts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.math.BigInteger

class DepositAmountsTest {
    @ParameterizedTest
    @CsvSource("25, 25.00", "25.1, 25.10", "0.01, 0.01", "00000000000000000025, 25.00", "99999999999999999.99, 99999999999999999.99")
    fun `decimal amounts normalize without rounding`(input: String, expected: String) {
        assertThat(DepositAmounts.parse(input)).isEqualTo(BigDecimal(expected))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0", "-1.00", "1.001", "1e2", "NaN", " 1.00", "+1", "100000000000000000.00"])
    fun `invalid decimal amounts are rejected`(input: String) {
        assertThrows<IllegalArgumentException> { DepositAmounts.parse(input) }
    }

    @ParameterizedTest
    @CsvSource("1, 0.01", "199, 1.99", "2500, 25.00", "9999999999999999999, 99999999999999999.99")
    fun `integer cents convert exactly including amounts above Long range`(cents: BigInteger, expected: String) {
        assertThat(DepositAmounts.fromCents(cents)).isEqualTo(BigDecimal(expected))
    }

    @Test
    fun `cents must be positive and fit numeric 19 2`() {
        for (cents in listOf(BigInteger.ZERO, BigInteger.valueOf(-1), BigInteger("10000000000000000000"))) {
            assertThrows<IllegalArgumentException> { DepositAmounts.fromCents(cents) }
        }
    }
}
