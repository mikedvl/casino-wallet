package com.example.casinowallet.wallet.application

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.bonus.application.BonusLifecycle
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class WalletApplicationService(private val walletRepository: JdbcWalletRepository, private val lifecycle: BonusLifecycle) {
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = [Exception::class])
    fun getDemoWallet(): WalletSummary = lifecycle.resolve(DemoPlayer.ID, walletRepository.lockByPlayerId(DemoPlayer.ID))
}
