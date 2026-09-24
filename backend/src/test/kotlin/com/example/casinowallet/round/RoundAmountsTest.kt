package com.example.casinowallet.round

import com.example.casinowallet.round.domain.RoundAmounts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class RoundAmountsTest {
    @ParameterizedTest
    @CsvSource("8, 0, 8.00, 0.00", "4.1, 10, 4.10, 10.00", "0.01, 0.01, 0.01, 0.01",
        "99999999999999999.99, 99999999999999999.99, 99999999999999999.99, 99999999999999999.99")
    fun `amounts normalize to exact cents without rounding`(stake: String, win: String, expectedStake: String, expectedWin: String) {
        val amounts = RoundAmounts.parse(stake, win)
        assertThat(amounts.stake).isEqualTo(BigDecimal(expectedStake))
        assertThat(amounts.totalWin).isEqualTo(BigDecimal(expectedWin))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "-1", "8.001", "1e1", "NaN", "Infinity", "+8", " 8.00", "8.00 ", "100000000000000000.00"])
    fun `neither amount accepts invalid decimal syntax or out of range money`(input: String) {
        assertThrows<IllegalArgumentException> { RoundAmounts.parse(input, "0") }
        assertThrows<IllegalArgumentException> { RoundAmounts.parse("8", input) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "0.00"])
    fun `zero is allowed only as total payout`(zero: String) {
        assertThrows<IllegalArgumentException> { RoundAmounts.parse(zero, "10") }
        assertThat(RoundAmounts.parse("8", zero).totalWin).isEqualTo(BigDecimal("0.00"))
    }
}
