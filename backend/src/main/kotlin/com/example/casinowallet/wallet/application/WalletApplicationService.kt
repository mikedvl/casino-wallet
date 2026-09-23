package com.example.casinowallet.wallet.application

import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class WalletApplicationService(private val walletRepository: JdbcWalletRepository) {
    fun getDemoWallet(): WalletSummary = walletRepository.getByPlayerId(DEMO_PLAYER_ID)

    private companion object {
        val DEMO_PLAYER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
