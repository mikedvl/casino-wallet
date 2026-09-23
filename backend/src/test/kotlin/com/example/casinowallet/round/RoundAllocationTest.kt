package com.example.casinowallet.round

import com.example.casinowallet.round.domain.RoundAllocation
import com.example.casinowallet.round.domain.RoundAmounts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

class RoundAllocationTest {
    @ParameterizedTest
    @CsvSource(
        "10.00, 4.00, 10.00, 4.00, 0.00, 10.00, 0.00",
        "1.00, 4.00, 10.00, 1.00, 3.00, 2.50, 7.50",
        "0.00, 4.00, 10.00, 0.00, 4.00, 0.00, 10.00",
        "1.00, 3.00, 10.00, 1.00, 2.00, 3.33, 6.67",
        "0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.00",
        "1.00, 4.00, 0.00, 1.00, 3.00, 0.00, 0.00",
        "1.00, 3.00, 99999999999999999.99, 1.00, 2.00, 33333333333333333.33, 66666666666666666.66",
    )
    fun `real first allocation rounds real payout once and assigns the exact remainder to bonus`(
        realBalance: String, stake: String, win: String, realStake: String, bonusStake: String, realWin: String, bonusWin: String,
    ) {
        val amounts = RoundAmounts.parse(stake, win)
        val result = RoundAllocation.realFirst(amounts, BigDecimal(realBalance))
        assertThat(result.realStake).isEqualTo(BigDecimal(realStake))
        assertThat(result.bonusStake).isEqualTo(BigDecimal(bonusStake))
        assertThat(result.realWin).isEqualTo(BigDecimal(realWin))
        assertThat(result.bonusWin).isEqualTo(BigDecimal(bonusWin))
        assertThat(result.realStake + result.bonusStake).isEqualTo(amounts.stake)
        assertThat(result.realWin + result.bonusWin).isEqualTo(amounts.totalWin)
    }
}
