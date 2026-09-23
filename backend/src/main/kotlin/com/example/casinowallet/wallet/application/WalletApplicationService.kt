package com.example.casinowallet.wallet.application

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service

@Service
class WalletApplicationService(private val walletRepository: JdbcWalletRepository) {
    fun getDemoWallet(): WalletSummary = walletRepository.getByPlayerId(DemoPlayer.ID)
}
