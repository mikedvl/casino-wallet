package com.example.casinowallet.wallet.web

import com.example.casinowallet.wallet.domain.WalletSummary
import java.math.RoundingMode

data class WalletResponse(
    val realBalance: String,
    val bonusBalance: String,
) {
    companion object {
        fun from(wallet: WalletSummary): WalletResponse = WalletResponse(
            realBalance = wallet.realBalance.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            bonusBalance = wallet.bonusBalance.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
        )
    }
}
