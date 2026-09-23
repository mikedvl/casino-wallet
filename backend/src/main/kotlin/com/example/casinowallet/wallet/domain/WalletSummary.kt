package com.example.casinowallet.wallet.domain

import com.example.casinowallet.bonus.domain.WelcomeBonus
import java.math.BigDecimal

data class WalletSummary(
    val realBalance: BigDecimal,
    val bonusBalance: BigDecimal,
    val bonus: WelcomeBonus? = null,
) {
    companion object {
        val MAX_BALANCE = BigDecimal("99999999999999999.99")
    }
}
