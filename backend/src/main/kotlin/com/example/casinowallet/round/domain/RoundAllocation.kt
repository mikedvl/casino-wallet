package com.example.casinowallet.round.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class RoundAllocation(
    val realStake: BigDecimal,
    val bonusStake: BigDecimal,
    val realWin: BigDecimal,
    val bonusWin: BigDecimal,
) {
    companion object {
        fun realFirst(amounts: RoundAmounts, realBalance: BigDecimal): RoundAllocation {
            val realStake = realBalance.min(amounts.stake)
            val realWin = (amounts.totalWin * realStake).divide(amounts.stake, 2, RoundingMode.HALF_UP)
            return RoundAllocation(realStake, amounts.stake - realStake, realWin, amounts.totalWin - realWin)
        }
    }
}
