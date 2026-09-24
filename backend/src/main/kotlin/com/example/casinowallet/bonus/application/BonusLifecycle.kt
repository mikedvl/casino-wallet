package com.example.casinowallet.bonus.application

import com.example.casinowallet.bonus.domain.BonusStatus
import com.example.casinowallet.bonus.domain.WelcomeBonus
import com.example.casinowallet.bonus.persistence.JdbcBonusRepository
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import com.example.casinowallet.observability.CasinoWalletMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

// The calling application service owns one transaction and must already hold the wallet row lock.
@Component
class BonusLifecycle(
    private val bonuses: JdbcBonusRepository,
    private val wallets: JdbcWalletRepository,
    private val ledger: JdbcLedgerRepository,
    private val clock: Clock,
    private val metrics: CasinoWalletMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(playerId: UUID, wallet: WalletSummary): WalletSummary {
        val bonus = bonuses.findByPlayerId(playerId) ?: return wallet
        val status = bonus.resolvedStatus(clock.instant())
        return if (status == bonus.status) wallet.copy(bonus = bonus) else finish(playerId, wallet, bonus, status)
    }

    fun completeIfWagered(playerId: UUID, wallet: WalletSummary): WalletSummary {
        val bonus = checkNotNull(bonuses.findByPlayerId(playerId))
        return if (bonus.status == BonusStatus.ACTIVE && bonus.wageringProgress >= bonus.wageringTarget) {
            finish(playerId, wallet, bonus, BonusStatus.COMPLETED)
        } else {
            wallet.copy(bonus = bonus)
        }
    }

    private fun finish(playerId: UUID, wallet: WalletSummary, bonus: WelcomeBonus, status: BonusStatus): WalletSummary {
        val remaining = wallet.bonusBalance
        val finalWallet = when {
            remaining.signum() == 0 -> wallet
            status == BonusStatus.COMPLETED -> {
                val converted = wallets.convertBonusToReal(playerId) ?: throw WalletBalanceLimitException()
                ledger.appendBonusConversion(playerId, bonus.id, remaining, converted.realBalance)
                converted
            }
            else -> {
                val forfeited = wallets.forfeitBonus(playerId)
                ledger.appendBonusForfeiture(playerId, bonus.id, remaining)
                forfeited
            }
        }
        bonuses.finish(bonus.id, status)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                metrics.bonusLifecycle(status)
                log.info("event={} bonus_id={}", if (status == BonusStatus.COMPLETED) "bonus_completed" else "bonus_expired", bonus.id)
            }
        })
        return finalWallet.copy(bonus = bonus.copy(status = status))
    }
}
