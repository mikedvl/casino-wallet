package com.example.casinowallet.wallet.web

import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.bonus.domain.BonusStatus
import com.example.casinowallet.bonus.domain.WelcomeBonus
import java.math.RoundingMode
import java.time.Instant

data class WalletResponse(
    val realBalance: String,
    val bonusBalance: String,
    val bonus: WelcomeBonusResponse?,
) {
    companion object {
        fun from(wallet: WalletSummary): WalletResponse = WalletResponse(
            realBalance = wallet.realBalance.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            bonusBalance = wallet.bonusBalance.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            bonus = wallet.bonus?.let(WelcomeBonusResponse::from),
        )
    }
}

data class WelcomeBonusResponse(
    val status: BonusStatus,
    val initialAmount: String,
    val wageringProgress: String,
    val wageringTarget: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(bonus: WelcomeBonus): WelcomeBonusResponse = WelcomeBonusResponse(
            bonus.status, bonus.initialAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            bonus.wageringProgress.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            bonus.wageringTarget.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), bonus.expiresAt,
        )
    }
}
