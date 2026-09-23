package com.example.casinowallet.wallet.domain

import java.math.BigDecimal

data class WalletSummary(
    val realBalance: BigDecimal,
    val bonusBalance: BigDecimal,
)
