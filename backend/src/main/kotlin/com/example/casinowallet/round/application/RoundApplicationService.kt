package com.example.casinowallet.round.application

import com.example.casinowallet.bonus.persistence.JdbcBonusRepository
import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.ledger.domain.WalletType
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import com.example.casinowallet.round.domain.GameRound
import com.example.casinowallet.round.domain.RoundAllocation
import com.example.casinowallet.round.domain.RoundAmounts
import com.example.casinowallet.round.persistence.JdbcRoundRepository
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class RoundApplicationService(
    private val wallets: JdbcWalletRepository,
    private val rounds: JdbcRoundRepository,
    private val ledger: JdbcLedgerRepository,
    private val bonuses: JdbcBonusRepository,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = [Exception::class])
    fun play(amounts: RoundAmounts): RoundResult {
        val playerId = DemoPlayer.ID
        val wallet = wallets.lockByPlayerId(playerId)
        // Every bonus mutation also owns this wallet lock, so the metadata cannot change concurrently.
        val activeBonusId = bonuses.findActiveId(playerId)
        if (activeBonusId != null && amounts.stake > MAX_ACTIVE_BONUS_STAKE) throw MaxBetExceededException()
        val available = if (activeBonusId != null) wallet.realBalance + wallet.bonusBalance else wallet.realBalance
        if (available < amounts.stake) throw InsufficientFundsException()

        val allocation = RoundAllocation.realFirst(amounts, wallet.realBalance)
        val round = GameRound(UUID.randomUUID(), playerId, amounts.stake, amounts.totalWin, allocation)
        val afterStake = wallets.debitBalances(playerId, allocation.realStake, allocation.bonusStake) ?: throw InsufficientFundsException()
        if (allocation.realStake.signum() > 0) {
            ledger.appendRoundStake(playerId, round.id, WalletType.REAL, allocation.realStake, afterStake.realBalance)
        }
        if (allocation.bonusStake.signum() > 0) {
            ledger.appendRoundStake(playerId, round.id, WalletType.BONUS, allocation.bonusStake, afterStake.bonusBalance)
        }
        rounds.insert(round)
        val finalWallet = if (round.totalWin.signum() > 0) {
            val afterWin = wallets.creditBalances(playerId, allocation.realWin, allocation.bonusWin) ?: throw WalletBalanceLimitException()
            if (allocation.realWin.signum() > 0) {
                ledger.appendRoundWin(playerId, round.id, WalletType.REAL, allocation.realWin, afterWin.realBalance)
            }
            if (allocation.bonusWin.signum() > 0) {
                ledger.appendRoundWin(playerId, round.id, WalletType.BONUS, allocation.bonusWin, afterWin.bonusBalance)
            }
            afterWin
        } else {
            afterStake
        }
        if (activeBonusId != null) bonuses.advanceWagering(activeBonusId, amounts.stake)
        return RoundResult(round, finalWallet)
    }

    companion object {
        private val MAX_ACTIVE_BONUS_STAKE = BigDecimal("5.00")
    }
}

data class RoundResult(val round: GameRound, val wallet: WalletSummary)

class InsufficientFundsException : RuntimeException()
class MaxBetExceededException : RuntimeException()
